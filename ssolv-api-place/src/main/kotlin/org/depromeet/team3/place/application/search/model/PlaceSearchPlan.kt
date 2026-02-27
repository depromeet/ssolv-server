package org.depromeet.team3.place.application.search.model

import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.application.search.support.CreateSurveyKeywordService

/**
 *  검색 실행 전 '검색 전략' 표현하는 모델
 */
sealed interface PlaceSearchPlan {
    val stationCoordinates: MeetingQuery.StationCoordinates?

    /**
     *  설문 결과로 도출된 키워드 기반 검색
     */
    data class Automatic(
        val keywords: List<CreateSurveyKeywordService.KeywordCandidate>,
        override val stationCoordinates: MeetingQuery.StationCoordinates?,
        val fallbackKeyword: String
    ) : PlaceSearchPlan
}