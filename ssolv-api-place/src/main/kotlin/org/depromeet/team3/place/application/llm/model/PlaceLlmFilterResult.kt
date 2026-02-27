package org.depromeet.team3.place.application.llm.model

/**
 * LLM을 통한 후보 장소 필터링 결과 모델
 */
/**
 * LLM을 사용해 검색된 후보 장소들을 모임 성격에 맞게 1차적으로 필터링한 결과 모델입니다.
 * 
 * [실행 흐름]
 * 1. Google 검색을 통해 방대한 후보 장소군을 수집
 * 2. LLM이 후보군 리스트와 모임 정보를 대조하여 선별 (filterCandidateByBasicInfo 호출)
 * 3. 선별된 장소의 ID와 맞춤형 추천 사유(reason)를 본 모델에 담아 반환
 */
data class PlaceLlmFilterResult(
    val id: String,
    val reason: String
)