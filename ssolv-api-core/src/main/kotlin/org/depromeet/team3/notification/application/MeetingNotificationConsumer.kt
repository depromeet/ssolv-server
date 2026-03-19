package org.depromeet.team3.notification.application
 
import org.depromeet.team3.common.constants.RedisStreamConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component

@Component
class MeetingNotificationConsumer(
    private val sendMeetingResultNotificationService: SendMeetingResultNotificationService,
    private val stringRedisTemplate: StringRedisTemplate,
) : StreamListener<String, MapRecord<String, String, String>> {
    
    private val logger = LoggerFactory.getLogger(MeetingNotificationConsumer::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onMessage(message: MapRecord<String, String, String>) {
        val meetingId = message.value["meetingId"]?.toLongOrNull()
        val userId = message.value["userId"]?.toLongOrNull()

        if (meetingId != null && userId != null) {
            logger.debug("식당 확정 알림 요청 수신 (meetingId: {}, userId: {}, messageId: {})", meetingId, userId, message.id)
            scope.launch(Dispatchers.IO) {
                try {
                    sendMeetingResultNotificationService.send(meetingId, userId)
                    // 처리 성공 시 XACK (Pending 해제)
                    val ackCount = stringRedisTemplate.opsForStream<String, String>()
                        .acknowledge(RedisStreamConstants.MEETING_NOTIFICATION_GROUP, message)
                    
                    if (ackCount != null && ackCount > 0) {
                        logger.debug("식당 확정 알림 처리 성공 및 ACK 완료 (meetingId: {}, userId: {})", meetingId, userId)
                    }
                } catch (e: Exception) {
                    logger.error("식당 확정 푸시 알림 전송 로직 실패 (meetingId: $meetingId, userId: $userId): ", e)
                    io.sentry.Sentry.captureException(e)
                    // 실패 시 ACK하지 않아 PEL에 머무르게 됨. 이후 WatchDog(Scheduler)가 재처리
                }
            }
        } else {
            logger.warn("Stream 메시지 파싱 실패: meetingId 또는 userId가 존재하지 않음 (message: {})", message)
            // 잘못된 형식의 메시지는 무시하고 삭제 처리(ACK)
            stringRedisTemplate.opsForStream<String, String>().acknowledge(RedisStreamConstants.MEETING_NOTIFICATION_GROUP, message)
        }
    }
}