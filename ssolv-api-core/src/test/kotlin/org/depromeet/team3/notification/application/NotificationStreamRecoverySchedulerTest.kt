package org.depromeet.team3.notification.application

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.depromeet.team3.common.constants.RedisStreamConstants.MEETING_NOTIFICATION_GROUP
import org.depromeet.team3.common.constants.RedisStreamConstants.MEETING_NOTIFICATION_STREAM
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate

@DisplayName("[RecoveryScheduler] 알림 스트림 복구 스케줄러 테스트")
class NotificationStreamRecoverySchedulerTest {

    private val stringRedisTemplate = mockk<StringRedisTemplate>()
    private val sendMeetingResultNotificationService = mockk<SendMeetingResultNotificationService>()
    private val streamOps = mockk<StreamOperations<String, String, String>>()

    private val scheduler = NotificationStreamRecoveryScheduler(
        stringRedisTemplate,
        sendMeetingResultNotificationService,
    )

    @BeforeEach
    fun setUp() {
        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps
    }

    private fun autoClaimResponse(vararg records: MapRecord<String, String, String>): List<Any> {
        val claimedRecords = records.map { record ->
            listOf(
                record.id.value.toByteArray(),
                record.value.flatMap { (key, value) ->
                    listOf(key.toByteArray(), value.toByteArray())
                },
            )
        }
        return listOf("0-0".toByteArray(), claimedRecords, emptyList<Any>())
    }

    private fun claimedRecord(id: String, meetingId: Long?, userId: Long?): MapRecord<String, String, String> {
        val fields = buildMap<String, String> {
            if (meetingId != null) put("meetingId", meetingId.toString())
            if (userId != null) put("userId", userId.toString())
        }
        return MapRecord.create(MEETING_NOTIFICATION_STREAM, fields).withId(RecordId.of(id))
    }

    @Test
    @DisplayName("XAUTOCLAIM 결과가 비어 있으면 서비스를 호출하지 않는다")
    fun `XAUTOCLAIM 결과가 비어 있으면 서비스를 호출하지 않는다`() {
        every { stringRedisTemplate.execute(any<RedisCallback<Any>>()) } returns autoClaimResponse()

        scheduler.recoverPendingMessages()

        coVerify(exactly = 0) { sendMeetingResultNotificationService.send(any(), any()) }
    }

    @Test
    @DisplayName("idle 메시지가 있으면 XAUTOCLAIM 후 서비스를 호출하고 성공 시 ACK한다")
    fun `idle 메시지 재처리 성공 시 ACK한다`() = runTest {
        val claimed = claimedRecord("2-0", meetingId = 10L, userId = 20L)

        every { stringRedisTemplate.execute(any<RedisCallback<Any>>()) } returns autoClaimResponse(claimed)
        coEvery { sendMeetingResultNotificationService.send(10L, 20L) } just Runs
        every { streamOps.acknowledge(any<String>(), any<String>(), any<RecordId>()) } returns 1L

        scheduler.recoverPendingMessages()

        coVerify { sendMeetingResultNotificationService.send(10L, 20L) }
        verify { streamOps.acknowledge(MEETING_NOTIFICATION_STREAM, MEETING_NOTIFICATION_GROUP, RecordId.of("2-0")) }
    }

    @Test
    @DisplayName("서비스 호출 실패 시 ACK를 호출하지 않아 PEL에 잔류시킨다")
    fun `서비스 실패 시 ACK하지 않는다`() = runTest {
        val claimed = claimedRecord("3-0", meetingId = 10L, userId = 20L)

        every { stringRedisTemplate.execute(any<RedisCallback<Any>>()) } returns autoClaimResponse(claimed)
        coEvery { sendMeetingResultNotificationService.send(any(), any()) } throws RuntimeException("FCM 장애")

        scheduler.recoverPendingMessages()

        verify(exactly = 0) { streamOps.acknowledge(MEETING_NOTIFICATION_STREAM, MEETING_NOTIFICATION_GROUP, RecordId.of("3-0")) }
    }

    @Test
    @DisplayName("meetingId 또는 userId가 없는 메시지는 서비스 호출 없이 즉시 ACK 폐기한다")
    fun `파싱 불가 메시지는 즉시 ACK 처리한다`() = runTest {
        val claimed = claimedRecord("4-0", meetingId = 10L, userId = null) // userId 누락

        every { stringRedisTemplate.execute(any<RedisCallback<Any>>()) } returns autoClaimResponse(claimed)
        every { streamOps.acknowledge(any<String>(), any<String>(), any<RecordId>()) } returns 1L

        scheduler.recoverPendingMessages()

        verify(exactly = 1) { streamOps.acknowledge(MEETING_NOTIFICATION_STREAM, MEETING_NOTIFICATION_GROUP, RecordId.of("4-0")) }
        coVerify(exactly = 0) { sendMeetingResultNotificationService.send(any(), any()) }
    }
}
