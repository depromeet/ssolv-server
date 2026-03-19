package org.depromeet.team3.auth.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.application.login.CreateAppleUserService
import org.depromeet.team3.common.util.TestEntityFactory
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionTemplate

@ExtendWith(MockitoExtension::class)
class CreateAppleUserServiceTest {

    @Mock private lateinit var userJpaRepository: UserRepository
    @Mock private lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var createAppleUserService: CreateAppleUserService

    @BeforeEach
    fun setUp() {
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(org.mockito.kotlin.mock())
        }
        createAppleUserService = CreateAppleUserService(
            userJpaRepository, jwtTokenProvider, transactionTemplate
        )
    }

    @Test
    fun `신규 사용자 저장 및 토큰 발급 성공`() = runBlocking {
        val email = "apple@example.com"
        val nickname = "ParkMineum"
        val socialId = "apple-social-id"
        val userEntity = TestEntityFactory.createUserEntity(
            id = 1L,
            provider = AuthProvider.APPLE,
            socialId = socialId,
            email = email,
            nickname = nickname
        )

        whenever(userJpaRepository.findByProviderAndSocialId(AuthProvider.APPLE, socialId)).thenReturn(null)
        whenever(userJpaRepository.findByEmail(email)).thenReturn(null)
        whenever(userJpaRepository.save(any())).thenReturn(userEntity)
        whenever(jwtTokenProvider.generateAccessToken(any(), anyOrNull())).thenReturn("access-token")
        whenever(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-token")

        val result = createAppleUserService.saveUserAndGenerateTokens(email, nickname, null, socialId)

        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.refreshToken).isEqualTo("refresh-token")
        assertThat(result.userProfile.email).isEqualTo(email)
        // 신규 생성(save) + 토큰 업데이트용 save = 2회
        verify(userJpaRepository, times(2)).save(any())
    }

    @Test
    fun `기존 사용자가 있는 경우 조회하여 토큰 발급`() = runBlocking {
        val email = "apple@example.com"
        val socialId = "apple-social-id"
        val existingUser = TestEntityFactory.createUserEntity(
            id = 1L,
            provider = AuthProvider.APPLE,
            socialId = socialId,
            email = email
        )

        whenever(userJpaRepository.findByProviderAndSocialId(AuthProvider.APPLE, socialId)).thenReturn(existingUser)
        whenever(jwtTokenProvider.generateAccessToken(any(), anyOrNull())).thenReturn("access-token")
        whenever(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-token")
        whenever(userJpaRepository.save(any())).thenReturn(existingUser)

        val result = createAppleUserService.saveUserAndGenerateTokens(email, "NewNickname", null, socialId)

        assertThat(result.accessToken).isEqualTo("access-token")
        // 토큰 업데이트용 save만 1회 (신규 생성 없음)
        verify(userJpaRepository, times(1)).save(any())
    }
}
