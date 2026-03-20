package org.depromeet.team3.meeting.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meeting.dto.request.CreateMeetingRequest
import org.depromeet.team3.meeting.dto.response.CreateMeetingResponse
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.meetingattendee.MuzziColor
import org.depromeet.team3.station.StationJpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * 새로운 모임을 생성하고 초기 참여자(호스트)를 등록하는 서비스
 */
@Service
class CreateMeetingService(
    private val meetingJpaRepository: MeetingJpaRepository,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val stationJpaRepository: StationJpaRepository,
    private val userJpaRepository: UserRepository,
    private val inviteTokenService: InviteTokenService,
    private val meetingExpirationSchedulerService: MeetingExpirationSchedulerService,
    private val transactionTemplate: TransactionTemplate
) {

    suspend operator fun invoke(request: CreateMeetingRequest, userId: Long): CreateMeetingResponse =
        withContext(Dispatchers.IO) {
            val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
            if (request.endAt != null && request.endAt.isBefore(now)) {
                throw MeetingException(
                    errorCode = ErrorCode.INVALID_END_TIME,
                    detail = mapOf("endAt" to request.endAt.toString())
                )
            }

            // Meeting + MeetingAttendee 저장을 하나의 트랜잭션으로 처리 (runBlocking 불필요)
            val meetingId = transactionTemplate.execute {
                val userEntity = userJpaRepository.findByIdOrNull(userId)
                    ?: throw MeetingException(
                        errorCode = ErrorCode.USER_NOT_FOUND,
                        detail = mapOf("userId" to userId)
                    )

                val stationEntity = stationJpaRepository.findByIdOrNull(request.stationId)
                    ?: throw MeetingException(
                        errorCode = ErrorCode.STATION_NOT_FOUND,
                        detail = mapOf("stationId" to request.stationId)
                    )

                val meetingEntity = meetingJpaRepository.save(
                    MeetingEntity(
                        name = request.name,
                        hostUser = userEntity,
                        attendeeCount = request.attendeeCount,
                        isClosed = false,
                        station = stationEntity,
                        endAt = request.endAt
                    )
                )

                meetingAttendeeJpaRepository.save(
                    MeetingAttendeeEntity(
                        meeting = meetingEntity,
                        user = userEntity,
                        attendeeNickname = userEntity.nickname,
                        muzziColor = MuzziColor.DEFAULT
                    )
                )

                meetingEntity.id!!
            }!!

            // suspend 함수이므로 트랜잭션 블록 바깥에서 호출
            val inviteToken = inviteTokenService.generateInviteToken(meetingId)
            
            if (request.endAt != null) {
                meetingExpirationSchedulerService.scheduleExpiration(meetingId, request.endAt)
            }
            
            CreateMeetingResponse(meetingId, inviteToken)
        }
}