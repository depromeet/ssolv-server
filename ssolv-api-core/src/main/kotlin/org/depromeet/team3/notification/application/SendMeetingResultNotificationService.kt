package org.depromeet.team3.notification.application
import kotlinx.coroutines.Dispatchers

import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.application.InviteTokenService
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.notification.domain.DeviceTokenQueryRepository
import org.depromeet.team3.notification.domain.FcmClient
import org.depromeet.team3.station.StationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 모임 결과(확정된 식당) 또는 설문 완료 정보를 기반으로 FCM 푸시 알림을 발송하는 서비스
 * 
 * 주요 기능:
 * 1. 모임 참여자 전원의 디바이스 토큰 및 알림 설정 확인
 * 2. 알림 제목, 내용 생성 및 랜딩 페이지 데이터 구성
 * 3. FCM 서버를 통한 멀티캐스트 알림 발송 최적화
 */
@Service
class SendMeetingResultNotificationService(
    private val meetingRepository: MeetingRepository,
    private val deviceTokenQueryRepository: DeviceTokenQueryRepository,
    private val fcmClient: FcmClient,
    private val stationRepository: StationRepository,
    private val inviteTokenService: InviteTokenService,
    private val transactionTemplate: TransactionTemplate,
    private val stringRedisTemplate: StringRedisTemplate
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(SendMeetingResultNotificationService::class.java)

    companion object {
        private const val IDEMPOTENCY_KEY_PREFIX = "sent:notification:MEETING_RESULT"
        private val IDEMPOTENCY_TTL = java.time.Duration.ofHours(1)
    }

    suspend fun send(meetingId: Long, userId: Long) = withContext(Dispatchers.IO) {
        // 0. 멱등성 체크 (중복 발송 방지)
        val idempotencyKey = "$IDEMPOTENCY_KEY_PREFIX:$meetingId:$userId"
        val isNew = stringRedisTemplate.opsForValue()
            .setIfAbsent(idempotencyKey, "true", IDEMPOTENCY_TTL) ?: false

        if (!isNew) {
            logger.debug("중복 처리 방지: 이미 발송된 알림입니다. (meetingId: $meetingId, userId: $userId)")
            return@withContext
        }

        val meeting = transactionTemplate.execute {
            runBlocking { meetingRepository.findById(meetingId) }
        } ?: throw MeetingException(org.depromeet.team3.common.exception.ErrorCode.MEETING_NOT_FOUND)

        // 1. 발송 대상의 유효한 토큰 조회 (한 명의 사용자 대상)
        val validTokens = transactionTemplate.execute {
            runBlocking {
                deviceTokenQueryRepository.findValidTokensByUserIds(
                    userIds = listOf(userId),
                    isNotificationEnabled = true
                )
            }
        } ?: emptyList()

        if (validTokens.isEmpty()) {
            logger.debug("모임 결과 알림을 보낼 유효한 FCM 토큰이 없습니다. (meetingId: $meetingId, userId: $userId)")
            return@withContext
        }

        // 2. 알림 내용 생성 ({모임이름}, {장소})
        val station = transactionTemplate.execute {
            runBlocking { stationRepository.findById(meeting.stationId) }
        }
        val stationName = station?.name ?: "약속 장소"
        
        val title = "${meeting.name}의 식당이 정해졌어요!"
        val body = "$stationName 근처 딱 맞는 식당을 골랐어요."
        
        // 3. 앱 이동 데이터
        val meetingToken = inviteTokenService.generateToken(meeting) ?: meetingId.toString()

        val data = mapOf(
            "type" to "MEETING_RESULT_READY",
            "meetingToken" to meetingToken,
            "path" to "/meetings/$meetingToken/result/overview"
        )

        // 4. 발송
        fcmClient.sendMulticast(
            tokens = validTokens,
            title = title,
            body = body,
            data = data
        )
    }
}