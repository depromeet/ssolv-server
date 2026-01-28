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

@Component
class KakaoOAuthClient(
    private val objectMapper: ObjectMapper,
    private val kakaoProperties: KakaoProperties
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
        
        return hardcodedUris + configUris
    }

    /**
     * 인가 코드를 이용해 카카오 서버로부터 OAuth 토큰 반환 받음.
     * 추후 OAuth Token 을 이용해, 카카오 서버로부터 사용자 정보 반환
     */
    fun requestToken(accessCode: String, redirectUri: String): KakaoResponse.OAuthToken {
        val trimmedRedirectUri = redirectUri.trim()

        if (!getAllowedRedirectUris().contains(trimmedRedirectUri)) {
            log.error("허용되지 않은 redirect_uri: {}", trimmedRedirectUri)
            log.error("허용된 URI 목록: {}", getAllowedRedirectUris())
            throw AuthException(ErrorCode.KAKAO_INVALID_REDIRECT_URI)
        }


        val decodedAccessCode = accessCode

        // 요청 헤더 및 파라미터 구성
        val restTemplate = RestTemplate()
        val headers = HttpHeaders().apply {
            add("Content-type", "application/x-www-form-urlencoded;charset=utf-8")
        }

        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", kakaoProperties.clientId)
            add("redirect_uri", trimmedRedirectUri)
            add("code", decodedAccessCode)
        }

        log.info("카카오 토큰 요청 - redirect_uri: {}", trimmedRedirectUri)
        log.info("카카오 토큰 요청 - client_id: {}", kakaoProperties.clientId)


        val kakaoTokenRequest = HttpEntity(params, headers)

        return try {
            val response = restTemplate.exchange(
                "https://kauth.kakao.com/oauth/token",
                HttpMethod.POST,
                kakaoTokenRequest,
                String::class.java
            )

            objectMapper.readValue(response.body, KakaoResponse.OAuthToken::class.java)

        } catch (e: HttpClientErrorException) {
            when (e.statusCode.value()) {
                400 -> {
                    log.error("카카오 API Bad Request (400): {}", e.responseBodyAsString)
                    throw AuthException(ErrorCode.KAKAO_INVALID_GRANT)
                }
                401 -> {
                    log.error("카카오 인증 실패 (401): {}", e.responseBodyAsString)
                    throw AuthException(ErrorCode.KAKAO_INVALID_GRANT)
                }
                429 -> {
                    log.error("카카오 API Rate Limit 초과 (429): {}", e.responseBodyAsString)
                    throw AuthException(ErrorCode.KAKAO_RATE_LIMIT_EXCEEDED)
                }
                else -> {
                    log.error("카카오 API HTTP 에러 - 상태코드: {}", e.statusCode)
                    throw AuthException(ErrorCode.KAKAO_API_ERROR)
                }
            }
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            when (e.javaClass.simpleName) {
                "JsonProcessingException" -> {
                    log.error("카카오 응답 JSON 파싱 오류: {}", e.message)
                    throw AuthException(ErrorCode.KAKAO_JSON_PARSE_ERROR)
                }
                else -> {
                    log.error("카카오 API 호출 중 오류 발생: {}", e.message)
                    throw AuthException(ErrorCode.KAKAO_API_ERROR)
                }
            }
        }
    }

    /**
     * access token 을 사용해 카카오 사용자 정보 요청
     */
    fun requestProfile(oAuthToken: KakaoResponse.OAuthToken?): KakaoResponse.KakaoProfile {
        if (oAuthToken?.access_token == null) {
            throw AuthException(ErrorCode.KAKAO_AUTH_FAILED)
        }

        val restTemplate = RestTemplate()
        val headers = HttpHeaders().apply {
            add("Content-type", "application/x-www-form-urlencoded;charset=utf-8")
            add("Authorization", "Bearer ${oAuthToken.access_token}")
        }
        val requestEntity = HttpEntity<Void>(headers)

        return try {
            val response = restTemplate.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.GET,
                requestEntity,
                String::class.java
            )

            objectMapper.readValue(response.body, KakaoResponse.KakaoProfile::class.java)
        } catch (e: HttpClientErrorException) {
            when (e.statusCode.value()) {
                401 -> {
                    log.error("카카오 프로필 조회 인증 실패 (401): {}", e.responseBodyAsString)
                    throw AuthException(ErrorCode.KAKAO_AUTH_FAILED)
                }
                else -> {
                    log.error("카카오 프로필 요청 중 HTTP 에러 - 상태코드: {}", e.statusCode)
                    throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
                }
            }
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            when (e.javaClass.simpleName) {
                "JsonProcessingException" -> {
                    log.error("카카오 프로필 파싱 오류: {}", e.message)
                    throw AuthException(ErrorCode.KAKAO_JSON_PARSE_ERROR)
                }
                else -> {
                    log.error("카카오 프로필 요청 중 오류 발생: {}", e.message)
                    throw AuthException(ErrorCode.KAKAO_PROFILE_REQUEST_FAILED)
                }
            }
        }
    }

    /**
     * 카카오 연결 끊기 (탈퇴 시 사용)
     */
    fun unlink(socialId: String) {
        val restTemplate = RestTemplate()
        val headers = HttpHeaders().apply {
            add("Content-Type", "application/x-www-form-urlencoded")
            add("Authorization", "KakaoAK ${kakaoProperties.adminKey}")
        }

        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("target_id_type", "user_id")
            add("target_id", socialId)
        }

        val request = HttpEntity(params, headers)

        try {
            restTemplate.exchange(
                "https://kapi.kakao.com/v1/user/unlink",
                HttpMethod.POST,
                request,
                String::class.java
            )
        } catch (e: Exception) {
            log.error("카카오 연결 끊기 실패 - socialId: {}, error: {}", socialId, e.message)
            // 탈퇴 과정이므로 에러가 발생해도 로컬 데이터 삭제는 진행할 수 있도록 예외를 던지지 않거나 로그만 남김
        }
    }
}
