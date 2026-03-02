package org.depromeet.team3.auth

import org.depromeet.team3.mapper.UserMapper
import org.springframework.stereotype.Repository

/**
 * User Query Repository 구현체
 * 읽기 작업만 처리
 */
@Repository
class UserQueryRepositoryImpl(
    private val userJpaRepository: UserRepository,
    private val userMapper: UserMapper,
) : UserQueryRepository {

    override suspend fun findById(id: Long): User? {
        return userJpaRepository.findById(id)
            .map { userMapper.toDomain(it) }
            .orElse(null)
    }

    override suspend fun findByEmail(email: String): User? {
        return userJpaRepository.findByEmail(email)
            ?.let { userMapper.toDomain(it) }
    }

    override suspend fun existsByEmail(email: String): Boolean {
        return userJpaRepository.findByEmail(email) != null
    }

    override suspend fun findByProviderAndSocialId(provider: AuthProvider, socialId: String): User? {
        return userJpaRepository.findByProviderAndSocialId(provider, socialId)
            ?.let { userMapper.toDomain(it) }
    }
}
