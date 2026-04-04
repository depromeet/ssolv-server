package org.depromeet.team3.meeting.application
import org.depromeet.team3.common.util.withTracingContext
import kotlinx.coroutines.Dispatchers
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meeting.dto.response.MeetingDetailResponse
import org.depromeet.team3.meeting.dto.response.MeetingInfoResponse
import org.depromeet.team3.meeting.dto.response.MeetingParticipantInfo
import org.depromeet.team3.meeting.dto.response.ParticipantSelectedCategory
import org.depromeet.team3.meeting.dto.response.SelectedLeafCategory
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.station.StationJpaRepository
import org.depromeet.team3.survey.SurveyJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveyresult.SurveyResultEntity
import org.depromeet.team3.surveyresult.SurveyResultJpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Service
class GetMeetingDetailService(
    private val meetingJpaRepository: MeetingJpaRepository,
    private val stationJpaRepository: StationJpaRepository,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val surveyJpaRepository: SurveyJpaRepository,
    private val surveyResultJpaRepository: SurveyResultJpaRepository,
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
    private val inviteTokenService: InviteTokenService,
    private val transactionTemplate: TransactionTemplate,
) {

    suspend operator fun invoke(
        meetingId: Long,
        userId: Long,
        allowClosed: Boolean = false
    ): MeetingDetailResponse = withTracingContext() {
        transactionTemplate.execute {
            // 모임 조회 및 endAt 기반 자동 종료 처리 (blocking)
            val meetingEntity = meetingJpaRepository.findByIdOrNull(meetingId)
                ?: throw MeetingException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId))

            // 자동 종료 처리
            val now = LocalDateTime.now()
            val resolvedEntity = if (meetingEntity.endAt != null &&
                now.isAfter(meetingEntity.endAt) &&
                !meetingEntity.isClosed
            ) {
                meetingJpaRepository.save(
                    MeetingEntity(
                        id = meetingEntity.id,
                        name = meetingEntity.name,
                        attendeeCount = meetingEntity.attendeeCount,
                        isClosed = true,
                        endAt = meetingEntity.endAt,
                        hostUser = meetingEntity.hostUser,
                        station = meetingEntity.station
                    )
                )
            } else meetingEntity

            // 종료 모임 검증
            if (resolvedEntity.isClosed && !allowClosed) {
                throw MeetingException(
                    ErrorCode.MEETING_ALREADY_CLOSED,
                    mapOf("meetingId" to meetingId, "userId" to userId)
                )
            }

            // 역 정보 조회
            val stationName = stationJpaRepository.findByIdOrNull(resolvedEntity.station.id!!)?.name ?: ""

            val endAt = requireNotNull(resolvedEntity.endAt) { "모임 종료 시간은 필수입니다" }
            val createdAt = requireNotNull(resolvedEntity.createdAt) { "모임 생성 시간은 필수입니다" }

            val meetingInfo = MeetingInfoResponse(
                id = resolvedEntity.id!!,
                title = resolvedEntity.name,
                hostUserId = resolvedEntity.hostUser.id!!,
                totalParticipantCnt = resolvedEntity.attendeeCount,
                isClosed = resolvedEntity.isClosed,
                stationName = stationName,
                endAt = endAt,
                createdAt = createdAt,
                updatedAt = resolvedEntity.updatedAt,
                token = inviteTokenService.generateToken(
                    org.depromeet.team3.meeting.Meeting(
                        id = resolvedEntity.id,
                        name = resolvedEntity.name,
                        hostUserId = resolvedEntity.hostUser.id!!,
                        attendeeCount = resolvedEntity.attendeeCount,
                        isClosed = resolvedEntity.isClosed,
                        stationId = resolvedEntity.station.id!!,
                        endAt = resolvedEntity.endAt,
                        createdAt = resolvedEntity.createdAt,
                        updatedAt = resolvedEntity.updatedAt
                    )
                )
            )

            // 참가자 목록 조회
            val attendeeEntities = meetingAttendeeJpaRepository.findByMeetingId(meetingId)

            // 모든 설문 조회
            val surveyEntities = surveyJpaRepository.findByMeetingId(meetingId)
            val surveyIds = surveyEntities.mapNotNull { it.id }

            // 설문 결과 조회 (survey, surveyCategory fetch join 적용됨)
            val allSurveyResults = if (surveyIds.isEmpty()) emptyList()
            else surveyResultJpaRepository.findBySurveyIdIn(surveyIds)
            val surveyResultsMap = allSurveyResults.groupBy { it.survey.id }

            // 카테고리 정보 일괄 조회 (N+1 해결)
            val allCategoryIds = allSurveyResults.map { it.surveyCategory.id!! }.distinct()
            val allCategoryMap = surveyCategoryJpaRepository.findAllById(allCategoryIds).associateBy { it.id }

            // Survey.participant.user.id == attendee.user.id 로 매핑
            val surveyByParticipantUserId = surveyEntities.associateBy { it.participant.user.id }

            val participantList = attendeeEntities
                .mapNotNull { attendeeEntity ->
                    val survey = surveyByParticipantUserId[attendeeEntity.user.id]
                        ?: return@mapNotNull null

                    val selectedCategoryList = buildParticipantSelectedCategories(
                        survey.id,
                        surveyResultsMap,
                        allCategoryMap
                    )

                    MeetingParticipantInfo(
                        userId = attendeeEntity.user.id!!,
                        attendeeNickname = attendeeEntity.attendeeNickname ?: "알 수 없음",
                        color = attendeeEntity.muzziColor.name.lowercase(),
                        selectedCategories = selectedCategoryList
                    )
                }
                .sortedByDescending { it.userId == userId }

            MeetingDetailResponse(
                currentUserId = userId,
                meetingInfo = meetingInfo,
                participantList = participantList
            )
        }!!
    }

    private fun buildParticipantSelectedCategories(
        surveyId: Long?,
        surveyResultsMap: Map<Long?, List<SurveyResultEntity>>,
        allCategoryMap: Map<Long?, org.depromeet.team3.surveycategory.SurveyCategoryEntity>
    ): List<ParticipantSelectedCategory> {
        val surveyResults = surveyResultsMap[surveyId] ?: emptyList()
        if (surveyResults.isEmpty()) return emptyList()

        // 이미 일괄 조회된 Map에서 카테고리 추출
        val categoryEntities = surveyResults.mapNotNull { allCategoryMap[it.surveyCategory.id] }

        val branchList = categoryEntities.filter { it.level == SurveyCategoryLevel.BRANCH && it.id != null }
        val leafList = categoryEntities.filter { it.level == SurveyCategoryLevel.LEAF && it.id != null }

        return branchList.mapNotNull { branch ->
            val branchId = branch.id ?: return@mapNotNull null
            val leaves = leafList
                .filter { it.parent?.id == branchId }
                .mapNotNull { leaf ->
                    leaf.id?.let { SelectedLeafCategory(id = it, name = leaf.name) }
                }
            ParticipantSelectedCategory(id = branchId, name = branch.name, leafCategoryList = leaves)
        }
    }

}
