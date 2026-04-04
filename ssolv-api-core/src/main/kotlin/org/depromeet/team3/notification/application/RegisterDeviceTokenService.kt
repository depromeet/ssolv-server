package org.depromeet.team3.notification.application
import org.depromeet.team3.common.util.withTracingContext
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

@Service
class RegisterDeviceTokenService(
    private val deviceTokenQueryRepository: DeviceTokenQueryRepository,
    private val deviceTokenCommandRepository: DeviceTokenCommandRepository,
    private val userQueryRepository: UserQueryRepository
) {
    @Transactional
    suspend fun execute(userId: Long, request: RegisterDeviceTokenRequest) = withTracingContext() {
        // 사용자 존재 여부 확인
        userQueryRepository.findById(userId) ?: throw UserException(ErrorCode.USER_NOT_FOUND)

        val platform = try {
            DevicePlatform.valueOf(request.platform.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("유효하지 않은 플랫폼입니다: ${request.platform}")
        }

        // 1. 해당 토큰 문자열이 시스템에 이미 존재하는지 확인 (기기 공유 케이스)
        val existingTokenByString = deviceTokenQueryRepository.findByFcmToken(request.fcmToken)

        if (existingTokenByString != null) {
            /**
             * [CASE 1: 토큰 문자열이 이미 존재]
             * 기기를 다른 사용자가 로그아웃/로그인하여 사용 중인 경우입니다.
             * 해당 레코드의 소유자(userId)와 플랫폼을 현재 요청값으로 교체합니다.
             */
            val updatedToken = existingTokenByString.copy(
                userId = userId,
                platform = platform,
                updatedAt = LocalDateTime.now()
            )
            deviceTokenCommandRepository.save(updatedToken)
            return@withTracingContext
        }

        // 2. 해당 사용자에게 이미 해당 플랫폼용 토큰이 있는지 확인 (토큰 갱신 케이스)
        val existingTokenByUser = deviceTokenQueryRepository.findByUserIdAndPlatform(userId, platform)

        if (existingTokenByUser != null) {
            /**
             * [CASE 2: 해당 사용자의 해당 플랫폼 레코드 존재]
             * 기존의 오래된 토큰을 새로운 토큰 문자열로 '갱신'합니다.
             * 이 과정에서 사용자가 말씀하신 "기존 값의 수정"이 일어납니다.
             */
            val updatedToken = existingTokenByUser.copy(
                fcmToken = request.fcmToken,
                updatedAt = LocalDateTime.now()
            )
            deviceTokenCommandRepository.save(updatedToken)
        } else {
            /**
             * [CASE 3: 완전히 새로운 요청]
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
