package org.depromeet.team3.fixture

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.User
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.auth.model.KakaoResponse
import java.time.LocalDateTime

object UserFixture {

    fun create(
        id: Long = 1L,
        provider: AuthProvider = AuthProvider.KAKAO,
        socialId: String = "kakao-123",
        email: String = "test@example.com",
        nickname: String = "테스트유저",
        profileImage: String? = null,
        refreshToken: String? = null,
        deletedAt: LocalDateTime? = null,
    ) = User(
        id = id,
        provider = provider,
        socialId = socialId,
        email = email,
        nickname = nickname,
        profileImage = profileImage,
        refreshToken = refreshToken,
        createdAt = LocalDateTime.now(),
        updatedAt = null,
        deletedAt = deletedAt,
    )

    fun createEntity(
        id: Long? = 1L,
        provider: AuthProvider = AuthProvider.KAKAO,
        socialId: String = "kakao-123",
        email: String = "test@example.com",
        nickname: String = "테스트유저",
        profileImage: String? = null,
        refreshToken: String? = null,
        deletedAt: LocalDateTime? = null,
    ) = UserEntity(
        id = id,
        provider = provider,
        socialId = socialId,
        email = email,
        nickname = nickname,
        profileImage = profileImage,
        refreshToken = refreshToken,
        deletedAt = deletedAt,
    )

    fun createEntityWithoutId(
        provider: AuthProvider = AuthProvider.KAKAO,
        socialId: String = "kakao-123",
        email: String = "test@example.com",
        nickname: String = "테스트유저",
        profileImage: String? = null,
        refreshToken: String? = null,
    ) = createEntity(
        id = null,
        provider = provider,
        socialId = socialId,
        email = email,
        nickname = nickname,
        profileImage = profileImage,
        refreshToken = refreshToken,
    )

    fun createKakaoProfile(
        id: Long = 12345L,
        email: String = "test@example.com",
        nickname: String = "테스트사용자",
        profileImageUrl: String? = "http://example.com/profile.jpg",
    ) = KakaoResponse.KakaoProfile(
        id = id,
        kakao_account = KakaoResponse.KakaoAccount(
            email = email,
            profile = KakaoResponse.Profile(
                nickname = nickname,
                profile_image_url = profileImageUrl,
            ),
        ),
    )

    fun createOAuthToken(accessToken: String = "test-access-token") = KakaoResponse.OAuthToken(access_token = accessToken)
}
