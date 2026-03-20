package org.depromeet.team3.auth.application.login
import kotlinx.coroutines.Dispatchers
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.command.KakaoLoginCommand
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.properties.KakaoProperties
import org.depromeet.team3.common.exception.ErrorCode
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

/**
 * 카카오 로그인 Orchestrator
 */
@Service
class KakaoLoginService(
    private val kakaoOAuthClient: KakaoOAuthClient,
    private val createKakaoUserService: CreateKakaoUserService,
    private val kakaoProperties: KakaoProperties,
) {

    suspend fun login(command: KakaoLoginCommand): LoginResponse = withContext(Dispatchers.IO) {
        val code = command.authorizationCode
        val redirectUri = command.redirectUri ?: getDefaultRedirectUri()

        // 1. 카카오 OAuth 토큰 요청 및 프로필 조회
        val oAuthToken = kakaoOAuthClient.requestToken(code, redirectUri)
        val kakaoProfile = kakaoOAuthClient.requestProfile(oAuthToken)

        // 2. 카카오 프로필 정보 추출
        val socialId = kakaoProfile.id.toString()
        val email = kakaoProfile.kakao_account.email
        val nickname = kakaoProfile.kakao_account.profile.nickname
        val profileImage = kakaoProfile.kakao_account.profile.profile_image_url

        // 3. DB 작업 위임 (자체적으로 IO 디스패처/VT를 사용할 수 있지만 여기서는 순차 실행)
        createKakaoUserService.saveUserAndGenerateTokens(email, nickname, profileImage, socialId)
    }
    
    private fun getDefaultRedirectUri(): String {
        return when {
            kakaoProperties.redirectUris.isNotEmpty() -> kakaoProperties.redirectUris.first()
            !kakaoProperties.redirectUri.isNullOrBlank() -> kakaoProperties.redirectUri
            else -> throw AuthException(errorCode = ErrorCode.KAKAO_REDIRECT_URI_NOT_CONFIGURED)
        }
    }
}
