package org.depromeet.team3.auth.controller

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.auth.application.common.LogoutService
import org.depromeet.team3.auth.application.common.WithdrawService
import org.depromeet.team3.auth.application.login.AppleOAuthService
import org.depromeet.team3.auth.application.login.DemoLoginService
import org.depromeet.team3.auth.application.login.KakaoLoginService
import org.depromeet.team3.auth.application.token.UpdateTokenService
import org.depromeet.team3.auth.command.RefreshTokenCommand
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.dto.LogoutResponse
import org.depromeet.team3.auth.dto.RefreshTokenRequest
import org.depromeet.team3.auth.dto.TokenResponse
import org.depromeet.team3.auth.dto.UserProfileResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.exception.GlobalExceptionHandler
import org.depromeet.team3.config.TestUserIdArgumentResolver
import org.junit.jupiter.api.Test
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.kotlin.*
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@UnitTest
class AuthControllerTest {

    @Mock private lateinit var kakaoLoginService: KakaoLoginService

    @Mock private lateinit var appleOAuthService: AppleOAuthService

    @Mock private lateinit var demoLoginService: DemoLoginService

    @Mock private lateinit var updateTokenService: UpdateTokenService

    @Mock private lateinit var logoutService: LogoutService

    @Mock private lateinit var withdrawService: WithdrawService

    @InjectMocks private lateinit var authController: AuthController

    private val objectMapper = ObjectMapper()
    private val testUserIdArgumentResolver = TestUserIdArgumentResolver()

    private val mockMvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(authController)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(testUserIdArgumentResolver)
            .build()
    }

    @Test
    fun `카카오 로그인 성공 - 200 응답`() = runTest {
        // given
        val loginResponse = LoginResponse(
            accessToken = "access-token-123",
            refreshToken = "refresh-token-456",
            userProfile = UserProfileResponse(
                email = "test@example.com",
                nickname = "테스트사용자",
                profileImage = "https://example.com/profile.jpg",
            ),
        )
        kakaoLoginService.stub {
            onBlocking { login(any()) }.doReturn(loginResponse)
        }

        // when
        val mvcResult = mockMvc.perform(
            get("/api/v1/auth/kakao-login")
                .param("code", "test-auth-code")
                .contentType(MediaType.APPLICATION_JSON),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").value("access-token-123"))
            .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-456"))
            .andExpect(jsonPath("$.data.userProfile.email").value("test@example.com"))
    }

    @Test
    fun `카카오 로그인 실패 - 400 에러 (코드 누락)`() {
        mockMvc.perform(
            get("/api/v1/auth/kakao-login").contentType(MediaType.APPLICATION_JSON),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `토큰 재발급 성공 - 200 응답`() = runTest {
        // given
        val tokenResponse = TokenResponse(accessToken = "new-access-token", refreshToken = "new-refresh-token")
        updateTokenService.stub {
            onBlocking { refresh(any<RefreshTokenCommand>()) }.doReturn(tokenResponse)
        }

        // when
        val mvcResult = mockMvc.perform(
            post("/api/v1/auth/reissue-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken = "valid-refresh-token"))),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
            .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"))
    }

    @Test
    fun `토큰 재발급 실패 - 401 에러 (Refresh Token 유효하지 않음)`() = runTest {
        // given
        updateTokenService.stub {
            onBlocking { refresh(any<RefreshTokenCommand>()) }.doThrow(AuthException(ErrorCode.REFRESH_TOKEN_INVALID))
        }

        // when
        val mvcResult = mockMvc.perform(
            post("/api/v1/auth/reissue-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken = "invalid-refresh-token"))),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("J008"))
    }

    @Test
    fun `토큰 재발급 실패 - 401 에러 (토큰 사용자 ID 유효하지 않음)`() = runTest {
        updateTokenService.stub {
            onBlocking { refresh(any<RefreshTokenCommand>()) }.doThrow(AuthException(ErrorCode.TOKEN_USER_ID_INVALID))
        }
        val mvcResult = mockMvc.perform(
            post("/api/v1/auth/reissue-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken = "valid-refresh-token"))),
        ).andExpect(request().asyncStarted()).andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("J012"))
    }

    @Test
    fun `토큰 재발급 실패 - 404 에러 (사용자 정보 없음)`() = runTest {
        updateTokenService.stub {
            onBlocking { refresh(any<RefreshTokenCommand>()) }.doThrow(AuthException(ErrorCode.USER_NOT_FOUND_FOR_TOKEN))
        }
        val mvcResult = mockMvc.perform(
            post("/api/v1/auth/reissue-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken = "valid-refresh-token"))),
        ).andExpect(request().asyncStarted()).andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("J011"))
    }

    @Test
    fun `토큰 재발급 실패 - 401 에러 (Refresh Token 불일치)`() = runTest {
        updateTokenService.stub {
            onBlocking { refresh(any<RefreshTokenCommand>()) }.doThrow(AuthException(ErrorCode.REFRESH_TOKEN_MISMATCH))
        }
        val mvcResult = mockMvc.perform(
            post("/api/v1/auth/reissue-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken = "different-refresh-token"))),
        ).andExpect(request().asyncStarted()).andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("J010"))
    }

    @Test
    fun `서버 내부 오류 - 500 에러`() = runTest {
        updateTokenService.stub {
            onBlocking { refresh(any<RefreshTokenCommand>()) }.doThrow(RuntimeException("서버 오류"))
        }
        val mvcResult = mockMvc.perform(
            post("/api/v1/auth/reissue-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken = "valid-refresh-token"))),
        ).andExpect(request().asyncStarted()).andReturn()

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isInternalServerError)
    }

    @Test
    fun `로그아웃 성공 - 200 응답`() = runTest {
        // given
        val userId = 1L
        testUserIdArgumentResolver.setTestUserId(userId)
        logoutService.stub {
            onBlocking { logout(userId) }.thenReturn(LogoutResponse(kakaoLogoutUrl = null))
        }

        // when
        val mvcResult = mockMvc.perform(
            post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON),
        ).andExpect(request().asyncStarted()).andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk)
        verify(logoutService).logout(userId)
    }

    @Test
    fun `회원 탈퇴 성공 - 200 응답`() = runTest {
        // given
        val userId = 1L
        testUserIdArgumentResolver.setTestUserId(userId)
        withdrawService.stub {
            onBlocking { withdraw(userId) }.doAnswer { }
        }

        // when
        val mvcResult = mockMvc.perform(
            delete("/api/v1/auth/withdraw").contentType(MediaType.APPLICATION_JSON),
        ).andExpect(request().asyncStarted()).andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk)
    }

    @Test
    fun `회원 탈퇴 실패 - 404 응답 (사용자 없음)`() = runTest {
        // given
        val userId = 1L
        testUserIdArgumentResolver.setTestUserId(userId)
        withdrawService.stub {
            onBlocking { withdraw(any()) }.doAnswer { throw AuthException(ErrorCode.USER_NOT_FOUND) }
        }

        // when
        val mvcResult = mockMvc.perform(
            delete("/api/v1/auth/withdraw").contentType(MediaType.APPLICATION_JSON),
        ).andExpect(request().asyncStarted()).andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("C4051"))
    }
}
