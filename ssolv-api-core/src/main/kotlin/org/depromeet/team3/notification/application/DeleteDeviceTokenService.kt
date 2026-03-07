package org.depromeet.team3.notification.application

import org.depromeet.team3.notification.domain.DeviceTokenCommandRepository
import org.depromeet.team3.notification.dto.DeleteDeviceTokenRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DeleteDeviceTokenService(
    private val deviceTokenCommandRepository: DeviceTokenCommandRepository
) {
    fun execute(userId: Long, request: DeleteDeviceTokenRequest) {
        deviceTokenCommandRepository.deleteByUserIdAndFcmToken(userId, request.fcmToken)
    }
}