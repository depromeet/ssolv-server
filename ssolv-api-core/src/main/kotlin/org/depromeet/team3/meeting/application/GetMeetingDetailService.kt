package org.depromeet.team3.meeting.application

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.Meeting
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.dto.response.MeetingDetailResponse
import org.depromeet.team3.meeting.dto.response.MeetingInfoResponse
import org.depromeet.team3.meeting.dto.response.MeetingParticipantInfo
import org.depromeet.team3.meeting.dto.response.ParticipantSelectedCategory
import org.depromeet.team3.meeting.dto.response.SelectedLeafCategory
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.station.StationRepository
import org.depromeet.team3.survey.SurveyRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveyresult.SurveyResult
import org.depromeet.team3.surveyresult.SurveyResultRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.requireNotNull

@Service
class GetMeetingDetailService(
    private val meetingRepository: MeetingRepository,
    private val stationRepository: StationRepository,
    private val meetingAttendeeRepository: MeetingAttendeeRepository,
    private val surveyRepository: SurveyRepository,
    private val surveyResultRepository: SurveyResultRepository,
    private val surveyCategoryRepository: SurveyCategoryRepository,
    private val inviteTokenService: InviteTokenService,
) {

    @Transactional
    suspend operator fun invoke(
        meetingId: Long,
        userId: Long,
        allowClosed: Boolean = false
    ): MeetingDetailResponse {
        // 모임 조회 및 endAt 기반 자동 종료 처리
        val meeting = findAndAutoCloseIfExpired(meetingId)
        
        // 종료 모임 검증
        if (meeting.isClosed && !allowClosed) {
            throw MeetingException(
                ErrorCode.MEETING_ALREADY_CLOSED,
                mapOf(
                    "meetingId" to meetingId,
                    "userId" to userId
                )
            )
        }

        // 역 정보 조회
        val station = stationRepository.findById(meeting.stationId)
        val stationName = station?.name ?: ""

        // 필수 필드 검증
        val validatedMeetingId = requireNotNull(meeting.id) { "모임 ID는 필수입니다" }
        val endAt = requireNotNull(meeting.endAt) { "모임 종료 시간은 필수입니다" }
        val createdAt = requireNotNull(meeting.createdAt) { "모임 생성 시간은 필수입니다" }

        // MeetingInfoResponse 생성
        val meetingInfo = MeetingInfoResponse(
            id = validatedMeetingId,
            title = meeting.name,
            hostUserId = meeting.hostUserId,
            totalParticipantCnt = meeting.attendeeCount,
            isClosed = meeting.isClosed,
            stationName = stationName,
            endAt = endAt,
            createdAt = createdAt,
            updatedAt = meeting.updatedAt,
            token = inviteTokenService.generateToken(meeting)
        )

        // 참가자 목록 조회
        val attendeeList = meetingAttendeeRepository.findByMeetingId(meetingId)
        
        // 모든 설문을 한 번에 조회 (N+1 문제 해결)
        val surveyList = surveyRepository.findByMeetingId(meetingId)
        // 주의: Survey.participantId 는 사용자 ID(userId)로 저장됨
        val surveyMap = surveyList.associateBy { it.participantId }
        
        // 모든 설문 결과를 한 번에 조회 (N+1 문제 해결)
        val surveyIds = surveyList.mapNotNull { it.id }
        val allSurveyResults = if (surveyIds.isEmpty()) {
            emptyList()
        } else {
            surveyResultRepository.findBySurveyIdIn(surveyIds)
        }
        val surveyResultsMap = allSurveyResults.groupBy { it.surveyId }

        // 설문이 있는 참가자만 participantList에 포함 (참가자의 userId 기준 매칭)
        val participantList = attendeeList
            .mapNotNull { attendee ->
                // Map에서 참가자의 설문 조회 (Survey.participantId == attendee.userId)
                val survey = surveyMap[attendee.userId]

                // 설문이 없는 경우 null 반환하여 제외
                survey ?: return@mapNotNull null

                // 설문이 있는 경우 선택한 카테고리 목록 생성
                val surveyId = requireNotNull(survey.id) { "설문 ID는 필수입니다" }
                val selectedCategoryList = buildParticipantSelectedCategories(surveyId, surveyResultsMap)

                MeetingParticipantInfo(
                    userId = attendee.userId,
                    attendeeNickname = attendee.attendeeNickname ?: "알 수 없음",
                    color = attendee.muzziColor.name.lowercase(),
                    selectedCategories = selectedCategoryList
                )
            }
            .sortedByDescending { it.userId == userId } // 현재 사용자의 설문 결과를 맨 앞으로 정렬

        return MeetingDetailResponse(
            currentUserId = userId,
            meetingInfo = meetingInfo,
            participantList = participantList
        )
    }

    private suspend fun buildParticipantSelectedCategories(surveyId: Long, surveyResultsMap: Map<Long, List<SurveyResult>>): List<ParticipantSelectedCategory> {
        // 설문 결과 조회 (Map에서 조회)
        val surveyResults = surveyResultsMap[surveyId] ?: emptyList()
        if (surveyResults.isEmpty()) {
            return emptyList()
        }

        // 카테고리 ID 목록 조회
        val categoryIdList = surveyResults.map { it.surveyCategoryId }
        val categoryList = surveyCategoryRepository.findAllById(categoryIdList)

        // BRANCH 카테고리와 LEAF 카테고리 분리 (null id 제외)
        val branchCategoryList = categoryList.filter { it.level == SurveyCategoryLevel.BRANCH && it.id != null }
        val leafCategoryList = categoryList.filter { it.level == SurveyCategoryLevel.LEAF && it.id != null }

        // BRANCH 카테고리별로 해당하는 LEAF 카테고리들을 그룹화
        return branchCategoryList.mapNotNull { branchCategory ->
            val branchId = branchCategory.id ?: return@mapNotNull null
            
            val leafCategoriesForBranch = leafCategoryList
                .filter { it.parentId == branchId }
                .mapNotNull { leafCategory ->
                    val leafId = leafCategory.id ?: return@mapNotNull null
                    SelectedLeafCategory(
                        id = leafId,
                        name = leafCategory.name
                    )
                }

            ParticipantSelectedCategory(
                id = branchId,
                name = branchCategory.name,
                leafCategoryList = leafCategoriesForBranch
            )
        }
    }

    /**
     * 모임을 조회하고, endAt이 지났다면 자동으로 isClosed를 true로 설정하여 DB에 반영
     */
    private suspend fun findAndAutoCloseIfExpired(meetingId: Long): Meeting {
        val meeting = meetingRepository.findById(meetingId)
            ?: throw MeetingException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId))

        // endAt이 지났고 아직 종료되지 않은 경우 자동 종료 처리
        val now = LocalDateTime.now()
        if (meeting.endAt != null && 
            now.isAfter(meeting.endAt) && 
            !meeting.isClosed) {
            val closedMeeting = meeting.copy(
                isClosed = true,
                updatedAt = now
            )
            return meetingRepository.save(closedMeeting)
        }

        return meeting
    }
}

