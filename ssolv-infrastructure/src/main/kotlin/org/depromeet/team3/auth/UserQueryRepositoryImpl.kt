package org.depromeet.team3.auth

import org.depromeet.team3.mapper.UserMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * User Query Repository 구현체
 * 읽기 작업만 처리
 */
@Repository
@Transactional(readOnly = true)
class UserQueryRepositoryImpl(
    private val userJpaRepository: UserRepository,
    private val userMapper: UserMapper
) : UserQueryRepository {

    override fun findById(id: Long): User? {
        return userJpaRepository.findById(id)
            .map { userMapper.toDomain(it) }
            .orElse(null)
    }

    override fun findByEmail(email: String): User? {
        return userJpaRepository.findByEmail(email)
            ?.let { userMapper.toDomain(it) }
    }

    override fun existsByEmail(email: String): Boolean {
        return userJpaRepository.findByEmail(email) != null
    }

    override fun findByProviderAndSocialId(provider: AuthProvider, socialId: String): User? {
        return userJpaRepository.findByProviderAndSocialId(provider, socialId)
            ?.let { userMapper.toDomain(it) }
    }
}
