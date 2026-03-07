package org.depromeet.team3.common

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "api.google.places")
data class GooglePlacesApiProperties(
    val apiKey: String,
    val baseUrl: String
)
