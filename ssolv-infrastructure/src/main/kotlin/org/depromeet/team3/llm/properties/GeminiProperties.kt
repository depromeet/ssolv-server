package org.depromeet.team3.llm.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "api.gemini")
data class GeminiProperties(
    var apiKey: String = "",
    var baseUrl: String = "https://generativelanguage.googleapis.com",
    var model: String = "gemini-2.5-flash"
)