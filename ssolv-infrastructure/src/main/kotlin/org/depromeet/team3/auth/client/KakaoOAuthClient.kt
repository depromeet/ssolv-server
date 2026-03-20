package org.depromeet.team3.auth.client

import org.depromeet.team3.auth.properties.KakaoProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.model.KakaoResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitBodilessEntity
import com.fasterxml.jackson.databind.ObjectMapper

import org.springframework.beans.factory.annotation.Qualifier

@Component
class KakaoOAuthClient(
    private val kakaoProperties: KakaoProperties,
    @Qualifier("commonWebClient")
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
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

        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", kakaoProperties.clientId)
            add("redirect_uri", trimmedRedirectUri)
            add("code", accessCode)
        }

        return try {
            kotlinx.coroutines.withTimeout(apiTimeoutMillis) {
                webClient.post()
                    .uri(kakaoProperties.tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(params))
                    .retrieve()
                    .onStatus({ status -> status.isError }) { response ->
                        response.bodyToMono(String::class.java).map { body ->
                            log.error("카카오 토큰 요청 에러 ({}): {}", response.statusCode(), body)
                            when (response.statusCode().value()) {
                                400, 401 -> AuthException(ErrorCode.KAKAO_INVALID_GRANT)
                                429 -> AuthException(ErrorCode.KAKAO_RATE_LIMIT_EXCEEDED)
                                else -> AuthException(ErrorCode.KAKAO_API_ERROR)
                            }
                        }
                    }
                    .awaitBody<KakaoResponse.OAuthToken>()
            }
        } catch (e: Exception) {
            if (e is AuthException) throw e
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
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
            kotlinx.coroutines.withTimeout(apiTimeoutMillis) {
                webClient.get()
                    .uri(kakaoProperties.userInfoUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                    .retrieve()
                    .onStatus({ status -> status.isError }) { response ->
                        response.bodyToMono(String::class.java).map { body ->
                            log.error("카카오 프로필 요청 에러 ({}): {}", response.statusCode(), body)
                            when (response.statusCode().value()) {
                                401 -> AuthException(ErrorCode.KAKAO_AUTH_FAILED)
                                else -> AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
                            }
                        }
                    }
                    .awaitBody<KakaoResponse.KakaoProfile>()
            }
        } catch (e: Exception) {
            if (e is AuthException) throw e
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                log.error("카카오 프로필 요청 타임아웃: {}", e.message)
                throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
            }
            log.error("카카오 프로필 요청 중 예외 발생: {}", e.message)
            throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
        }
    }

    suspend fun unlink(socialId: String) {
        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("target_id_type", "user_id")
            add("target_id", socialId)
        }

        try {
            kotlinx.coroutines.withTimeout(apiTimeoutMillis) {
                webClient.post()
                    .uri("https://kapi.kakao.com/v1/user/unlink")
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK ${kakaoProperties.adminKey}")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(params))
                    .retrieve()
                    .awaitBodilessEntity()
            }
            log.info("카카오 연결 끊기 성공 - socialId: {}", socialId)
        } catch (e: Exception) {
            log.error("카카오 연결 끊기 실패 - socialId: {}, error: {}", socialId, e.message)
        }
    }
}
