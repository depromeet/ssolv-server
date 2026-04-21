package org.depromeet.team3.meeting.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "모임 참여자 정보")
data class MeetingParticipantInfo(
    @Schema(description = "사용자 ID", example = "456")
    val userId: Long,

    @Schema(description = "참여자 닉네임", example = "아따맘마")
    val attendeeNickname: String,

    @Schema(description = "프로필 색상", example = "choco")
    val color: String,

    @Schema(description = "선택한 설문 카테고리 목록")
    val selectedCategories: List<ParticipantSelectedCategory>,
)
