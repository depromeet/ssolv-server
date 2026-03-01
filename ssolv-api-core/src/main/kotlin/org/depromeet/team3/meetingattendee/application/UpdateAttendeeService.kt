package org.depromeet.team3.meetingattendee.application

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.meetingattendee.MuzziColor
import org.depromeet.team3.meetingattendee.exception.MeetingAttendeeException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateAttendeeService(
    private val meetingAttendeeRepository: MeetingAttendeeRepository
) {

    @Transactional
    suspend operator fun invoke(
        userId: Long,
        meetingId: Long,
        attendeeNickname: String,
        color: String?
    ): Unit {
        val attendee = meetingAttendeeRepository.findByMeetingIdAndUserId(meetingId, userId)
            ?: throw MeetingAttendeeException(
                errorCode = ErrorCode.PARTICIPANT_NOT_FOUND,
                detail = mapOf(
                    "meetingId" to meetingId,
                    "userId" to userId
                )
            )
        
        val currentNickname = attendee.attendeeNickname
        
        // 현재 닉네임과 동일하면 바로 에러
        if (currentNickname != null && currentNickname == attendeeNickname) {
            throw MeetingAttendeeException(
                errorCode = ErrorCode.DUPLICATE_NICKNAME,
                detail = mapOf(
                    "meetingId" to meetingId,
                    "nickname" to attendeeNickname
                )
            )
        }
        
        validateNicknameDuplication(meetingId, attendeeNickname, currentNickname, userId)
        
        attendee.attendeeNickname = attendeeNickname
        attendee.muzziColor = MuzziColor.getOrDefault(color)

        meetingAttendeeRepository.save(attendee)
    }

    private suspend fun validateNicknameDuplication(
        meetingId: Long,
        attendeeNickname: String,
        currentNickname: String?,
        userId: Long
    ) {
        // 같은 모임 내의 모든 참여자 조회
        val allAttendees = meetingAttendeeRepository.findByMeetingId(meetingId)
        
        // 다른 사용자가 같은 닉네임을 사용 중인지 확인 (본인 제외)
        val hasDuplicate = allAttendees.any { attendee ->
            attendee.userId != userId &&
            attendee.attendeeNickname != null &&
            attendee.attendeeNickname == attendeeNickname
        }
        
        if (hasDuplicate) {
            throw MeetingAttendeeException(
                errorCode = ErrorCode.DUPLICATE_NICKNAME,
                detail = mapOf(
                    "meetingId" to meetingId,
                    "nickname" to attendeeNickname
                )
            )
        }
    }
}