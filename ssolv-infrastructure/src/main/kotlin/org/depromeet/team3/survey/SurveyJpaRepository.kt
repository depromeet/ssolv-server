package org.depromeet.team3.survey

import org.springframework.data.jpa.repository.JpaRepository

interface SurveyJpaRepository : JpaRepository<SurveyEntity, Long> {
    fun findByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): SurveyEntity?
    fun findByMeetingId(meetingId: Long): List<SurveyEntity>
    fun existsByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): Boolean
    fun countByMeetingId(meetingId: Long): Long
}
