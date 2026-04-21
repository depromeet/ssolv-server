package org.depromeet.team3.meetingattendee

import org.depromeet.team3.common.BaseTimeDomain
import java.time.LocalDateTime

data class MeetingAttendee(
    val id: Long? = null,
    val meetingId: Long,
    val userId: Long,
    var attendeeNickname: String?,
    var muzziColor: MuzziColor,
    override val createdAt: LocalDateTime? = null,
    override val updatedAt: LocalDateTime? = null,
) : BaseTimeDomain(createdAt, updatedAt)
