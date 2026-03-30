package org.depromeet.team3.meeting.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
) {

    suspend operator fun invoke(userId: Long): List<MeetingsResponse> = withContext(Dispatchers.IO) {
        withTimeout(10000L) { // 10초 타임아웃 설정
            // 1. 호스트로 등록된 모임 조회
            val hostMeetings = meetingRepository.findMeetingsByUserId(userId)

            // 2. 참가자로 참여한 모임 조회
            val attendeeList = meetingAttendeeRepository.findByUserId(userId)
            val attendedMeetingIds = attendeeList.map { it.meetingId }.distinct()
            val attendedMeetings = meetingRepository.findAllById(attendedMeetingIds)

            // 3. 호스트 모임과 참가 모임 합치고 중복 제거
            val allMeetings = (hostMeetings + attendedMeetings)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }

            // 4. 역 정보 및 응답자 정보 일괄 조회 (N+1 문제 해결)
            val meetingIds = allMeetings.mapNotNull { it.id }
            val stationIds = allMeetings.mapNotNull { it.stationId }.distinct()

            val stationMap = stationRepository.findAllById(stationIds).associateBy { it.id }
            val respondentsMap = getSurveyListService.getRespondentsMap(meetingIds)

            allMeetings.map { meeting ->
                val stationName = stationMap[meeting.stationId]?.name ?: ""
                val meetingId = meeting.id!!

                val meetingInfo = MeetingInfoResponse(
                    id = meetingId,
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

                val participantList = respondentsMap[meetingId] ?: emptyList()

                MeetingsResponse(
                    meetingInfo = meetingInfo,
                    participantList = participantList
                )
            }
        }
    }
}