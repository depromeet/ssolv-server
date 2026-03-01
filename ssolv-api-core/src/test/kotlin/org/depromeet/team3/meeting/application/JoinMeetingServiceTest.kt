package org.depromeet.team3.meeting.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meeting.util.MeetingTestDataFactory
import org.depromeet.team3.meetingattendee.MeetingAttendee
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.meetingattendee.MuzziColor
import org.depromeet.team3.meetingattendee.exception.MeetingAttendeeException
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.util.DataEncoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

class JoinMeetingServiceTest {

    @Mock
    private lateinit var meetingRepository: MeetingRepository

    @Mock
    private lateinit var meetingAttendeeRepository: MeetingAttendeeRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var coroutineDispatchers: CoroutineDispatchers
    @Mock
    private lateinit var userRepository: UserRepository

    private lateinit var joinMeetingService: JoinMeetingService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        coroutineDispatchers = mock()
        whenever(coroutineDispatchers.VT).thenReturn(Dispatchers.Unconfined)
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        joinMeetingService = JoinMeetingService(
            meetingRepository,
            meetingAttendeeRepository,
            transactionTemplate,
            coroutineDispatchers
        )
        joinMeetingService = JoinMeetingService(meetingRepository, meetingAttendeeRepository, userRepository)
    }

    @Test
    fun `모임 참여 시 muzziColor가 DEFAULT로 설정된다`() = runBlocking {
        // Given
        val userId = 1L
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "테스트 미팅",
            hostUserId = 2L,
            attendeeCount = 5,
            isClosed = false,
            stationId = 1L,
            endAt = LocalDateTime.now().plusHours(2)
        )

        val user = UserEntity(id = userId, nickname = "테스트유저")
        whenever(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(null)
        whenever(meetingAttendeeRepository.countByMeetingId(meetingId)).thenReturn(2)
        whenever(meetingAttendeeRepository.save(any())).thenAnswer { it.arguments[0] }

        // When
        joinMeetingService.invoke(userId, token)

        // Then
        val attendeeCaptor = argumentCaptor<MeetingAttendee>()
        verify(meetingAttendeeRepository).save(attendeeCaptor.capture())
        
        val savedAttendee = attendeeCaptor.firstValue
        assertEquals(MuzziColor.DEFAULT, savedAttendee.muzziColor)
        assertEquals(userId, savedAttendee.userId)
        assertEquals(meetingId, savedAttendee.meetingId)
        assertEquals("테스트유저", savedAttendee.attendeeNickname)
    }

    @Test
    fun `모임 참여 성공 시 닉네임과 muzziColor가 함께 저장된다`() = runBlocking {
        // Given
        val userId = 10L
        val meetingId = 200L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "점심 모임",
            hostUserId = 5L,
            attendeeCount = 10,
            isClosed = false,
            stationId = 1L,
            endAt = LocalDateTime.now().plusDays(1)
        )

        val user = UserEntity(id = userId, nickname = "성공유저")
        whenever(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user))
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(null)
        whenever(meetingAttendeeRepository.countByMeetingId(meetingId)).thenReturn(5)
        whenever(meetingAttendeeRepository.save(any())).thenAnswer { it.arguments[0] }

        // When
        joinMeetingService.invoke(userId, token)

        // Then
        val attendeeCaptor = argumentCaptor<MeetingAttendee>()
        verify(meetingAttendeeRepository).save(attendeeCaptor.capture())
        
        val savedAttendee = attendeeCaptor.firstValue
        assertNotNull(savedAttendee)
        assertEquals(MuzziColor.DEFAULT, savedAttendee.muzziColor)
        assertEquals("성공유저", savedAttendee.attendeeNickname)
    }

    @Test
    fun `존재하지 않는 모임에 참여하려고 하면 예외가 발생한다`() = runBlocking {
        // Given
        val userId = 1L
        val meetingId = 999L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")

        whenever(meetingRepository.findById(meetingId)).thenReturn(null)

        // When & Then
        val exception = assertThrows<MeetingException> {
            joinMeetingService.invoke(userId, token)
        }
        
        assertEquals(ErrorCode.MEETING_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `종료된 모임에 참여하려고 하면 예외가 발생한다`() = runBlocking {
        // Given
        val userId = 1L
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        
        val closedMeeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "종료된 미팅",
            hostUserId = 2L,
            attendeeCount = 5,
            isClosed = true,
            stationId = 1L,
            endAt = LocalDateTime.now().minusHours(1)
        )

        whenever(meetingRepository.findById(meetingId)).thenReturn(closedMeeting)

        // When & Then
        val exception = assertThrows<MeetingException> {
            joinMeetingService.invoke(userId, token)
        }
        
        assertEquals(ErrorCode.MEETING_ALREADY_CLOSED, exception.errorCode)
    }

    @Test
    fun `이미 참여한 모임에 다시 참여하려고 하면 예외가 발생한다`() = runBlocking {
        // Given
        val userId = 1L
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "테스트 미팅",
            hostUserId = 2L,
            attendeeCount = 5,
            isClosed = false,
            stationId = 1L,
            endAt = LocalDateTime.now().plusHours(2)
        )

        val existingAttendee = MeetingAttendee(
            id = 1L,
            meetingId = meetingId,
            userId = userId,
            attendeeNickname = "기존닉네임",
            muzziColor = MuzziColor.DEFAULT,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )

        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(existingAttendee)

        // When & Then
        val exception = assertThrows<MeetingAttendeeException> {
            joinMeetingService.invoke(userId, token)
        }
        
        assertEquals(ErrorCode.MEETING_ALREADY_JOINED, exception.errorCode)
    }

    @Test
    fun `정원이 다 찬 모임에 참여하려고 하면 예외가 발생한다`() = runBlocking {
        // Given
        val userId = 1L
        val meetingId = 100L
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), "validKey")
        
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "테스트 미팅",
            hostUserId = 2L,
            attendeeCount = 5,
            isClosed = false,
            stationId = 1L,
            endAt = LocalDateTime.now().plusHours(2)
        )

        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(null)
        whenever(meetingAttendeeRepository.countByMeetingId(meetingId)).thenReturn(5)

        // When & Then
        val exception = assertThrows<MeetingException> {
            joinMeetingService.invoke(userId, token)
        }
        
        assertEquals(ErrorCode.MEETING_FULL, exception.errorCode)
    }
}

