package org.depromeet.team3.auth.application.common
import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.dto.LogoutResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.withTracingContext
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 로그아웃 Service
 * withContext(VT) + transactionTemplate.execute 로 트랜잭션 경계를 동일 스레드에 고정.
 */
@Service
class LogoutService(
    private val userJpaRepository: UserRepository,
    private val kakaoOAuthClient: KakaoOAuthClient,
    private val transactionTemplate: TransactionTemplate,
) {
    suspend fun logout(userId: Long): LogoutResponse = withTracingContext {
        val provider = transactionTemplate.execute {
            val entity = userJpaRepository.findByIdOrNull(userId)
                ?: throw AuthException(ErrorCode.USER_NOT_FOUND)

            entity.refreshToken = null
            userJpaRepository.save(entity)

            entity.provider
        }!!

        val kakaoLogoutUrl = if (provider == AuthProvider.KAKAO) kakaoOAuthClient.getLogoutUrl() else null
        LogoutResponse(kakaoLogoutUrl = kakaoLogoutUrl)
    }
}
