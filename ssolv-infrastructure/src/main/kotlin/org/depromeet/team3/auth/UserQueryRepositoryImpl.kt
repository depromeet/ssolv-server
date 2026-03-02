package org.depromeet.team3.auth

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
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
    private val coroutineDispatchers: CoroutineDispatchers
) : UserQueryRepository {

    override suspend fun findById(id: Long): User? = withContext(coroutineDispatchers.VT) {
        userJpaRepository.findById(id)
            .map { userMapper.toDomain(it) }
            .orElse(null)
    }

    override suspend fun findByEmail(email: String): User? = withContext(coroutineDispatchers.VT) {
        userJpaRepository.findByEmail(email)
            ?.let { userMapper.toDomain(it) }
    }

    override suspend fun existsByEmail(email: String): Boolean = withContext(coroutineDispatchers.VT) {
        userJpaRepository.findByEmail(email) != null
    }

    override suspend fun findByProviderAndSocialId(provider: AuthProvider, socialId: String): User? = withContext(coroutineDispatchers.VT) {
        userJpaRepository.findByProviderAndSocialId(provider, socialId)
            ?.let { userMapper.toDomain(it) }
    }
}
