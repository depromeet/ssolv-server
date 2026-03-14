package org.depromeet.team3.meetingplace

import org.depromeet.team3.common.BaseTimeDomain
import java.time.LocalDateTime

data class MeetingPlace(
    val id: Long? = null,
    val meetingId: Long,
    val placeId: Long,
    override val createdAt: LocalDateTime? = null,
    override val updatedAt: LocalDateTime? = null,
) : BaseTimeDomain(createdAt, updatedAt)
