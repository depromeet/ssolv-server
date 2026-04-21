package org.depromeet.team3.mapper

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.survey.Survey
import org.depromeet.team3.survey.SurveyEntity
import org.depromeet.team3.survey.exception.SurveyException
import org.springframework.stereotype.Component

@Component
class SurveyMapper(
    private val meetingJpaRepository: MeetingJpaRepository,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
) : DomainMapper<Survey, SurveyEntity> {

    override fun toDomain(entity: SurveyEntity): Survey = Survey(
        id = entity.id,
        meetingId =
        entity.meeting.id ?: throw SurveyException(ErrorCode.MEETING_NOT_FOUND, mapOf("message" to "Meeting ID cannot be null")),
        participantId =
        entity.participant.user.id
            ?: throw SurveyException(ErrorCode.PARTICIPANT_NOT_FOUND, mapOf("message" to "Participant User ID cannot be null")),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    override fun toEntity(domain: Survey): SurveyEntity {
        val meetingEntity = meetingJpaRepository.findById(domain.meetingId)
            .orElseThrow { SurveyException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to domain.meetingId)) }

        val participantEntity = meetingAttendeeJpaRepository.findByMeetingIdAndUserId(domain.meetingId, domain.participantId)
            ?: throw SurveyException(
                ErrorCode.PARTICIPANT_NOT_FOUND,
                mapOf(
                    "meetingId" to domain.meetingId,
                    "participantId" to domain.participantId,
                ),
            )

        return SurveyEntity(
            id = domain.id,
            meeting = meetingEntity,
            participant = participantEntity,
        )
    }
}
