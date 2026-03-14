package org.depromeet.team3.meetingplace

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.place.PlaceEntity

@Entity
@Table(
    name = "tb_meeting_places",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_meeting_place",
            columnNames = ["meeting_id", "place_id"]
        )
    ]
)
class MeetingPlaceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    val meeting: MeetingEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    val place: PlaceEntity,
) : BaseTimeEntity()
