package org.depromeet.team3.auth.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.depromeet.team3.auth.properties.KakaoProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.model.KakaoResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers

@Component
class KakaoOAuthClient(
    private val objectMapper: ObjectMapper,
    private val kakaoProperties: KakaoProperties,
    private val restTemplate: RestTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {
    private val log = LoggerFactory.getLogger(KakaoOAuthClient::class.java)

    private fun getAllowedRedirectUris(): Set<String> {
        val hardcodedUris = setOf(
            "http://localhost:3000/auth/callback",
            "http://192.168.35.119:3000/auth/callback",
            "http://localhost:8080/auth/callback",
            "https://api.ssolv.site/auth/callback",
            "https://www.ssolv.site/auth/callback"
        )
        
        val configUris = kakaoProperties.redirectUris.toSet()
        val singleUri = setOfNotNull(kakaoProperties.redirectUri.takeIf { it.isNotBlank() })
        
        return hardcodedUris + configUris + singleUri
    }

    /**
     * 인가 코드를 이용해 카카오 서버로부터 OAuth 토큰 반환 받음.
     */
    suspend fun requestToken(accessCode: String, redirectUri: String): KakaoResponse.OAuthToken = withContext(coroutineDispatchers.VT) {
        val trimmedRedirectUri = redirectUri.trim()

        if (!getAllowedRedirectUris().contains(trimmedRedirectUri)) {
            log.error("허용되지 않은 redirect_uri: {}", trimmedRedirectUri)
            log.error("허용된 URI 목록: {}", getAllowedRedirectUris())
            throw AuthException(ErrorCode.KAKAO_INVALID_REDIRECT_URI)
        }

        val headers = HttpHeaders().apply {
            add("Content-type", "application/x-www-form-urlencoded;charset=utf-8")
        }

        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", kakaoProperties.clientId)
            add("redirect_uri", trimmedRedirectUri)
            add("code", accessCode)
        }

        log.debug("카카오 토큰 요청 - redirect_uri: {}, client_id: {}", trimmedRedirectUri, kakaoProperties.clientId)

        try {
            val response = restTemplate.exchange(
                kakaoProperties.tokenUri,
                HttpMethod.POST,
                HttpEntity(params, headers),
                String::class.java
            )

            objectMapper.readValue(response.body, KakaoResponse.OAuthToken::class.java)

        } catch (e: HttpClientErrorException) {
            log.error("카카오 토큰 요청 에러 ({}): {}", e.statusCode, e.responseBodyAsString)
            when (e.statusCode.value()) {
                400, 401 -> throw AuthException(ErrorCode.KAKAO_INVALID_GRANT)
                429 -> throw AuthException(ErrorCode.KAKAO_RATE_LIMIT_EXCEEDED)
                else -> throw AuthException(ErrorCode.KAKAO_API_ERROR)
            }
        } catch (e: Exception) {
            log.error("카카오 API 호출 중 예외 발생: {}", e.message)
            throw AuthException(ErrorCode.KAKAO_API_ERROR)
        }
    }

    /**
     * access token 을 사용해 카카오 사용자 정보 요청
     */
    suspend fun requestProfile(oAuthToken: KakaoResponse.OAuthToken?): KakaoResponse.KakaoProfile = withContext(coroutineDispatchers.VT) {
        val accessToken = oAuthToken?.access_token ?: throw AuthException(ErrorCode.KAKAO_AUTH_FAILED)

        val headers = HttpHeaders().apply {
            add("Content-type", "application/x-www-form-urlencoded;charset=utf-8")
            add("Authorization", "Bearer $accessToken")
        }

        try {
            val response = restTemplate.exchange(
                kakaoProperties.userInfoUri,
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java
            )

            objectMapper.readValue(response.body, KakaoResponse.KakaoProfile::class.java)
        } catch (e: HttpClientErrorException) {
            log.error("카카오 프로필 요청 에러 ({}): {}", e.statusCode, e.responseBodyAsString)
            when (e.statusCode.value()) {
                401 -> throw AuthException(ErrorCode.KAKAO_AUTH_FAILED)
                else -> throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
            }
        } catch (e: Exception) {
            log.error("카카오 프로필 요청 중 예외 발생: {}", e.message)
            throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
        }
    }

    /**
     * 카카오 연결 끊기 (탈퇴 시 사용)
     */
    suspend fun unlink(socialId: String) = withContext(coroutineDispatchers.VT) {
        val headers = HttpHeaders().apply {
            add("Content-Type", "application/x-www-form-urlencoded")
            add("Authorization", "KakaoAK ${kakaoProperties.adminKey}")
        }

        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("target_id_type", "user_id")
            add("target_id", socialId)
        }

        try {
            restTemplate.exchange(
                "https://kapi.kakao.com/v1/user/unlink",
                HttpMethod.POST,
                HttpEntity(params, headers),
                String::class.java
            )
        } catch (e: Exception) {
            log.error("카카오 연결 끊기 실패 - socialId: {}, error: {}", socialId, e.message)
        }
    }
}
