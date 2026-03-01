package org.depromeet.team3.auth.application

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.util.TestDataFactory
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.auth.exception.AuthException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.quality.Strictness
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.application.common.WithdrawService
import org.depromeet.team3.meeting.MeetingRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.depromeet.team3.meeting.util.MeetingTestDataFactory
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.meetingattendee.util.MeetingAttendeeTestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawServiceTest {

    @Mock
    private lateinit var userQueryRepository: UserQueryRepository

    @Mock
    private lateinit var userCommandRepository: UserCommandRepository

    @Mock
    private lateinit var kakaoOAuthClient: KakaoOAuthClient

    @Mock
    private lateinit var meetingRepository: MeetingRepository

    @Mock
    private lateinit var meetingAttendeeRepository: MeetingAttendeeRepository

    @Mock
    private lateinit var coroutineDispatchers: CoroutineDispatchers

    private lateinit var withdrawService: WithdrawService

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        lenient().whenever(coroutineDispatchers.VT).thenReturn(testDispatcher)
        
        withdrawService = WithdrawService(
            userQueryRepository,
            userCommandRepository,
            kakaoOAuthClient,
            meetingRepository,
            meetingAttendeeRepository,
            coroutineDispatchers
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `회원 탈퇴 성공 - 카카오 연동 해제 및 데이터 삭제`() {
        runTest {
            // given
            val userId = 1L
            val socialId = "kakao-123"
            val user = TestDataFactory.createUser(
                id = userId,
                provider = AuthProvider.KAKAO,
                socialId = socialId
            )
            whenever(userQueryRepository.findById(userId)).thenReturn(user)
            whenever(meetingRepository.findMeetingsByUserId(userId)).thenReturn(emptyList())

            // when
            withdrawService.withdraw(userId)

            // then
            verify(kakaoOAuthClient).unlink(socialId)
            verify(userCommandRepository).save(argThat { 
                this.email.startsWith("withdrawn_") && 
                this.socialId.startsWith("withdrawn_") &&
                this.deletedAt != null
            })
        }
    }

    @Test
    fun `회원 탈퇴 성공 - 애플 데이터 삭제`() {
        runTest {
            // given
            val userId = 1L
            val user = TestDataFactory.createUser(
                id = userId,
                provider = AuthProvider.APPLE
            )
            whenever(userQueryRepository.findById(userId)).thenReturn(user)
            whenever(meetingRepository.findMeetingsByUserId(userId)).thenReturn(emptyList())

            // when
            withdrawService.withdraw(userId)

            // then
            verify(userCommandRepository).save(argThat { 
                this.email.startsWith("withdrawn_") && 
                this.socialId.startsWith("withdrawn_") &&
                this.deletedAt != null
            })
            verifyNoInteractions(kakaoOAuthClient)
        }
    }

    @Test
    fun `회원 탈퇴 실패 - 사용자를 찾을 수 없음`() {
        runTest {
            // given
            val userId = 1L
            whenever(userQueryRepository.findById(userId)).thenReturn(null)

            // when & then
            val exception = assertThrows<AuthException> {
                runBlocking { withdrawService.withdraw(userId) }
            }
            assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
        }
    }

    @Test
    fun `회원 탈퇴 실패 - 다른 참석자가 있는 모임 호스팅 중`() {
        runTest {
            // given
            val userId = 1L
            val user = TestDataFactory.createUser(id = userId)
            val meetingId = 100L
            val meeting = MeetingTestDataFactory.createMeeting(
                id = meetingId,
                hostUserId = userId
            )
            val otherAttendee = MeetingAttendeeTestDataFactory.createMeetingAttendee(
                id = 200L,
                meetingId = meetingId,
                userId = 2L // 다른 사용자
            )
            val hostAttendee = MeetingAttendeeTestDataFactory.createMeetingAttendee(
                id = 201L,
                meetingId = meetingId,
                userId = userId
            )

            whenever(userQueryRepository.findById(userId)).thenReturn(user)
            whenever(meetingRepository.findMeetingsByUserId(userId)).thenReturn(listOf(meeting))
            whenever(meetingAttendeeRepository.findByMeetingId(meetingId)).thenReturn(listOf(hostAttendee, otherAttendee))

            // when & then
            val exception = assertThrows<AuthException> {
                runBlocking { withdrawService.withdraw(userId) }
            }
            assertThat(exception.errorCode).isEqualTo(ErrorCode.CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS)
        }
    }
}
