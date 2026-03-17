package org.depromeet.team3.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByEmail(email: String): UserEntity?
    fun findByNickname(nickname: String): UserEntity?
    fun findByProviderAndSocialId(provider: AuthProvider, socialId: String): UserEntity?
}