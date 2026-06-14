package org.depromeet.team3.batch.restaurant.job

import org.depromeet.team3.restaurant.RestaurantImportStatus
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class RestaurantImportJobStatusListener(private val jdbcTemplate: JdbcTemplate) : JobExecutionListener {

    override fun afterJob(jobExecution: JobExecution) {
        val context = jobExecution.executionContext
        if (!context.containsKey(RestaurantImportContextKeys.IMPORT_JOB_ID)) return

        val importJobId = context.getLong(RestaurantImportContextKeys.IMPORT_JOB_ID)
        val status = if (jobExecution.status == BatchStatus.COMPLETED) {
            RestaurantImportStatus.COMPLETED
        } else {
            RestaurantImportStatus.FAILED
        }
        val failureReason = jobExecution.allFailureExceptions
            .joinToString("\n") { it.message ?: it.javaClass.simpleName }
            .ifBlank { null }

        jdbcTemplate.update(
            """
            UPDATE tb_restaurant_import_job
            SET status = ?,
                completed_at = ?,
                failure_reason = ?,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            status.name,
            LocalDateTime.now(),
            failureReason,
            LocalDateTime.now(),
            importJobId,
        )
    }
}
