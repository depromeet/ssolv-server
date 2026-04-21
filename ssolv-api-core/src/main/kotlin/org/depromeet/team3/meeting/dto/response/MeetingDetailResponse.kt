package org.depromeet.team3.meeting.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "모임 상세 응답")
data class MeetingDetailResponse(
    @Schema(description = "현재 사용자 ID", example = "123")
    val currentUserId: Long,

    @Schema(description = "모임 정보")
    val meetingInfo: MeetingInfoResponse,

    @Schema(description = "모임 참여자 정보")
    val participantList: List<MeetingParticipantInfo>,
)
