package org.depromeet.team3.place.application.llm.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * LLM을 통한 장소 분석 결과 모델
 */
/**
 * 특정 장소에 대한 LLM의 심층 분석 결과를 담는 모델입니다.
 * 
 * [실행 흐름]
 * 1. 랭킹을 통해 최종 선정된 상위 장소들에 대해 개별 상세 정보(PlaceDetails) 조회
 * 2. LLM이 해당 상세 정보를 분석하여 요약, 주변 랜드마크, 특징 등을 추출 (getPlaceLlmInfo 호출)
 * 3. 추출된 정보를 본 모델로 파싱하여 최종 응답에 포함하거나 DB에 캐싱
 */
data class PlaceLlmAnalysisResult(
    @JsonProperty("id")
    val id: String? = null,

    @JsonProperty("summary")
    val summary: String? = null,
    
    @JsonProperty("landmarks")
    val landmarks: List<String>? = null,
    
    @JsonProperty("reason")
    val reason: String? = null
)
