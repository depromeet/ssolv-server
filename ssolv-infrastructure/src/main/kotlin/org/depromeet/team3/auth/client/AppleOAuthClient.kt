package org.depromeet.team3.auth.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.model.AppleResponse
import org.depromeet.team3.auth.properties.AppleProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*

@Component
class AppleOAuthClient(
    private val objectMapper: ObjectMapper,
    private val appleProperties: AppleProperties
) {
    private val log = LoggerFactory.getLogger(AppleOAuthClient::class.java)

    private fun getAllowedRedirectUris(): Set<String> {
        val hardcodedUris = setOf(
            "http://localhost:3000/auth/callback",
            "http://192.168.35.119:3000/auth/callback",
            "http://localhost:8080/auth/callback",
            "https://api.ssolv.site/auth/callback",
            "https://www.ssolv.site/auth/callback"
        )
        
        val configUris = appleProperties.redirectUris.toSet()
        
        return hardcodedUris + configUris
    }

    /**
     * 인가 코드를 이용해 애플 서버로부터 OAuth 토큰 반환 받음
     */
    fun requestToken(accessCode: String, redirectUri: String): AppleResponse.OAuthToken {
        val trimmedRedirectUri = redirectUri.trim()

        if (!getAllowedRedirectUris().contains(trimmedRedirectUri)) {
            log.error("허용되지 않은 redirect_uri: {}", trimmedRedirectUri)
            log.error("허용된 URI 목록: {}", getAllowedRedirectUris())
            throw AuthException(ErrorCode.APPLE_INVALID_REDIRECT_URI)
        }

        // 애플은 client_secret으로 JWT를 사용
        val clientSecret = generateClientSecret()

        val restTemplate = RestTemplate()
        val headers = HttpHeaders().apply {
            add("Content-type", "application/x-www-form-urlencoded")
        }

        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", appleProperties.clientId)
            add("client_secret", clientSecret)
            add("code", accessCode)
            add("redirect_uri", trimmedRedirectUri)
        }

        log.info("애플 토큰 요청 - redirect_uri: {}", trimmedRedirectUri)
        log.info("애플 토큰 요청 - client_id: {}", appleProperties.clientId)

        val appleTokenRequest = HttpEntity(params, headers)

        return try {
            val response = restTemplate.exchange(
                appleProperties.tokenUri,
                HttpMethod.POST,
                appleTokenRequest,
                String::class.java
            )

            objectMapper.readValue(response.body, AppleResponse.OAuthToken::class.java)

        } catch (e: HttpClientErrorException) {
            when (e.statusCode.value()) {
                400 -> {
                    log.error("애플 API Bad Request (400): {}", e.responseBodyAsString)
                    throw AuthException(ErrorCode.APPLE_INVALID_GRANT)
                }
                401 -> {
                    log.error("애플 인증 실패 (401): {}", e.responseBodyAsString)
                    throw AuthException(ErrorCode.APPLE_AUTH_FAILED)
                }
                else -> {
                    log.error("애플 API HTTP 에러 - 상태코드: {}", e.statusCode)
                    throw AuthException(ErrorCode.APPLE_API_ERROR)
                }
            }
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            when (e.javaClass.simpleName) {
                "JsonProcessingException" -> {
                    log.error("애플 응답 JSON 파싱 오류: {}", e.message)
                    throw AuthException(ErrorCode.APPLE_JSON_PARSE_ERROR)
                }
                else -> {
                    log.error("애플 API 호출 중 오류 발생: {}", e.message)
                    throw AuthException(ErrorCode.APPLE_API_ERROR)
                }
            }
        }
    }

    /**
     * ID 토큰에서 사용자 정보 추출
     * 애플은 별도의 프로필 API가 없고, ID 토큰(JWT)에 사용자 정보가 포함됨
     */
    fun parseIdToken(idToken: String): AppleResponse.IdTokenPayload {
        return try {
            // JWT 토큰을 Base64 디코딩하여 페이로드 추출
            val parts = idToken.split(".")
            if (parts.size != 3) {
                throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
            }

            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            objectMapper.readValue(payload, AppleResponse.IdTokenPayload::class.java)

        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            log.error("애플 ID 토큰 파싱 오류: {}", e.message)
            throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
        }
    }

    /**
     * 애플 OAuth용 Client Secret 생성 (JWT)
     * 애플은 client_secret으로 개발자가 서명한 JWT를 요구함
     */
    private fun generateClientSecret(): String {
        return try {
            val now = Date()
            val expirationDate = Date(now.time + 3600000 * 6) // 6시간 유효

            val privateKey = getPrivateKey()

            Jwts.builder()
                .header()
                .keyId(appleProperties.keyId)
                .and()
                .issuer(appleProperties.teamId)
                .issuedAt(now)
                .expiration(expirationDate)
                .audience()
                .add("https://appleid.apple.com")
                .and()
                .subject(appleProperties.clientId)
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact()

        } catch (e: Exception) {
            log.error("애플 Client Secret 생성 실패: {}", e.message)
            throw AuthException(ErrorCode.APPLE_AUTH_FAILED)
        }
    }

    /**
     * Private Key 파싱 (P8 파일 내용)
     */
    private fun getPrivateKey(): PrivateKey {
        return try {
            val privateKeyContent = appleProperties.privateKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")

            val keyBytes = Base64.getDecoder().decode(privateKeyContent)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePrivate(keySpec)

        } catch (e: Exception) {
            log.error("애플 Private Key 파싱 실패: {}", e.message)
            throw AuthException(ErrorCode.APPLE_AUTH_FAILED)
        }
    }
}
