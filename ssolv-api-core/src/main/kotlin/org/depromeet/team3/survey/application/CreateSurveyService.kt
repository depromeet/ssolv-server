package org.depromeet.team3.survey.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meeting.application.MeetingExpirationSchedulerService
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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/*
 * 모임 참여자의 설문 응답을 저장하고 모든 참여자가 완료했는지 확인하여 결과를 트리거하는 서비스
 */
@Service
class CreateSurveyService(
    private val surveyJpaRepository: SurveyJpaRepository,
    private val surveyResultJpaRepository: SurveyResultJpaRepository,
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
    private val meetingJpaRepository: MeetingJpaRepository,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val stringRedisTemplate: StringRedisTemplate,
    private val meetingExpirationSchedulerService: MeetingExpirationSchedulerService
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

                // 모든 인원 설문 완료 시 식당 추천 자동 트리거 및 알림 발송 (Case 1)
                // 현재 시점은 마지막 인원이 설문 조사를 완료한 시점입니다.
                val currentSurveyCount = surveyJpaRepository.countByMeetingId(meetingId)
                if (currentSurveyCount >= meetingEntity.attendeeCount) {
                    
                    // 1. 식당 검색 추천 요청 발행
                    // 부하가 걸릴 수 있는 검색 로직을 별도 워커나 비동기 리스너에서 처리하도록 큐에 적재합니다.
                    val calcRecord = org.springframework.data.redis.connection.stream.MapRecord.create(
                        "meeting_calculation_stream", mapOf<String, String>("meetingId" to meetingId.toString())
                    )
                    stringRedisTemplate.opsForStream<String, String>().add(calcRecord)

                    // 2. 전 멤버 설문 완료 알림 트리거 (User-facing)
                    // 유저들에게 즉시 알림을 보내어 식당 추천 결과 페이지로 유입되도록 유도합니다.
                    val notarRecord = org.springframework.data.redis.connection.stream.MapRecord.create(
                        "meeting_notification_stream", mapOf<String, String>("meetingId" to meetingId.toString())
                    )
                    stringRedisTemplate.opsForStream<String, String>().add(notarRecord)

                    // 3. 기존 만료 타이머 제거 (Case 2 방지)
                    // 전원 완료로 일찍 끝났으므로, 모임 시간 만료 시점에 불필요한 알림이 중복 발송되지 않도록 타이머를 제거합니다.
                    meetingExpirationSchedulerService.cancelExpiration(meetingId)
                }

                SurveyCreateResponse()
            } ?: throw IllegalStateException("Transaction result is null")
        }
}

