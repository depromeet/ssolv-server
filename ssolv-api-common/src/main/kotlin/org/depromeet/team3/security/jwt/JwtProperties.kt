package org.depromeet.team3.security.jwt

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    var secret: String = "",
    var accessTokenValidity: Long = 3600000, // 1시간
    var refreshTokenValidity: Long = 604800000, // 1주일
) {
    // 기존 코드와의 호환성을 위한 alias
    val accessTokenExpiration: Long get() = accessTokenValidity
    val refreshTokenExpiration: Long get() = refreshTokenValidity
    val secretKey: String get() = secret
}
