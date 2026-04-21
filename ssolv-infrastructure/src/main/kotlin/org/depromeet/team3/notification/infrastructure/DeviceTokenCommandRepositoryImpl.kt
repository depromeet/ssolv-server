package org.depromeet.team3.notification.infrastructure

import org.depromeet.team3.mapper.DeviceTokenMapper
import org.depromeet.team3.notification.domain.DeviceToken
import org.depromeet.team3.notification.domain.DeviceTokenCommandRepository
import org.springframework.stereotype.Repository

@Repository
class DeviceTokenCommandRepositoryImpl(
    private val deviceTokenJpaRepository: DeviceTokenJpaRepository,
    private val deviceTokenMapper: DeviceTokenMapper,
) : DeviceTokenCommandRepository {

    override fun save(deviceToken: DeviceToken): DeviceToken {
        val entity = deviceTokenMapper.toEntity(deviceToken)
        val savedEntity = deviceTokenJpaRepository.save(entity)
        return deviceTokenMapper.toDomain(savedEntity)
    }

    override fun deleteByUserIdAndFcmToken(userId: Long, fcmToken: String) {
        deviceTokenJpaRepository.deleteByUserIdAndFcmToken(userId, fcmToken)
    }

    override fun deleteAllByUserId(userId: Long) {
        deviceTokenJpaRepository.deleteAllByUserId(userId)
    }
}
