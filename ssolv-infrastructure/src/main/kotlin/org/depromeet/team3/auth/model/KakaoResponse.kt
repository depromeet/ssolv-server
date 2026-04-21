package org.depromeet.team3.auth.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 카카오 OAuth API 응답 모델
 */
object KakaoResponse {

    /**
     * 카카오 OAuth 토큰 응답
     */
    data class OAuthToken(
        @JsonProperty("access_token")
        val access_token: String,

        @JsonProperty("token_type")
        val token_type: String = "bearer",
    )

    /**
     * 카카오 사용자 프로필 응답
     */
    data class KakaoProfile(
        val id: Long,

        @JsonProperty("kakao_account")
        val kakao_account: KakaoAccount,
    )

    data class KakaoAccount(val email: String, val profile: Profile)

    data class Profile(
        val nickname: String,

        @JsonProperty("profile_image_url")
        val profile_image_url: String?,
    )
}
