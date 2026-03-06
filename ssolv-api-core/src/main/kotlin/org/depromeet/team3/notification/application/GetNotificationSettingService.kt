package org.depromeet.team3.notification.application


import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.exception.UserException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.notification.dto.NotificationSettingResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import kotlinx.coroutines.runBlocking

/*
 * 사용자의 알림 설정 상태를 조회하는 서비스
 */
@Service
class GetNotificationSettingService(
    private val userQueryRepository: UserQueryRepository,
    private val transactionTemplate: TransactionTemplate
) {
    suspend fun execute(userId: Long): NotificationSettingResponse {
        val user = transactionTemplate.execute {
            runBlocking { userQueryRepository.findById(userId) }
        } ?: throw UserException(ErrorCode.USER_NOT_FOUND)
        
        return NotificationSettingResponse(notificationEnabled = user.notificationEnabled)
    }
}