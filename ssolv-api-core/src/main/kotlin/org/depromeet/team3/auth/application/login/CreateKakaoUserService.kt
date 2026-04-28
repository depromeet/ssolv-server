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
import kotlin.random.Random

@Service
class CreateKakaoUserService(
    private val userJpaRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val transactionTemplate: TransactionTemplate,
) {

    suspend fun saveUserAndGenerateTokens(email: String?, nickname: String, profileImage: String?, socialId: String): LoginResponse =
        withTracingContext {
            val userEmail = email ?: "kakao_$socialId@kakao.com"

            transactionTemplate.execute {
                // 1. 소셜 ID로 기존 회원 확인
                val existingBySocial = userJpaRepository.findByProviderAndSocialId(AuthProvider.KAKAO, socialId)

                val userEntity: UserEntity = if (existingBySocial != null) {
                    existingBySocial
                } else {
                    // 2. 이메일 중복 확인 (다른 로그인이면 중복 체크)
                    userJpaRepository.findByEmail(userEmail)?.let {
                        throw AuthException(
                            errorCode = ErrorCode.ALREADY_REGISTERED_WITH_OTHER_LOGIN,
                            detail = mapOf("provider" to it.provider.name),
                        )
                    }
                    // 3. 신규 회원 생성 (닉네임 충돌 시 suffix 자동 부여)
                    val uniqueNickname = resolveUniqueNickname(nickname)
                    userJpaRepository.save(
                        UserEntity(
                            provider = AuthProvider.KAKAO,
                            socialId = socialId,
                            email = userEmail,
                            nickname = uniqueNickname,
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
            }!!
        }

    private fun resolveUniqueNickname(base: String): String {
        if (userJpaRepository.findByNickname(base) == null) return base
        var candidate: String
        do { candidate = "$base${Random.nextInt(1000, 9999)}" } while (userJpaRepository.findByNickname(candidate) != null)
        return candidate
    }
}
