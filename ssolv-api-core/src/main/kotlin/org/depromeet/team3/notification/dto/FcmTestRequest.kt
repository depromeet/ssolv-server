package org.depromeet.team3.notification.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "FCM 테스트 발송 요청")
data class FcmTestRequest(
    @field:NotBlank(message = "FCM 토큰은 필수입니다.")
    @Schema(description = "대상 기기 FCM 토큰", example = "fcm_token_here")
    val token: String,

    @field:NotBlank(message = "제목은 필수입니다.")
    @Schema(description = "알림 제목", example = "테스트 알림")
    val title: String,

    @field:NotBlank(message = "내용은 필수입니다.")
    @Schema(description = "알림 내용", example = "FCM 발송 테스트입니다.")
    val body: String,
)
