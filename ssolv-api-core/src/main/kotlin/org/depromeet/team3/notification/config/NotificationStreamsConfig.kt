package org.depromeet.team3.notification.config

import org.depromeet.team3.common.constants.RedisStreamConstants
import org.springframework.context.annotation.Profile
import org.depromeet.team3.notification.application.MeetingNotificationConsumer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
 * 알림(Notification) 처리를 위한 Redis Streams 설정 클래스
 *
 * 주요 역할:
 * 1. 알림 전송 이벤트 수신을 위한 Consumer Group 관리
 * 2. 분산 서버 환경에서 메시지 중복 처리 방지 및 전달 신뢰성 보장
 * 3. Redis 연결 종료 시 자동 재시작으로 스트림 구독 안정성 보장
 */
@Configuration
@Profile("!test")
class NotificationStreamsConfig(
    private val meetingNotificationConsumer: MeetingNotificationConsumer,
    private val stringRedisTemplate: StringRedisTemplate
) {
    private val logger = LoggerFactory.getLogger(NotificationStreamsConfig::class.java)

    private lateinit var savedFactory: RedisConnectionFactory
    private lateinit var container: StreamMessageListenerContainer<String, MapRecord<String, String, String>>

    @Bean
    fun notificationStreamMessageListenerContainer(
        redisConnectionFactory: RedisConnectionFactory
    ): Subscription {
        savedFactory = redisConnectionFactory
        return createAndStart()
    }

    private fun createAndStart(): Subscription {
        val streamKey = RedisStreamConstants.MEETING_NOTIFICATION_STREAM
        val groupName = RedisStreamConstants.MEETING_NOTIFICATION_GROUP
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
            meetingNotificationConsumer
        )
        container.start()
        logger.info("Redis Stream 구독 시작 ($streamKey / $groupName / $consumerName)")
        return subscription
    }

    private fun initConsumerGroup(streamKey: String, groupName: String) {
        try {
            stringRedisTemplate.opsForStream<String, String>().createGroup(streamKey, groupName)
        } catch (e: Exception) {
            if (e.message?.contains("BUSYGROUP") != true) {
                logger.warn("Consumer Group 생성 실패 ($groupName): ${e.message}")
            }
        }
    }
}