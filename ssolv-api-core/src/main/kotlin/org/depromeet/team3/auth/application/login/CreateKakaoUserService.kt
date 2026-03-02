package org.depromeet.team3.auth.application.login

import kotlinx.coroutines.withContext
import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.dto.UserProfileResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Service
class CreateKakaoUserService(
    private val userJpaRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend fun saveUserAndGenerateTokens(
        email: String?,
        nickname: String,
        profileImage: String?,
        socialId: String
    ): LoginResponse = withContext(coroutineDispatchers.VT) {
        val userEmail = email ?: "kakao_${socialId}@kakao.com"

        transactionTemplate.execute {
            // 1. 소셜 ID로 기존 회원 확인
            val existingBySocial = userJpaRepository.findByProviderAndSocialId(AuthProvider.KAKAO, socialId)

            val userEntity: UserEntity = if (existingBySocial != null) {
                existingBySocial
            } else {
                // 2. 이메일 중복 확인
                userJpaRepository.findByEmail(userEmail)?.let {
                    throw AuthException(
                        errorCode = ErrorCode.ALREADY_REGISTERED_WITH_OTHER_LOGIN,
                        detail = mapOf("provider" to it.provider.name)
                    )
                }
                // 3. 신규 회원 생성
                userJpaRepository.save(
                    UserEntity(
                        provider = AuthProvider.KAKAO,
                        socialId = socialId,
                        email = userEmail,
                        nickname = nickname,
                        profileImage = profileImage,
                        refreshToken = null
                    )
                )
            }

            // 4. 토큰 발급 및 프로필 업데이트
            val userId = userEntity.id!!
            val accessToken = jwtTokenProvider.generateAccessToken(userId, userEntity.email)
            val refreshToken = jwtTokenProvider.generateRefreshToken(userId)

            if (profileImage != null && profileImage != userEntity.profileImage) {
                userEntity.profileImage = profileImage
            }
            userEntity.refreshToken = refreshToken
            userJpaRepository.save(userEntity)

            LoginResponse(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userProfile = UserProfileResponse(
                    email = userEntity.email,
                    nickname = userEntity.nickname,
                    profileImage = userEntity.profileImage
                )
            )
        }!!
    }
}

