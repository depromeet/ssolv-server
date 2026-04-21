package org.depromeet.team3.meeting.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ValidateInviteTokenResponse(
    @Schema(description = "모임 ID", example = "1")
    val meetingId: Long,
)
