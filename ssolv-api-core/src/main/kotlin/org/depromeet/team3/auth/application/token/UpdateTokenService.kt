package org.depromeet.team3.auth.application.token
import org.depromeet.team3.common.util.withTracingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.command.RefreshTokenCommand
import org.depromeet.team3.auth.dto.TokenResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * 토큰 갱신 Service
 */
@Service
class UpdateTokenService(
    private val userQueryRepository: UserQueryRepository,
    private val userCommandRepository: UserCommandRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val transactionTemplate: TransactionTemplate,
) {

    suspend fun refresh(command: RefreshTokenCommand): TokenResponse = withTracingContext() {
        transactionTemplate.execute {
            val refreshToken = command.refreshToken
            
            if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
                throw AuthException(ErrorCode.REFRESH_TOKEN_INVALID)
            }

            val userId = jwtTokenProvider.getUserIdFromToken(refreshToken)?.toLongOrNull()
                ?: throw AuthException(ErrorCode.TOKEN_USER_ID_INVALID)

            val user = runBlocking { userQueryRepository.findById(userId) }
                ?: throw AuthException(ErrorCode.USER_NOT_FOUND_FOR_TOKEN)

            if (user.refreshToken != refreshToken) {
                throw AuthException(ErrorCode.REFRESH_TOKEN_MISMATCH)
            }

            val accessToken = jwtTokenProvider.generateAccessToken(userId, user.email)
            val newRefreshToken = jwtTokenProvider.generateRefreshToken(userId)
            
            val updatedUser = user.copy(
                refreshToken = newRefreshToken,
                updatedAt = LocalDateTime.now()
            )
            runBlocking { userCommandRepository.save(updatedUser) }

            TokenResponse(
                accessToken = accessToken,
                refreshToken = newRefreshToken
            )
        } ?: throw IllegalStateException("Transaction result is null")
    }
}
