package org.depromeet.team3.survey.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendee
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.survey.SurveyRepository
import org.depromeet.team3.survey.dto.GetRespondents
import org.depromeet.team3.surveyresult.SurveyResultRepository
import org.depromeet.team3.survey.dto.response.SurveyItemResponse
import org.depromeet.team3.survey.dto.response.SurveyListResponse
import org.depromeet.team3.survey.exception.SurveyException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class GetSurveyListService(
    private val surveyRepository: SurveyRepository,
    private val surveyResultRepository: SurveyResultRepository,
    private val meetingJpaRepository: MeetingJpaRepository,
    private val meetingAttendeeRepository: MeetingAttendeeRepository,
) {
    private val logger = LoggerFactory.getLogger(GetSurveyListService::class.java)

    suspend fun invoke(meetingId: Long, userId: Long): SurveyListResponse = withContext(Dispatchers.IO) {
        // 모임 존재 확인
        if (!meetingJpaRepository.existsById(meetingId)) {
            throw SurveyException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId))
        }

        // 사용자가 해당 모임의 참가자인지 확인
        if (!meetingAttendeeRepository.existsByMeetingIdAndUserId(meetingId, userId)) {
            throw SurveyException(ErrorCode.PARTICIPANT_NOT_FOUND, mapOf("userId" to userId))
        }

        // 모임의 모든 설문 조회
        val surveys = surveyRepository.findByMeetingId(meetingId)
        logger.debug("Found {} surveys for meetingId={}", surveys.size, meetingId)

        // 전체 참가자 수 조회
        val totalParticipants = meetingAttendeeRepository.countByMeetingId(meetingId)
        logger.debug("Total participants: {} for meetingId={}", totalParticipants, meetingId)

        // 설문 참여율 계산 (참여자 수 / 전체 참가자 수) * 100
        val participationRate = if (totalParticipants > 0) {
            (surveys.size.toDouble() / totalParticipants) * 100.0
        } else {
            0.0
        }

        // 설문 완료 여부 (모든 참가자가 설문을 완료했는지)
        val isCompleted = surveys.size == totalParticipants

        logger.debug("Participation rate: {}, Is completed: {} for meetingId={}", participationRate, isCompleted, meetingId)

        // 모든 설문 결과를 한 번에 조회 (N+1 문제 해결)
        val surveyIds = surveys.mapNotNull { it.id }
        val allSurveyResults = if (surveyIds.isEmpty()) {
            emptyList()
        } else {
            surveyResultRepository.findBySurveyIdIn(surveyIds)
        }
        val surveyResultsMap = allSurveyResults.groupBy { it.surveyId }

        // 모든 참가자를 한 번에 조회 (N+1 문제 해결)
        val attendeeList = meetingAttendeeRepository.findByMeetingId(meetingId)
        val attendeeMap = attendeeList.associateBy { it.userId }

        // 각 설문의 결과 정보와 함께 응답 생성
        val surveyItems = surveys.map { survey ->
            logger.debug("Processing survey id={}, participantId={} for meetingId={}", survey.id, survey.participantId, meetingId)

            val results = surveyResultsMap[survey.id] ?: emptyList()

            // 설문 결과에서 카테고리 목록 생성
            val selectedCategoryList = results.map { it.surveyCategoryId }

            // Map에서 참가자 정보 조회
            val participant = attendeeMap[survey.participantId]
            logger.debug("Participant found: {} for meetingId={}, participantId={}", participant, meetingId, survey.participantId)

            if (participant == null) {
                throw SurveyException(ErrorCode.PARTICIPANT_NOT_FOUND, mapOf("participantId" to survey.participantId))
            }

            SurveyItemResponse(
                participantId = survey.participantId,
                attendeeNickname = participant.attendeeNickname,
                selectedCategoryList = selectedCategoryList
            )
        }

        SurveyListResponse(
            surveys = surveyItems,
            participationRate = participationRate,
            isCompleted = isCompleted
        )
    }

    suspend fun getRespondents(meetingId: Long): List<GetRespondents> = withContext(Dispatchers.IO) {
        // 모든 참가자를 한 번에 조회 (N+1 문제 해결)
        val attendeeList = meetingAttendeeRepository.findByMeetingId(meetingId)
        val attendeeMap = attendeeList.associateBy { it.userId }
        
        // 설문 작성자 ID 목록 조회
        val participantIdList = surveyRepository.findByMeetingId(meetingId)
            .map { it.participantId }
        
        participantIdList
            .mapNotNull { id -> attendeeMap[id] }
            .map { attendee -> attendee.toGetRespondents() }
    }

    suspend fun getRespondentsMap(meetingIds: List<Long>): Map<Long, List<GetRespondents>> = withContext(Dispatchers.IO) {
        if (meetingIds.isEmpty()) return@withContext emptyMap()

        // 1. 모든 미팅의 참가자 일괄 조회
        val allAttendees = meetingAttendeeRepository.findByMeetingIdIn(meetingIds)
        val attendeesByMeeting = allAttendees.groupBy { it.meetingId }

        // 2. 모든 미팅의 설문 일괄 조회
        val allSurveys = surveyRepository.findByMeetingIdIn(meetingIds)
        val surveysByMeeting = allSurveys.groupBy { it.meetingId }

        // 3. 미팅 ID별로 응답자 리스트 구성
        meetingIds.associateWith { meetingId ->
            val attendeeMap = attendeesByMeeting[meetingId]?.associateBy { it.userId } ?: emptyMap()
            val participantIds = surveysByMeeting[meetingId]?.map { it.participantId } ?: emptyList()

            participantIds.mapNotNull { id -> attendeeMap[id] }
                .map { attendee -> attendee.toGetRespondents() }
        }
    }

    private fun MeetingAttendee.toGetRespondents(): GetRespondents {
        return GetRespondents(
            userId = this.userId,
            attendeeNickname = this.attendeeNickname ?: "",
            color = this.muzziColor.name
        )
    }
}
