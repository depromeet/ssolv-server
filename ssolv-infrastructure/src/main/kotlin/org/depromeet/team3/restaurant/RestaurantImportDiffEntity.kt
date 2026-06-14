package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity

@Entity
@Table(
    name = "tb_restaurant_import_diff",
    indexes = [
        Index(name = "idx_restaurant_diff_job_type", columnList = "import_job_id, diff_type"),
        Index(name = "idx_restaurant_diff_source", columnList = "source_type, source_key"),
        Index(name = "idx_restaurant_diff_master", columnList = "restaurant_master_id"),
    ],
)
class RestaurantImportDiffEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "import_job_id", nullable = false)
    var importJobId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    var sourceType: RestaurantSourceType = RestaurantSourceType.GYEONGGI,

    @Column(name = "source_key", nullable = false, length = 200)
    var sourceKey: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "diff_type", nullable = false, length = 30)
    var diffType: RestaurantDiffType = RestaurantDiffType.UNCHANGED,

    @Column(name = "restaurant_master_id")
    var restaurantMasterId: Long? = null,

    @Column(name = "previous_hash", length = 64)
    var previousHash: String? = null,

    @Column(name = "current_hash", length = 64)
    var currentHash: String? = null,
) : BaseTimeEntity()
