package org.depromeet.team3.batch.restaurant.job

import org.depromeet.team3.batch.restaurant.io.RestaurantImportManifestService
import org.depromeet.team3.restaurant.RestaurantImportStatus
import org.depromeet.team3.restaurant.RestaurantSourceType
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.sql.Statement
import java.time.LocalDateTime

@Component
class RestaurantFinalizeContextTasklet(
    private val jdbcTemplate: JdbcTemplate,
    private val manifestService: RestaurantImportManifestService?,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val parameters = chunkContext.stepContext.jobParameters
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
        val sourceType = parameters["sourceType"]?.toString()
            ?.let { RestaurantSourceType.valueOf(it) }
            ?: manifest?.sourceType
            ?: error("sourceType or manifestKey job parameter is required")
        val importMonth = parameters["importMonth"]?.toString()
            ?: manifest?.importMonth
            ?: error("importMonth or manifestKey job parameter is required")
        val runKey = parameters["runKey"]?.toString()
            ?: manifestKey
            ?: manifestFile
            ?: "$sourceType-$importMonth"
        val importJobId = createFinalizeJob(importMonth, sourceType, runKey, manifestKey ?: manifestFile ?: runKey)

        val executionContext = chunkContext.stepContext.stepExecution.jobExecution.executionContext
        executionContext.putLong(RestaurantImportContextKeys.IMPORT_JOB_ID, importJobId)
        executionContext.putString(RestaurantImportContextKeys.IMPORT_RUN_KEY, runKey)
        executionContext.putString(RestaurantImportContextKeys.SOURCE_TYPE, sourceType.name)
        executionContext.putString(RestaurantImportContextKeys.IMPORT_MONTH, importMonth)
        return RepeatStatus.FINISHED
    }

    private fun createFinalizeJob(
        importMonth: String,
        sourceType: RestaurantSourceType,
        runKey: String,
        sourceObjectKey: String,
    ): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            val statement = connection.prepareStatement(
                """
                INSERT INTO tb_restaurant_import_job
                    (import_month, source_type, source_object_key, run_key, status, started_at,
                     total_count, valid_count, invalid_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            )
            statement.setString(1, importMonth)
            statement.setString(2, sourceType.name)
            statement.setString(3, sourceObjectKey)
            statement.setString(4, runKey)
            statement.setString(5, RestaurantImportStatus.RUNNING.name)
            statement.setObject(6, LocalDateTime.now())
            statement.setObject(7, LocalDateTime.now())
            statement
        }, keyHolder)
        return keyHolder.key!!.toLong()
    }
}
