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
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlinx.coroutines.withContext

@Service
class RegisterDeviceTokenService(
    private val deviceTokenQueryRepository: DeviceTokenQueryRepository,
    private val deviceTokenCommandRepository: DeviceTokenCommandRepository,
    private val userQueryRepository: UserQueryRepository
) {
    @Transactional
    suspend fun execute(userId: Long, request: RegisterDeviceTokenRequest) = withContext(Dispatchers.IO) {
        // 사용자 존재 여부 확인
        userQueryRepository.findById(userId) ?: throw UserException(ErrorCode.USER_NOT_FOUND)

        val platform = try {
            DevicePlatform.valueOf(request.platform.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("유효하지 않은 플랫폼입니다: ${request.platform}")
        }

        // 1. 해당 토큰이 이미 등록되어 있는지 확인
        val existingToken = deviceTokenQueryRepository.findByFcmToken(request.fcmToken)

        if (existingToken != null) {
            /**
             * [이미 등록된 토큰인 경우]
             * userId나 platform이 바뀌었다면 당연히 업데이트하고,
             * 내용이 같더라도 save를 호출하여 updatedAt(마지막 갱신일)을 최신화합니다.
             */
            val updatedToken = existingToken.copy(
                userId = userId,
                platform = platform,
                updatedAt = LocalDateTime.now() // 명시적으로 갱신 시간 반영
            )
            deviceTokenCommandRepository.save(updatedToken)
        } else {
            /**
             * [새로운 토큰인 경우]
             * 신규 등록 처리를 합니다.
             */
            val newToken = DeviceToken(
                userId = userId,
                fcmToken = request.fcmToken,
                platform = platform,
                createdAt = LocalDateTime.now()
            )
            deviceTokenCommandRepository.save(newToken)
        }
    }
}
