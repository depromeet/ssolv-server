package org.depromeet.team3.common.monitoring

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Redis 비즈니스 지표 수집기 — Prometheus/Grafana 연동
 *
 * 수집 주기: 30초
 *
 * 노출 메트릭:
 * - redis.business.active_meetings     : 현재 Redis에 장소 데이터가 있는 활성 모임 수
 * - redis.business.cached_places       : 캐시된 장소 상세정보 수 (place:details:*)
 * - redis.business.active_like_keys    : 현재 좋아요 집합 Key 수 (좋아요 발생한 모임×장소 쌍)
 * - redis.stream.calculation.length    : 장소검색 Stream 총 메시지 수
 * - redis.stream.notification.length   : 알림 Stream 총 메시지 수
 * - redis.stream.calculation.pel       : 장소검색 Stream PEL (처리 중 / 미완료 메시지)
 * - redis.stream.notification.pel      : 알림 Stream PEL
 * - redis.stream.calculation.consumers : 장소검색 Stream 활성 Consumer 수
 * - redis.stream.notification.consumers: 알림 Stream 활성 Consumer 수
 */
@Component
@Profile("!test")
class RedisBusinessMetricsCollector(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(RedisBusinessMetricsCollector::class.java)

    private val activeMeetings = AtomicLong(0)
    private val cachedPlaces = AtomicLong(0)
    private val activeLikeKeys = AtomicLong(0)

    private val calcStreamLength = AtomicLong(0)
    private val notifStreamLength = AtomicLong(0)
    private val calcStreamPel = AtomicLong(0)
    private val notifStreamPel = AtomicLong(0)
    private val calcStreamConsumers = AtomicLong(0)
    private val notifStreamConsumers = AtomicLong(0)

    @PostConstruct
    fun registerGauges() {
        meterRegistry.gauge("redis.business.active_meetings", activeMeetings) { it.toDouble() }
        meterRegistry.gauge("redis.business.cached_places", cachedPlaces) { it.toDouble() }
        meterRegistry.gauge("redis.business.active_like_keys", activeLikeKeys) { it.toDouble() }

        meterRegistry.gauge("redis.stream.calculation.length", calcStreamLength) { it.toDouble() }
        meterRegistry.gauge("redis.stream.notification.length", notifStreamLength) { it.toDouble() }
        meterRegistry.gauge("redis.stream.calculation.pel", calcStreamPel) { it.toDouble() }
        meterRegistry.gauge("redis.stream.notification.pel", notifStreamPel) { it.toDouble() }
        meterRegistry.gauge("redis.stream.calculation.consumers", calcStreamConsumers) { it.toDouble() }
        meterRegistry.gauge("redis.stream.notification.consumers", notifStreamConsumers) { it.toDouble() }
    }

    @Scheduled(fixedDelay = 30_000)
    fun collect() {
        runCatching { doCollect() }
            .onFailure { logger.warn("[RedisMetrics] 수집 실패: ${it.message}") }
    }

    private fun doCollect() {
        activeMeetings.set(scanKeyCount("meeting:places:*"))
        cachedPlaces.set(scanKeyCount("place:details:*"))
        activeLikeKeys.set(scanKeyCount("meeting:*:place:*:likes"))

        collectStreamMetrics(
            stream = RedisStreamConstants.MEETING_CALCULATION_STREAM,
            group = RedisStreamConstants.MEETING_CALCULATION_GROUP,
            pelGauge = calcStreamPel,
            lengthGauge = calcStreamLength,
            consumersGauge = calcStreamConsumers
        )
        collectStreamMetrics(
            stream = RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
            group = RedisStreamConstants.MEETING_NOTIFICATION_GROUP,
            pelGauge = notifStreamPel,
            lengthGauge = notifStreamLength,
            consumersGauge = notifStreamConsumers
        )
    }

    /**
     * SCAN으로 키 수 집계 — KEYS 대신 SCAN 사용해 Redis 블로킹 방지
     */
    private fun scanKeyCount(pattern: String): Long {
        var count = 0L
        runCatching {
            redisTemplate.execute { connection ->
                val options = ScanOptions.scanOptions().match(pattern).count(200).build()
                connection.scan(options).use { cursor ->
                    cursor.forEachRemaining { count++ }
                }
                null
            }
        }.onFailure { logger.warn("[RedisMetrics] SCAN 실패 ($pattern): ${it.message}") }
        return count
    }

    /**
     * Stream 메트릭 수집:
     * - XLEN → stream 총 길이
     * - XPENDING summary → PEL 크기 + Consumer 수
     */
    private fun collectStreamMetrics(
        stream: String,
        group: String,
        pelGauge: AtomicLong,
        lengthGauge: AtomicLong,
        consumersGauge: AtomicLong
    ) {
        runCatching {
            val ops = redisTemplate.opsForStream<String, String>()

            lengthGauge.set(ops.size(stream) ?: 0L)

            val summary = ops.pending(stream, group)
            pelGauge.set(summary?.totalPendingMessages ?: 0L)
            consumersGauge.set(summary?.pendingMessagesPerConsumer?.size?.toLong() ?: 0L)
        }.onFailure {
            logger.warn("[RedisMetrics] Stream 메트릭 수집 실패 ($stream): ${it.message}")
        }
    }
}
