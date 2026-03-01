package org.depromeet.team3.meeting.application

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.Meeting
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.dto.request.CreateMeetingRequest
import org.depromeet.team3.meeting.dto.response.CreateMeetingResponse
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendee
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.meetingattendee.MuzziColor
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class CreateMeetingService(
    private val meetingRepository: MeetingRepository,
    private val meetingAttendeeRepository: MeetingAttendeeRepository,
    private val inviteTokenService: InviteTokenService,
    private val userRepository: UserRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend operator fun invoke(request: CreateMeetingRequest, userId: Long): CreateMeetingResponse = withContext(coroutineDispatchers.VT) {
        val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        if (request.endAt != null && request.endAt.isBefore(now)) {
            throw MeetingException(
                errorCode = ErrorCode.INVALID_END_TIME,
                detail = mapOf("endAt" to request.endAt.toString())
            )
        }

        // Meeting + MeetingAttendee 저장을 하나의 트랜잭션으로 처리
        val meetingId = transactionTemplate.execute {
            val meeting = Meeting(
                id = null,
                name = request.name,
                hostUserId = userId,
                attendeeCount = request.attendeeCount,
                isClosed = false,
                stationId = request.stationId,
                endAt = request.endAt,
                createdAt = null,
                updatedAt = null
            )

            val savedMeeting = runBlocking { meetingRepository.save(meeting) }
            val meetingId = savedMeeting.id ?: throw IllegalStateException("Meeting ID is null")

            // 사용자 정보 조회
            val user = userRepository.findById(userId)
                .orElseThrow {
                    MeetingException(
                        errorCode = ErrorCode.USER_NOT_FOUND,
                        detail = mapOf("userId" to userId)
                    )
                }

            val meetingAttendee = MeetingAttendee(
                id = null,
                meetingId = meetingId,
                userId = userId,
                attendeeNickname = user.nickname ?: "Guest",
                muzziColor = MuzziColor.DEFAULT,
                createdAt = null,
                updatedAt = null
            )
            runBlocking { meetingAttendeeRepository.save(meetingAttendee) }

            meetingId
        } ?: throw IllegalStateException("Transaction result is null")
        val meetingAttendee = MeetingAttendee(
            id = null,
            meetingId = meetingId,
            userId = userId,
            attendeeNickname = user.nickname,
            muzziColor = MuzziColor.DEFAULT,
            createdAt = null,
            updatedAt = null
        )
        meetingAttendeeRepository.save(meetingAttendee)

        // suspend 함수이므로 트랜잭션 블록 바깥에서 호출
        val inviteToken = inviteTokenService.generateInviteToken(meetingId)
        CreateMeetingResponse(meetingId, inviteToken)
    }
}