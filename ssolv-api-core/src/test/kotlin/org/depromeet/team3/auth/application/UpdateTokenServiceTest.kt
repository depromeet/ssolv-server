package org.depromeet.team3.auth.application

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.application.token.UpdateTokenService
import org.depromeet.team3.auth.command.RefreshTokenCommand
import org.depromeet.team3.auth.dto.TokenResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.fixture.UserFixture
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@UnitTest
class UpdateTokenServiceTest {

    @Mock private lateinit var userQueryRepository: UserQueryRepository

    @Mock private lateinit var userCommandRepository: UserCommandRepository

    @Mock private lateinit var jwtTokenProvider: JwtTokenProvider

    @Mock private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var updateTokenService: UpdateTokenService

    @BeforeEach
    fun setUp() {
        whenever(transactionTemplate.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        updateTokenService = UpdateTokenService(
            userQueryRepository,
            userCommandRepository,
            jwtTokenProvider,
            transactionTemplate,
        )
    }

    @Test
    fun `토큰 갱신 실패 - 유효하지 않은 Refresh Token`() = runTest {
        // given
        val command = RefreshTokenCommand(refreshToken = "invalid-refresh-token")
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken)).thenReturn(false)

        // when & then
        val exception = assertThrows<AuthException> { updateTokenService.refresh(command) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID)
    }

    @Test
    fun `토큰 갱신 실패 - 사용자 정보 없음`() = runTest {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken)).thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken)).thenReturn(null)

        // when & then
        val exception = assertThrows<AuthException> { updateTokenService.refresh(command) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.TOKEN_USER_ID_INVALID)
    }

    @Test
    fun `토큰 갱신 실패 - 사용자를 찾을 수 없음`() = runTest {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken)).thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken)).thenReturn("1")
        whenever(userQueryRepository.findById(1L)).thenReturn(null)

        // when & then
        val exception = assertThrows<AuthException> { updateTokenService.refresh(command) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND_FOR_TOKEN)
    }

    @Test
    fun `토큰 갱신 실패 - Refresh Token 불일치`() = runTest {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        val userWithDifferentToken = UserFixture.create(id = 1L, refreshToken = "different-refresh-token")
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken)).thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken)).thenReturn("1")
        whenever(userQueryRepository.findById(1L)).thenReturn(userWithDifferentToken)

        // when & then
        val exception = assertThrows<AuthException> { updateTokenService.refresh(command) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.REFRESH_TOKEN_MISMATCH)
    }

    @Test
    fun `토큰 갱신 성공`() = runTest {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        val user = UserFixture.create(id = 1L, email = "test@example.com", refreshToken = command.refreshToken)
        val updatedUser = user.copy(refreshToken = "new-refresh-token", updatedAt = LocalDateTime.now())

        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken)).thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken)).thenReturn("1")
        whenever(userQueryRepository.findById(1L)).thenReturn(user)
        whenever(jwtTokenProvider.generateAccessToken(1L, "test@example.com")).thenReturn("new-access-token")
        whenever(jwtTokenProvider.generateRefreshToken(1L)).thenReturn("new-refresh-token")
        whenever(userCommandRepository.save(any())).thenReturn(updatedUser)

        // when
        val result = updateTokenService.refresh(command)

        // then
        assertThat(result).isInstanceOf(TokenResponse::class.java)
        assertThat(result.accessToken).isEqualTo("new-access-token")
        assertThat(result.refreshToken).isEqualTo("new-refresh-token")
        verify(jwtTokenProvider).generateAccessToken(1L, "test@example.com")
        verify(jwtTokenProvider).generateRefreshToken(1L)
        verify(userCommandRepository).save(any())
    }
}
