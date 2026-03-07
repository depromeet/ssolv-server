package org.depromeet.team3.mapper

import org.depromeet.team3.notification.domain.DeviceToken
import org.depromeet.team3.notification.infrastructure.DeviceTokenEntity
import org.springframework.stereotype.Component

@Component
class DeviceTokenMapper : DomainMapper<DeviceToken, DeviceTokenEntity> {
    override fun toDomain(entity: DeviceTokenEntity): DeviceToken {
        return DeviceToken(
            id = entity.id,
            userId = entity.userId,
            fcmToken = entity.fcmToken,
            platform = entity.platform,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    override fun toEntity(domain: DeviceToken): DeviceTokenEntity {
        return DeviceTokenEntity(
            id = domain.id,
            userId = domain.userId,
            fcmToken = domain.fcmToken,
            platform = domain.platform
        )
    }
}