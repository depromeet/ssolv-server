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
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import java.math.BigInteger
import java.security.Key
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Component
class AppleOAuthClient(
    private val objectMapper: ObjectMapper,
    private val appleProperties: AppleProperties,
    private val restTemplate: RestTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {
    private val log = LoggerFactory.getLogger(AppleOAuthClient::class.java)
    
    // 공개키 캐시 (간단한 동기화 처리)
    private val publicKeyCache = ConcurrentHashMap<String, PublicKey>()

    private fun getAllowedRedirectUris(): Set<String> {
        val hardcodedUris = setOf(
            "http://localhost:3000/auth/callback",
            "http://192.168.35.119:3000/auth/callback",
            "http://localhost:8080/auth/callback",
            "https://api.ssolv.site/auth/callback",
            "https://www.ssolv.site/auth/callback",
            "https://ec01-58-29-179-24.ngrok-free.app/auth/callback"
        )
        
        val configUris = appleProperties.redirectUris.toSet()
        val singleUri = setOfNotNull(appleProperties.redirectUri.takeIf { it.isNotBlank() })
        
        return hardcodedUris + configUris + singleUri
    }

    /**
     * 인가 코드를 이용해 애플 서버로부터 OAuth 토큰 반환 받음
     */
    suspend fun requestToken(accessCode: String, redirectUri: String): AppleResponse.OAuthToken = withContext(coroutineDispatchers.VT) {
        val trimmedRedirectUri = redirectUri.trim()

        if (!getAllowedRedirectUris().contains(trimmedRedirectUri)) {
            log.error("허용되지 않은 redirect_uri: {}", trimmedRedirectUri)
            log.error("허용된 URI 목록: {}", getAllowedRedirectUris())
            throw AuthException(ErrorCode.APPLE_INVALID_REDIRECT_URI)
        }

        val clientSecret = generateClientSecret()

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

        log.debug("애플 토큰 요청 - redirect_uri: {}, client_id: {}", trimmedRedirectUri, appleProperties.clientId)

        try {
            val response = restTemplate.exchange(
                appleProperties.tokenUri,
                HttpMethod.POST,
                HttpEntity(params, headers),
                String::class.java
            )

            objectMapper.readValue(response.body, AppleResponse.OAuthToken::class.java)

        } catch (e: HttpClientErrorException) {
            log.error("애플 API 에러 ({}): {}", e.statusCode, e.responseBodyAsString)
            when (e.statusCode.value()) {
                400 -> throw AuthException(ErrorCode.APPLE_INVALID_GRANT)
                401 -> throw AuthException(ErrorCode.APPLE_AUTH_FAILED)
                else -> throw AuthException(ErrorCode.APPLE_API_ERROR)
            }
        } catch (e: Exception) {
            log.error("애플 API 호출 중 예외 발생: {}", e.message)
            throw AuthException(ErrorCode.APPLE_API_ERROR)
        }
    }

    /**
     * ID 토큰 서명 및 클레임 검증 후 사용자 정보 추출
     */
    suspend fun parseIdToken(idToken: String): AppleResponse.IdTokenPayload {
        return try {
            val claims = Jwts.parser()
                .keyLocator { header ->
                    val kid = header["kid"] as? String ?: throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
                    getOrFetchPublicKey(kid)
                }
                .requireIssuer("https://appleid.apple.com")
                .requireAudience(appleProperties.clientId)
                .build()
                .parseSignedClaims(idToken)
                .payload

            AppleResponse.IdTokenPayload(
                iss = claims.issuer,
                aud = claims.audience.first(), 
                exp = claims.expiration.time / 1000,
                iat = (claims["iat"] as? Number)?.toLong() ?: 0L,
                sub = claims.subject,
                email = claims["email"] as? String,
                email_verified = claims["email_verified"]?.toString(),
                is_private_email = claims["is_private_email"]?.toString(),
                nonce_supported = claims["nonce_supported"] as? Boolean
            )
        } catch (e: Exception) {
            log.error("애플 ID 토큰 검증 실패: {}", e.message)
            throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
        }
    }

    private fun getOrFetchPublicKey(kid: String): Key {
        return publicKeyCache[kid] ?: run {
            refreshPublicKeys()
            publicKeyCache[kid] ?: throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
        }
    }

    private fun refreshPublicKeys() {
        try {
            val response = restTemplate.getForObject("https://appleid.apple.com/auth/keys", AppleResponse.PublicKeys::class.java)
            response?.keys?.forEach { key ->
                val publicKey = generatePublicKey(key.n, key.e)
                publicKeyCache[key.kid] = publicKey
            }
        } catch (e: Exception) {
            log.error("애플 공개키 조회 실패: {}", e.message)
            throw AuthException(ErrorCode.APPLE_API_ERROR)
        }
    }

    private fun generatePublicKey(n: String, e: String): PublicKey {
        val nBytes = Base64.getUrlDecoder().decode(n)
        val eBytes = Base64.getUrlDecoder().decode(e)
        
        val nBI = BigInteger(1, nBytes)
        val eBI = BigInteger(1, eBytes)
        
        val spec = RSAPublicKeySpec(nBI, eBI)
        val factory = KeyFactory.getInstance("RSA")
        return factory.generatePublic(spec)
    }

    /**
     * 애플 OAuth용 Client Secret 생성 (JWT)
     */
    private fun generateClientSecret(): String {
        return try {
            val now = Date()
            val expirationDate = Date(now.time + 3600000 * 6) // 6시간 유효

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
                .signWith(getPrivateKey(), Jwts.SIG.ES256)
                .compact()

        } catch (e: Exception) {
            log.error("애플 Client Secret 생성 실패: {}", e.message)
            throw AuthException(ErrorCode.APPLE_AUTH_FAILED)
        }
    }

    private fun getPrivateKey(): PrivateKey {
        val privateKeyContent = appleProperties.privateKey
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(privateKeyContent)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("EC").generatePrivate(keySpec)
    }
}
