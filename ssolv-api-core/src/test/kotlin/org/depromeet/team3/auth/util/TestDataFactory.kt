package org.depromeet.team3.auth.util

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.User
import org.depromeet.team3.auth.model.KakaoResponse
import java.time.LocalDateTime

/**
 * 테스트용 데이터 팩토리 클래스
 */
object TestDataFactory {

    fun createKakaoProfile(
        id: Long = 12345L,
        email: String = "test@example.com",
        nickname: String = "테스트사용자",
        profileImageUrl: String? = "http://example.com/profile.jpg"
    ): KakaoResponse.KakaoProfile {
        return KakaoResponse.KakaoProfile(
            id = id,
            kakao_account = KakaoResponse.KakaoAccount(
                email = email,
                profile = KakaoResponse.Profile(
                    nickname = nickname,
                    profile_image_url = profileImageUrl
                )
            )
        )
    }

    fun createOAuthToken(
        accessToken: String = "test-access-token"
    ): KakaoResponse.OAuthToken {
        return KakaoResponse.OAuthToken(
            access_token = accessToken
        )
    }

    fun createUser(
        id: Long? = null,
        provider: AuthProvider = AuthProvider.KAKAO,
        socialId: String = "12345",
        email: String = "test@example.com",
        nickname: String = "테스트사용자",
        profileImage: String? = "http://example.com/profile.jpg",
        refreshToken: String? = null,
        createdAt: LocalDateTime = LocalDateTime.now(),
        updatedAt: LocalDateTime? = null
    ): User {
        return User(
            id = id,
            provider = provider,
            socialId = socialId,
            email = email,
            nickname = nickname,
            profileImage = profileImage,
            refreshToken = refreshToken,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
