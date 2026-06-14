package org.depromeet.team3.batch.restaurant.config

import org.depromeet.team3.batch.restaurant.enrichment.RestaurantEnrichmentConsumer
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.data.redis.stream.Subscription
import java.time.Duration
import java.util.UUID

@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "restaurant.enrichment", name = ["enabled"], havingValue = "true")
class RestaurantEnrichmentStreamsConfig(
    private val consumer: RestaurantEnrichmentConsumer,
    private val stringRedisTemplate: StringRedisTemplate,
) {
    private val logger = LoggerFactory.getLogger(RestaurantEnrichmentStreamsConfig::class.java)

    @Bean
    fun restaurantEnrichmentStreamSubscription(redisConnectionFactory: RedisConnectionFactory): Subscription {
        val streamKey = RedisStreamConstants.RESTAURANT_ENRICHMENT_STREAM
        val groupName = RedisStreamConstants.RESTAURANT_ENRICHMENT_GROUP
        val consumerName = "restaurant_enrichment_${UUID.randomUUID()}"

        initConsumerGroup(streamKey, groupName)

        val options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
            .builder()
            .pollTimeout(Duration.ofSeconds(1))
            .build()
        val container = StreamMessageListenerContainer.create(redisConnectionFactory, options)
        val subscription = container.receive(
            Consumer.from(groupName, consumerName),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
            consumer,
        )
        container.start()
        logger.info("식당 보강 Redis Stream 구독 시작: {}/{}/{}", streamKey, groupName, consumerName)
        return subscription
    }

    private fun initConsumerGroup(streamKey: String, groupName: String) {
        try {
            stringRedisTemplate.opsForStream<String, String>().createGroup(streamKey, groupName)
        } catch (e: Exception) {
            val causeMessage = e.cause?.message ?: ""
            if (e.message?.contains("BUSYGROUP") == true || causeMessage.contains("BUSYGROUP")) return
            logger.warn("식당 보강 Consumer Group 생성 실패: {} / {}", e.message, causeMessage)
        }
    }
}
