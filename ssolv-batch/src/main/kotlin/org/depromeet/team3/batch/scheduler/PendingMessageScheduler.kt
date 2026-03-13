package org.depromeet.team3.batch.scheduler

import org.depromeet.team3.common.constants.RedisStreamConstants
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import kotlinx.coroutines.runBlocking

/**
 * Redis Streams의 미처리 메시지를 감시하고 재시도하는 스케줄러 (WatchDog)
 */
@Component
@org.springframework.context.annotation.Profile("!test")
class PendingMessageScheduler(
    private val stringRedisTemplate: StringRedisTemplate,
    private val watchdogManager: CoroutineWatchdogManager
) {
    private val logger = LoggerFactory.getLogger(PendingMessageScheduler::class.java)

    companion object {
        private val PENDING_THRESHOLD = Duration.ofMinutes(1)
        private const val MAX_RETRY_COUNT = 3L
        private const val LOCK_KEY = "lock:pending:scheduler"
    }

    private val consumers = listOf(
        RedisStreamConstants.MEETING_NOTIFICATION_STREAM to RedisStreamConstants.MEETING_NOTIFICATION_GROUP,
        RedisStreamConstants.MEETING_CALCULATION_STREAM to RedisStreamConstants.MEETING_CALCULATION_GROUP
    )

    @Scheduled(fixedDelay = 60000) // 1분마다 주기적으로 체크
    fun processPendingMessages() {
        runBlocking {
            watchdogManager.executeWithLock(LOCK_KEY, 10000, 10000) {
                for ((streamKey, groupName) in consumers) {
                    try {
                        val pendingSummary = stringRedisTemplate.opsForStream<String, String>()
                            .pending(streamKey, groupName)

                        if (pendingSummary == null || pendingSummary.totalPendingMessages == 0L) {
                            continue
                        }

                        logger.debug("스트림 {} 의 미처리 메시지 {} 건 발견", streamKey, pendingSummary.totalPendingMessages)

                        val pendingMessages = stringRedisTemplate.opsForStream<String, String>()
                            .pending(
                                streamKey, 
                                groupName, 
                                Range.unbounded<String>(), 
                                100L
                            )

                        if (pendingMessages.isEmpty()) {
                            continue
                        }

                        for (pending in pendingMessages) {
                            if (pending.elapsedTimeSinceLastDelivery.toMillis() > PENDING_THRESHOLD.toMillis()) {
                                val messageRecordList = stringRedisTemplate.opsForStream<String, String>()
                                    .range(streamKey, Range.just(pending.idAsString))
                                    
                                if (messageRecordList.isNullOrEmpty()) {
                                    stringRedisTemplate.opsForStream<String, String>().acknowledge(streamKey, groupName, pending.id)
                                    continue
                                }

                                val messageRecord = messageRecordList[0]
                                val currentRetryCount = messageRecord.value["retryCount"]?.toLongOrNull() ?: 0L

                                // 1. 최대 재시도 횟수 초과 여부 확인 (Poison Message 방지)
                                if (currentRetryCount >= MAX_RETRY_COUNT) {
                                    logger.error("WatchDog: {} 에서 메시지 ({}) 가 {}회 이상 실패하여 폐기합니다. (retryCount: {})", 
                                        streamKey, pending.id, MAX_RETRY_COUNT, currentRetryCount)
                                    stringRedisTemplate.opsForStream<String, String>().acknowledge(streamKey, groupName, pending.id)
                                    continue
                                }

                                logger.warn("WatchDog: {} 에서 {} 이상 정체된 메시지 ({}) 발견 (retryCount: {}). 재발행 수행", 
                                    streamKey, PENDING_THRESHOLD, pending.id, currentRetryCount)

                                try {
                                    val updatedValue = messageRecord.value.toMutableMap()
                                    updatedValue["retryCount"] = (currentRetryCount + 1).toString()
                                    
                                    val reRecord = MapRecord.create(streamKey, updatedValue)
                                    stringRedisTemplate.opsForStream<String, String>().add(reRecord)
                                    stringRedisTemplate.opsForStream<String, String>().acknowledge(streamKey, groupName, pending.id)
                                    logger.debug("WatchDog: 메시지 ({}) 재발행 및 기존 메시지 ACK 완료 (new retryCount: {})", 
                                        pending.id, updatedValue["retryCount"])
                                } catch (e: Exception) {
                                    logger.error("WatchDog: 메시지 ({}) 재발행 도중 오류 발생", pending.id, e)
                                    io.sentry.Sentry.captureException(e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("WatchDog: 스트림 {} 에 대한 Pending 확인 오류", streamKey, e)
                        io.sentry.Sentry.captureException(e)
                    }
                }
            }
        }
    }
}