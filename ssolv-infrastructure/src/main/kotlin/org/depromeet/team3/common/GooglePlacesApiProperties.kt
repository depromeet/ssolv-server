package org.depromeet.team3.common

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "api.google.places")
data class GooglePlacesApiProperties(
    var apiKey: String = "",
    var baseUrl: String = "https://places.googleapis.com"
)
