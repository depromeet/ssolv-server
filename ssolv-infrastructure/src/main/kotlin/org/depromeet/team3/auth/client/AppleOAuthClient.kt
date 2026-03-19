package org.depromeet.team3.auth.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.model.AppleResponse
import org.depromeet.team3.auth.properties.AppleProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap

import org.springframework.beans.factory.annotation.Qualifier

@Component
class AppleOAuthClient(
    private val objectMapper: ObjectMapper,
    private val appleProperties: AppleProperties,
    @Qualifier("commonWebClient")
    private val webClient: WebClient,
) {
    private val log = LoggerFactory.getLogger(AppleOAuthClient::class.java)
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

    private val apiTimeoutMillis = 5_000L

    suspend fun requestToken(accessCode: String, redirectUri: String): AppleResponse.OAuthToken {
        val trimmedRedirectUri = redirectUri.trim()
        if (!getAllowedRedirectUris().contains(trimmedRedirectUri)) {
            log.error("허용되지 않은 redirect_uri: {}", trimmedRedirectUri)
            throw AuthException(ErrorCode.APPLE_INVALID_REDIRECT_URI)
        }

        val clientSecret = generateClientSecret()
        val params: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", appleProperties.clientId)
            add("client_secret", clientSecret)
            add("code", accessCode)
            add("redirect_uri", trimmedRedirectUri)
        }

        return try {
            kotlinx.coroutines.withTimeout(apiTimeoutMillis) {
                webClient.post()
                    .uri(appleProperties.tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(params))
                    .retrieve()
                    .onStatus({ status -> status.isError }) { response ->
                        response.bodyToMono(String::class.java).map { body ->
                            log.error("애플 토큰 요청 에러 ({}): {}", response.statusCode(), body)
                            when (response.statusCode().value()) {
                                400 -> AuthException(ErrorCode.APPLE_INVALID_GRANT)
                                401 -> AuthException(ErrorCode.APPLE_AUTH_FAILED)
                                else -> AuthException(ErrorCode.APPLE_API_ERROR)
                            }
                        }
                    }
                    .awaitBody<AppleResponse.OAuthToken>()
            }
        } catch (e: Exception) {
            if (e is AuthException) throw e
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                log.error("애플 토큰 요청 타임아웃: {}", e.message)
                throw AuthException(ErrorCode.APPLE_API_ERROR)
            }
            log.error("애플 API 호출 중 예외 발생: {}", e.message)
            throw AuthException(ErrorCode.APPLE_API_ERROR)
        }
    }

    suspend fun parseIdToken(idToken: String): AppleResponse.IdTokenPayload {
        try {
            // jjwt 파서의 KeyLocator는 suspend를 지원하지 않으므로 미리 kid를 추출합니다.
            val headerPart = idToken.split(".")[0]
            val headerJson = String(Base64.getUrlDecoder().decode(headerPart))
            val headerMap = objectMapper.readValue(headerJson, Map::class.java)
            val kid = headerMap["kid"] as? String ?: throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)

            if (!publicKeyCache.containsKey(kid)) {
                refreshPublicKeys()
            }

            val claims = Jwts.parser()
                .keyLocator { header ->
                    val k = header["kid"] as? String ?: throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
                    publicKeyCache[k] ?: throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
                }
                .requireIssuer("https://appleid.apple.com")
                .requireAudience(appleProperties.clientId)
                .build()
                .parseSignedClaims(idToken)
                .payload

            return AppleResponse.IdTokenPayload(
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
            when (e) {
                is AuthException -> throw e
                is io.jsonwebtoken.ExpiredJwtException -> log.error("애플 ID 토큰 만료: {}", e.message)
                is io.jsonwebtoken.security.SignatureException -> log.error("애플 ID 토큰 서명 유효하지 않음: {}", e.message)
                is io.jsonwebtoken.IncorrectClaimException -> log.error("애플 ID 토큰 클레임 불일치(iss/aud): {}", e.message)
                else -> log.error("애플 ID 토큰 검증 중 알 수 없는 예외 발생: {}", e.message)
            }
            throw AuthException(ErrorCode.APPLE_INVALID_ID_TOKEN)
        }
    }

    private suspend fun refreshPublicKeys() {
        try {
            kotlinx.coroutines.withTimeout(apiTimeoutMillis) {
                val response = webClient.get()
                    .uri("https://appleid.apple.com/auth/keys")
                    .retrieve()
                    .onStatus({ status -> status.isError }) { response ->
                        response.bodyToMono(String::class.java).map { body ->
                            log.error("애플 공개키 조회 API 오류 ({}): {}", response.statusCode(), body)
                            AuthException(ErrorCode.APPLE_API_ERROR)
                        }
                    }
                    .awaitBody<AppleResponse.PublicKeys>()
                
                response.keys.forEach { key ->
                    val publicKey = generatePublicKey(key.n, key.e)
                    publicKeyCache[key.kid] = publicKey
                }
            }
        } catch (e: Exception) {
            if (e is AuthException) throw e
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
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    private fun generateClientSecret(): String {
        return try {
            val now = Date()
            Jwts.builder()
                .header().keyId(appleProperties.keyId).and()
                .issuer(appleProperties.teamId)
                .issuedAt(now)
                .expiration(Date(now.time + 3600000 * 6))
                .audience().add("https://appleid.apple.com").and()
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
