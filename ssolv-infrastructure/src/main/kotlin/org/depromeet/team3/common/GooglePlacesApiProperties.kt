package org.depromeet.team3.common

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "api.google.places")
data class GooglePlacesApiProperties(
    var apiKey: String = "",
    var baseUrl: String = "https://places.googleapis.com",
    var globalSemaphoreSize: Int = 12,
    var requestSemaphoreSize: Int = 5,
    var totalFetchSize: Int = 10,
    var photoFallbackBuffer: Int = 5,
    var keywordFetchSize: Int = 20,
    var apiTimeoutMs: Long = 3000,
    var semaphoreTimeoutMs: Long = 3000
)
