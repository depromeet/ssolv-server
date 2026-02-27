package org.depromeet.team3.place.application.search.planner

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.application.search.model.PlaceSurveySummary
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.station.StationRepository
import org.depromeet.team3.survey.SurveyRepository
import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveyresult.SurveyResultRepository
import org.springframework.stereotype.Service

/**
 * 모임 → 역 → 설문 → 카테고리 순으로 존재 여부 확인
 * 설문 응답 결과를 branch / leaf 단위로 그룹화 및 득표수 집계
 */
@Service
class GetSurveyAggregateService(
    private val meetingQuery: MeetingQuery,
    private val surveyRepository: SurveyRepository,
    private val surveyResultRepository: SurveyResultRepository,
    private val surveyCategoryRepository: SurveyCategoryRepository,
    private val stationRepository: StationRepository
) {

    fun load(meetingId: Long): PlaceSurveySummary {
        val meeting = meetingQuery.findById(meetingId)
            ?: throw PlaceSearchException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId))

        val station = stationRepository.findById(meeting.stationId)
            ?: throw PlaceSearchException(ErrorCode.STATION_NOT_FOUND, mapOf("stationId" to meeting.stationId))

        val stationCoordinates = meetingQuery.getStationCoordinates(meetingId)

        val surveys = surveyRepository.findByMeetingId(meetingId)
        if (surveys.isEmpty()) {
            throw PlaceSearchException(ErrorCode.SURVEY_NOT_FOUND, mapOf("meetingId" to meetingId))
        }

        val surveyIds = surveys.mapNotNull { it.id }
        val surveyResults = surveyResultRepository.findBySurveyIdIn(surveyIds)
        if (surveyResults.isEmpty()) {
            throw PlaceSearchException(ErrorCode.SURVEY_RESULT_NOT_FOUND, mapOf("meetingId" to meetingId))
        }

        val categoryIds = surveyResults.map { it.surveyCategoryId }.distinct()
        if (categoryIds.isEmpty()) {
            throw PlaceSearchException(ErrorCode.SURVEY_CATEGORY_NOT_FOUND, mapOf("meetingId" to meetingId))
        }

        val initialCategories = surveyCategoryRepository.findAllById(categoryIds).toList()
        if (initialCategories.isEmpty()) {
            throw PlaceSearchException(ErrorCode.SURVEY_CATEGORY_NOT_FOUND, mapOf("meetingId" to meetingId))
        }

        val parentIds = initialCategories
            .filter { it.level == SurveyCategoryLevel.LEAF }
            .mapNotNull { it.parentId }
            .distinct()

        val parentCategories = if (parentIds.isNotEmpty()) {
            surveyCategoryRepository.findAllById(parentIds).toList()
        } else {
            emptyList()
        }

        val categories = (initialCategories + parentCategories)
            .distinctBy { it.id }

        val branchCategories = mutableMapOf<Long, SurveyCategory>()
        val leafCategories = mutableMapOf<Long, SurveyCategory>()
        categories.forEach { category ->
            val id = category.id ?: return@forEach
            when (category.level) {
                SurveyCategoryLevel.BRANCH -> branchCategories[id] = category
                SurveyCategoryLevel.LEAF -> if (category.parentId != null) {
                    leafCategories[id] = category
                }
            }
        }

        // 각 설문(참가자)별로 선택한 카테고리를 그룹화
        // Survey는 참가자당 1개씩 생성되므로, surveyId로 그룹화하면 참가자별 응답을 구분할 수 있음
        val resultsBySurvey = surveyResults.groupBy { it.surveyId }
        
        // 설문을 제출한 참가자 수 = 설문 개수
        // (Survey는 meetingId + participantId 조합이 unique하므로, 각 surveyId는 고유한 참가자를 의미)
        val totalRespondents = resultsBySurvey.size
        if (totalRespondents == 0) {
            throw PlaceSearchException(ErrorCode.SURVEY_RESULT_NOT_FOUND, mapOf("meetingId" to meetingId))
        }

        val branchVotes = mutableMapOf<Long, Int>()
        val leafVotes = mutableMapOf<Long, Int>()

        // 각 참가자(설문)별로 선택한 카테고리를 집계
        resultsBySurvey.values.forEach { answers ->
            // 한 참가자가 같은 카테고리를 중복 선택한 경우를 제거 (실제로는 발생하지 않아야 함)
            val uniqueCategoryIds = answers.map { it.surveyCategoryId }.toSet()
            
            // 이 참가자가 선택한 BRANCH와 LEAF 카테고리를 분류
            val branchSet = mutableSetOf<Long>()
            val leafSet = mutableSetOf<Long>()

            uniqueCategoryIds.forEach { categoryId ->
                when {
                    branchCategories.containsKey(categoryId) -> branchSet.add(categoryId)
                    leafCategories.containsKey(categoryId) -> leafSet.add(categoryId)
                }
            }

            // LEAF 득표를 먼저 집계하고, 부모 BRANCH를 branchSet에 추가
            leafSet.forEach { leafId ->
                leafVotes[leafId] = (leafVotes[leafId] ?: 0) + 1

                val parentId = leafCategories[leafId]?.parentId
                if (parentId != null) {
                    branchSet.add(parentId)
                }
            }

            // BRANCH 득표는 부모 정보까지 모두 반영한 뒤 집계
            branchSet.forEach { branchId ->
                branchVotes[branchId] = (branchVotes[branchId] ?: 0) + 1
            }
        }

        return PlaceSurveySummary(
            stationName = station.name,
            stationCoordinates = stationCoordinates,
            totalRespondents = totalRespondents,
            leafVotes = leafVotes,
            branchVotes = branchVotes,
            leafCategories = leafCategories,
            branchCategories = branchCategories
        )
    }
}