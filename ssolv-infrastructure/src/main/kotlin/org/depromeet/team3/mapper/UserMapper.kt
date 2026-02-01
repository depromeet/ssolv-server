package org.depromeet.team3.mapper

import org.depromeet.team3.auth.User
import org.depromeet.team3.auth.UserEntity
import org.springframework.stereotype.Component

@Component
class UserMapper : DomainMapper<User, UserEntity> {

    override fun toDomain(entity: UserEntity): User {
        return User(
            id = entity.id,
            provider = entity.provider,
            socialId = entity.socialId,
            email = entity.email,
            nickname = entity.nickname,
            profileImage = entity.profileImage,
            refreshToken = entity.refreshToken,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            deletedAt = entity.deletedAt
        )
    }

    override fun toEntity(domain: User): UserEntity {
        return UserEntity(
            id = domain.id,
            provider = domain.provider,
            socialId = domain.socialId,
            email = domain.email,
            profileImage = domain.profileImage,
            refreshToken = domain.refreshToken,
            nickname = domain.nickname,
            deletedAt = domain.deletedAt
        )
        // Note: createdAt and updatedAt are now managed by JPA auditing
        // Do not manually set these fields to prevent audit integrity issues
    }
}
