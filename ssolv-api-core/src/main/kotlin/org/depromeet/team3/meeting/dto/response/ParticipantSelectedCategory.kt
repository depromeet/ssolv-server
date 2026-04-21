package org.depromeet.team3.meeting.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "참가자가 선택한 브랜치 카테고리 정보")
data class ParticipantSelectedCategory(
    @Schema(description = "카테고리 ID", example = "1")
    val id: Long,

    @Schema(description = "카테고리명", example = "한식")
    val name: String,

    @Schema(description = "선택된 리프 카테고리 목록")
    val leafCategoryList: List<SelectedLeafCategory>,
)

@Schema(description = "선택된 리프 카테고리 정보")
data class SelectedLeafCategory(
    @Schema(description = "카테고리 ID", example = "8")
    val id: Long,

    @Schema(description = "카테고리명", example = "밥류")
    val name: String,
)
