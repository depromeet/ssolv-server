package org.depromeet.team3.auth.application.login

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.dto.UserProfileResponse
import org.depromeet.team3.auth.properties.DemoProperties
import org.depromeet.team3.common.util.withTracingContext
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class DemoLoginService(
    private val demoProperties: DemoProperties,
    private val userJpaRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val transactionTemplate: TransactionTemplate,
) {

    suspend fun login(): LoginResponse = withTracingContext() {
        transactionTemplate.execute {
            val userEntity = userJpaRepository.findByProviderAndSocialId(AuthProvider.DEMO, "demo")
                ?: userJpaRepository.save(
                    UserEntity(
                        provider = AuthProvider.DEMO,
                        socialId = "demo",
                        email = demoProperties.email,
                        nickname = demoProperties.nickname,
                        profileImage = null,
                        refreshToken = null
                    )
                )

            val userId = userEntity.id!!
            val accessToken = jwtTokenProvider.generateAccessToken(userId, userEntity.email)
            val refreshToken = jwtTokenProvider.generateRefreshToken(userId)
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
