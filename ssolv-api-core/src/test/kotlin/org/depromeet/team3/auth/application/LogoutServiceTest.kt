package org.depromeet.team3.auth.application

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.application.common.LogoutService
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.fixture.UserFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional

@UnitTest
class LogoutServiceTest {

    @Mock private lateinit var userJpaRepository: UserRepository
    @Mock private lateinit var kakaoOAuthClient: KakaoOAuthClient

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var logoutService: LogoutService

    @BeforeEach
    fun setUp() {
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        logoutService = LogoutService(userJpaRepository, kakaoOAuthClient, transactionTemplate)
    }

    @Test
    fun `로그아웃 성공 - 리프레시 토큰 제거`() = runTest {
        // given
        val userId = 1L
        val userEntity = UserFixture.createEntity(id = userId, refreshToken = "existing-token")
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(userEntity))
        whenever(userJpaRepository.save(any())).thenReturn(userEntity)

        // when
        logoutService.logout(userId)

        // then
        verify(userJpaRepository).save(any())
    }

    @Test
    fun `로그아웃 실패 - 사용자를 찾을 수 없음`() = runTest {
        // given
        val userId = 1L
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.empty())

        // when & then
        val exception = assertThrows<AuthException> {
            logoutService.logout(userId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
    }
}
