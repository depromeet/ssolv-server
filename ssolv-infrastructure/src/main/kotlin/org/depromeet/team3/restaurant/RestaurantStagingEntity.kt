package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity

@Entity
@Table(
    name = "tb_restaurant_staging",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_restaurant_staging_job_source",
            columnNames = ["import_job_id", "source_type", "source_key"],
        ),
    ],
    indexes = [
        Index(name = "idx_restaurant_staging_source_hash", columnList = "source_type, source_key, content_hash"),
        Index(name = "idx_restaurant_staging_region_category", columnList = "region_code, category"),
    ],
)
class RestaurantStagingEntity(
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

    @Column(nullable = false, length = 500)
    var name: String = "",

    @Column(name = "normalized_name", nullable = false, length = 500)
    var normalizedName: String = "",

    @Column(name = "road_address", length = 1000)
    var roadAddress: String? = null,

    @Column(name = "lot_address", length = 1000)
    var lotAddress: String? = null,

    @Column(nullable = false, length = 1000)
    var address: String = "",

    @Column(name = "latitude")
    var latitude: Double? = null,

    @Column(name = "longitude")
    var longitude: Double? = null,

    @Column(length = 100)
    var category: String? = null,

    @Column(name = "region_code", length = 20)
    var regionCode: String? = null,

    @Column(name = "phone_number", length = 100)
    var phoneNumber: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "business_status", nullable = false, length = 30)
    var businessStatus: RestaurantBusinessStatus = RestaurantBusinessStatus.UNKNOWN,

    @Column(name = "content_hash", nullable = false, length = 64)
    var contentHash: String = "",
) : BaseTimeEntity()
