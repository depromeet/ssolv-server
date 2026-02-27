package org.depromeet.team3.survey.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "설문 생성 응답")
data class SurveyCreateResponse(
    @Schema(description = "응답 메시지", example = "설문 제출이 완료되었습니다")
    val message: String = "설문 제출이 완료되었습니다"
)

@Schema(description = "설문 항목 응답")
data class SurveyItemResponse(
    @Schema(description = "참가자 ID", example = "1")
    val participantId: Long,
    
    @Schema(description = "참가자 닉네임", example = "홍길동")
    val attendeeNickname: String?,
    
    @Schema(description = "선택된 카테고리 ID 목록", example = "[1, 3, 5]")
    val selectedCategoryList: List<Long>
)

@Schema(description = "설문 목록 응답")
data class SurveyListResponse(
    @Schema(description = "설문 목록")
    val surveys: List<SurveyItemResponse>,
    
    @Schema(description = "설문 참여율 (0.0 ~ 100.0)", example = "75.0")
    val participationRate: Double,
    
    @Schema(description = "모임 설문 완료 여부", example = "false")
    val isCompleted: Boolean
)
