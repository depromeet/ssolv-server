package org.depromeet.team3.auth.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.application.common.WithdrawService
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.TestEntityFactory
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionSynchronizationManager

@ExtendWith(MockitoExtension::class)
class WithdrawServiceTest {

    @Mock private lateinit var userJpaRepository: UserRepository
    @Mock private lateinit var kakaoOAuthClient: KakaoOAuthClient
    @Mock private lateinit var meetingJpaRepository: MeetingJpaRepository
    @Mock private lateinit var meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var withdrawService: WithdrawService

    @BeforeEach
    fun setUp() {
        val coroutineDispatchers = object : CoroutineDispatchers() {
            override val VT = Dispatchers.Unconfined
        }
        transactionTemplate = mock()
        // transactionTemplate 결과 리턴 보장 (NPE 방지)
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock()) ?: Unit
        }
        withdrawService = WithdrawService(
            userJpaRepository, kakaoOAuthClient,
            meetingJpaRepository, meetingAttendeeJpaRepository,
            transactionTemplate
        )
    }

    @Test
    fun `회원 탈퇴 성공 - 카카오 연동 해제 및 데이터 소프트삭제`() = runBlocking {
        val userId = 1L
        val socialId = "kakao-123"
        val userEntity = TestEntityFactory.createUserEntity(
            id = userId, provider = AuthProvider.KAKAO, socialId = socialId
        )

        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(userEntity))
        whenever(meetingJpaRepository.findByHostUserId(userId)).thenReturn(emptyList())
        whenever(userJpaRepository.save(any())).thenReturn(userEntity)

        withdrawService.withdraw(userId)

        verify(userJpaRepository).save(any())
    }

    @Test
    fun `회원 탈퇴 성공 - 애플 데이터 소프트삭제`() = runBlocking {
        val userId = 1L
        val userEntity = TestEntityFactory.createUserEntity(id = userId, provider = AuthProvider.APPLE)

        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(userEntity))
        whenever(meetingJpaRepository.findByHostUserId(userId)).thenReturn(emptyList())
        whenever(userJpaRepository.save(any())).thenReturn(userEntity)

        withdrawService.withdraw(userId)

        verify(userJpaRepository).save(any())
    }

    @Test
    fun `회원 탈퇴 실패 - 사용자를 찾을 수 없음`() = runBlocking {
        val userId = 1L
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.empty())

        val exception = assertThrows<AuthException> {
            withdrawService.withdraw(userId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
    }

    @Test
    fun `회원 탈퇴 실패 - 다른 참석자가 있는 모임 호스팅 중`() = runBlocking {
        val userId = 1L
        val meetingId = 100L

        val hostUserEntity = TestEntityFactory.createUserEntity(id = userId)
        val otherUserEntity = TestEntityFactory.createUserEntity(id = 2L, email = "other@test.com", socialId = "other-social")
        val station = TestEntityFactory.createStationEntity()
        val meetingEntity = TestEntityFactory.createMeetingEntity(id = meetingId, hostUser = hostUserEntity, station = station)

        val hostAttendee = TestEntityFactory.createMeetingAttendeeEntity(id = 201L, meeting = meetingEntity, user = hostUserEntity)
        val otherAttendee = TestEntityFactory.createMeetingAttendeeEntity(id = 200L, meeting = meetingEntity, user = otherUserEntity)

        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(hostUserEntity))
        whenever(meetingJpaRepository.findByHostUserId(userId)).thenReturn(listOf(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingId(meetingId)).thenReturn(listOf(hostAttendee, otherAttendee))

        val exception = assertThrows<AuthException> {
            withdrawService.withdraw(userId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS)
    }
}
