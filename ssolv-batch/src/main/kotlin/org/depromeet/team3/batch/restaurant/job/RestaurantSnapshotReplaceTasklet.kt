package org.depromeet.team3.batch.restaurant.job

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
class RestaurantSnapshotReplaceTasklet(private val jdbcTemplate: JdbcTemplate, private val transactionTemplate: TransactionTemplate) :
    Tasklet {
    private val logger = LoggerFactory.getLogger(RestaurantSnapshotReplaceTasklet::class.java)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val executionContext = chunkContext.stepContext.stepExecution.jobExecution.executionContext
        val importJobId = executionContext.getLong(RestaurantImportContextKeys.IMPORT_JOB_ID)
        val runKey = executionContext.getString(RestaurantImportContextKeys.IMPORT_RUN_KEY)
        val sourceType = executionContext.getString(RestaurantImportContextKeys.SOURCE_TYPE)
        val importMonth = executionContext.getString(RestaurantImportContextKeys.IMPORT_MONTH)

        var inserted = 0
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update("DELETE FROM tb_restaurant_snapshot WHERE source_type = ?", sourceType)
            inserted = jdbcTemplate.update(
                """
                INSERT INTO tb_restaurant_snapshot
                    (source_type, source_key, content_hash, restaurant_master_id, created_at)
                SELECT s.source_type, s.source_key, s.content_hash, m.id, ?
                FROM tb_restaurant_staging s
                JOIN tb_restaurant_import_job j
                  ON s.import_job_id = j.id
                JOIN tb_restaurant_master m
                  ON s.source_type = m.source_type
                 AND s.source_key = m.source_key
                WHERE j.run_key = ?
                  AND j.source_type = ?
                  AND j.import_month = ?
                """.trimIndent(),
                LocalDateTime.now(),
                runKey,
                sourceType,
                importMonth,
            )
        }

        logger.info("식당 snapshot 교체 완료: importJobId={}, sourceType={}, inserted={}", importJobId, sourceType, inserted)
        return RepeatStatus.FINISHED
    }
}
