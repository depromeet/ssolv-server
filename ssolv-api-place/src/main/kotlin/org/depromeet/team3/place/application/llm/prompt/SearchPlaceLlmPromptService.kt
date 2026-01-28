package org.depromeet.team3.place.application.llm.prompt

import org.depromeet.team3.place.model.PlaceDetailsResponse
import org.springframework.stereotype.Service


@Service
class SearchPlaceLlmPromptService {

    /**
     * 장소의 기본 정보와 평점 정보를 바탕으로 특징 요약 및 주변 랜드마크 분석 프롬프트 생성
     */
    fun createPlaceInsightPrompt(place: PlaceDetailsResponse): String {
        val name = place.displayName?.text ?: "알 수 없는 장소"
        val types = place.types?.joinToString(", ") ?: "정보 없음"
        val address = place.formattedAddress ?: ""

        return """
        너는 장소 정보를 분석하여 사용자에게 유익한 정보를 제공하는 도우미야.
        다음 정보를 바탕으로 장소의 특징을 요약하고 주변 랜드마크를 분석해서 JSON 형식으로 응답해줘.

        [장소 정보]
        이름: $name
        유형: $types
        주소: $address
        평점: ${place.rating} (리뷰 ${place.userRatingCount}개)

        [응답 형식]
        {
          "summary": "장소의 특징을 한 문장으로 요약 (예: 합정역 근처의 세련된 이탈리안 레스토랑)",
          "landmarks": ["주소 주위의 유명한 건물, 역, 명소 리스트 (최대 3개)"]
        }
        
        주의사항:
        - summary는 공백 포함 최대 50자, 문장 하나로 작성해.
        - landmarks는 누구나 알법한 유명한 장소나 대중교통역 위주로 골라줘.
        - 한국어로 응답해.
        - JSON 결과 외의 다른 설명은 하지 마.
        """.trimIndent()
    }

    /**
     * 여러 후보 장소 중 사용자 모임 조건에 부합하는 장소들을 선별하기 위한 필터링 프롬프트 생성
     */
    fun createFilterPrompt(candidates: String, criteria: String): String {
        return """
        다음은 사용자의 검색 조건과 여러 장소 후보들이야.
        조건과 직접적으로 관련된 요소를 기준으로 판단하여, 최대 10개까지 골라줘.
        JSON 리스트 형식으로만 응답해.

        [사용자 조건]
        $criteria

        [장소 리스트]
        $candidates

        [응답 형식]
        [
          {
            "id": "place_id",
            "reason": "해당 장소가 선택된 이유 (한 문장)"
          }
        ]
        """.trimIndent()
    }

    /**
     * 여러 장소의 정보를 바탕으로 특징 요약 및 주변 랜드마크 분석을 일괄 요청하는 프롬프트 생성
     */
    fun createBulkPlaceInsightPrompt(places: List<PlaceDetailsResponse>): String {
        val placesString = places.joinToString("\n---\n") { 
            "id: ${it.id}, name: ${it.displayName?.text}, types: ${it.types?.joinToString(", ")}, address: ${it.formattedAddress ?: ""}, rating: ${it.rating ?: 0.0}" 
        }

        return """
        너는 장소 정보를 분석하여 사용자에게 유익한 정보를 제공하는 도우미야.
        다음 장소 리스트를 바탕으로 각각의 특징을 요약하고 주변 랜드마크를 분석해서 JSON 리스트 형식으로 응답해줘.

        [장소 리스트]
        $placesString

        [응답 형식]
        [
          {
            "id": "해당 장소의 id",
            "summary": "장소의 특징을 한 문장으로 요약 (최대 50자)",
            "landmarks": ["주소 주위의 유명한 건물, 역, 명소 (최대 3개)"]
          }
        ]
        
        주의사항:
        - 결과는 반드시 입력받은 id를 정확히 포함하는 JSON 리스트여야 해.
        - summary는 공백 포함 최대 50자, 문장 하나로 작성해.
        - landmarks는 누구나 알법한 유명한 장소나 대중교통역 위주로 골라줘.
        - 한국어로 응답해.
        - JSON 결과 외의 다른 설명은 하지 마.
        """.trimIndent()
    }
}