package org.depromeet.team3.auth

/**
 * User Query Repository
 * 읽기 작업만 담당
 */
interface UserQueryRepository {
    fun findById(id: Long): User?
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean
    fun findByProviderAndSocialId(provider: AuthProvider, socialId: String): User?
}
