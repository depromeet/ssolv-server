package org.depromeet.team3.notification.domain

import org.depromeet.team3.common.BaseTimeDomain
import java.time.LocalDateTime

data class DeviceToken(
    val id: Long? = null,
    val userId: Long,
    val fcmToken: String,
    val platform: DevicePlatform,
    override val createdAt: LocalDateTime,
    override val updatedAt: LocalDateTime? = null,
) : BaseTimeDomain(createdAt, updatedAt)
