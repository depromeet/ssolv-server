package org.depromeet.team3.auth.application

import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
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
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.auth.application.common.LogoutService

@ExtendWith(MockitoExtension::class)
class LogoutServiceTest {

    @Mock
    private lateinit var userQueryRepository: UserQueryRepository

    @Mock
    private lateinit var userCommandRepository: UserCommandRepository

    private lateinit var logoutService: LogoutService

    @BeforeEach
    fun setUp() {
        logoutService = LogoutService(userQueryRepository, userCommandRepository)
    }

    @Test
    fun `로그아웃 성공 - 리프레시 토큰 제거`() {
        runBlocking {
            // given
            val userId = 1L
            val user = TestDataFactory.createUser(id = userId, refreshToken = "existing-token")
            whenever(userQueryRepository.findById(userId)).thenReturn(user)

            // when
            logoutService.logout(userId)

            // then
            verify(userCommandRepository).save(argThat {
                this.id == userId && this.refreshToken == null
            })
        }
    }

    @Test
    fun `로그아웃 실패 - 사용자를 찾을 수 없음`() {
        runBlocking {
            // given
            val userId = 1L
            whenever(userQueryRepository.findById(userId)).thenReturn(null)

            // when & then
            val exception = assertThrows<AuthException> {
                runBlocking { logoutService.logout(userId) }
            }
            assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND)
        }
    }
}
