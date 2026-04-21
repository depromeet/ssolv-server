package org.depromeet.team3.meeting.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "모임 초대 토큰 조회 응답")
data class GetInviteTokenResponse(
    @Schema(description = "초대 토큰", example = "MSK@KS...")
    val token: String,
)
