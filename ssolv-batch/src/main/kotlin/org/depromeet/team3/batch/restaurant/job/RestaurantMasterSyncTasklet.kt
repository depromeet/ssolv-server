package org.depromeet.team3.batch.restaurant.job

import org.depromeet.team3.restaurant.RestaurantBusinessStatus
import org.depromeet.team3.restaurant.RestaurantDiffType
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Component
class RestaurantMasterSyncTasklet(private val jdbcTemplate: JdbcTemplate, private val transactionTemplate: TransactionTemplate) : Tasklet {
    private val logger = LoggerFactory.getLogger(RestaurantMasterSyncTasklet::class.java)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val executionContext = chunkContext.stepContext.stepExecution.jobExecution.executionContext
        val importJobId = executionContext.getLong(RestaurantImportContextKeys.IMPORT_JOB_ID)
        val runKey = executionContext.getString(RestaurantImportContextKeys.IMPORT_RUN_KEY)

        var upserted = 0
        var missing = 0
        transactionTemplate.executeWithoutResult {
            upserted = upsertAddedAndUpdated(importJobId, runKey)
            missing = markMissing(importJobId)
            bindMasterIds(importJobId)
        }

        logger.info("식당 master 동기화 완료: importJobId={}, upserted={}, missing={}", importJobId, upserted, missing)
        return RepeatStatus.FINISHED
    }

    private fun upsertAddedAndUpdated(importJobId: Long, runKey: String): Int = jdbcTemplate.update(
        """
        INSERT INTO tb_restaurant_master
            (source_type, source_key, name, normalized_name, address, latitude, longitude, category,
             region_code, phone_number, business_status, content_hash, last_import_job_id,
             missing_count, created_at, updated_at)
        SELECT s.source_type, s.source_key, s.name, s.normalized_name, s.address, s.latitude, s.longitude,
               s.category, s.region_code, s.phone_number, s.business_status, s.content_hash,
               s.import_job_id, 0, ?, ?
        FROM tb_restaurant_staging s
        JOIN tb_restaurant_import_job j
          ON s.import_job_id = j.id
        JOIN tb_restaurant_import_diff d
          ON s.source_type = d.source_type
         AND s.source_key = d.source_key
        WHERE d.import_job_id = ?
          AND j.run_key = ?
          AND d.diff_type IN (?, ?)
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            normalized_name = VALUES(normalized_name),
            address = VALUES(address),
            latitude = VALUES(latitude),
            longitude = VALUES(longitude),
            category = VALUES(category),
            region_code = VALUES(region_code),
            phone_number = VALUES(phone_number),
            business_status = VALUES(business_status),
            content_hash = VALUES(content_hash),
            last_import_job_id = VALUES(last_import_job_id),
            missing_count = 0,
            updated_at = VALUES(updated_at)
        """.trimIndent(),
        LocalDateTime.now(),
        LocalDateTime.now(),
        importJobId,
        runKey,
        RestaurantDiffType.ADDED.name,
        RestaurantDiffType.UPDATED.name,
    )

    private fun markMissing(importJobId: Long): Int = jdbcTemplate.update(
        """
        UPDATE tb_restaurant_master m
        JOIN tb_restaurant_import_diff d
          ON m.source_type = d.source_type
         AND m.source_key = d.source_key
        SET m.business_status = ?,
            m.missing_count = m.missing_count + 1,
            m.last_import_job_id = ?,
            m.updated_at = ?
        WHERE d.import_job_id = ?
          AND d.diff_type = ?
        """.trimIndent(),
        RestaurantBusinessStatus.INACTIVE_CANDIDATE.name,
        importJobId,
        LocalDateTime.now(),
        importJobId,
        RestaurantDiffType.MISSING.name,
    )

    private fun bindMasterIds(importJobId: Long) {
        jdbcTemplate.update(
            """
            UPDATE tb_restaurant_import_diff d
            JOIN tb_restaurant_master m
              ON d.source_type = m.source_type
             AND d.source_key = m.source_key
            SET d.restaurant_master_id = m.id,
                d.updated_at = ?
            WHERE d.import_job_id = ?
              AND d.restaurant_master_id IS NULL
            """.trimIndent(),
            LocalDateTime.now(),
            importJobId,
        )
    }
}
