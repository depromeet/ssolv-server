package org.depromeet.team3.meeting.application

import kotlinx.coroutines.test.runTest
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.fixture.MeetingFixture
import org.depromeet.team3.fixture.UserFixture
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendee
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.meetingattendee.MuzziColor
import org.depromeet.team3.meetingattendee.exception.MeetingAttendeeException
import org.depromeet.team3.util.DataEncoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@UnitTest
class JoinMeetingServiceTest {

    @Mock private lateinit var meetingRepository: MeetingRepository

    @Mock private lateinit var meetingAttendeeRepository: MeetingAttendeeRepository

    @Mock private lateinit var userRepository: UserRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var joinMeetingService: JoinMeetingService

    @BeforeEach
    fun setUp() {
        transactionTemplate = mock()
        whenever(transactionTemplate.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        joinMeetingService = JoinMeetingService(
            meetingRepository,
            meetingAttendeeRepository,
            transactionTemplate,
            userRepository,
        )
    }

    @Test
    fun `모임 참여 시 muzziColor가 DEFAULT로 설정된다`() = runTest {
        // given
        val userId = 1L
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        val meeting = MeetingFixture.create(id = meetingId, attendeeCount = 5, isClosed = false, endAt = LocalDateTime.now().plusHours(2))
        val user = UserFixture.createEntity(id = userId, nickname = "테스트유저")
        whenever(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(null)
        whenever(meetingAttendeeRepository.countByMeetingId(meetingId)).thenReturn(2)
        whenever(meetingAttendeeRepository.save(any())).thenAnswer { it.arguments[0] }

        // when
        joinMeetingService.invoke(userId, token)

        // then
        val captor = argumentCaptor<MeetingAttendee>()
        verify(meetingAttendeeRepository).save(captor.capture())
        assertEquals(MuzziColor.DEFAULT, captor.firstValue.muzziColor)
        assertEquals(userId, captor.firstValue.userId)
        assertEquals(meetingId, captor.firstValue.meetingId)
        assertEquals("테스트유저", captor.firstValue.attendeeNickname)
    }

    @Test
    fun `모임 참여 성공 시 닉네임과 muzziColor가 함께 저장된다`() = runTest {
        // given
        val userId = 10L
        val meetingId = 200L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        val meeting = MeetingFixture.create(id = meetingId, attendeeCount = 10, isClosed = false, endAt = LocalDateTime.now().plusDays(1))
        val user = UserFixture.createEntity(id = userId, nickname = "성공유저")
        whenever(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(null)
        whenever(meetingAttendeeRepository.countByMeetingId(meetingId)).thenReturn(5)
        whenever(meetingAttendeeRepository.save(any())).thenAnswer { it.arguments[0] }

        // when
        joinMeetingService.invoke(userId, token)

        // then
        val captor = argumentCaptor<MeetingAttendee>()
        verify(meetingAttendeeRepository).save(captor.capture())
        assertNotNull(captor.firstValue)
        assertEquals(MuzziColor.DEFAULT, captor.firstValue.muzziColor)
        assertEquals("성공유저", captor.firstValue.attendeeNickname)
    }

    @Test
    fun `존재하지 않는 모임에 참여하려고 하면 예외가 발생한다`() = runTest {
        // given
        val token = DataEncoder.encodeWithSeparator(":", "999", "validKey")
        whenever(meetingRepository.findById(999L)).thenReturn(null)

        // when & then
        val exception = assertThrows<MeetingException> { joinMeetingService.invoke(1L, token) }
        assertEquals(ErrorCode.MEETING_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `종료된 모임에 참여하려고 하면 예외가 발생한다`() = runTest {
        // given
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        val closedMeeting = MeetingFixture.create(id = meetingId, isClosed = true, endAt = LocalDateTime.now().minusHours(1))
        whenever(meetingRepository.findById(meetingId)).thenReturn(closedMeeting)

        // when & then
        val exception = assertThrows<MeetingException> { joinMeetingService.invoke(1L, token) }
        assertEquals(ErrorCode.MEETING_ALREADY_CLOSED, exception.errorCode)
    }

    @Test
    fun `이미 참여한 모임에 다시 참여하려고 하면 예외가 발생한다`() = runTest {
        // given
        val userId = 1L
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        val meeting = MeetingFixture.create(id = meetingId, attendeeCount = 5, isClosed = false, endAt = LocalDateTime.now().plusHours(2))
        val existingAttendee = MeetingAttendee(
            id = 1L,
            meetingId = meetingId,
            userId = userId,
            attendeeNickname = "기존닉네임",
            muzziColor = MuzziColor.DEFAULT,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(existingAttendee)

        // when & then
        val exception = assertThrows<MeetingAttendeeException> { joinMeetingService.invoke(userId, token) }
        assertEquals(ErrorCode.MEETING_ALREADY_JOINED, exception.errorCode)
    }

    @Test
    fun `정원이 다 찬 모임에 참여하려고 하면 예외가 발생한다`() = runTest {
        // given
        val userId = 1L
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        val meeting = MeetingFixture.create(id = meetingId, attendeeCount = 5, isClosed = false, endAt = LocalDateTime.now().plusHours(2))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(null)
        whenever(meetingAttendeeRepository.countByMeetingId(meetingId)).thenReturn(5)

        // when & then
        val exception = assertThrows<MeetingException> { joinMeetingService.invoke(userId, token) }
        assertEquals(ErrorCode.MEETING_FULL, exception.errorCode)
    }
}
