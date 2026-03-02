package org.depromeet.team3.auth

/**
 * User Command Repository
 * 쓰기 작업만 담당
 */
interface UserCommandRepository {
    suspend fun save(user: User): User
    suspend fun delete(user: User)
}
