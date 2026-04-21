package org.depromeet.team3.notification.dto

import jakarta.validation.constraints.NotBlank

data class RegisterDeviceTokenRequest(
    @field:NotBlank(message = "fcmToken은 필수입니다.")
    val fcmToken: String,

    @field:NotBlank(message = "platform은 필수입니다.")
    val platform: String,
)
