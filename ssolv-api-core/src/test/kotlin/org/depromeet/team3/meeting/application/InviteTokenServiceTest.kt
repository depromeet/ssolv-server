package org.depromeet.team3.meeting.application

import kotlinx.coroutines.test.runTest
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.fixture.MeetingFixture
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.exception.InvalidInviteTokenException
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.*
import java.time.LocalDateTime

@UnitTest
class InviteTokenServiceTest {

    @Mock private lateinit var meetingRepository: MeetingRepository
    @Mock private lateinit var meetingAttendeeRepository: MeetingAttendeeRepository

    private lateinit var inviteTokenService: InviteTokenService

    @BeforeEach
    fun setUp() {
        inviteTokenService = InviteTokenService(meetingRepository, meetingAttendeeRepository)
    }

    @Test
    fun `초대 토큰 생성 성공`() = runTest {
        // given
        val meetingId = 1L
        val meeting = MeetingFixture.create(id = meetingId, isClosed = false, endAt = LocalDateTime.now().plusHours(2))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)

        // when
        val result = inviteTokenService.generateInviteToken(meetingId)

        // then
        assertNotNull(result)
        assertTrue(result.contains("token="))
        assertTrue(result.contains("validate-invite"))
    }

    @Test
    fun `존재하지 않는 모임으로 초대 토큰 생성 시 예외 발생`() = runTest {
        // given
        whenever(meetingRepository.findById(999L)).thenReturn(null)

        // when & then
        assertThrows<IllegalArgumentException> {
            inviteTokenService.generateInviteToken(999L)
        }
    }

    @Test
    fun `종료된 모임으로 초대 토큰 생성 시 예외 발생`() = runTest {
        // given
        val meetingId = 1L
        val meeting = MeetingFixture.create(id = meetingId, isClosed = true, endAt = LocalDateTime.now().minusHours(1))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)

        // when & then
        assertThrows<IllegalStateException> {
            inviteTokenService.generateInviteToken(meetingId)
        }
    }

    @Test
    fun `유효한 토큰 검증 성공`() = runTest {
        // given
        val meetingId = 1L
        val userId = 42L
        val meeting = MeetingFixture.create(id = meetingId, isClosed = false, endAt = LocalDateTime.now().plusHours(2))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(false)

        val tokenUrl = inviteTokenService.generateInviteToken(meetingId)
        val token = tokenUrl.substringAfter("token=")

        // when
        val result = inviteTokenService.validateInviteToken(userId, token)

        // then
        assertNotNull(result)
        assertEquals(meetingId, result.meetingId)
    }

    @Test
    fun `잘못된 토큰 검증 실패`() = runTest {
        assertThrows<InvalidInviteTokenException> {
            inviteTokenService.validateInviteToken(1L, "invalid_token")
        }
    }
}
