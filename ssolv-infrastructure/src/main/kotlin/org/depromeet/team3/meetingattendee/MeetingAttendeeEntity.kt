package org.depromeet.team3.meetingattendee

import jakarta.persistence.*
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.common.BaseTimeEntity
import org.depromeet.team3.meeting.MeetingEntity

@Entity
@Table(
    name = "tb_meeting_attendees",
    uniqueConstraints = [UniqueConstraint(name = "uk_meeting_attendee", columnNames = ["meeting_id", "user_id"])],
)
class MeetingAttendeeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    val meeting: MeetingEntity,

    @Column(name = "attendee_nickname", nullable = false)
    var attendeeNickname: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "muzzi_color", nullable = false)
    var muzziColor: MuzziColor,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: UserEntity,
) : BaseTimeEntity()
