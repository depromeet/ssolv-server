package org.depromeet.team3.surveycategory.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import org.depromeet.team3.surveycategory.SurveyCategoryLevel

@Schema(description = "설문 카테고리 생성 응답 DTO")
data class CreateSurveyCategoryResponse(
    @Schema(description = "생성된 카테고리 ID", example = "1")
    val id: Long,

    @Schema(description = "상위 카테고리 ID", example = "1", nullable = true)
    val parentId: Long?,

    @Schema(description = "카테고리 레벨", example = "BRANCH")
    val level: SurveyCategoryLevel,

    @Schema(description = "카테고리명", example = "한식")
    val name: String,

    @Schema(description = "카테고리 순서", example = "1")
    val sortOrder: Int,
)
