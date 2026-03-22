package org.depromeet.team3.notification.infrastructure

import org.depromeet.team3.notification.domain.DevicePlatform
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DeviceTokenJpaRepository : JpaRepository<DeviceTokenEntity, Long> {
    fun findByUserId(userId: Long): List<DeviceTokenEntity>
    fun findByFcmToken(fcmToken: String): DeviceTokenEntity?
    fun findByUserIdAndPlatform(userId: Long, platform: DevicePlatform): DeviceTokenEntity?
    fun deleteByFcmToken(fcmToken: String)
    fun deleteByUserIdAndFcmToken(userId: Long, fcmToken: String)
    fun deleteAllByUserId(userId: Long)

    @Query("""
        SELECT dt.fcmToken 
        FROM DeviceTokenEntity dt 
        JOIN UserEntity u ON dt.userId = u.id 
        WHERE dt.userId IN :userIds AND u.notificationEnabled = :isNotificationEnabled
    """)
    fun findValidTokensByUserIds(userIds: List<Long>, isNotificationEnabled: Boolean): List<String>
}