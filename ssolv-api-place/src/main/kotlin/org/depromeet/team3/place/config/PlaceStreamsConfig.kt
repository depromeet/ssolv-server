package org.depromeet.team3.place.config

import org.depromeet.team3.common.constants.RedisStreamConstants
import org.depromeet.team3.place.application.execution.PlaceSearchConsumer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.data.redis.stream.Subscription
import java.time.Duration
import java.util.UUID

/**
 * 식당 도출(Place Recommendation) 처리를 위한 Redis Streams 설정 클래스
 *
 * 주요 역할:
 * 1. 식당 도출 비동기 요청 수신을 위한 Consumer Group 관리
 * 2. 부하가 높은 검색 로직을 분산 처리하기 위한 메시지 큐 인프라 구축
 * 3. Redis 연결 종료 시 자동 재시작으로 스트림 구독 안정성 보장
 */
@Configuration
@Profile("!test")
class PlaceStreamsConfig(private val placeSearchConsumer: PlaceSearchConsumer, private val stringRedisTemplate: StringRedisTemplate) {
    private val logger = LoggerFactory.getLogger(PlaceStreamsConfig::class.java)

    private lateinit var savedFactory: RedisConnectionFactory
    private lateinit var container: StreamMessageListenerContainer<String, MapRecord<String, String, String>>

    @Bean
    fun placeSearchStreamMessageListenerContainer(redisConnectionFactory: RedisConnectionFactory): Subscription {
        savedFactory = redisConnectionFactory
        return createAndStart()
    }

    private fun createAndStart(): Subscription {
        val streamKey = RedisStreamConstants.MEETING_CALCULATION_STREAM
        val groupName = RedisStreamConstants.MEETING_CALCULATION_GROUP
        val consumerName = "app_server_${UUID.randomUUID()}"

        initConsumerGroup(streamKey, groupName)

        val options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
            .builder()
            .pollTimeout(Duration.ofSeconds(1))
            .errorHandler { throwable ->
                logger.error("[Redis Stream] [$streamKey] 연결 오류 감지, 3초 후 재시작 시도", throwable)
                Thread {
                    Thread.sleep(3_000)
                    runCatching { createAndStart() }
                        .onSuccess { logger.info("[Redis Stream] [$streamKey] 재시작 완료") }
                        .onFailure { logger.error("[Redis Stream] [$streamKey] 재시작 실패", it) }
                }.also { it.isDaemon = true }.start()
            }
            .build()

        if (::container.isInitialized && container.isRunning) {
            container.stop()
        }

        container = StreamMessageListenerContainer.create(savedFactory, options)
        val subscription = container.receive(
            Consumer.from(groupName, consumerName),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
            placeSearchConsumer,
        )
        container.start()
        logger.info("Redis Stream 구독 시작 ($streamKey / $groupName / $consumerName)")
        return subscription
    }

    private fun initConsumerGroup(streamKey: String, groupName: String) {
        try {
            stringRedisTemplate.opsForStream<String, String>().createGroup(streamKey, groupName)
        } catch (e: Exception) {
            val causeMessage = e.cause?.message ?: ""
            if (e.message?.contains("BUSYGROUP") == true || causeMessage.contains("BUSYGROUP")) {
                // 이미 존재하는 그룹인 경우 무시
                return
            }
            logger.warn("Consumer Group 생성 실패 ($groupName): ${e.message} / Cause: $causeMessage")
        }
    }
}
