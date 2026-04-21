package org.depromeet.team3.survey.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "설문 생성 요청")
data class SurveyCreateRequest(
    @field:NotEmpty(message = "선택한 음식 카테고리 목록은 필수입니다")
    @Schema(description = "선택된 카테고리 ID 목록", example = "[1, 3, 5]")
    val selectedCategoryList: List<Long>,
)
