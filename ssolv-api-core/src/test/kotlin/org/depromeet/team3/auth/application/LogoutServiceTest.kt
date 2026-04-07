package org.depromeet.team3.auth.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.application.common.LogoutService
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.TestEntityFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionCallback
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class LogoutServiceTest {

    @Mock
    private lateinit var userJpaRepository: UserRepository

    @Mock
    private lateinit var kakaoOAuthClient: KakaoOAuthClient

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
    fun `로그아웃 성공 - 리프레시 토큰 제거`() = runBlocking {
        val userId = 1L
        val userEntity = TestEntityFactory.createUserEntity(id = userId, refreshToken = "existing-token")
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.of(userEntity))
        whenever(userJpaRepository.save(any())).thenReturn(userEntity)

        logoutService.logout(userId)

        verify(userJpaRepository).save(any())
    }

    @Test
    fun `로그아웃 실패 - 사용자를 찾을 수 없음`() = runBlocking {
        val userId = 1L
        whenever(userJpaRepository.findById(userId)).thenReturn(Optional.empty())

        val exception = assertThrows<AuthException> {
            logoutService.logout(userId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
    }
}
