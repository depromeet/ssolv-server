package org.depromeet.team3.place.application.search.model

import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.surveycategory.SurveyCategory

/**
 *  설문 결과 전체를 요약한 스냅샷
 */
data class PlaceSurveySummary(
    val stationName: String,
    val stationCoordinates: MeetingQuery.StationCoordinates?,
    val totalRespondents: Int,
    val leafVotes: Map<Long, Int>,
    val branchVotes: Map<Long, Int>,
    val leafCategories: Map<Long, SurveyCategory>,
    val branchCategories: Map<Long, SurveyCategory>
)