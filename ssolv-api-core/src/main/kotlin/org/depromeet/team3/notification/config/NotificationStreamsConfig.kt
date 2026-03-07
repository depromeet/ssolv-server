package org.depromeet.team3.notification.config

import org.springframework.context.annotation.Profile
import org.depromeet.team3.notification.application.MeetingResultSubscriber
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
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
 */
@Configuration
@Profile("!test")
class NotificationStreamsConfig(
    private val meetingResultSubscriber: MeetingResultSubscriber, // 알림 메시지 실처리기
    private val stringRedisTemplate: StringRedisTemplate
) {
    private val logger = LoggerFactory.getLogger(NotificationStreamsConfig::class.java)

    @Bean
    fun notificationStreamMessageListenerContainer(
        redisConnectionFactory: RedisConnectionFactory
    ): Subscription {
        val streamKey = "meeting_notification_stream"
        val groupName = "meeting_notification_group"
        val consumerName = "app_server_${UUID.randomUUID()}"

        // Consumer Group 초기화 (존재하지 않으면 생성)
        try {
            stringRedisTemplate.opsForStream<String, String>().createGroup(streamKey, groupName)
        } catch (e: Exception) {
            // 그룹이 이미 존재하는 경우 무시
            if (e.message?.contains("BUSYGROUP") != true) {
                logger.warn("Consumer Group을 생성하는 데 실패했습니다: ${e.message}")
            }
        }

        val options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
            .pollTimeout(Duration.ofSeconds(1))
            .build()
            
        val container = StreamMessageListenerContainer.create(redisConnectionFactory, options)

        // 스트림 구독 시작
        val subscription = container.receive(
            Consumer.from(groupName, consumerName),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
            meetingResultSubscriber
        )
        
        container.start()
        logger.info("Redis Stream 구독 시작 ($streamKey / $groupName / $consumerName)")
        
        return subscription
    }
}