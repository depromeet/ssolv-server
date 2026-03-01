package org.depromeet.team3.auth.application.login

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.User
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.dto.UserProfileResponse
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class CreateKakaoUserService(
    private val userQueryRepository: UserQueryRepository,
    private val userCommandRepository: UserCommandRepository,
    private val jwtTokenProvider: JwtTokenProvider
) {

    @Transactional(rollbackFor = [Exception::class])
    suspend fun saveUserAndGenerateTokens(
        email: String?,
        nickname: String,
        profileImage: String?,
        socialId: String
    ): LoginResponse {
        val userEmail = email ?: "kakao_${socialId}@kakao.com"
        val user = findOrCreateUser(userEmail, nickname, profileImage, socialId)
        val tokens = generateAuthenticationTokens(user, profileImage)

        return LoginResponse(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            userProfile = UserProfileResponse(
                email = user.email,
                nickname = user.nickname,
                profileImage = tokens.updatedUserProfile?.profileImage
            )
        )
    }

    private suspend fun findOrCreateUser(
        email: String,
        nickname: String,
        profileImage: String?,
        socialId: String
    ): User {
        val existingUser = userQueryRepository.findByProviderAndSocialId(AuthProvider.KAKAO, socialId)

        return existingUser ?: createNewUser(email, nickname, profileImage, socialId)
    }

    private suspend fun createNewUser(
        email: String,
        nickname: String,
        profileImage: String?,
        socialId: String
    ): User {
        val newUser = User(
            id = null,
            provider = AuthProvider.KAKAO,
            socialId = socialId,
            email = email,
            nickname = nickname,
            profileImage = profileImage,
            refreshToken = null,
            createdAt = LocalDateTime.now(),
            updatedAt = null
        )
        return userCommandRepository.save(newUser)
    }

    private suspend fun generateAuthenticationTokens(user: User, newProfileImage: String?): AuthTokens {
        val userId = user.id!!
        val accessToken = jwtTokenProvider.generateAccessToken(userId, user.email)
        val refreshToken = jwtTokenProvider.generateRefreshToken(userId)

        val shouldUpdateProfile = newProfileImage != null && newProfileImage != user.profileImage
        val base = if (shouldUpdateProfile) user.copy(profileImage = newProfileImage) else user
        val updatedUser = base.copy(
            refreshToken = refreshToken,
            updatedAt = LocalDateTime.now()
        )

        userCommandRepository.save(updatedUser)

        return AuthTokens(accessToken, refreshToken, updatedUser)
    }

    private data class AuthTokens(
        val accessToken: String,
        val refreshToken: String,
        val updatedUserProfile: User? = null
    )
}
