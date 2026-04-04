package org.depromeet.team3.notification.application
import org.depromeet.team3.common.util.withTracingContext
import kotlinx.coroutines.Dispatchers

import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.exception.UserException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.notification.dto.UpdateNotificationSettingRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import kotlinx.coroutines.runBlocking

/*
 * 사용자의 알림 설정 상태를 변경하는 서비스
 */
@Service
class UpdateNotificationSettingService(
    private val userQueryRepository: UserQueryRepository,
    private val userCommandRepository: UserCommandRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    suspend fun execute(userId: Long, request: UpdateNotificationSettingRequest) = withTracingContext() {
        transactionTemplate.execute {
            runBlocking {
                val user = userQueryRepository.findById(userId) ?: throw UserException(ErrorCode.USER_NOT_FOUND)
                user.notificationEnabled = request.notificationEnabled
                userCommandRepository.save(user)
            }
        }
    }
}