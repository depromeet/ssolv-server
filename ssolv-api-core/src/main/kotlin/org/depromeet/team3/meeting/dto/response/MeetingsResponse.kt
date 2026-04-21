package org.depromeet.team3.meeting.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import org.depromeet.team3.survey.dto.GetRespondents

@Schema(description = "참여 모임 정보")
data class MeetingsResponse(
    @Schema(description = "모임 정보")
    val meetingInfo: MeetingInfoResponse,

    @Schema(description = "모임 참여자 정보")
    val participantList: List<GetRespondents>,
)
