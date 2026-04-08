package org.depromeet.team3.fixture

import org.depromeet.team3.survey.SurveyEntity
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity

object SurveyFixture {

    fun createEntity(
        id: Long? = 1L,
        meeting: MeetingEntity = MeetingFixture.createEntity(),
        participant: MeetingAttendeeEntity = MeetingAttendeeFixture.createEntity()
    ) = SurveyEntity(id = id, meeting = meeting, participant = participant)

    fun createEntityWithoutId(
        meeting: MeetingEntity = MeetingFixture.createEntityWithoutId(),
        participant: MeetingAttendeeEntity = MeetingAttendeeFixture.createEntityWithoutId()
    ) = createEntity(id = null, meeting = meeting, participant = participant)
}
