package org.depromeet.team3.meeting.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "모임 생성 응답")
data class CreateMeetingResponse(
    @Schema(description = "모임 ID", example = "1")
    val id: Long,

    @Schema(description = "토큰 검증 링크", example = "https://app.ssolv.site/meetings/validate-invite?token=MSK@KS")
    val validateTokenUrl: String,
)
