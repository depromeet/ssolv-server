package org.depromeet.team3.auth

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity
import org.hibernate.annotations.SQLRestriction
import java.time.LocalDateTime

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "tb_users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_user_social", columnNames = ["provider", "social_id"])
    ]
)
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    var provider: AuthProvider = AuthProvider.KAKAO,

    @Column(name = "social_id", nullable = false)
    var socialId: String = "",

    @Column(nullable = false, unique = true)
    var email: String = "",

    @Column(name = "profile_image")
    var profileImage: String? = null,

    @Column(name = "refresh_token")
    var refreshToken: String? = null,

    @Column(nullable = false)
    var nickname: String = "",

    @OneToMany(mappedBy = "hostUser", fetch = FetchType.LAZY)
    val meetings: MutableList<MeetingEntity> = mutableListOf(),

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val meetingAttendances: MutableList<MeetingAttendeeEntity> = mutableListOf(),

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
) : BaseTimeEntity()