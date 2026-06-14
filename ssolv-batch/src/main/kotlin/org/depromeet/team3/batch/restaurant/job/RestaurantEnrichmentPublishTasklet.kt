package org.depromeet.team3.batch.restaurant.job

import org.depromeet.team3.batch.restaurant.config.RestaurantImportProperties
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.depromeet.team3.restaurant.RestaurantDiffType
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class RestaurantEnrichmentPublishTasklet(
    private val jdbcTemplate: JdbcTemplate,
    private val stringRedisTemplate: StringRedisTemplate,
    private val properties: RestaurantImportProperties,
) : Tasklet {
    private val logger = LoggerFactory.getLogger(RestaurantEnrichmentPublishTasklet::class.java)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val importJobId = chunkContext.stepContext.stepExecution.jobExecution.executionContext
            .getLong(RestaurantImportContextKeys.IMPORT_JOB_ID)
        val limit = chunkContext.stepContext.jobParameters["enrichmentPublishLimit"]?.toString()?.toIntOrNull()
            ?: properties.defaults.enrichmentPublishLimit

        val rows = jdbcTemplate.queryForList(
            """
            SELECT m.id, m.name, m.address, m.latitude, m.longitude
            FROM tb_restaurant_master m
            JOIN tb_restaurant_import_diff d
              ON m.id = d.restaurant_master_id
            LEFT JOIN tb_restaurant_google_place_link l
              ON m.id = l.restaurant_id
            WHERE d.import_job_id = ?
              AND d.diff_type IN (?, ?)
              AND l.id IS NULL
            LIMIT ?
            """.trimIndent(),
            importJobId,
            RestaurantDiffType.ADDED.name,
            RestaurantDiffType.UPDATED.name,
            limit,
        )

        rows.forEach { row ->
            val restaurantId = row["id"].toString()
            val name = row["name"].toString()
            val address = row["address"].toString()
            val message = mapOf(
                "restaurantId" to restaurantId,
                "importJobId" to importJobId.toString(),
                "query" to "$name $address",
                "latitude" to row["latitude"].toString(),
                "longitude" to row["longitude"].toString(),
            )
            stringRedisTemplate.opsForStream<String, String>().add(
                StreamRecords.newRecord()
                    .`in`(RedisStreamConstants.RESTAURANT_ENRICHMENT_STREAM)
                    .ofMap(message),
            )
        }

        logger.info("Google Places 보강 대상 Stream 발행 완료: importJobId={}, published={}", importJobId, rows.size)
        return RepeatStatus.FINISHED
    }
}
