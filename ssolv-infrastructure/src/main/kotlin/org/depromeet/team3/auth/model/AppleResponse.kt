package org.depromeet.team3.auth.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 애플 OAuth API 응답 모델
 */
object AppleResponse {

    /**
     * 애플 OAuth 토큰 응답
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OAuthToken(
        @JsonProperty("access_token")
        val access_token: String,

        @JsonProperty("token_type")
        val token_type: String = "Bearer",

        @JsonProperty("expires_in")
        val expires_in: Int,

        @JsonProperty("refresh_token")
        val refresh_token: String?,

        @JsonProperty("id_token")
        val id_token: String  // JWT 형식의 ID 토큰
    )

    /**
     * 애플 ID 토큰 페이로드 (JWT 디코딩 후)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdTokenPayload(
        val iss: String,  // https://appleid.apple.com
        val aud: String,  // Client ID
        val exp: Long,    // Expiration time
        val iat: Long,    // Issued at
        val sub: String,  // User identifier (Apple User ID)
        val email: String?,
        @JsonProperty("email_verified")
        val email_verified: String?,
        @JsonProperty("is_private_email")
        val is_private_email: String?,
        @JsonProperty("nonce_supported")
        val nonce_supported: Boolean?
    )

    /**
     * 애플 로그인 시 최초 전달되는 사용자 정보
     * (첫 로그인 시에만 제공됨)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserInfo(
        val name: Name?,
        val email: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Name(
        val firstName: String?,
        val lastName: String?
    )

    /**
     * 애플 Public Key 응답
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PublicKeys(
        val keys: List<PublicKey>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PublicKey(
        val kty: String,
        val kid: String,
        val use: String,
        val alg: String,
        val n: String,
        val e: String
    )
}
