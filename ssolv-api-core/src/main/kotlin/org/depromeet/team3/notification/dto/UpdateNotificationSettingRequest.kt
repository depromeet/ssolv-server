package org.depromeet.team3.notification.dto

import jakarta.validation.constraints.NotNull

data class UpdateNotificationSettingRequest(
    @field:NotNull(message = "notificationEnabled 필드는 필수입니다.")
    val notificationEnabled: Boolean,
)
