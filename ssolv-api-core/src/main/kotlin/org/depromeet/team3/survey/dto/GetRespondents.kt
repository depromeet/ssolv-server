package org.depromeet.team3.survey.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "설문 참여자 목록")
data class GetRespondents(
    @Schema(description = "참가자 ID", example = "1")
    val userId: Long,

    @Schema(description = "참가자 닉네임", example = "김아무개")
    val attendeeNickname: String,

    @Schema(description = "캐릭터 색상", example = "blackpink")
    val color: String,
)
