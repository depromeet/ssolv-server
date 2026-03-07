package org.depromeet.team3.notification.application


import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.application.InviteTokenService
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.notification.domain.DeviceTokenQueryRepository
import org.depromeet.team3.notification.domain.FcmClient
import org.depromeet.team3.station.StationRepository
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

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
    private val meetingAttendeeRepository: MeetingAttendeeRepository,
    private val deviceTokenQueryRepository: DeviceTokenQueryRepository,
    private val fcmClient: FcmClient,
    private val stationRepository: StationRepository,
    private val inviteTokenService: InviteTokenService,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(SendMeetingResultNotificationService::class.java)

    suspend fun send(meetingId: Long) = withContext(coroutineDispatchers.VT) {
        val meeting = transactionTemplate.execute {
            runBlocking { meetingRepository.findById(meetingId) }
        } ?: throw MeetingException(org.depromeet.team3.common.exception.ErrorCode.MEETING_NOT_FOUND)

        // 1. 발송 대상 수집: 모임에 참여한 모든 인원
        val targetUserIds = transactionTemplate.execute {
            runBlocking {
                meetingAttendeeRepository.findByMeetingId(meetingId)
                    .map { it.userId }
                    .toSet()
            }
        } ?: emptySet()

        if (targetUserIds.isEmpty()) return@withContext

        // 2. 발송 조건(토큰 존재 & notificationEnabled = true) 만족하는 토큰 조회
        val validTokens = transactionTemplate.execute {
            runBlocking {
                deviceTokenQueryRepository.findValidTokensByUserIds(
                    userIds = targetUserIds.toList(),
                    isNotificationEnabled = true
                )
            }
        } ?: emptyList()

        if (validTokens.isEmpty()) {
            logger.info("모임 결과 알림을 보낼 유효한 FCM 토큰이 없습니다. (meetingId: $meetingId)")
            return@withContext
        }

        // 3. 알림 내용 생성 ({모임이름}, {장소})
        val station = transactionTemplate.execute {
            runBlocking { stationRepository.findById(meeting.stationId) }
        }
        val stationName = station?.name ?: "약속 장소"
        
        val title = "${meeting.name}의 식당이 정해졌어요!"
        val body = "$stationName 근처 딱 맞는 식당을 골랐어요."
        
        // 4. 앱 이동 데이터
        val meetingToken = inviteTokenService.generateToken(meeting) ?: meetingId.toString()

        val data = mapOf(
            "type" to "MEETING_RESULT_READY",
            "meetingToken" to meetingToken,
            "path" to "/meetings/$meetingToken/result/overview"
        )

        // 5. 발송
        fcmClient.sendMulticast(
            tokens = validTokens,
            title = title,
            body = body,
            data = data
        )
    }
}