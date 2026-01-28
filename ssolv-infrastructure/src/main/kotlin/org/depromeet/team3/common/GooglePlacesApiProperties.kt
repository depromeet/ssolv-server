package org.depromeet.team3.common

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "api.google.places")
data class GooglePlacesApiProperties(
    var apiKey: String = "",
    var baseUrl: String = "https://places.googleapis.com",
    var proxyBaseUrl: String = "https://api.ssolv.site"
)
