package org.depromeet.team3.meeting.application

import org.depromeet.team3.common.ContextConstants.API_VERSION_V1
import org.depromeet.team3.common.ContextConstants.BASE_DOMAIN
import org.depromeet.team3.common.ContextConstants.HTTPS_PROTOCOL
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.Meeting
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.dto.response.ValidateInviteTokenResponse
import org.depromeet.team3.meeting.exception.InvalidInviteTokenException
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.util.DataEncoder
import org.depromeet.team3.util.MeetingIdParser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

@Service
class InviteTokenService(
    private val meetingRepository: MeetingRepository,
    private val meetingAttendeeRepository: MeetingAttendeeRepository
) {
    
    private companion object {
        const val SEPARATOR = ":"
    }

    @Transactional(readOnly = true)
    suspend fun generateInviteToken(
        meetingId: Long
    ): String {
        val meeting = meetingRepository.findById(meetingId)
            ?: throw IllegalArgumentException("Not Found meeting ID: $meetingId")

        val token = generateToken(meeting)
            ?: throw IllegalStateException("Ended meeting ID: $meetingId")
        
        return "$HTTPS_PROTOCOL/$BASE_DOMAIN/$API_VERSION_V1/meetings/validate-invite?token=$token"
    }

    fun generateToken(meeting: Meeting): String? {
        if (meeting.isClosed) {
            return null
        }

        val endAtTimestamp = meeting.endAt?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: Long.MAX_VALUE
        
        return DataEncoder.encodeWithSeparator(SEPARATOR, meeting.id.toString(), endAtTimestamp.toString())
    }

    @Transactional(readOnly = true)
    suspend fun validateInviteToken(userId: Long, token: String): ValidateInviteTokenResponse {
        val (meetingId, expiryTimestamp) = parseTokenData(token)

        // 토큰 만료 조회
        if (System.currentTimeMillis() > expiryTimestamp) {
            throw InvalidInviteTokenException(ErrorCode.TOKEN_EXPIRED)
        }

        // 모임 조회
        val meeting = meetingRepository.findById(meetingId)
            ?: throw MeetingException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId))

        // 이미 참여 모임 검증
        val joined = meetingAttendeeRepository.existsByMeetingIdAndUserId(meetingId, userId)
        if (joined) throw MeetingException(
            ErrorCode.MEETING_ALREADY_JOINED,
            mapOf(
                "userId" to userId,
                "meetingId" to meetingId
            )
        )

        // 종료 모임 검증
        if (meeting.isClosed) {
            throw MeetingException(
                ErrorCode.MEETING_ALREADY_CLOSED,
                mapOf(
                    "meetingId" to meetingId,
                    "userId" to userId
                )
            )
        }

        return ValidateInviteTokenResponse(meetingId)
    }

    @Transactional(readOnly = true)
    fun resolveMeetingId(identifier: String): Long {
        return MeetingIdParser.parse(identifier)
    }

    private fun parseTokenData(token: String): Pair<Long, Long> {
        val parts = DataEncoder.decodeWithSeparator(token, SEPARATOR)
            ?.takeIf { it.size == 2 }
            ?: throw InvalidInviteTokenException(ErrorCode.INVALID_TOKEN_FORMAT)

        val meetingId = parts[0].toLongOrNull()
            ?: throw InvalidInviteTokenException(ErrorCode.INVALID_MEETING_ID_IN_TOKEN)

        val expiryTimestamp = parts[1].toLongOrNull()
            ?: throw InvalidInviteTokenException(ErrorCode.INVALID_EXPIRY_TIME_IN_TOKEN)

        return Pair(meetingId, expiryTimestamp)
    }
}
