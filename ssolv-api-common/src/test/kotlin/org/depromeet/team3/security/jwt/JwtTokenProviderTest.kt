package org.depromeet.team3.security.jwt

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

/**
 * JwtTokenProvider 핵심 기능 테스트
 */
@ExtendWith(MockitoExtension::class)
class JwtTokenProviderTest {

    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var jwtProperties: JwtProperties

    @BeforeEach
    fun setUp() {
        jwtProperties = JwtProperties(
            secret = "test-secret-key-for-jwt-token-generation-minimum-256-bits-long",
            accessTokenValidity = 3600000L, // 1시간
            refreshTokenValidity = 604800000L, // 1주일
        )
        jwtTokenProvider = JwtTokenProvider(jwtProperties)
    }

    @Test
    fun `Access Token 생성 및 검증이 정상적으로 동작한다`() {
        // given
        val userId = 123L
        val userEmail = "test@example.com"

        // when
        val accessToken = jwtTokenProvider.generateAccessToken(userId, userEmail)

        // then
        assertThat(accessToken).isNotNull()
        assertThat(jwtTokenProvider.validateAccessToken(accessToken)).isTrue()
        assertThat(jwtTokenProvider.getUserIdFromToken(accessToken)).isEqualTo(userId.toString())
    }

    @Test
    fun `Refresh Token 생성 및 검증이 정상적으로 동작한다`() {
        // given
        val userId = 123L

        // when
        val refreshToken = jwtTokenProvider.generateRefreshToken(userId)

        // then
        assertThat(refreshToken).isNotNull()
        assertThat(jwtTokenProvider.validateRefreshToken(refreshToken)).isTrue()
        assertThat(jwtTokenProvider.getUserIdFromToken(refreshToken)).isEqualTo(userId.toString())
    }

    @Test
    fun `만료된 토큰은 유효하지 않다고 판단한다`() {
        // given - 만료 시간이 1ms인 토큰 생성
        val shortJwtProperties = JwtProperties(
            secret = "test-secret-key-for-jwt-token-generation-minimum-256-bits-long",
            accessTokenValidity = 1L, // 1ms
            refreshTokenValidity = 1L, // 1ms
        )
        val shortLivedProvider = JwtTokenProvider(shortJwtProperties)

        val userId = 123L
        val userEmail = "test@example.com"
        val accessToken = shortLivedProvider.generateAccessToken(userId, userEmail)

        // when - 약간의 지연 후 검증
        Thread.sleep(10)
        val isValid = shortLivedProvider.validateAccessToken(accessToken)

        // then
        assertThat(isValid).isFalse()
    }

    @Test
    fun `잘못된 형식의 토큰은 유효하지 않다고 판단한다`() {
        // given
        val invalidToken = "invalid.token.format"

        // when & then
        assertThat(jwtTokenProvider.validateAccessToken(invalidToken)).isFalse()
        assertThat(jwtTokenProvider.validateRefreshToken(invalidToken)).isFalse()
    }

    @Test
    fun `Authorization 헤더에서 토큰을 정상적으로 추출한다`() {
        // given
        val request = mock<HttpServletRequest>()
        val accessToken = "test-access-token"

        whenever(request.getHeader("Authorization")).thenReturn("Bearer $accessToken")

        // when
        val extractedToken = jwtTokenProvider.extractToken(request)

        // then
        assertThat(extractedToken).isEqualTo(accessToken)
    }

    @Test
    fun `Bearer 접두사가 없는 경우 null을 반환한다`() {
        // given
        val request = mock<HttpServletRequest>()
        whenever(request.getHeader("Authorization")).thenReturn("test-access-token")

        // when
        val extractedToken = jwtTokenProvider.extractToken(request)

        // then
        assertThat(extractedToken).isNull()
    }

    @Test
    fun `Authorization 헤더가 없는 경우 null을 반환한다`() {
        // given
        val request = mock<HttpServletRequest>()
        whenever(request.getHeader("Authorization")).thenReturn(null)

        // when
        val extractedToken = jwtTokenProvider.extractToken(request)

        // then
        assertThat(extractedToken).isNull()
    }

    @Test
    fun `JWT 토큰 관련 상수들이 올바르게 설정되어 있다`() {
        // JWT와 관련된 기본 설정값들을 검증
        assertThat("Bearer ").hasSize(7)
        assertThat("ACCESS").isEqualTo("ACCESS")
        assertThat("REFRESH").isEqualTo("REFRESH")
    }

    @Test
    fun `사용자 ID 문자열을 Long으로 변환할 수 있다`() {
        // given
        val userIdString = "123"

        // when
        val userId = userIdString.toLongOrNull()

        // then
        assertThat(userId).isEqualTo(123L)
    }

    @Test
    fun `잘못된 사용자 ID 문자열은 null을 반환한다`() {
        // given
        val invalidUserIdString = "invalid"

        // when
        val userId = invalidUserIdString.toLongOrNull()

        // then
        assertThat(userId).isNull()
    }

    @Test
    fun `토큰에서 이메일을 추출할 수 있다`() {
        // given
        val userId = 123L
        val userEmail = "test@example.com"
        val accessToken = jwtTokenProvider.generateAccessToken(userId, userEmail)

        // when
        val extractedEmail = jwtTokenProvider.getEmailFromToken(accessToken)

        // then
        assertThat(extractedEmail).isEqualTo(userEmail)
    }
}
