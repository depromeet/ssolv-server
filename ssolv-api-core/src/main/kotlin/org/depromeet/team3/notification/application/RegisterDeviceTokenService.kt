package org.depromeet.team3.notification.application
import kotlinx.coroutines.Dispatchers
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.exception.UserException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.notification.domain.DevicePlatform
import org.depromeet.team3.notification.domain.DeviceToken
import org.depromeet.team3.notification.domain.DeviceTokenCommandRepository
import org.depromeet.team3.notification.domain.DeviceTokenQueryRepository
import org.depromeet.team3.notification.dto.RegisterDeviceTokenRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

@Service
class RegisterDeviceTokenService(
    private val deviceTokenQueryRepository: DeviceTokenQueryRepository,
    private val deviceTokenCommandRepository: DeviceTokenCommandRepository,
    private val userQueryRepository: UserQueryRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    suspend fun execute(userId: Long, request: RegisterDeviceTokenRequest) = withContext(Dispatchers.IO) {
        transactionTemplate.execute {
            runBlocking {
                if (userQueryRepository.findById(userId) == null) {
                    throw UserException(ErrorCode.USER_NOT_FOUND)
                }
            }
        }

        val platform = try {
            DevicePlatform.valueOf(request.platform.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("유효하지 않은 플랫폼입니다: ${request.platform}")
        }

        transactionTemplate.execute {
            runBlocking {
                val existingToken = deviceTokenQueryRepository.findByFcmToken(request.fcmToken)
                
                if (existingToken != null) {
                    // 사용자 아이디가 다르거나 플랫폼이 변경된 경우 갱신 처리
                    if (existingToken.userId != userId || existingToken.platform != platform) {
                        val updatedToken = existingToken.copy(
                            userId = userId,
                            platform = platform
                        )
                        deviceTokenCommandRepository.save(updatedToken)
                    }
                } else {
                    val newToken = DeviceToken(
                        userId = userId,
                        fcmToken = request.fcmToken,
                        platform = platform,
                        createdAt = LocalDateTime.now()
                    )
                    deviceTokenCommandRepository.save(newToken)
                }
                Unit
            }
        }
    }
}
