package org.depromeet.team3.meetingattendee.application
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.withTracingContext
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.meetingattendee.MuzziColor
import org.depromeet.team3.meetingattendee.exception.MeetingAttendeeException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class UpdateAttendeeService(
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val transactionTemplate: TransactionTemplate,
) {

    suspend operator fun invoke(userId: Long, meetingId: Long, attendeeNickname: String, color: String?): Unit = withTracingContext {
        transactionTemplate.execute {
            val entity = meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)
                ?: throw MeetingAttendeeException(
                    errorCode = ErrorCode.PARTICIPANT_NOT_FOUND,
                    detail = mapOf(
                        "meetingId" to meetingId,
                        "userId" to userId,
                    ),
                )

            val currentNickname = entity.attendeeNickname
            if (currentNickname != null && currentNickname == attendeeNickname) {
                throw MeetingAttendeeException(
                    errorCode = ErrorCode.DUPLICATE_NICKNAME,
                    detail = mapOf(
                        "meetingId" to meetingId,
                        "nickname" to attendeeNickname,
                    ),
                )
            }

            // 닉네임 중복 검증 (본인 제외) - blocking
            val hasDuplicate = meetingAttendeeJpaRepository.existsByMeetingIdAndNickname(
                meetingId = meetingId,
                nickname = attendeeNickname,
                excludeUserId = userId,
            )
            if (hasDuplicate) {
                throw MeetingAttendeeException(
                    errorCode = ErrorCode.DUPLICATE_NICKNAME,
                    detail = mapOf(
                        "meetingId" to meetingId,
                        "nickname" to attendeeNickname,
                    ),
                )
            }

            entity.attendeeNickname = attendeeNickname
            entity.muzziColor = MuzziColor.getOrDefault(color)
            meetingAttendeeJpaRepository.save(entity)
        }
    }
}
