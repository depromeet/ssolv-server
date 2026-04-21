package org.depromeet.team3.mapper

import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.exception.UserException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendee
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity
import org.springframework.stereotype.Component

@Component
class MeetingAttendeeMapper(private val userRepository: UserRepository, private val meetingJpaRepository: MeetingJpaRepository) :
    DomainMapper<MeetingAttendee, MeetingAttendeeEntity> {

    override fun toDomain(entity: MeetingAttendeeEntity): MeetingAttendee = MeetingAttendee(
        id = entity.id,
        meetingId = entity.meeting.id!!,
        userId = entity.user.id!!,
        attendeeNickname = entity.attendeeNickname,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        muzziColor = entity.muzziColor,
    )

    override fun toEntity(domain: MeetingAttendee): MeetingAttendeeEntity {
        val meetingEntity = meetingJpaRepository.findById(domain.meetingId)
            .orElseThrow {
                MeetingException(
                    errorCode = ErrorCode.MEETING_NOT_FOUND,
                    detail = mapOf("meetingId" to domain.meetingId),
                )
            }

        val userEntity = userRepository.findById(domain.userId)
            .orElseThrow {
                UserException(
                    errorCode = ErrorCode.USER_NOT_FOUND,
                    detail = mapOf("userId" to domain.userId),
                )
            }

        return MeetingAttendeeEntity(
            id = domain.id,
            meeting = meetingEntity,
            attendeeNickname = domain.attendeeNickname,
            user = userEntity,
            muzziColor = domain.muzziColor,
        )
    }
}
