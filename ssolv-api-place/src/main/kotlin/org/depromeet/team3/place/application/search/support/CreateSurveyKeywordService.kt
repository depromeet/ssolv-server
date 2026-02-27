package org.depromeet.team3.place.application.search.support

import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.application.search.planner.GetSurveyAggregateService
import org.depromeet.team3.place.application.search.planner.SelectSurveyKeywordsService
import org.springframework.stereotype.Service

/**
 *  설문, 카테고리, 역 데이터 검증 및 집계
 *  대표 키워드 후보(KeywordCandidate) 추출
 *  역 좌표 포함한 KeywordPlan 반환
 */
@Service
class CreateSurveyKeywordService(
    private val meetingQuery: MeetingQuery,
    private val getSurveyAggregateService: GetSurveyAggregateService,
    private val selectSurveyKeywordsService: SelectSurveyKeywordsService
) {

    fun getStationCoordinates(meetingId: Long): MeetingQuery.StationCoordinates? =
        meetingQuery.getStationCoordinates(meetingId)

    /**
     * 단계 요약:
     * 1. 모임/역/설문/카테고리 존재 여부를 순차 검증한다.
     * 2. 참가자별 설문 결과를 BRANCH/LEAF 카테고리로 집계한다.
     * 3. 전원 선택한 LEAF → 득표율 60% 이상의 LEAF → BRANCH 다수결 → 부족 시 득표 상위 LEAF → 마지막으로 일반 키워드를 채워 최대 5개 키워드를 만든다.
     * 4. 각 키워드에는 득표 비율 기반 가중치를 붙여 상위 순서를 유지한다.
     *
     * @return 키워드 후보들과 역 좌표를 담은 계획 정보
     */
    fun generateKeywordPlan(meetingId: Long): KeywordPlan {
        val aggregate = getSurveyAggregateService.load(meetingId)
        val keywordCandidates = selectSurveyKeywordsService.selectKeywords(aggregate)
        return KeywordPlan(
            keywords = keywordCandidates,
            stationCoordinates = aggregate.stationCoordinates,
            fallbackKeyword = buildFallbackKeyword(aggregate.stationName)
        )
    }

    private fun buildFallbackKeyword(stationName: String): String =
        "$stationName 맛집"

    companion object {
        private val whitespaceRegex = "\\s+".toRegex()

        fun normalizeKeyword(keyword: String): String =
            keyword.replace(whitespaceRegex, " ").trim()
    }

    data class KeywordPlan(
        val keywords: List<KeywordCandidate>,
        val stationCoordinates: MeetingQuery.StationCoordinates?,
        val fallbackKeyword: String
    )

    data class KeywordCandidate(
        val keyword: String,
        val weight: Double,
        val type: KeywordType,
        val categoryName: String? = null,
        val matchKeywords: Set<String> = emptySet(),
        val fallbackKeyword: String? = null,
        val fallbackMatchKeywords: Set<String> = emptySet()
    )

    enum class KeywordType {
        LEAF,
        BRANCH,
        GENERAL
    }
}
