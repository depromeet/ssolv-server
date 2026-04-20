package org.depromeet.team3.survey.application
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import org.depromeet.team3.common.util.withTracingContext
import kotlinx.coroutines.Dispatchers
import org.depromeet.team3.common.exception.ErrorCode
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
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.springframework.data.redis.connection.stream.MapRecord
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
    private val stringRedisTemplate: StringRedisTemplate,
    private val meetingExpirationSchedulerService: MeetingExpirationSchedulerService,
    private val openTelemetry: OpenTelemetry,
) {

    suspend fun invoke(meetingId: Long, userId: Long, request: SurveyCreateRequest): SurveyCreateResponse =
        withTracingContext() {
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
                    
                    val requestId = org.slf4j.MDC.get(org.depromeet.team3.common.filter.MdcLoggingFilter.REQUEST_ID) ?: java.util.UUID.randomUUID().toString().substring(0, 8)

                    // 현재 OTEL 컨텍스트의 traceparent를 메시지에 포함하여 컨슈머에서 child span 생성 가능하게 함
                    val traceCarrier = mutableMapOf<String, String>()
                    openTelemetry.propagators.textMapPropagator.inject(
                        Context.current(),
                        traceCarrier,
                        TextMapSetter { carrier, key, value -> carrier?.set(key, value) }
                    )

                    // 1. 식당 검색 추천 요청 발행
                    // 부하가 걸릴 수 있는 검색 로직을 별도 워커나 비동기 리스너에서 처리하도록 큐에 적재합니다.
                    val calcRecord = MapRecord.create(
                        RedisStreamConstants.MEETING_CALCULATION_STREAM, mapOf<String, String>(
                            "meetingId" to meetingId.toString(),
                            "requestId" to requestId,
                            "traceparent" to (traceCarrier["traceparent"] ?: ""),
                            "tracestate" to (traceCarrier["tracestate"] ?: "")
                        )
                    )
                    stringRedisTemplate.opsForStream<String, String>().add(calcRecord)

                    // 2. 전 멤버 설문 완료 알림 트리거 (Fan-out: 구성원 한 명 당 1개 메시지)
                    // 개별 유저별 전송 성공 여부를 추적하기 위해 메시지를 분할하여 발행합니다.
                    val attendees = meetingAttendeeJpaRepository.findByMeetingId(meetingId)
                    attendees.forEach { attendee ->
                        val notarRecord = MapRecord.create(
                            RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
                            mapOf(
                                "meetingId" to meetingId.toString(),
                                "userId" to attendee.user.id.toString(),
                                "requestId" to requestId,
                                "traceparent" to (traceCarrier["traceparent"] ?: ""),
                                "tracestate" to (traceCarrier["tracestate"] ?: "")
                            )
                        )
                        stringRedisTemplate.opsForStream<String, String>().add(notarRecord)
                    }

                    // 3. 기존 만료 타이머 제거 (Case 2 방지)
                    meetingExpirationSchedulerService.cancelExpiration(meetingId)
                }

                SurveyCreateResponse()
            } ?: throw IllegalStateException("Transaction result is null")
    }
}

