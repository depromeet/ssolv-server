package org.depromeet.team3.notification.infrastructure

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import org.depromeet.team3.notification.domain.DevicePlatform

@Entity
@Table(name = "tb_device_tokens")
class DeviceTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "fcm_token", nullable = false, unique = true)
    val fcmToken: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    val platform: DevicePlatform

) : BaseTimeEntity()