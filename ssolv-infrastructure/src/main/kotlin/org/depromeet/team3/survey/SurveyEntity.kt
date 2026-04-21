package org.depromeet.team3.survey

import com.querydsl.core.annotations.QueryEntity
import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity
import org.depromeet.team3.surveyresult.SurveyResultEntity

@Entity
@QueryEntity
@Table(
    name = "tb_surveys",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_survey_meeting_participant",
            columnNames = ["meeting_id", "participant_id"],
        ),
    ],
)
class SurveyEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    val meeting: MeetingEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    val participant: MeetingAttendeeEntity,

    @OneToMany(mappedBy = "survey", fetch = FetchType.LAZY, cascade = [CascadeType.ALL])
    val surveyResults: MutableList<SurveyResultEntity> = mutableListOf(),
) : BaseTimeEntity()
