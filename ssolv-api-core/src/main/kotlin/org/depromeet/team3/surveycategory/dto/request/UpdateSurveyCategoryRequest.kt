package org.depromeet.team3.surveycategory.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.validation.ValidSurveyCategoryLevel

@Schema(description = "설문 카테고리 수정 요청 DTO")
data class UpdateSurveyCategoryRequest(

    @Schema(description = "상위 카테고리 ID", example = "1", nullable = true)
    val parentId: Long? = null,

    @Schema(description = "카테고리 레벨", example = "BRANCH")
    @field:NotNull(message = "카테고리 레벨은 필수입니다")
    @field:ValidSurveyCategoryLevel(message = "카테고리 레벨은 BRANCH 또는 LEAF만 허용됩니다")
    val level: SurveyCategoryLevel,

    @Schema(description = "카테고리명", example = "한식")
    @field:NotBlank(message = "카테고리명은 필수입니다")
    val name: String,

    @Schema(description = "카테고리 순서", example = "1")
    @field:NotNull(message = "카테고리 순서는 필수입니다")
    val sortOrder: Int,
)
