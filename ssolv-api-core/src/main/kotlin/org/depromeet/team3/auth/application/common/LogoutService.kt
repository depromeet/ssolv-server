package org.depromeet.team3.auth.application.common
import org.depromeet.team3.common.util.withTracingContext
import kotlinx.coroutines.Dispatchers
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
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
    private val transactionTemplate: TransactionTemplate,
) {
    suspend fun logout(userId: Long): Unit = withTracingContext() {
        transactionTemplate.execute {
            val entity = userJpaRepository.findByIdOrNull(userId)
                ?: throw AuthException(ErrorCode.USER_NOT_FOUND)

            entity.refreshToken = null
            userJpaRepository.save(entity)
        }
    }
}
