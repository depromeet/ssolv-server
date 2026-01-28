package org.depromeet.team3.auth.application.common

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
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
    @Transactional
    fun withdraw(userId: Long) {
        val user = userQueryRepository.findById(userId)
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
            
        // 1. 소셜 플랫폼 연동 해제
        when (user.provider) {
            AuthProvider.KAKAO -> kakaoOAuthClient.unlink(user.socialId)
            AuthProvider.APPLE -> {
                // 애플 연동 해제는 추후 구현
            }
        }
        
        // 2. 로컬 데이터 삭제
        userCommandRepository.delete(user)
    }
}
