package org.depromeet.team3.place.application.execution

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.depromeet.team3.common.constants.RedisStreamConstants.MEETING_CALCULATION_GROUP
import org.depromeet.team3.common.constants.RedisStreamConstants.MEETING_CALCULATION_STREAM
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.PendingMessage
import org.springframework.data.redis.connection.stream.PendingMessages
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

@DisplayName("[RecoveryScheduler] 장소 검색 스트림 복구 스케줄러 테스트")
class PlaceStreamRecoverySchedulerTest {

    private val stringRedisTemplate = mockk<StringRedisTemplate>()
    private val executePlaceSearchService = mockk<ExecutePlaceSearchService>()
    private val streamOps = mockk<StreamOperations<String, String, String>>()

    private val scheduler = PlaceStreamRecoveryScheduler(
        stringRedisTemplate,
        executePlaceSearchService,
    )

    @BeforeEach
    fun setUp() {
        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps
    }

    private fun pendingMessage(id: String, idleSeconds: Long, deliveryCount: Long = 1L) = PendingMessage(
        RecordId.of(id),
        Consumer.from(MEETING_CALCULATION_GROUP, "consumer-1"),
        Duration.ofSeconds(idleSeconds),
        deliveryCount,
    )

    private fun claimedRecord(id: String, meetingIdStr: String?): MapRecord<String, String, String> {
        val fields = buildMap<String, String> {
            if (meetingIdStr != null) put("meetingId", meetingIdStr)
        }
        return MapRecord.create(MEETING_CALCULATION_STREAM, fields).withId(RecordId.of(id))
    }

    @Test
    @DisplayName("pending 메시지가 없으면 claim을 호출하지 않는다")
    fun `pending 메시지가 없으면 claim을 호출하지 않는다`() {
        val emptyPending = PendingMessages(MEETING_CALCULATION_GROUP, emptyList())
        every { streamOps.pending(any<String>(), any<String>(), any<Range<*>>(), any<Long>()) } returns emptyPending

        scheduler.recoverPendingMessages()

        verify(exactly = 0) { streamOps.claim(any<String>(), any<String>(), any<String>(), any<XClaimOptions>()) }
    }

    @Test
    @DisplayName("threshold 미만인 메시지는 claim하지 않는다")
    fun `idle threshold 미만 메시지는 claim하지 않는다`() {
        val recentMessage = pendingMessage("1-0", idleSeconds = 30)
        val pending = PendingMessages(MEETING_CALCULATION_GROUP, listOf(recentMessage))
        every { streamOps.pending(any<String>(), any<String>(), any<Range<*>>(), any<Long>()) } returns pending

        scheduler.recoverPendingMessages()

        verify(exactly = 0) { streamOps.claim(any<String>(), any<String>(), any<String>(), any<XClaimOptions>()) }
    }

    @Test
    @DisplayName("idle 메시지가 있으면 XCLAIM 후 서비스를 호출하고 성공 시 ACK한다")
    fun `idle 메시지 재처리 성공 시 ACK한다`() = runTest {
        val idleMessage = pendingMessage("2-0", idleSeconds = 120)
        val pending = PendingMessages(MEETING_CALCULATION_GROUP, listOf(idleMessage))
        val claimed = claimedRecord("2-0", meetingIdStr = "10")

        every { streamOps.pending(any<String>(), any<String>(), any<Range<*>>(), any<Long>()) } returns pending
        every { streamOps.claim(any<String>(), any<String>(), any<String>(), any<XClaimOptions>()) } returns listOf(claimed)
        coEvery { executePlaceSearchService.execute(10L) } returns PlacesSearchResponse(emptyList())
        every { streamOps.acknowledge(any<String>(), any<String>(), any<RecordId>()) } returns 1L

        scheduler.recoverPendingMessages()

        coVerify { executePlaceSearchService.execute(10L) }
        verify { streamOps.acknowledge(MEETING_CALCULATION_STREAM, MEETING_CALCULATION_GROUP, RecordId.of("2-0")) }
    }

    @Test
    @DisplayName("서비스 호출 실패 시 ACK를 호출하지 않아 PEL에 잔류시킨다")
    fun `서비스 실패 시 ACK하지 않는다`() = runTest {
        val idleMessage = pendingMessage("3-0", idleSeconds = 120)
        val pending = PendingMessages(MEETING_CALCULATION_GROUP, listOf(idleMessage))
        val claimed = claimedRecord("3-0", meetingIdStr = "10")

        every { streamOps.pending(any<String>(), any<String>(), any<Range<*>>(), any<Long>()) } returns pending
        every { streamOps.claim(any<String>(), any<String>(), any<String>(), any<XClaimOptions>()) } returns listOf(claimed)
        coEvery { executePlaceSearchService.execute(any()) } throws RuntimeException("Google API 장애")

        scheduler.recoverPendingMessages()

        verify(exactly = 0) { streamOps.acknowledge(MEETING_CALCULATION_STREAM, MEETING_CALCULATION_GROUP, RecordId.of("3-0")) }
    }

    @Test
    @DisplayName("meetingId가 없거나 파싱 불가한 메시지는 서비스 호출 없이 즉시 ACK 폐기한다")
    fun `파싱 불가 메시지는 즉시 ACK 처리한다`() = runTest {
        val idleMessage = pendingMessage("4-0", idleSeconds = 120)
        val pending = PendingMessages(MEETING_CALCULATION_GROUP, listOf(idleMessage))
        val claimed = claimedRecord("4-0", meetingIdStr = null) // meetingId 누락

        every { streamOps.pending(any<String>(), any<String>(), any<Range<*>>(), any<Long>()) } returns pending
        every { streamOps.claim(any<String>(), any<String>(), any<String>(), any<XClaimOptions>()) } returns listOf(claimed)
        every { streamOps.acknowledge(any<String>(), any<String>(), any<RecordId>()) } returns 1L

        scheduler.recoverPendingMessages()

        verify(exactly = 1) { streamOps.acknowledge(MEETING_CALCULATION_STREAM, MEETING_CALCULATION_GROUP, RecordId.of("4-0")) }
        coVerify(exactly = 0) { executePlaceSearchService.execute(any()) }
    }
}
