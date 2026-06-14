package org.depromeet.team3.batch.restaurant.job

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
class RestaurantDeltaDetectTasklet(private val jdbcTemplate: JdbcTemplate, private val transactionTemplate: TransactionTemplate) :
    Tasklet {
    private val logger = LoggerFactory.getLogger(RestaurantDeltaDetectTasklet::class.java)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val executionContext = chunkContext.stepContext.stepExecution.jobExecution.executionContext
        val importJobId = executionContext.getLong(RestaurantImportContextKeys.IMPORT_JOB_ID)
        val runKey = executionContext.getString(RestaurantImportContextKeys.IMPORT_RUN_KEY)
        val sourceType = executionContext.getString(RestaurantImportContextKeys.SOURCE_TYPE)
        val importMonth = executionContext.getString(RestaurantImportContextKeys.IMPORT_MONTH)

        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update("DELETE FROM tb_restaurant_import_diff WHERE import_job_id = ?", importJobId)
            insertAdded(importJobId, runKey, sourceType, importMonth)
            insertUpdated(importJobId, runKey, sourceType, importMonth)
            insertUnchanged(importJobId, runKey, sourceType, importMonth)
            insertMissing(importJobId, runKey, sourceType, importMonth)
        }

        val counts = jdbcTemplate.queryForList(
            """
            SELECT diff_type, COUNT(*) AS count
            FROM tb_restaurant_import_diff
            WHERE import_job_id = ?
            GROUP BY diff_type
            """.trimIndent(),
            importJobId,
        )
        logger.info("식당 변경분 계산 완료: importJobId={}, counts={}", importJobId, counts)
        return RepeatStatus.FINISHED
    }

    private fun insertAdded(importJobId: Long, runKey: String, sourceType: String, importMonth: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_restaurant_import_diff
                (import_job_id, source_type, source_key, diff_type, previous_hash, current_hash, created_at)
            SELECT ?, s.source_type, s.source_key, ?, NULL, s.content_hash, ?
            FROM tb_restaurant_staging s
            JOIN tb_restaurant_import_job j
              ON s.import_job_id = j.id
            LEFT JOIN tb_restaurant_snapshot p
              ON s.source_type = p.source_type
             AND s.source_key = p.source_key
            WHERE j.run_key = ?
              AND j.source_type = ?
              AND j.import_month = ?
              AND p.source_key IS NULL
            """.trimIndent(),
            importJobId,
            RestaurantDiffType.ADDED.name,
            LocalDateTime.now(),
            runKey,
            sourceType,
            importMonth,
        )
    }

    private fun insertUpdated(importJobId: Long, runKey: String, sourceType: String, importMonth: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_restaurant_import_diff
                (import_job_id, source_type, source_key, diff_type, previous_hash, current_hash, created_at)
            SELECT ?, s.source_type, s.source_key, ?, p.content_hash, s.content_hash, ?
            FROM tb_restaurant_staging s
            JOIN tb_restaurant_import_job j
              ON s.import_job_id = j.id
            JOIN tb_restaurant_snapshot p
              ON s.source_type = p.source_type
             AND s.source_key = p.source_key
            WHERE j.run_key = ?
              AND j.source_type = ?
              AND j.import_month = ?
              AND s.content_hash <> p.content_hash
            """.trimIndent(),
            importJobId,
            RestaurantDiffType.UPDATED.name,
            LocalDateTime.now(),
            runKey,
            sourceType,
            importMonth,
        )
    }

    private fun insertUnchanged(importJobId: Long, runKey: String, sourceType: String, importMonth: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_restaurant_import_diff
                (import_job_id, source_type, source_key, diff_type, previous_hash, current_hash, created_at)
            SELECT ?, s.source_type, s.source_key, ?, p.content_hash, s.content_hash, ?
            FROM tb_restaurant_staging s
            JOIN tb_restaurant_import_job j
              ON s.import_job_id = j.id
            JOIN tb_restaurant_snapshot p
              ON s.source_type = p.source_type
             AND s.source_key = p.source_key
            WHERE j.run_key = ?
              AND j.source_type = ?
              AND j.import_month = ?
              AND s.content_hash = p.content_hash
            """.trimIndent(),
            importJobId,
            RestaurantDiffType.UNCHANGED.name,
            LocalDateTime.now(),
            runKey,
            sourceType,
            importMonth,
        )
    }

    private fun insertMissing(importJobId: Long, runKey: String, sourceType: String, importMonth: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_restaurant_import_diff
                (import_job_id, source_type, source_key, diff_type, restaurant_master_id, previous_hash, current_hash, created_at)
            SELECT ?, p.source_type, p.source_key, ?, p.restaurant_master_id, p.content_hash, NULL, ?
            FROM tb_restaurant_snapshot p
            LEFT JOIN tb_restaurant_staging s
              ON p.source_type = s.source_type
             AND p.source_key = s.source_key
            LEFT JOIN tb_restaurant_import_job j
              ON s.import_job_id = j.id
             AND j.run_key = ?
             AND j.source_type = ?
             AND j.import_month = ?
            WHERE p.source_type = ?
              AND s.source_key IS NULL
            """.trimIndent(),
            importJobId,
            RestaurantDiffType.MISSING.name,
            LocalDateTime.now(),
            runKey,
            sourceType,
            importMonth,
            sourceType,
        )
    }
}
