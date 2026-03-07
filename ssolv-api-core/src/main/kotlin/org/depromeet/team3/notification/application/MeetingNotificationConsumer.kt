package org.depromeet.team3.notification.application
 
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component

@Component
class MeetingNotificationConsumer(
    private val sendMeetingResultNotificationService: SendMeetingResultNotificationService,
    private val stringRedisTemplate: StringRedisTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) : StreamListener<String, MapRecord<String, String, String>> {
    
    private val logger = LoggerFactory.getLogger(MeetingNotificationConsumer::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onMessage(message: MapRecord<String, String, String>) {
        val meetingIdStr = message.value["meetingId"]
        val meetingId = meetingIdStr?.toLongOrNull()

        if (meetingId != null) {
            logger.info("식당 확정 알림 요청 수신 (meetingId: {}, messageId: {})", meetingId, message.id)
            scope.launch(coroutineDispatchers.VT) {
                try {
                    sendMeetingResultNotificationService.send(meetingId)
                    // 처리 성공 시 XACK (Pending 해제)
                    val ackCount = stringRedisTemplate.opsForStream<String, String>()
                        .acknowledge("meeting_notification_group", message)
                    
                    if (ackCount != null && ackCount > 0) {
                        logger.info("식당 확정 알림 처리 성공 및 ACK 완료 (meetingId: {})", meetingId)
                    }
                } catch (e: Exception) {
                    logger.error("식당 확정 푸시 알림 전송 로직 실패 (meetingId: $meetingId): ", e)
                    // 실패 시 ACK하지 않아 PEL에 머무르게 됨. 이후 WatchDog(Scheduler)가 재처리
                }
            }
        } else {
            logger.warn("Stream 메시지 파싱 실패: meetingId가 존재하지 않거나 유효하지 않음 (message: {})", message)
            // 잘못된 형식의 메시지는 무시하고 삭제 처리(ACK)
            stringRedisTemplate.opsForStream<String, String>().acknowledge("meeting_notification_group", message)
        }
    }
}