package org.depromeet.team3.place.application.ratelimit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.AdaptiveRateLimit
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.RateLimit
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

class GooglePlacesTokenBucketServiceTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val meterRegistry = SimpleMeterRegistry()
    private val properties = GooglePlacesApiProperties(
        rateLimit = RateLimit(
            refillRatePerSecond = 15,
            bucketCapacity = 30,
            tokenAcquireTimeoutMs = 10,
            retryDelayMs = 1,
            adaptive = AdaptiveRateLimit(
                enabled = true,
                minRatePerSecond = 5,
                maxRatePerSecond = 20,
                decreaseOn429Ratio = 0.5,
                increaseStep = 1,
            ),
        ),
    )

    @Test
    @DisplayName("Redis Token Bucket에서 토큰 획득에 성공하면 true를 반환하고 메트릭을 기록한다")
    fun `acquire returns true when redis token bucket allows request`() = runTest {
        val service = GooglePlacesTokenBucketService(redisTemplate, properties, meterRegistry)

        every {
            redisTemplate.execute(any<RedisScript<List<*>>>(), any<List<String>>(), *anyVararg())
        } returns listOf(1L, 29L, 0L, 15L)

        val acquired = service.acquire(priority = "primary")

        assertThat(acquired).isTrue()
        assertThat(meterRegistry.get("ssolv.place.google.token_bucket.available_tokens").gauge().value()).isEqualTo(29.0)
        assertThat(
            meterRegistry.get("ssolv.place.google.token_bucket.acquire.count")
                .tag("result", "success")
                .tag("priority", "primary")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    @DisplayName("429가 관측되면 다음 평가 주기에 refill rate를 곱셈 감소한다")
    fun `adjustRefillRate decreases refill rate when rate limit is observed`() {
        val service = GooglePlacesTokenBucketService(redisTemplate, properties, meterRegistry)

        service.recordApiResult(GooglePlacesApiResult.SUCCESS)
        service.recordApiResult(GooglePlacesApiResult.RATE_LIMITED)
        service.adjustRefillRate()

        assertThat(meterRegistry.get("ssolv.place.google.token_bucket.refill_rate").gauge().value()).isEqualTo(8.0)
    }

    @Test
    @DisplayName("토큰 획득 타임아웃 비율이 임계치를 넘으면 refill rate를 완만하게 감소한다")
    fun `adjustRefillRate decreases refill rate when token timeout ratio is high`() {
        val service = GooglePlacesTokenBucketService(redisTemplate, properties, meterRegistry)

        service.recordApiResult(GooglePlacesApiResult.SUCCESS)
        service.recordApiResult(GooglePlacesApiResult.TIMEOUT)
        service.adjustRefillRate()

        assertThat(meterRegistry.get("ssolv.place.google.token_bucket.refill_rate").gauge().value()).isEqualTo(11.0)
    }

    @Test
    @DisplayName("안정 구간이 관측되면 refill rate를 한 단계 증가한다")
    fun `adjustRefillRate increases refill rate after stable window`() {
        val service = GooglePlacesTokenBucketService(redisTemplate, properties, meterRegistry)

        service.recordApiResult(GooglePlacesApiResult.SUCCESS)
        service.adjustRefillRate()

        assertThat(meterRegistry.get("ssolv.place.google.token_bucket.refill_rate").gauge().value()).isEqualTo(16.0)
    }
}
