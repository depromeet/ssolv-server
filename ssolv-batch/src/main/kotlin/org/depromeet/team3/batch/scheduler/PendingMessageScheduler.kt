package org.depromeet.team3.batch.scheduler

import io.sentry.Sentry
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.depromeet.team3.common.filter.MdcLoggingFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Redis Streams 의 데드레터(dead-letter) 처리 스케줄러.
 *
 * 메시지 재처리는 각 API 모듈의 RecoveryScheduler (XCLAIM 기반) 가 담당한다.
 * 이 스케줄러는 deliveryCount 가 MAX_DELIVERY_COUNT 를 초과한 메시지만 XACK 로 폐기한다.
 *
 * deliveryCount 증가 시점:
 *   - 최초 XREADGROUP 배달: +1
 *   - RecoveryScheduler XCLAIM 마다: +1
 *
 * 기본값 MAX_DELIVERY_COUNT = 4 → 최초 1회 + 복구 3회 시도 후 폐기
 */
@Component
@Profile("!test")
class PendingMessageScheduler(
    private val stringRedisTemplate: StringRedisTemplate,
    private val watchdogManager: CoroutineWatchdogManager,
) {
    private val logger = LoggerFactory.getLogger(PendingMessageScheduler::class.java)

    companion object {
        private const val MAX_DELIVERY_COUNT = 4L
        private const val LOCK_KEY = "lock:pending:scheduler"
        private const val BATCH_SIZE = 100L
    }

    private val streams = listOf(
        RedisStreamConstants.MEETING_NOTIFICATION_STREAM to RedisStreamConstants.MEETING_NOTIFICATION_GROUP,
        RedisStreamConstants.MEETING_CALCULATION_STREAM to RedisStreamConstants.MEETING_CALCULATION_GROUP,
    )

    @Scheduled(fixedDelay = 60_000)
    fun processPendingMessages() {
        val requestId = "watchdog-" + UUID.randomUUID().toString().substring(0, 8)
        MDC.put(MdcLoggingFilter.REQUEST_ID, requestId)
        try {
            runBlocking {
                watchdogManager.executeWithLock(LOCK_KEY, 10_000, 10_000) {
                    for ((streamKey, groupName) in streams) {
                        deadLetterExceededMessages(streamKey, groupName)
                    }
                }
            }
        } finally {
            MDC.clear()
        }
    }

    private fun deadLetterExceededMessages(streamKey: String, groupName: String) {
        try {
            val summary = stringRedisTemplate.opsForStream<String, String>()
                .pending(streamKey, groupName)

            if (summary == null || summary.totalPendingMessages == 0L) return

            val pendingMessages = stringRedisTemplate.opsForStream<String, String>()
                .pending(streamKey, groupName, Range.unbounded<String>(), BATCH_SIZE)
            if (pendingMessages.isEmpty()) return

            pendingMessages
                .filter { it.totalDeliveryCount >= MAX_DELIVERY_COUNT }
                .forEach { pending ->
                    logger.error(
                        "[watchdog] 최대 재시도 초과 메시지 폐기 (stream: {}, id: {}, deliveryCount: {})",
                        streamKey, pending.id, pending.totalDeliveryCount,
                    )
                    stringRedisTemplate.opsForStream<String, String>()
                        .acknowledge(streamKey, groupName, pending.id)
                    Sentry.captureMessage(
                        "Dead letter: stream=$streamKey, id=${pending.id}, deliveryCount=${pending.totalDeliveryCount}"
                    )
                }
        } catch (e: Exception) {
            logger.error("[watchdog] 스트림 {} 데드레터 처리 중 오류", streamKey, e)
            Sentry.captureException(e)
        }
    }
}
