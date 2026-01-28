package org.depromeet.team3.auth.application.common

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 회원 탈퇴 Service
 */
@Service
class WithdrawService(
    private val userQueryRepository: UserQueryRepository,
    private val userCommandRepository: UserCommandRepository,
    private val kakaoOAuthClient: KakaoOAuthClient
) {
    private val log = LoggerFactory.getLogger(WithdrawService::class.java)

    /**
     * 회원 탈퇴 처리
     * 1. 로컬 데이터 삭제 (Transaction)
     * 2. 소셜 플랫폼 연동 해제 (Transaction 외부)
     */
    fun withdraw(userId: Long) {
        val user = userQueryRepository.findById(userId)
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)

        // 1. 로컬 데이터 삭제 (트랜잭션 내부)
        deleteLocalUser(user)

        // 2. 소셜 플랫폼 연동 해제 (트랜잭션 외부 호출로 지연 방지 및 장애 전파 최소화)
        try {
            when (user.provider) {
                AuthProvider.KAKAO -> kakaoOAuthClient.unlink(user.socialId)
                AuthProvider.APPLE -> {
                    // 애플 연동 해제는 추후 구현
                }
            }
        } catch (e: Exception) {
            log.error("소셜 연동 해제 실패 - userId: {}, provider: {}, error: {}", userId, user.provider, e.message)
        }
    }

    @Transactional
    protected fun deleteLocalUser(user: User) {
        userCommandRepository.delete(user)
    }
}
