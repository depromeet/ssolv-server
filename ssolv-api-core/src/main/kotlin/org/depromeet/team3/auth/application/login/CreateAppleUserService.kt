package org.depromeet.team3.auth.application.login
import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.dto.UserProfileResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.withTracingContext
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 애플 로그인 Save Service
 * withContext(VT) + transactionTemplate.execute 로 트랜잭션 경계를 동일 스레드에 고정.
 */
@Service
class CreateAppleUserService(
    private val userJpaRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val transactionTemplate: TransactionTemplate,
) {

    suspend fun saveUserAndGenerateTokens(email: String?, nickname: String, profileImage: String?, socialId: String): LoginResponse =
        withTracingContext {
            transactionTemplate.execute {
                // 1. 소셜 ID로 기존 회원 확인
                val existingBySocial = userJpaRepository.findByProviderAndSocialId(AuthProvider.APPLE, socialId)

                val userEntity: UserEntity = if (existingBySocial != null) {
                    existingBySocial
                } else {
                    // 2. 닉네임 중복 확인
                    userJpaRepository.findByNickname(nickname)?.let {
                        throw AuthException(
                            errorCode = ErrorCode.DUPLICATE_NICKNAME,
                            detail = mapOf("nickname" to nickname),
                        )
                    }

                    // 3. 이메일 중복 확인
                    if (email != null) {
                        userJpaRepository.findByEmail(email)?.let {
                            throw AuthException(
                                errorCode = ErrorCode.ALREADY_REGISTERED_WITH_OTHER_LOGIN,
                                detail = mapOf("provider" to it.provider.name),
                            )
                        }
                    }
                    // 3. 신규 회원 생성
                    userJpaRepository.save(
                        UserEntity(
                            provider = AuthProvider.APPLE,
                            socialId = socialId,
                            email = email ?: "apple_$socialId@privaterelay.appleid.com",
                            nickname = nickname,
                            profileImage = profileImage,
                            refreshToken = null,
                        ),
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
                        profileImage = userEntity.profileImage,
                    ),
                )
            }
                ?: throw org.depromeet.team3.auth.exception.AuthException(
                    org.depromeet.team3.common.exception.ErrorCode.INTERNAL_SERVER_ERROR,
                )
        }
}
