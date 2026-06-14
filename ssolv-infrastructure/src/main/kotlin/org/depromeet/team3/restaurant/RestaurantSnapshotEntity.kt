package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity

@Entity
@Table(
    name = "tb_restaurant_snapshot",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_restaurant_snapshot_source",
            columnNames = ["source_type", "source_key"],
        ),
    ],
    indexes = [
        Index(name = "idx_restaurant_snapshot_source_hash", columnList = "source_type, source_key, content_hash"),
    ],
)
class RestaurantSnapshotEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    var sourceType: RestaurantSourceType = RestaurantSourceType.GYEONGGI,

    @Column(name = "source_key", nullable = false, length = 200)
    var sourceKey: String = "",

    @Column(name = "content_hash", nullable = false, length = 64)
    var contentHash: String = "",

    @Column(name = "restaurant_master_id")
    var restaurantMasterId: Long? = null,
) : BaseTimeEntity()
