package org.depromeet.team3.notification.dto

import jakarta.validation.constraints.NotBlank

data class DeleteDeviceTokenRequest(
    @field:NotBlank(message = "fcmToken은 필수입니다.")
    val fcmToken: String
)