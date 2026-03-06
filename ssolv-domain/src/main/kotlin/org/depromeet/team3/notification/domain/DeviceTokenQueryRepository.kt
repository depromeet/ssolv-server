package org.depromeet.team3.notification.domain

interface DeviceTokenQueryRepository {
    fun findByUserId(userId: Long): List<DeviceToken>
    fun findByFcmToken(fcmToken: String): DeviceToken?
    fun findValidTokensByUserIds(userIds: List<Long>, isNotificationEnabled: Boolean): List<String>
}
