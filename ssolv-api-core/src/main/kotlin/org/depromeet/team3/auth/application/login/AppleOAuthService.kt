package org.depromeet.team3.auth.application.login

import com.fasterxml.jackson.databind.ObjectMapper
import org.depromeet.team3.auth.client.AppleOAuthClient
import org.depromeet.team3.auth.command.AppleLoginCommand
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.model.AppleResponse
import org.depromeet.team3.auth.properties.AppleProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 애플 로그인 Orchestrator
 */
@Service
class AppleOAuthService(
    private val appleOAuthClient: AppleOAuthClient,
    private val createAppleUserService: CreateAppleUserService,
    private val appleProperties: AppleProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AppleOAuthService::class.java)

    fun login(command: AppleLoginCommand): LoginResponse {
        val code = command.authorizationCode
        val redirectUri = command.redirectUri ?: getDefaultRedirectUri()

        // 1. 애플 OAuth 토큰 요청
        val oAuthToken = appleOAuthClient.requestToken(code, redirectUri)

        // 2. ID 토큰에서 사용자 정보 추출
        val idTokenPayload = appleOAuthClient.parseIdToken(oAuthToken.id_token)

        // 3. 사용자 정보 파싱
        val socialId = idTokenPayload.sub
        val email = idTokenPayload.email
        
        // 4. 최초 로그인 시 전달되는 사용자 정보 파싱
        val userInfo = command.user?.let { parseUserInfo(it) }
        val nickname = buildNickname(userInfo, email)
        val profileImage: String? = null

        log.debug("애플 로그인 요청 처리 완료")

        // 5. DB 작업 위임
        return createAppleUserService.saveUserAndGenerateTokens(email, nickname, profileImage, socialId)
    }

    private fun getDefaultRedirectUri(): String {
        return when {
            appleProperties.redirectUris.isNotEmpty() -> appleProperties.redirectUris.first()
            !appleProperties.redirectUri.isNullOrBlank() -> appleProperties.redirectUri
            else -> throw AuthException(errorCode = ErrorCode.APPLE_REDIRECT_URI_NOT_CONFIGURED)
        }
    }

    private fun parseUserInfo(userJson: String): AppleResponse.UserInfo {
        return try {
            objectMapper.readValue(userJson, AppleResponse.UserInfo::class.java)
        } catch (e: Exception) {
            log.warn("애플 사용자 정보 파싱 실패: {}", e.message)
            AppleResponse.UserInfo(name = null, email = null)
        }
    }

    private fun buildNickname(userInfo: AppleResponse.UserInfo?, email: String?): String {
        val name = userInfo?.name
        return when {
            name != null -> {
                val firstName = name.firstName ?: ""
                val lastName = name.lastName ?: ""
                "$lastName$firstName".trim().ifBlank { email?.substringBefore("@") ?: "Apple User" }
            }
            email != null -> email.substringBefore("@")
            else -> "Apple User"
        }
    }
}
