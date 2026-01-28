package org.depromeet.team3.auth.application

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.util.TestDataFactory
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.application.login.CreateAppleUserService

@ExtendWith(MockitoExtension::class)
class CreateAppleUserServiceTest {

    @Mock
    private lateinit var userQueryRepository: UserQueryRepository

    @Mock
    private lateinit var userCommandRepository: UserCommandRepository

    @Mock
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var createAppleUserService: CreateAppleUserService

    @BeforeEach
    fun setUp() {
        createAppleUserService = CreateAppleUserService(
            userQueryRepository,
            userCommandRepository,
            jwtTokenProvider
        )
    }

    @Test
    fun `신규 사용자 저장 및 토큰 발급 성공`() {
        // given
        val email = "apple@example.com"
        val nickname = "ParkMineum"
        val socialId = "apple-social-id"
        
        val user = TestDataFactory.createUser(
            id = 1L,
            provider = AuthProvider.APPLE,
            socialId = socialId,
            email = email,
            nickname = nickname
        )

        whenever(userQueryRepository.findByProviderAndSocialId(AuthProvider.APPLE, socialId)).thenReturn(null)
        whenever(userQueryRepository.findByEmail(email)).thenReturn(null)
        whenever(userCommandRepository.save(any())).thenReturn(user)
        whenever(jwtTokenProvider.generateAccessToken(any(), anyOrNull(), any())).thenReturn("access-token")
        whenever(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-token")

        // when
        val result = createAppleUserService.saveUserAndGenerateTokens(email, nickname, null, socialId)

        // then
        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.refreshToken).isEqualTo("refresh-token")
        assertThat(result.userProfile.email).isEqualTo(email)

        verify(userCommandRepository, times(2)).save(any()) // 1: 생성, 2: 토큰 업데이트
    }

    @Test
    fun `기존 사용자가 있는 경우 조회하여 토큰 발급`() {
        // given
        val email = "apple@example.com"
        val socialId = "apple-social-id"
        val existingUser = TestDataFactory.createUser(
            id = 1L,
            provider = AuthProvider.APPLE,
            socialId = socialId,
            email = email
        )

        whenever(userQueryRepository.findByProviderAndSocialId(AuthProvider.APPLE, socialId)).thenReturn(existingUser)
        whenever(jwtTokenProvider.generateAccessToken(any(), anyOrNull(), any())).thenReturn("access-token")
        whenever(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-token")

        // when
        val result = createAppleUserService.saveUserAndGenerateTokens(email, "NewNickname", null, socialId)

        // then
        assertThat(result.accessToken).isEqualTo("access-token")
        verify(userCommandRepository, times(1)).save(any()) // 토큰 업데이트만 수행
        verify(userCommandRepository, never()).save(argThat { id == null }) // 신규 생성은 안 함
    }
}
