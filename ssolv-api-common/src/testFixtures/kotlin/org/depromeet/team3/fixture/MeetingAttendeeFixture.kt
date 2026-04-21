package org.depromeet.team3.fixture

import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity
import org.depromeet.team3.meetingattendee.MuzziColor

object MeetingAttendeeFixture {

    fun createEntity(
        id: Long? = 1L,
        meeting: MeetingEntity = MeetingFixture.createEntity(),
        user: UserEntity = UserFixture.createEntity(),
        attendeeNickname: String? = "참가자",
        muzziColor: MuzziColor = MuzziColor.DEFAULT,
    ) = MeetingAttendeeEntity(
        id = id,
        meeting = meeting,
        user = user,
        attendeeNickname = attendeeNickname,
        muzziColor = muzziColor,
    )

    fun createEntityWithoutId(
        meeting: MeetingEntity = MeetingFixture.createEntityWithoutId(),
        user: UserEntity = UserFixture.createEntityWithoutId(),
        attendeeNickname: String? = "참가자",
        muzziColor: MuzziColor = MuzziColor.DEFAULT,
    ) = createEntity(id = null, meeting = meeting, user = user, attendeeNickname = attendeeNickname, muzziColor = muzziColor)
}
