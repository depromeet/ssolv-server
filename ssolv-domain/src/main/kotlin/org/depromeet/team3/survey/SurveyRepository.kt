package org.depromeet.team3.survey

interface SurveyRepository {
    suspend fun save(survey: Survey): Survey
    suspend fun findByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): Survey?
    suspend fun findByMeetingId(meetingId: Long): List<Survey>
    suspend fun findByMeetingIdIn(meetingIds: List<Long>): List<Survey>
    suspend fun existsByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): Boolean
}
