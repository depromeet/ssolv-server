package org.depromeet.team3.notification.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.OpenTelemetry
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate

@DisplayName("[NotificationConsumer] 알림 스트림 소비 테스트")
class MeetingNotificationConsumerTest {

    private val sendMeetingResultNotificationService = mockk<SendMeetingResultNotificationService>()
    private val stringRedisTemplate = mockk<StringRedisTemplate>()
    private val streamOps = mockk<StreamOperations<String, String, String>>()
    private val consumer = MeetingNotificationConsumer(
        sendMeetingResultNotificationService,
        stringRedisTemplate,
        OpenTelemetry.noop(),
    )

    @Test
    @DisplayName("sent key를 FCM 전에 선점하면 실패 메시지가 재처리되어도 발송이 skip되어 유실될 수 있다")
    fun `sent key 선점 후 FCM 실패 시 재처리에서 유실 가능성을 재현한다`() {
        val message = MapRecord.create(
            RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
            mapOf("meetingId" to "1", "userId" to "10"),
        ).withId(RecordId.of("999-0"))

        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps

        var sendAttempts = 0
        coEvery { sendMeetingResultNotificationService.send(1L, 10L) } answers {
            sendAttempts++
            if (sendAttempts == 1) {
                throw RuntimeException("sent key 선점 후 FCM 실패 (meetingId=1, userId=10)")
            }
            // 2번째 호출은 sent key 존재로 발송 로직이 skip된 상황을 재현한다.
        }

        every { streamOps.acknowledge(any<String>(), any<MapRecord<String, String, String>>()) } returns 1L

        consumer.onMessage(message)
        coVerify(timeout = 5_000, exactly = 1) {
            sendMeetingResultNotificationService.send(1L, 10L)
        }
        verify(exactly = 0) {
            streamOps.acknowledge(RedisStreamConstants.MEETING_NOTIFICATION_GROUP, message)
        }

        consumer.onMessage(message)
        coVerify(timeout = 5_000, exactly = 2) {
            sendMeetingResultNotificationService.send(1L, 10L)
        }
        verify(exactly = 1) {
            streamOps.acknowledge(RedisStreamConstants.MEETING_NOTIFICATION_GROUP, message)
        }
    }

    @Test
    @DisplayName("FCM 성공 후 ACK 실패로 같은 메시지가 재처리되면 FCM이 중복 호출될 수 있다")
    fun `FCM 성공 후 ACK 실패 시 재처리로 중복 발송 가능성을 재현한다`() {
        val message = MapRecord.create(
            RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
            mapOf("meetingId" to "1", "userId" to "10"),
        ).withId(RecordId.of("999-1"))

        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps
        var sendAttempts = 0
        coEvery { sendMeetingResultNotificationService.send(1L, 10L) } answers {
            sendAttempts++
            if (sendAttempts == 2) {
                throw RuntimeException("FCM 중복 발송 시도 감지 (meetingId=1, userId=10, attempt=$sendAttempts)")
            }
        }

        var ackAttempts = 0
        every { streamOps.acknowledge(any<String>(), any<MapRecord<String, String, String>>()) } answers {
            ackAttempts++
            if (ackAttempts == 1) throw RuntimeException("Redis ACK 장애") else 1L
        }

        consumer.onMessage(message)
        coVerify(timeout = 5_000, exactly = 1) {
            sendMeetingResultNotificationService.send(1L, 10L)
        }

        consumer.onMessage(message)
        coVerify(timeout = 5_000, exactly = 2) {
            sendMeetingResultNotificationService.send(1L, 10L)
        }
        verify(exactly = 1) {
            streamOps.acknowledge(RedisStreamConstants.MEETING_NOTIFICATION_GROUP, message)
        }
    }
}
