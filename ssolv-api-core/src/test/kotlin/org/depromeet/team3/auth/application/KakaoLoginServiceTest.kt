package org.depromeet.team3.auth.application

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.auth.application.login.CreateKakaoUserService
import org.depromeet.team3.auth.application.login.KakaoLoginService
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.command.KakaoLoginCommand
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.dto.UserProfileResponse
import org.depromeet.team3.auth.properties.KakaoProperties
import org.depromeet.team3.fixture.UserFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.*

@UnitTest
class KakaoLoginServiceTest {

    @Mock private lateinit var kakaoOAuthClient: KakaoOAuthClient
    @Mock private lateinit var createKakaoUserService: CreateKakaoUserService

    private lateinit var kakaoLoginService: KakaoLoginService

    @BeforeEach
    fun setUp() {
        val kakaoProperties = KakaoProperties().apply {
            redirectUri = "http://localhost:8080/login/oauth2/code/kakao"
        }
        kakaoLoginService = KakaoLoginService(kakaoOAuthClient, createKakaoUserService, kakaoProperties)
    }

    @Test
    fun `카카오 로그인 성공 - 신규 사용자`() = runTest {
        // given
        val command = KakaoLoginCommand(authorizationCode = "test-code")
        val kakaoProfile = UserFixture.createKakaoProfile()
        val oAuthToken = UserFixture.createOAuthToken()
        val loginResponse = LoginResponse(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            userProfile = UserProfileResponse(
                email = "test@example.com",
                nickname = "테스트사용자",
                profileImage = "http://example.com/profile.jpg"
            )
        )

        kakaoOAuthClient.stub {
            onBlocking { requestToken(any(), any()) }.doReturn(oAuthToken)
            onBlocking { requestProfile(any()) }.doReturn(kakaoProfile)
        }
        whenever(createKakaoUserService.saveUserAndGenerateTokens(
            email = "test@example.com",
            nickname = "테스트사용자",
            profileImage = "http://example.com/profile.jpg",
            socialId = "12345"
        )).thenReturn(loginResponse)

        // when
        val result = kakaoLoginService.login(command)

        // then
        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.refreshToken).isEqualTo("refresh-token")
        assertThat(result.userProfile.email).isEqualTo("test@example.com")
        assertThat(result.userProfile.nickname).isEqualTo("테스트사용자")
        verify(createKakaoUserService).saveUserAndGenerateTokens(
            email = "test@example.com",
            nickname = "테스트사용자",
            profileImage = "http://example.com/profile.jpg",
            socialId = "12345"
        )
    }

    @Test
    fun `카카오 로그인 성공 - 기존 사용자`() = runTest {
        // given
        val command = KakaoLoginCommand(authorizationCode = "test-code")
        val kakaoProfile = UserFixture.createKakaoProfile()
        val oAuthToken = UserFixture.createOAuthToken()
        val loginResponse = LoginResponse(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            userProfile = UserProfileResponse(
                email = "test@example.com",
                nickname = "기존사용자",
                profileImage = "http://example.com/profile.jpg"
            )
        )

        kakaoOAuthClient.stub {
            onBlocking { requestToken(any(), any()) }.doReturn(oAuthToken)
            onBlocking { requestProfile(any()) }.doReturn(kakaoProfile)
        }
        whenever(createKakaoUserService.saveUserAndGenerateTokens(
            email = "test@example.com",
            nickname = "테스트사용자",
            profileImage = "http://example.com/profile.jpg",
            socialId = "12345"
        )).thenReturn(loginResponse)

        // when
        val result = kakaoLoginService.login(command)

        // then
        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.userProfile.email).isEqualTo("test@example.com")
        verify(createKakaoUserService).saveUserAndGenerateTokens(
            email = "test@example.com",
            nickname = "테스트사용자",
            profileImage = "http://example.com/profile.jpg",
            socialId = "12345"
        )
    }
}
