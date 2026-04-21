package org.depromeet.team3.auth.application

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.application.common.WithdrawService
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.fixture.MeetingAttendeeFixture
import org.depromeet.team3.fixture.MeetingFixture
import org.depromeet.team3.fixture.StationFixture
import org.depromeet.team3.fixture.UserFixture
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional

@UnitTest
class WithdrawServiceTest {

    @Mock private lateinit var userJpaRepository: UserRepository

    @Mock private lateinit var kakaoOAuthClient: KakaoOAuthClient

    @Mock private lateinit var meetingJpaRepository: MeetingJpaRepository

    @Mock private lateinit var meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var withdrawService: WithdrawService

    @BeforeEach
    fun setUp() {
        transactionTemplate = mock()
        whenever(transactionTemplate.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        withdrawService = WithdrawService(
            userJpaRepository,
            kakaoOAuthClient,
            meetingJpaRepository,
            meetingAttendeeJpaRepository,
            transactionTemplate,
        )
    }

    @Test
    fun `회원 탈퇴 성공 - 카카오 연동 해제 및 데이터 소프트삭제`() = runTest {
        // given
        val userId = 1L
        val userEntity = UserFixture.createEntity(id = userId, provider = AuthProvider.KAKAO, socialId = "kakao-123")
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(userEntity))
        whenever(meetingJpaRepository.findByHostUserId(userId)).thenReturn(emptyList())
        doReturn(userEntity).whenever(userJpaRepository).save(any())

        // when
        withdrawService.withdraw(userId)

        // then
        verify(userJpaRepository).save(any())
    }

    @Test
    fun `회원 탈퇴 성공 - 애플 데이터 소프트삭제`() = runTest {
        // given
        val userId = 1L
        val userEntity = UserFixture.createEntity(id = userId, provider = AuthProvider.APPLE)
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(userEntity))
        whenever(meetingJpaRepository.findByHostUserId(userId)).thenReturn(emptyList())
        doReturn(userEntity).whenever(userJpaRepository).save(any())

        // when
        withdrawService.withdraw(userId)

        // then
        verify(userJpaRepository).save(any())
    }

    @Test
    fun `회원 탈퇴 실패 - 사용자를 찾을 수 없음`() = runTest {
        // given
        val userId = 1L
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.empty())

        // when & then
        val exception = assertThrows<AuthException> { withdrawService.withdraw(userId) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
    }

    @Test
    fun `회원 탈퇴 실패 - 다른 참석자가 있는 모임 호스팅 중`() = runTest {
        // given
        val userId = 1L
        val meetingId = 100L
        val hostUser = UserFixture.createEntity(id = userId)
        val otherUser = UserFixture.createEntity(id = 2L, email = "other@test.com", socialId = "other-social")
        val station = StationFixture.createEntity()
        val meeting = MeetingFixture.createEntity(id = meetingId, hostUser = hostUser, station = station)
        val hostAttendee = MeetingAttendeeFixture.createEntity(id = 201L, meeting = meeting, user = hostUser)
        val otherAttendee = MeetingAttendeeFixture.createEntity(id = 200L, meeting = meeting, user = otherUser)

        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(hostUser))
        whenever(meetingJpaRepository.findByHostUserId(userId)).thenReturn(listOf(meeting))
        whenever(meetingAttendeeJpaRepository.findByMeetingId(meetingId)).thenReturn(listOf(hostAttendee, otherAttendee))

        // when & then
        val exception = assertThrows<AuthException> { withdrawService.withdraw(userId) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS)
    }
}
