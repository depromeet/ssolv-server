package org.depromeet.team3.meeting.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.util.MeetingTestDataFactory
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

class InviteTokenServiceTest {

    @Mock
    private lateinit var meetingRepository: MeetingRepository


    @Mock
    lateinit var meetingAttendeeRepository: MeetingAttendeeRepository

    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var inviteTokenService: InviteTokenService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        coroutineDispatchers = mock()
        whenever(coroutineDispatchers.VT).thenReturn(Dispatchers.Unconfined)
        inviteTokenService = InviteTokenService(meetingRepository, meetingAttendeeRepository, coroutineDispatchers)
    }

    @Test
    fun `초대 토큰 생성 성공 테스트`() = runBlocking {
        // Given
        val meetingId = 1L
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "테스트 미팅",
            hostUserId = 1L,
            attendeeCount = 5,
            isClosed = false,
            stationId = 1L,
            endAt = LocalDateTime.now().plusHours(2)
        )

        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)

        // When
        val result = inviteTokenService.generateInviteToken(meetingId)

        // Then
        assertNotNull(result)
        assertTrue(result.contains("token="))
        assertTrue(result.contains("validate-invite"))
    }

    @Test
    fun `존재하지 않는 모임으로 초대 토큰 생성 시 예외 발생`() = runBlocking {
        // Given
        val meetingId = 999L

        whenever(meetingRepository.findById(meetingId)).thenReturn(null)

        // When & Then
        assertThrows<IllegalArgumentException> {
            inviteTokenService.generateInviteToken(meetingId)
        }
    }

    @Test
    fun `종료된 모임으로 초대 토큰 생성 시 예외 발생`() = runBlocking {
        // Given
        val meetingId = 1L
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "종료된 미팅",
            hostUserId = 1L,
            attendeeCount = 5,
            isClosed = true,
            stationId = 1L,
            endAt = LocalDateTime.now().minusHours(1)
        )

        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)

        // When & Then
        assertThrows<IllegalStateException> {
            inviteTokenService.generateInviteToken(meetingId)
        }
    }

    @Test
    fun `유효한 토큰 검증 성공 테스트`() = runBlocking {
        // Given
        val meetingId = 1L
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "테스트 미팅",
            hostUserId = 1L,
            attendeeCount = 5,
            isClosed = false,
            stationId = 1L,
            endAt = LocalDateTime.now().plusHours(2)
        )

        val userId = 42L
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(false)

        val tokenUrl = inviteTokenService.generateInviteToken(meetingId)
        val token = tokenUrl.substringAfter("token=")

        // When
        val result = inviteTokenService.validateInviteToken(userId, token)

        // Then
        assertNotNull(result)
        assertEquals(meetingId, result.meetingId)
    }

    @Test
    fun `잘못된 토큰 검증 실패 테스트`() = runBlocking {
        // Given
        val invalidToken = "invalid_token"

        // When & Then
        assertThrows<org.depromeet.team3.meeting.exception.InvalidInviteTokenException> {
            inviteTokenService.validateInviteToken(1L, invalidToken)
        }
    }

    @Test
    fun `존재하지 않는 모임 ID로 토큰 검증 실패 테스트`() = runBlocking {
        // Given
        val meetingId = 999L

        whenever(meetingRepository.findById(meetingId)).thenReturn(null)

        assertThrows<IllegalArgumentException> {
            inviteTokenService.generateInviteToken(meetingId)
        }

        val fakeToken = "fake_token_for_testing"

        // When & Then
        assertThrows<org.depromeet.team3.meeting.exception.InvalidInviteTokenException> {
            inviteTokenService.validateInviteToken(1L, fakeToken)
        }
    }
}
