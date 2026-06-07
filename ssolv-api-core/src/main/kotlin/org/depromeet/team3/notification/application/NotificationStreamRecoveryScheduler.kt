package org.depromeet.team3.notification.application

import io.sentry.Sentry
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.depromeet.team3.common.filter.MdcLoggingFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * meeting_notification_stream 의 idle 메시지를 XAUTOCLAIM 으로 소유권 이전 후 직접 재처리하는 복구 스케줄러.
 *
 * 흐름:
 * 1. XAUTOCLAIM 으로 RECLAIM_THRESHOLD 이상 idle 한 메시지를 회수
 * 2. 직접 서비스 호출 후 성공 시 XACK, 실패 시 PEL 에 남겨 다음 사이클에 재시도
 * 3. deliveryCount 가 MAX_DELIVERY_COUNT 를 초과하면 PendingMessageScheduler(batch) 가 데드레터 처리
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

            val claimed = autoClaimPendingMessages(streamKey, groupName)
            if (claimed.isEmpty()) return

            logger.debug("[notification-recovery] idle 메시지 {} 건 XAUTOCLAIM 회수", claimed.size)

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

    private fun autoClaimPendingMessages(
        streamKey: String,
        groupName: String,
    ): List<MapRecord<String, String, String>> {
        val response = stringRedisTemplate.execute(
            RedisCallback<Any> { connection: RedisConnection ->
                connection.execute(
                    "XAUTOCLAIM",
                    streamKey.bytes(),
                    groupName.bytes(),
                    recoveryConsumerName.bytes(),
                    RECLAIM_THRESHOLD.toMillis().toString().bytes(),
                    "0-0".bytes(),
                    "COUNT".bytes(),
                    BATCH_SIZE.toString().bytes(),
                )
            },
        ) ?: return emptyList()

        return parseAutoClaimResponse(streamKey, response)
    }

    private fun parseAutoClaimResponse(
        streamKey: String,
        response: Any,
    ): List<MapRecord<String, String, String>> {
        val parts = response as? List<*> ?: return emptyList()
        val records = parts.getOrNull(1) as? List<*> ?: return emptyList()

        return records.mapNotNull { rawRecord ->
            val recordParts = rawRecord as? List<*> ?: return@mapNotNull null
            val id = recordParts.getOrNull(0).asRedisString() ?: return@mapNotNull null
            val fieldValues = recordParts.getOrNull(1) as? List<*> ?: return@mapNotNull null
            val fields = fieldValues.chunked(2).mapNotNull { pair ->
                val key = pair.getOrNull(0).asRedisString()
                val value = pair.getOrNull(1).asRedisString()
                if (key == null || value == null) null else key to value
            }.toMap()

            MapRecord
                .create(streamKey, fields)
                .withId(RecordId.of(id))
        }
    }

    private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)

    private fun Any?.asRedisString(): String? = when (this) {
        is ByteArray -> String(this, Charsets.UTF_8)
        null -> null
        else -> toString()
    }
}
