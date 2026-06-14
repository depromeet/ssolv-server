package org.depromeet.team3.batch.restaurant.enrichment

import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component

@Component
class RestaurantEnrichmentConsumer(
    private val enrichmentService: RestaurantGoogleEnrichmentService,
    private val stringRedisTemplate: StringRedisTemplate,
) : StreamListener<String, MapRecord<String, String, String>> {
    private val logger = LoggerFactory.getLogger(RestaurantEnrichmentConsumer::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onMessage(message: MapRecord<String, String, String>) {
        val restaurantId = message.value["restaurantId"]?.toLongOrNull()
        if (restaurantId == null) {
            logger.warn("식당 보강 Stream 메시지 파싱 실패: {}", message)
            stringRedisTemplate.opsForStream<String, String>()
                .acknowledge(
                    RedisStreamConstants.RESTAURANT_ENRICHMENT_STREAM,
                    RedisStreamConstants.RESTAURANT_ENRICHMENT_GROUP,
                    message.id,
                )
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                enrichmentService.enrich(restaurantId, message.value["query"])
                stringRedisTemplate.opsForStream<String, String>()
                    .acknowledge(
                        RedisStreamConstants.RESTAURANT_ENRICHMENT_STREAM,
                        RedisStreamConstants.RESTAURANT_ENRICHMENT_GROUP,
                        message.id,
                    )
                logger.info("식당 Google Places 보강 ACK 완료: restaurantId={}", restaurantId)
            } catch (e: Exception) {
                logger.error("식당 Google Places 보강 실패: restaurantId={}", restaurantId, e)
                Sentry.captureException(e)
            }
        }
    }
}
