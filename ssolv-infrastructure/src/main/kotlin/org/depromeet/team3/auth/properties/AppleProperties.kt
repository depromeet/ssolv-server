package org.depromeet.team3.auth.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "apple")
data class AppleProperties(
    var teamId: String = "",
    var keyId: String = "",
    var clientId: String = "",
    var bundleId: String = "",
    var redirectUri: String = "",
    var redirectUris: List<String> = emptyList(),
    var privateKey: String = "",
    var tokenUri: String = "https://appleid.apple.com/auth/token",
)
