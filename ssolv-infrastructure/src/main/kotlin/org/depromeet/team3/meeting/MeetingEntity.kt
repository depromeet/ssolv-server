package org.depromeet.team3.meeting

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity
import org.depromeet.team3.station.StationEntity
import org.depromeet.team3.auth.UserEntity
import java.time.LocalDateTime

@Entity
@Table(name = "tb_meetings")
class MeetingEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @Column(name = "attendee_count", nullable = false)
    val attendeeCount: Int,
    
    @Column(name = "is_closed", nullable = false)
    val isClosed: Boolean = false,
    
    @Column(name = "end_at")
    val endAt: LocalDateTime? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_user_id", nullable = false)
    val hostUser: UserEntity,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id")
    val station: StationEntity,
    
    @OneToMany(mappedBy = "meeting", fetch = FetchType.LAZY)
    val attendees: MutableList<MeetingAttendeeEntity> = mutableListOf()
) : BaseTimeEntity()