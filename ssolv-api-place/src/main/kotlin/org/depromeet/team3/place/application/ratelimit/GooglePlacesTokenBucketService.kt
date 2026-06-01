package org.depromeet.team3.place.application.ratelimit

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Service
class GooglePlacesTokenBucketService(
    private val redisTemplate: StringRedisTemplate,
    private val properties: GooglePlacesApiProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(GooglePlacesTokenBucketService::class.java)
    private val rateLimit get() = properties.rateLimit
    private val adaptive get() = rateLimit.adaptive
    private val currentRefillRate = AtomicInteger(rateLimit.refillRatePerSecond)
    private val lastAvailableTokens = AtomicLong(rateLimit.bucketCapacity.toLong())
    private val successCount = AtomicLong(0)
    private val rateLimitedCount = AtomicLong(0)
    private val timeoutCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    private val consumeScript = DefaultRedisScript(
        """
        local bucket = KEYS[1]
        local now = tonumber(ARGV[1])
        local capacity = tonumber(ARGV[2])
        local refillRate = tonumber(ARGV[3])
        local requested = tonumber(ARGV[4])
        local ttlSeconds = tonumber(ARGV[5])

        local tokens = tonumber(redis.call('HGET', bucket, 'tokens'))
        local updatedAt = tonumber(redis.call('HGET', bucket, 'updated_at_ms'))

        if tokens == nil then
            tokens = capacity
        end
        if updatedAt == nil then
            updatedAt = now
        end

        local elapsedMs = math.max(0, now - updatedAt)
        local refill = elapsedMs * refillRate / 1000
        tokens = math.min(capacity, tokens + refill)

        local allowed = 0
        local retryAfterMs = 0
        if tokens >= requested then
            tokens = tokens - requested
            allowed = 1
        else
            retryAfterMs = math.ceil((requested - tokens) * 1000 / refillRate)
        end

        redis.call('HSET', bucket, 'tokens', tokens, 'updated_at_ms', now, 'refill_rate', refillRate, 'capacity', capacity)
        redis.call('EXPIRE', bucket, ttlSeconds)

        return { allowed, math.floor(tokens), retryAfterMs, refillRate }
        """.trimIndent(),
        List::class.java,
    )

    init {
        meterRegistry.gauge("ssolv.place.google.token_bucket.refill_rate", currentRefillRate) { it.get().toDouble() }
        meterRegistry.gauge("ssolv.place.google.token_bucket.available_tokens", lastAvailableTokens) { it.get().toDouble() }
    }

    suspend fun acquire(cost: Int = 1, priority: String): Boolean {
        if (!rateLimit.enabled) return true

        val startedAt = System.nanoTime()
        val deadline = System.currentTimeMillis() + rateLimit.tokenAcquireTimeoutMs

        while (true) {
            val result = consume(cost)
            if (result.allowed) {
                meterRegistry.counter(
                    "ssolv.place.google.token_bucket.acquire.count",
                    "result",
                    "success",
                    "priority",
                    priority,
                ).increment()
                meterRegistry.timer("ssolv.place.google.token_bucket.acquire.wait", "result", "success", "priority", priority)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
                return true
            }

            if (System.currentTimeMillis() >= deadline) {
                meterRegistry.counter(
                    "ssolv.place.google.token_bucket.acquire.count",
                    "result",
                    "timeout",
                    "priority",
                    priority,
                ).increment()
                meterRegistry.timer("ssolv.place.google.token_bucket.acquire.wait", "result", "timeout", "priority", priority)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
                return false
            }

            delay(min(max(result.retryAfterMs, rateLimit.retryDelayMs), rateLimit.tokenAcquireTimeoutMs))
        }
    }

    fun recordApiResult(result: GooglePlacesApiResult) {
        when (result) {
            GooglePlacesApiResult.SUCCESS -> successCount.incrementAndGet()
            GooglePlacesApiResult.RATE_LIMITED -> rateLimitedCount.incrementAndGet()
            GooglePlacesApiResult.TIMEOUT -> timeoutCount.incrementAndGet()
            GooglePlacesApiResult.ERROR -> errorCount.incrementAndGet()
        }
        meterRegistry.counter("ssolv.place.google.api.result.count", "result", result.metricTag).increment()
    }

    @Scheduled(fixedDelayString = "\${api.google.places.rate-limit.adaptive.evaluation-interval-ms:30000}")
    fun adjustRefillRate() {
        if (!rateLimit.enabled || !adaptive.enabled) return

        val success = successCount.getAndSet(0)
        val rateLimited = rateLimitedCount.getAndSet(0)
        val timeout = timeoutCount.getAndSet(0)
        val error = errorCount.getAndSet(0)
        val total = success + rateLimited + timeout + error

        if (total == 0L) return

        val current = currentRefillRate.get()
        val next = when {
            rateLimited > 0 -> ceil(current * adaptive.decreaseOn429Ratio).toInt()
            timeout.toDouble() / total >= adaptive.timeoutRatioThreshold -> ceil(current * adaptive.decreaseOnTimeoutRatio).toInt()
            error == 0L -> current + adaptive.increaseStep
            else -> current
        }.coerceIn(adaptive.minRatePerSecond, adaptive.maxRatePerSecond)

        if (currentRefillRate.getAndSet(next) != next) {
            logger.info(
                "Google Places token bucket refill rate adjusted: {} -> {} (success={}, 429={}, timeout={}, error={})",
                current,
                next,
                success,
                rateLimited,
                timeout,
                error,
            )
            meterRegistry.counter("ssolv.place.google.token_bucket.refill_rate.adjusted.count").increment()
        }
    }

    private fun consume(cost: Int): ConsumeResult {
        val result = redisTemplate.execute(
            consumeScript,
            listOf(rateLimit.bucketKey),
            System.currentTimeMillis().toString(),
            rateLimit.bucketCapacity.toString(),
            currentRefillRate.get().toString(),
            cost.coerceAtLeast(1).toString(),
            rateLimit.bucketTtlSeconds.toString(),
        )

        val allowed = (result.getOrNull(0) as? Number)?.toLong() == 1L
        val availableTokens = (result.getOrNull(1) as? Number)?.toLong() ?: 0L
        val retryAfterMs = (result.getOrNull(2) as? Number)?.toLong() ?: rateLimit.retryDelayMs
        lastAvailableTokens.set(availableTokens)

        return ConsumeResult(allowed, availableTokens, retryAfterMs)
    }

    private data class ConsumeResult(val allowed: Boolean, val availableTokens: Long, val retryAfterMs: Long)
}

enum class GooglePlacesApiResult(val metricTag: String) {
    SUCCESS("success"),
    RATE_LIMITED("rate_limited"),
    TIMEOUT("timeout"),
    ERROR("error"),
}
