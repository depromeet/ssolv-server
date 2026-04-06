package org.depromeet.team3.auth.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "kakao")
data class KakaoProperties(
    var clientId: String = "",
    var redirectUri: String = "",
    var redirectUris: List<String> = emptyList(),
    var tokenUri: String = "https://kauth.kakao.com/oauth/token",
    var userInfoUri: String = "https://kapi.kakao.com/v2/user/me",
    var adminKey: String = "",
    var logoutRedirectUri: String = ""
)
