package org.depromeet.team3.auth

import org.depromeet.team3.common.BaseTimeDomain
import org.depromeet.team3.meeting.Meeting
import java.time.LocalDateTime

data class User(
    val id: Long? = null,
    val provider: AuthProvider,
    val socialId: String,
    val email: String,
    val nickname: String,
    var profileImage: String?,
    var refreshToken: String?,
    val meetings: MutableList<Meeting> = mutableListOf(),
    override val createdAt: LocalDateTime,
    override val updatedAt: LocalDateTime? = null,
    val deletedAt: LocalDateTime? = null,
) : BaseTimeDomain(createdAt, updatedAt)
