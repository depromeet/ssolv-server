package org.depromeet.team3.batch.restaurant.job

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.csv.CSVFormat
import org.depromeet.team3.batch.restaurant.adapter.RestaurantSourceAdapterRegistry
import org.depromeet.team3.batch.restaurant.config.RestaurantImportProperties
import org.depromeet.team3.batch.restaurant.io.RestaurantImportManifestService
import org.depromeet.team3.batch.restaurant.io.RestaurantSourceFileService
import org.depromeet.team3.batch.restaurant.model.RestaurantCsvRecord
import org.depromeet.team3.batch.restaurant.model.RestaurantImportManifest
import org.depromeet.team3.batch.restaurant.model.RestaurantImportManifestFile
import org.depromeet.team3.batch.restaurant.model.StandardRestaurantRow
import org.depromeet.team3.batch.restaurant.support.RestaurantTextNormalizer
import org.depromeet.team3.restaurant.RestaurantImportStatus
import org.depromeet.team3.restaurant.RestaurantSourceType
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.io.BufferedReader
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.LocalDateTime

@Component
class RestaurantRawImportTasklet(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val adapterRegistry: RestaurantSourceAdapterRegistry,
    private val objectMapper: ObjectMapper,
    private val normalizer: RestaurantTextNormalizer,
    private val properties: RestaurantImportProperties,
    private val sourceFileService: RestaurantSourceFileService?,
    private val manifestService: RestaurantImportManifestService?,
) : Tasklet {
    private val logger = LoggerFactory.getLogger(RestaurantRawImportTasklet::class.java)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val parameters = chunkContext.stepContext.jobParameters
        val request = resolveImportRequest(parameters)
        val maxRows = parameters["maxRows"]?.toString()?.toLongOrNull()
        val inputPath = resolveInputFile(parameters, request.sourceObjectKey)
        val importJobId = createImportJob(
            importMonth = request.importMonth,
            sourceType = request.sourceType,
            sourceObjectKey = request.sourceObjectKey ?: inputPath.toString(),
            runKey = request.runKey,
            inputPath = inputPath,
        )

        val executionContext = chunkContext.stepContext.stepExecution.jobExecution.executionContext
        executionContext.putLong(RestaurantImportContextKeys.IMPORT_JOB_ID, importJobId)
        executionContext.putString(RestaurantImportContextKeys.IMPORT_RUN_KEY, request.runKey)
        executionContext.putString(RestaurantImportContextKeys.SOURCE_TYPE, request.sourceType.name)
        executionContext.putString(RestaurantImportContextKeys.IMPORT_MONTH, request.importMonth)

        logger.info(
            "식당 원천 import 시작: importJobId={}, runKey={}, sourceType={}, file={}, chunkSize={}",
            importJobId,
            request.runKey,
            request.sourceType,
            inputPath,
            request.chunkSize,
        )

        var totalCount = 0L
        var validCount = 0L
        var invalidCount = 0L
        val adapter = adapterRegistry.get(request.sourceType)
        val rawBuffer = mutableListOf<RawRecordRow>()
        val stagingBuffer = mutableListOf<StandardRestaurantRow>()
        val invalidBuffer = mutableListOf<InvalidRecordRow>()

        Files.newBufferedReader(inputPath, request.charset).use { reader ->
            val records = maxRows
                ?.let { parseCsv(reader).take(it.toInt()) }
                ?: parseCsv(reader)
            records.forEach { csvRecord ->
                totalCount++
                val rawHash = normalizer.sha256(csvRecord.rawPayload)
                rawBuffer.add(
                    RawRecordRow(
                        rowNumber = csvRecord.rowNumber,
                        sourceKey = rawHash,
                        rawHash = rawHash,
                        rawPayload = csvRecord.rawPayload,
                    ),
                )
                try {
                    val standardized = adapter.convert(csvRecord)
                    rawBuffer[rawBuffer.lastIndex] = rawBuffer.last().copy(sourceKey = standardized.sourceKey)
                    stagingBuffer.add(standardized)
                    validCount++
                } catch (e: Exception) {
                    invalidBuffer.add(
                        InvalidRecordRow(
                            rowNumber = csvRecord.rowNumber,
                            failureReason = e.message ?: e.javaClass.simpleName,
                            rawPayload = csvRecord.rawPayload,
                        ),
                    )
                    invalidCount++
                }

                if (rawBuffer.size >= request.chunkSize) {
                    flush(importJobId, request.sourceType, rawBuffer, stagingBuffer, invalidBuffer)
                }
            }
        }

        flush(importJobId, request.sourceType, rawBuffer, stagingBuffer, invalidBuffer)
        updateImportCounts(importJobId, totalCount, validCount, invalidCount)
        logger.info(
            "식당 원천 import 완료: importJobId={}, total={}, valid={}, invalid={}",
            importJobId,
            totalCount,
            validCount,
            invalidCount,
        )
        return RepeatStatus.FINISHED
    }

    private fun resolveImportRequest(parameters: Map<String, Any>): ImportRequest {
        val manifestKey = parameters["manifestKey"]?.toString()
        val manifestFile = parameters["manifestFile"]?.toString()
        val manifest = when {
            !manifestKey.isNullOrBlank() -> {
                require(manifestService != null) { "S3 manifest service is not available" }
                manifestService.loadFromS3(manifestKey)
            }
            !manifestFile.isNullOrBlank() -> {
                require(manifestService != null) { "manifest service is not available" }
                manifestService.loadFromFile(Path.of(manifestFile))
            }
            else -> null
        }
        val manifestEntry = manifest?.let { selectManifestFile(it, parameters) }
        val sourceType = parameters["sourceType"]?.toString()
            ?.let { RestaurantSourceType.valueOf(it) }
            ?: manifest?.sourceType
            ?: RestaurantSourceType.valueOf(required(parameters, "sourceType"))
        val importMonth = parameters["importMonth"]?.toString()
            ?: manifest?.importMonth
            ?: required(parameters, "importMonth")
        val sourceObjectKey = parameters["s3Key"]?.toString()
            ?: manifestEntry?.s3Key
        val runKey = parameters["runKey"]?.toString()
            ?: manifestKey
            ?: manifestFile
            ?: sourceObjectKey
            ?: parameters["inputFile"]?.toString()
            ?: "$sourceType-$importMonth"
        val chunkSize = parameters["chunkSize"]?.toString()?.toIntOrNull()
            ?: manifestEntry?.chunkSize
            ?: manifest?.chunkSize
            ?: properties.defaults.chunkSize
        val charset = Charset.forName(
            parameters["charset"]?.toString()
                ?: manifestEntry?.charset
                ?: manifest?.charset
                ?: "UTF-8",
        )

        return ImportRequest(
            sourceType = sourceType,
            importMonth = importMonth,
            sourceObjectKey = sourceObjectKey,
            runKey = runKey,
            chunkSize = chunkSize,
            charset = charset,
        )
    }

    private fun selectManifestFile(manifest: RestaurantImportManifest, parameters: Map<String, Any>): RestaurantImportManifestFile {
        require(manifest.files.isNotEmpty()) { "manifest.files must not be empty" }

        val s3Key = parameters["s3Key"]?.toString()
        if (!s3Key.isNullOrBlank()) {
            return manifest.files.first { it.s3Key == s3Key }
        }

        val fileIndex = parameters["fileIndex"]?.toString()?.toIntOrNull() ?: 0
        return manifest.files[fileIndex]
    }

    private fun resolveInputFile(parameters: Map<String, Any>, sourceObjectKey: String?): Path {
        val inputFile = parameters["inputFile"]?.toString()
        if (!inputFile.isNullOrBlank()) return Path.of(inputFile)

        require(!sourceObjectKey.isNullOrBlank()) { "Either inputFile or s3Key job parameter is required" }
        require(sourceFileService != null) { "S3 source file service is not available" }
        return sourceFileService.downloadToTempFile(sourceObjectKey)
    }

    private fun parseCsv(reader: BufferedReader): Sequence<RestaurantCsvRecord> {
        val format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .build()
        val parser = format.parse(reader)
        return parser.asSequence().map { record ->
            val values = record.toMap().mapKeys { it.key.trim() }
            RestaurantCsvRecord(
                rowNumber = record.recordNumber,
                values = values,
                rawPayload = objectMapper.writeValueAsString(values),
            )
        }
    }

    private fun createImportJob(
        importMonth: String,
        sourceType: RestaurantSourceType,
        sourceObjectKey: String,
        runKey: String,
        inputPath: Path,
    ): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val statement = connection.prepareStatement(
                """
                INSERT INTO tb_restaurant_import_job
                    (import_month, source_type, source_object_key, run_key, local_file_path, status, started_at,
                     total_count, valid_count, invalid_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            )
            statement.setString(1, importMonth)
            statement.setString(2, sourceType.name)
            statement.setString(3, sourceObjectKey)
            statement.setString(4, runKey)
            statement.setString(5, inputPath.toString())
            statement.setString(6, RestaurantImportStatus.RUNNING.name)
            statement.setObject(7, LocalDateTime.now())
            statement.setObject(8, LocalDateTime.now())
            statement
        }, keyHolder)
        return keyHolder.key!!.toLong()
    }

    private fun flush(
        importJobId: Long,
        sourceType: RestaurantSourceType,
        rawRows: MutableList<RawRecordRow>,
        stagingRows: MutableList<StandardRestaurantRow>,
        invalidRows: MutableList<InvalidRecordRow>,
    ) {
        if (rawRows.isEmpty() && stagingRows.isEmpty() && invalidRows.isEmpty()) return

        val rawChunk = rawRows.toList()
        val stagingChunk = stagingRows.toList()
        val invalidChunk = invalidRows.toList()
        transactionTemplate.executeWithoutResult {
            insertRawRows(importJobId, sourceType, rawChunk)
            insertStagingRows(importJobId, stagingChunk)
            insertInvalidRows(importJobId, invalidChunk)
        }
        rawRows.clear()
        stagingRows.clear()
        invalidRows.clear()
    }

    private fun insertRawRows(importJobId: Long, sourceType: RestaurantSourceType, rows: List<RawRecordRow>) {
        if (rows.isEmpty()) return
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO tb_restaurant_raw_record
                (import_job_id, source_type, source_key, row_no, raw_hash, raw_payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE raw_hash = VALUES(raw_hash), raw_payload = VALUES(raw_payload)
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val row = rows[i]
                    ps.setLong(1, importJobId)
                    ps.setString(2, sourceType.name)
                    ps.setString(3, row.sourceKey)
                    ps.setLong(4, row.rowNumber)
                    ps.setString(5, row.rawHash)
                    ps.setString(6, row.rawPayload)
                    ps.setObject(7, LocalDateTime.now())
                }

                override fun getBatchSize(): Int = rows.size
            },
        )
    }

    private fun insertStagingRows(importJobId: Long, rows: List<StandardRestaurantRow>) {
        if (rows.isEmpty()) return
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO tb_restaurant_staging
                (import_job_id, source_type, source_key, name, normalized_name, road_address, lot_address, address,
                 latitude, longitude, category, region_code, phone_number, business_status, content_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                normalized_name = VALUES(normalized_name),
                road_address = VALUES(road_address),
                lot_address = VALUES(lot_address),
                address = VALUES(address),
                latitude = VALUES(latitude),
                longitude = VALUES(longitude),
                category = VALUES(category),
                region_code = VALUES(region_code),
                phone_number = VALUES(phone_number),
                business_status = VALUES(business_status),
                content_hash = VALUES(content_hash)
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val row = rows[i]
                    ps.setLong(1, importJobId)
                    ps.setString(2, row.sourceType.name)
                    ps.setString(3, row.sourceKey)
                    ps.setString(4, row.name)
                    ps.setString(5, row.normalizedName)
                    ps.setString(6, row.roadAddress)
                    ps.setString(7, row.lotAddress)
                    ps.setString(8, row.address)
                    ps.setObject(9, row.latitude)
                    ps.setObject(10, row.longitude)
                    ps.setString(11, row.category)
                    ps.setString(12, row.regionCode)
                    ps.setString(13, row.phoneNumber)
                    ps.setString(14, row.businessStatus.name)
                    ps.setString(15, row.contentHash)
                    ps.setObject(16, LocalDateTime.now())
                }

                override fun getBatchSize(): Int = rows.size
            },
        )
    }

    private fun insertInvalidRows(importJobId: Long, rows: List<InvalidRecordRow>) {
        if (rows.isEmpty()) return
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO tb_restaurant_invalid_record
                (import_job_id, row_no, failure_reason, raw_payload, created_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val row = rows[i]
                    ps.setLong(1, importJobId)
                    ps.setLong(2, row.rowNumber)
                    ps.setString(3, row.failureReason.take(500))
                    ps.setString(4, row.rawPayload)
                    ps.setObject(5, LocalDateTime.now())
                }

                override fun getBatchSize(): Int = rows.size
            },
        )
    }

    private fun updateImportCounts(importJobId: Long, totalCount: Long, validCount: Long, invalidCount: Long) {
        jdbcTemplate.update(
            """
            UPDATE tb_restaurant_import_job
            SET total_count = ?, valid_count = ?, invalid_count = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            totalCount,
            validCount,
            invalidCount,
            LocalDateTime.now(),
            importJobId,
        )
    }

    private fun required(parameters: Map<String, Any>, name: String): String {
        val value = parameters[name]?.toString()
        require(!value.isNullOrBlank()) { "$name job parameter is required" }
        return value
    }

    private data class RawRecordRow(val rowNumber: Long, val sourceKey: String, val rawHash: String, val rawPayload: String)

    private data class InvalidRecordRow(val rowNumber: Long, val failureReason: String, val rawPayload: String)

    private data class ImportRequest(
        val sourceType: RestaurantSourceType,
        val importMonth: String,
        val sourceObjectKey: String?,
        val runKey: String,
        val chunkSize: Int,
        val charset: Charset,
    )
}
