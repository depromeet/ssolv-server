package org.depromeet.team3.common

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "api.google.places")
data class GooglePlacesApiProperties(
    var apiKey: String = "",
    var baseUrl: String = "https://places.googleapis.com",
    var requestSemaphoreSize: Int = 5,
    var totalFetchSize: Int = 10,
    var photoFallbackBuffer: Int = 5,
    var keywordFetchSize: Int = 20,
    var apiTimeoutMs: Long = 3000,
    var semaphoreTimeoutMs: Long = 3000,
    var rateLimit: RateLimit = RateLimit(),
)

data class RateLimit(
    var enabled: Boolean = true,
    var bucketKey: String = "google:places:token-bucket",
    var refillRatePerSecond: Int = 15,
    var bucketCapacity: Int = 30,
    var tokenAcquireTimeoutMs: Long = 800,
    var retryDelayMs: Long = 50,
    var bucketTtlSeconds: Long = 120,
    var adaptive: AdaptiveRateLimit = AdaptiveRateLimit(),
)

data class AdaptiveRateLimit(
    var enabled: Boolean = true,
    var minRatePerSecond: Int = 5,
    var maxRatePerSecond: Int = 20,
    var evaluationIntervalMs: Long = 30_000,
    var timeoutRatioThreshold: Double = 0.05,
    var decreaseOn429Ratio: Double = 0.5,
    var decreaseOnTimeoutRatio: Double = 0.7,
    var increaseStep: Int = 1,
)
