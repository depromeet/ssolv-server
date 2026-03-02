package org.depromeet.team3.meeting.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.dto.response.MeetingInfoResponse
import org.depromeet.team3.meeting.dto.response.MeetingsResponse
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.station.StationRepository
import org.depromeet.team3.survey.application.GetSurveyListService
import org.springframework.stereotype.Service

@Service
class GetMeetingService(
    private val meetingRepository: MeetingRepository,
    private val meetingAttendeeRepository: MeetingAttendeeRepository,
    private val stationRepository: StationRepository,
    private val getSurveyListService: GetSurveyListService,
    private val inviteTokenService: InviteTokenService,
    private val coroutineDispatchers: CoroutineDispatchers,
) {

    suspend operator fun invoke(userId: Long): List<MeetingsResponse> = withContext(coroutineDispatchers.VT) {
        // 1. 호스트로 등록된 모임 조회
        val hostMeetings = meetingRepository.findMeetingsByUserId(userId)

        // 2. 참가자로 참여한 모임 조회
        val attendeeList = meetingAttendeeRepository.findByUserId(userId)
        val attendedMeetingIds = attendeeList.map { it.meetingId }.distinct()
        val attendedMeetings = attendedMeetingIds.mapNotNull { meetingRepository.findById(it) }

        // 3. 호스트 모임과 참가 모임 합치고 중복 제거
        val allMeetings = (hostMeetings + attendedMeetings)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }

        // 4. 역 정보 조회 (N+1 문제 해결)
        val stationIds = allMeetings.mapNotNull { it.stationId }.distinct()
        val stationMap = stationRepository.findAllById(stationIds).associateBy { it.id }

        allMeetings.map { meeting ->
            val stationName = stationMap[meeting.stationId]?.name ?: ""
            val meetingInfo = MeetingInfoResponse(
                id = meeting.id!!,
                title = meeting.name,
                hostUserId = meeting.hostUserId,
                totalParticipantCnt = meeting.attendeeCount,
                isClosed = meeting.isClosed,
                stationName = stationName,
                endAt = meeting.endAt!!,
                createdAt = meeting.createdAt!!,
                updatedAt = meeting.updatedAt,
                token = inviteTokenService.generateToken(meeting)
            )

            val participantList = try {
                getSurveyListService.getRespondents(meeting.id!!)
            } catch (e: Exception) {
                emptyList()
            }

            MeetingsResponse(
                meetingInfo = meetingInfo,
                participantList = participantList
            )
        }
    }
}