package org.depromeet.team3.notification.domain

interface DeviceTokenCommandRepository {
    fun save(deviceToken: DeviceToken): DeviceToken
    fun deleteByUserIdAndFcmToken(userId: Long, fcmToken: String)
    fun deleteAllByUserId(userId: Long)
}