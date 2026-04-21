package org.depromeet.team3.meeting.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class JoinMeetingRequest(
    @Schema(description = "참여 토큰", example = "MXDg5")
    @field:NotBlank(message = "참여 토큰은 필수입니다.")
    val token: String,
)
