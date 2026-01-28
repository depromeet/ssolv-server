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
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.application.common.WithdrawService

@ExtendWith(MockitoExtension::class)
class WithdrawServiceTest {

    @Mock
    private lateinit var userQueryRepository: UserQueryRepository

    @Mock
    private lateinit var userCommandRepository: UserCommandRepository

    @Mock
    private lateinit var kakaoOAuthClient: KakaoOAuthClient

    private lateinit var withdrawService: WithdrawService

    @BeforeEach
    fun setUp() {
        withdrawService = WithdrawService(
            userQueryRepository,
            userCommandRepository,
            kakaoOAuthClient
        )
    }

    @Test
    fun `회원 탈퇴 성공 - 카카오 연동 해제 및 데이터 삭제`() {
        // given
        val userId = 1L
        val socialId = "kakao-123"
        val user = TestDataFactory.createUser(
            id = userId,
            provider = AuthProvider.KAKAO,
            socialId = socialId
        )
        whenever(userQueryRepository.findById(userId)).thenReturn(user)

        // when
        withdrawService.withdraw(userId)

        // then
        verify(kakaoOAuthClient).unlink(socialId)
        verify(userCommandRepository).delete(user)
    }

    @Test
    fun `회원 탈퇴 성공 - 애플 데이터 삭제`() {
        // given
        val userId = 1L
        val user = TestDataFactory.createUser(
            id = userId,
            provider = AuthProvider.APPLE
        )
        whenever(userQueryRepository.findById(userId)).thenReturn(user)

        // when
        withdrawService.withdraw(userId)

        // then
        verify(userCommandRepository).delete(user)
        verifyNoInteractions(kakaoOAuthClient)
    }

    @Test
    fun `회원 탈퇴 실패 - 사용자를 찾을 수 없음`() {
        // given
        val userId = 1L
        whenever(userQueryRepository.findById(userId)).thenReturn(null)

        // when & then
        val exception = assertThrows<AuthException> {
            withdrawService.withdraw(userId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
    }
}
