package org.depromeet.team3.survey.application

import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.survey.Survey
import org.depromeet.team3.survey.SurveyRepository
import org.depromeet.team3.survey.dto.request.SurveyCreateRequest
import org.depromeet.team3.survey.dto.response.SurveyCreateResponse
import org.depromeet.team3.survey.exception.SurveyException
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveyresult.SurveyResult
import org.depromeet.team3.surveyresult.SurveyResultRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class CreateSurveyService(
    private val surveyRepository: SurveyRepository,
    private val surveyResultRepository: SurveyResultRepository,
    private val surveyCategoryRepository: SurveyCategoryRepository,
    private val meetingJpaRepository: MeetingJpaRepository,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend fun invoke(meetingId: Long, userId: Long, request: SurveyCreateRequest): SurveyCreateResponse = withContext(coroutineDispatchers.VT) {
        transactionTemplate.execute {
            // 모임 존재 확인
            if (!meetingJpaRepository.existsById(meetingId)){
                throw SurveyException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId))
            }

            // 참가자 존재 확인 (userId 기준)
            if (!meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)) {
                throw SurveyException(ErrorCode.PARTICIPANT_NOT_FOUND, mapOf("userId" to userId))
            }

            // 중복 설문 제출 확인
            if (runBlocking { surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId) }) {
                throw SurveyException(ErrorCode.SURVEY_ALREADY_SUBMITTED, mapOf("userId" to userId))
            }

            // 설문 생성
            val survey = Survey(
                meetingId = meetingId,
                participantId = userId
            )
            val savedSurvey = runBlocking { surveyRepository.save(survey) }

            // 설문 결과 생성 (선택된 카테고리 ID들을 SurveyResult로 저장)
            val surveyResultList = mutableListOf<SurveyResult>()

            // 선택한 카테고리 목록 검증 및 결과 생성
            if (request.selectedCategoryList.isEmpty()) {
                throw SurveyException(ErrorCode.INVALID_PARAMETER, mapOf("message" to "최소 하나 이상의 카테고리를 선택해야 합니다."))
            }

            // 모든 카테고리를 한 번에 조회 (N+1 문제 해결)
            val selectedCategories = runBlocking { surveyCategoryRepository.findAllById(request.selectedCategoryList) }

            // 조회된 카테고리 수가 요청된 카테고리 수와 다르면 존재하지 않는 카테고리가 있음
            if (selectedCategories.size != request.selectedCategoryList.size) {
                val foundCategoryIds = selectedCategories.mapNotNull { it.id }.toSet()
                val notFoundCategoryIds = request.selectedCategoryList.filter { !foundCategoryIds.contains(it) }
                throw SurveyException(
                    ErrorCode.SURVEY_CATEGORY_NOT_FOUND,
                    mapOf("categoryIds" to notFoundCategoryIds as List<Any>)
                )
            }

            // LEAF 카테고리 검증: LEAF를 선택한 경우 부모 BRANCH도 선택되어야 함
            val selectedCategoryIds = request.selectedCategoryList.toSet()
            selectedCategories.forEach { category ->
                if (category.level == SurveyCategoryLevel.LEAF) {
                    category.parentId?.let { parentId ->
                        if (!selectedCategoryIds.contains(parentId)) {
                            val leafCategoryId = category.id ?: return@forEach
                            throw SurveyException(
                                ErrorCode.SURVEY_BRANCH_CATEGORY_REQUIRED,
                                mapOf<String, Any>(
                                    "leafCategoryId" to leafCategoryId,
                                    "leafCategoryName" to category.name,
                                    "requiredBranchCategoryId" to parentId
                                )
                            )
                        }
                    }
                }
            }

            // LEAF 카테고리 개수 제한 검증: 최대 5개까지 선택 가능
            val leafCategoryCount = selectedCategories.count { it.level == SurveyCategoryLevel.LEAF }
            if (leafCategoryCount > 5) {
                throw SurveyException(
                    ErrorCode.SURVEY_LEAF_CATEGORY_LIMIT_EXCEEDED,
                    mapOf<String, Any>(
                        "selectedLeafCategoryCount" to leafCategoryCount,
                        "maxAllowedCount" to 5
                    )
                )
            }

            // 설문 결과 생성
            request.selectedCategoryList.forEach { categoryId ->
                surveyResultList.add(
                    SurveyResult(
                        surveyId = savedSurvey.id ?: throw IllegalStateException("Survey ID is null"),
                        surveyCategoryId = categoryId
                    )
                )
            }

            // 설문 결과 저장
            runBlocking { surveyResultRepository.saveAll(surveyResultList) }

            SurveyCreateResponse()
        } ?: throw IllegalStateException("Transaction result is null")
    }
}
