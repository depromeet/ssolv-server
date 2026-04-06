package org.depromeet.team3.auth.client

import org.depromeet.team3.auth.properties.KakaoProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.model.KakaoResponse
import org.slf4j.LoggerFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

@Component
class KakaoOAuthClient(
    private val kakaoProperties: KakaoProperties,
    private val httpClient: HttpClient,
) {
    private val log = LoggerFactory.getLogger(KakaoOAuthClient::class.java)

    private fun getAllowedRedirectUris(): Set<String> {
        val hardcodedUris = setOf(
            "http://localhost:3000/auth/callback",
            "http://192.168.35.119:3000/auth/callback",
            "http://localhost:8080/auth/callback",
            "https://api.ssolv.site/auth/callback",
            "https://www.ssolv.site/auth/callback",
            "https://ec01-58-29-179-24.ngrok-free.app/auth/callback"
        )
        
        val configUris = kakaoProperties.redirectUris.toSet()
        val singleUri = setOfNotNull(kakaoProperties.redirectUri.takeIf { it.isNotBlank() })
        
        return hardcodedUris + configUris + singleUri
    }

    private val apiTimeoutMillis = 5_000L

    suspend fun requestToken(accessCode: String, redirectUri: String): KakaoResponse.OAuthToken {
        val trimmedRedirectUri = redirectUri.trim()

        if (!getAllowedRedirectUris().contains(trimmedRedirectUri)) {
            log.error("허용되지 않은 redirect_uri: {}", trimmedRedirectUri)
            throw AuthException(ErrorCode.KAKAO_INVALID_REDIRECT_URI)
        }

        return try {
            withTimeout(apiTimeoutMillis) {
                httpClient.submitForm(
                    url = kakaoProperties.tokenUri,
                    formParameters = parameters {
                        append("grant_type", "authorization_code")
                        append("client_id", kakaoProperties.clientId)
                        append("redirect_uri", trimmedRedirectUri)
                        append("code", accessCode)
                    }
                ).body<KakaoResponse.OAuthToken>()
            }
        } catch (e: Exception) {
            if (e is ResponseException) {
                val body = e.response.body<String>()
                log.error("카카오 토큰 요청 에러 ({}): {}", e.response.status, body)
                when (e.response.status.value) {
                    400, 401 -> throw AuthException(ErrorCode.KAKAO_INVALID_GRANT)
                    429 -> throw AuthException(ErrorCode.KAKAO_RATE_LIMIT_EXCEEDED)
                    else -> throw AuthException(ErrorCode.KAKAO_API_ERROR)
                }
            }
            if (e is TimeoutCancellationException) {
                log.error("카카오 토큰 요청 타임아웃: {}", e.message)
                throw AuthException(ErrorCode.KAKAO_API_ERROR)
            }
            log.error("카카오 API 호출 중 예외 발생: {}", e.message)
            throw AuthException(ErrorCode.KAKAO_API_ERROR)
        }
    }

    suspend fun requestProfile(oAuthToken: KakaoResponse.OAuthToken?): KakaoResponse.KakaoProfile {
        val accessToken = oAuthToken?.access_token ?: throw AuthException(ErrorCode.KAKAO_AUTH_FAILED)

        return try {
            withTimeout(apiTimeoutMillis) {
                httpClient.get(kakaoProperties.userInfoUri) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }.body<KakaoResponse.KakaoProfile>()
            }
        } catch (e: Exception) {
            if (e is ResponseException) {
                val body = e.response.body<String>()
                log.error("카카오 프로필 요청 에러 ({}): {}", e.response.status, body)
                when (e.response.status.value) {
                    401 -> throw AuthException(ErrorCode.KAKAO_AUTH_FAILED)
                    else -> throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
                }
            }
            if (e is TimeoutCancellationException) {
                log.error("카카오 프로필 요청 타임아웃: {}", e.message)
                throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
            }
            log.error("카카오 프로필 요청 중 예외 발생: {}", e.message)
            throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
        }
    }

    fun getLogoutUrl(): String =
        "https://kauth.kakao.com/oauth/logout?client_id=${kakaoProperties.clientId}&logout_redirect_uri=${kakaoProperties.logoutRedirectUri}"

    suspend fun unlink(socialId: String) {
        try {
            withTimeout(apiTimeoutMillis) {
                httpClient.submitForm(
                    url = "https://kapi.kakao.com/v1/user/unlink",
                    formParameters = parameters {
                        append("target_id_type", "user_id")
                        append("target_id", socialId)
                    }
                ) {
                    header(HttpHeaders.Authorization, "KakaoAK ${kakaoProperties.adminKey}")
                }
            }
            log.info("카카오 연결 끊기 성공 - socialId: {}", socialId)
        } catch (e: Exception) {
            log.error("카카오 연결 끊기 실패 - socialId: {}, error: {}", socialId, e.message)
        }
    }
}
