package org.depromeet.team3.auth.application.common

import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 로그아웃 Service
 */
@Service
class LogoutService(
    private val userQueryRepository: UserQueryRepository,
    private val userCommandRepository: UserCommandRepository
) {
    @Transactional
    suspend fun logout(userId: Long) {
        val user = userQueryRepository.findById(userId)
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
            
        val loggedOutUser = user.copy(
            refreshToken = null,
            updatedAt = LocalDateTime.now()
        )
        userCommandRepository.save(loggedOutUser)
    }
}
