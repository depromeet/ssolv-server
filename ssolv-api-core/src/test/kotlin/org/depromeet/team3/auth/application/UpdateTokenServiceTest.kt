package org.depromeet.team3.auth.application

import org.depromeet.team3.auth.User
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.command.RefreshTokenCommand
import org.depromeet.team3.auth.dto.TokenResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.util.TestDataFactory
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.application.token.UpdateTokenService
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class UpdateTokenServiceTest {

    @Mock
    private lateinit var userQueryRepository: UserQueryRepository

    @Mock
    private lateinit var userCommandRepository: UserCommandRepository

    @Mock
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Mock
    private lateinit var transactionTemplate: TransactionTemplate

    @Mock
    private lateinit var coroutineDispatchers: CoroutineDispatchers

    private lateinit var updateTokenService: UpdateTokenService
    
    @BeforeEach
    fun setUp() {
        whenever(coroutineDispatchers.VT).thenReturn(Dispatchers.Unconfined)
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(org.mockito.kotlin.mock())
        }
        updateTokenService = UpdateTokenService(
            userQueryRepository,
            userCommandRepository,
            jwtTokenProvider,
            transactionTemplate,
            coroutineDispatchers
        )
    }

    @Test
    fun `토큰 갱신 실패 - 유효하지 않은 Refresh Token`() = runBlocking {
        // given
        val command = RefreshTokenCommand(refreshToken = "invalid-refresh-token")
        
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken))
            .thenReturn(false)

        // when & then
        val exception = assertThrows<AuthException> {
            updateTokenService.refresh(command)
        }
        
        assertThat(exception.errorCode).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID)
    }

    @Test
    fun `토큰 갱신 실패 - 사용자 정보 없음`() = runBlocking {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken))
            .thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken))
            .thenReturn(null)

        // when & then
        val exception = assertThrows<AuthException> {
            updateTokenService.refresh(command)
        }
        
        assertThat(exception.errorCode).isEqualTo(ErrorCode.TOKEN_USER_ID_INVALID)
    }

    @Test
    fun `토큰 갱신 실패 - 사용자를 찾을 수 없음`() = runBlocking {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        val userId = 1L
        
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken))
            .thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken))
            .thenReturn(userId.toString())
        whenever(userQueryRepository.findById(userId))
            .thenReturn(null)

        // when & then
        val exception = assertThrows<AuthException> {
            updateTokenService.refresh(command)
        }
        
        assertThat(exception.errorCode).isEqualTo(ErrorCode.USER_NOT_FOUND_FOR_TOKEN)
    }

    @Test
    fun `토큰 갱신 실패 - Refresh Token 불일치`() = runBlocking {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        val differentRefreshToken = "different-refresh-token"
        val userId = 1L
        
        val userWithDifferentToken = TestDataFactory.createUser(
            id = userId,
            refreshToken = differentRefreshToken
        )
        
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken))
            .thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken))
            .thenReturn(userId.toString())
        whenever(userQueryRepository.findById(userId))
            .thenReturn(userWithDifferentToken)

        // when & then
        val exception = assertThrows<AuthException> {
            updateTokenService.refresh(command)
        }
        
        assertThat(exception.errorCode).isEqualTo(ErrorCode.REFRESH_TOKEN_MISMATCH)
    }

    @Test
    fun `토큰 갱신 성공`() = runBlocking {
        // given
        val command = RefreshTokenCommand(refreshToken = "valid-refresh-token")
        val userId = 1L
        
        val userWithValidToken = TestDataFactory.createUser(
            id = userId,
            email = "test@example.com",
            refreshToken = command.refreshToken
        )
        
        val updatedUser = userWithValidToken.copy(
            refreshToken = "new-refresh-token",
            updatedAt = LocalDateTime.now()
        )
        
        whenever(jwtTokenProvider.validateRefreshToken(command.refreshToken))
            .thenReturn(true)
        whenever(jwtTokenProvider.getUserIdFromToken(command.refreshToken))
            .thenReturn(userId.toString())
        whenever(userQueryRepository.findById(userId))
            .thenReturn(userWithValidToken)
        whenever(jwtTokenProvider.generateAccessToken(userId, "test@example.com"))
            .thenReturn("new-access-token")
        whenever(jwtTokenProvider.generateRefreshToken(userId))
            .thenReturn("new-refresh-token")
        whenever(userCommandRepository.save(any<User>()))
            .thenReturn(updatedUser)

        // when
        val result = updateTokenService.refresh(command)

        // then
        assertThat(result).isInstanceOf(TokenResponse::class.java)
        assertThat(result.accessToken).isEqualTo("new-access-token")
        assertThat(result.refreshToken).isEqualTo("new-refresh-token")
        
        verify(jwtTokenProvider).generateAccessToken(userId, "test@example.com")
        verify(jwtTokenProvider).generateRefreshToken(userId)
        verify(userCommandRepository).save(any<User>())
    }
}
