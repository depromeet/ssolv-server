package org.depromeet.team3.survey.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.survey.SurveyEntity
import org.depromeet.team3.survey.SurveyJpaRepository
import org.depromeet.team3.survey.dto.request.SurveyCreateRequest
import org.depromeet.team3.survey.dto.response.SurveyCreateResponse
import org.depromeet.team3.survey.exception.SurveyException
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveyresult.SurveyResultEntity
import org.depromeet.team3.surveyresult.SurveyResultJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class CreateSurveyService(
    private val surveyJpaRepository: SurveyJpaRepository,
    private val surveyResultJpaRepository: SurveyResultJpaRepository,
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
    private val meetingJpaRepository: MeetingJpaRepository,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend fun invoke(meetingId: Long, userId: Long, request: SurveyCreateRequest): SurveyCreateResponse =
        withContext(coroutineDispatchers.VT) {
            transactionTemplate.execute {
                // 모임 존재 확인
                val meetingEntity = meetingJpaRepository.findById(meetingId).orElseThrow {
                    SurveyException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId))
                }

                // 참가자 존재 확인 (userId 기준)
                val attendeeEntity = meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)
                    ?: throw SurveyException(ErrorCode.PARTICIPANT_NOT_FOUND, mapOf("userId" to userId))

                // 중복 설문 제출 확인 (participant_id는 MeetingAttendeeEntity.id)
                if (surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)) {
                    throw SurveyException(ErrorCode.SURVEY_ALREADY_SUBMITTED, mapOf("userId" to userId))
                }

                // 설문 생성
                val savedSurvey = surveyJpaRepository.save(
                    SurveyEntity(meeting = meetingEntity, participant = attendeeEntity)
                )

                // 카테고리 선택 검증
                if (request.selectedCategoryList.isEmpty()) {
                    throw SurveyException(
                        ErrorCode.INVALID_PARAMETER,
                        mapOf("message" to "최소 하나 이상의 카테고리를 선택해야 합니다.")
                    )
                }

                // 모든 카테고리를 한 번에 조회 (N+1 문제 해결)
                val selectedCategories = surveyCategoryJpaRepository.findAllById(request.selectedCategoryList)

                if (selectedCategories.size != request.selectedCategoryList.size) {
                    val foundIds = selectedCategories.mapNotNull { it.id }.toSet()
                    val notFoundIds = request.selectedCategoryList.filter { !foundIds.contains(it) }
                    throw SurveyException(
                        ErrorCode.SURVEY_CATEGORY_NOT_FOUND,
                        mapOf("categoryIds" to notFoundIds as List<Any>)
                    )
                }

                // LEAF 카테고리 검증
                val selectedCategoryIds = request.selectedCategoryList.toSet()
                selectedCategories.forEach { category ->
                    if (category.level == SurveyCategoryLevel.LEAF) {
                        category.parent?.id?.let { parentId ->
                            if (!selectedCategoryIds.contains(parentId)) {
                                val leafId = category.id ?: return@forEach
                                throw SurveyException(
                                    ErrorCode.SURVEY_BRANCH_CATEGORY_REQUIRED,
                                    mapOf<String, Any>(
                                        "leafCategoryId" to leafId,
                                        "leafCategoryName" to category.name,
                                        "requiredBranchCategoryId" to parentId
                                    )
                                )
                            }
                        }
                    }
                }

                // LEAF 카테고리 개수 제한 (최대 5개)
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

                // 설문 결과 저장
                val surveyResultEntities = selectedCategories.map { categoryEntity ->
                    SurveyResultEntity(survey = savedSurvey, surveyCategory = categoryEntity)
                }
                surveyResultJpaRepository.saveAll(surveyResultEntities)

                SurveyCreateResponse()
            } ?: throw IllegalStateException("Transaction result is null")
        }
}

