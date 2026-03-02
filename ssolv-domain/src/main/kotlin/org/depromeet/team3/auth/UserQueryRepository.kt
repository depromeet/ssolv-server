package org.depromeet.team3.auth

/**
 * User Query Repository
 * 읽기 작업만 담당
 */
interface UserQueryRepository {
    suspend fun findById(id: Long): User?
    suspend fun findByEmail(email: String): User?
    suspend fun existsByEmail(email: String): Boolean
    suspend fun findByProviderAndSocialId(provider: AuthProvider, socialId: String): User?
}
