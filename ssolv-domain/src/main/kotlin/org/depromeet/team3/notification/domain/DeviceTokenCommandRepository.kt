package org.depromeet.team3.notification.domain

interface DeviceTokenCommandRepository {
    fun save(deviceToken: DeviceToken): DeviceToken
    fun deleteByFcmToken(fcmToken: String)
    fun deleteAllByUserId(userId: Long)
}