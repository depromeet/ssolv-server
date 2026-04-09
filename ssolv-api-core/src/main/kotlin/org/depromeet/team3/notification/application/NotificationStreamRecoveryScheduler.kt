package org.depromeet.team3.notification.application

import io.sentry.Sentry
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.depromeet.team3.common.filter.MdcLoggingFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * meeting_notification_stream 의 idle 메시지를 XCLAIM 으로 소유권 이전 후 직접 재처리하는 복구 스케줄러.
 *
 * 흐름:
 * 1. XPENDING 으로 그룹 전체의 미처리 메시지 조회
 * 2. RECLAIM_THRESHOLD 이상 idle 한 메시지를 XCLAIM (소유권 이전 + deliveryCount 증가)
 * 3. 직접 서비스 호출 후 성공 시 XACK, 실패 시 PEL 에 남겨 다음 사이클에 재시도
 * 4. deliveryCount 가 MAX_DELIVERY_COUNT 를 초과하면 PendingMessageScheduler(batch) 가 데드레터 처리
 */
@Component
@Profile("!test")
class NotificationStreamRecoveryScheduler(
    private val stringRedisTemplate: StringRedisTemplate,
    private val sendMeetingResultNotificationService: SendMeetingResultNotificationService,
) {
    private val logger = LoggerFactory.getLogger(NotificationStreamRecoveryScheduler::class.java)

    // 인스턴스별 고정 consumer 이름 — 앱이 재시작해도 이전 PEL 항목을 이어받을 수 있도록 UUID 사용
    private val recoveryConsumerName = "recovery_${UUID.randomUUID()}"

    companion object {
        private val RECLAIM_THRESHOLD = Duration.ofMinutes(1)
        private const val BATCH_SIZE = 100L
    }

    @Scheduled(fixedDelay = 60_000)
    fun recoverPendingMessages() {
        val requestId = "notification-recovery-" + UUID.randomUUID().toString().substring(0, 8)
        MDC.put(MdcLoggingFilter.REQUEST_ID, requestId)
        try {
            val streamKey = RedisStreamConstants.MEETING_NOTIFICATION_STREAM
            val groupName = RedisStreamConstants.MEETING_NOTIFICATION_GROUP

            val pendingMessages = stringRedisTemplate.opsForStream<String, String>()
                .pending(streamKey, groupName, Range.unbounded<String>(), BATCH_SIZE)
            if (pendingMessages.isEmpty()) return

            val idleMessages = pendingMessages.filter {
                it.elapsedTimeSinceLastDelivery >= RECLAIM_THRESHOLD
            }.toList()
            if (idleMessages.isEmpty()) return

            logger.debug("[notification-recovery] idle 메시지 {} 건 발견 → XCLAIM 시작", idleMessages.size)

            val recordIds = idleMessages.map { RecordId.of(it.idAsString) }.toTypedArray()
            val claimOptions = XClaimOptions.minIdle(RECLAIM_THRESHOLD).ids(*recordIds)
            val claimed = stringRedisTemplate.opsForStream<String, String>()
                .claim(streamKey, groupName, recoveryConsumerName, claimOptions)
                ?: return

            claimed.forEach { message ->
                val meetingId = message.value["meetingId"]?.toLongOrNull()
                val userId = message.value["userId"]?.toLongOrNull()

                if (meetingId == null || userId == null) {
                    // 파싱 불가 메시지는 즉시 폐기
                    stringRedisTemplate.opsForStream<String, String>()
                        .acknowledge(streamKey, groupName, message.id)
                    logger.warn("[notification-recovery] 파싱 불가 메시지 폐기 (id: {})", message.id)
                    return@forEach
                }

                runBlocking {
                    try {
                        sendMeetingResultNotificationService.send(meetingId, userId)
                        stringRedisTemplate.opsForStream<String, String>()
                            .acknowledge(streamKey, groupName, message.id)
                        logger.debug("[notification-recovery] 재처리 성공 (meetingId: {}, userId: {})", meetingId, userId)
                    } catch (e: Exception) {
                        logger.error("[notification-recovery] 재처리 실패 (meetingId: $meetingId, userId: $userId)", e)
                        Sentry.captureException(e)
                        // ACK 없이 PEL 에 잔류 → 다음 사이클 재시도, batch 가 데드레터 판정
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("[notification-recovery] 스케줄러 실행 중 예외 발생", e)
            Sentry.captureException(e)
        } finally {
            MDC.clear()
        }
    }
}
