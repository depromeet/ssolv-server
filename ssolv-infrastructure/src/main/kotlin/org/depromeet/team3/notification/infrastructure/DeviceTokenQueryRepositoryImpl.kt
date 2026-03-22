package org.depromeet.team3.notification.infrastructure

import org.depromeet.team3.mapper.DeviceTokenMapper
import org.depromeet.team3.notification.domain.DeviceToken
import org.depromeet.team3.notification.domain.DeviceTokenQueryRepository
import org.springframework.stereotype.Repository

@Repository
class DeviceTokenQueryRepositoryImpl(
    private val deviceTokenJpaRepository: DeviceTokenJpaRepository,
    private val deviceTokenMapper: DeviceTokenMapper
) : DeviceTokenQueryRepository {

    override fun findByUserId(userId: Long): List<DeviceToken> {
        return deviceTokenJpaRepository.findByUserId(userId)
            .map { deviceTokenMapper.toDomain(it) }
    }

    override fun findByFcmToken(fcmToken: String): DeviceToken? {
        return deviceTokenJpaRepository.findByFcmToken(fcmToken)
            ?.let { deviceTokenMapper.toDomain(it) }
    }

    override fun findByUserIdAndPlatform(userId: Long, platform: org.depromeet.team3.notification.domain.DevicePlatform): DeviceToken? {
        return deviceTokenJpaRepository.findByUserIdAndPlatform(userId, platform)
            ?.let { deviceTokenMapper.toDomain(it) }
    }

    override fun findValidTokensByUserIds(userIds: List<Long>, isNotificationEnabled: Boolean): List<String> {
        if (userIds.isEmpty()) return emptyList()
        return deviceTokenJpaRepository.findValidTokensByUserIds(userIds, isNotificationEnabled)
    }
}
