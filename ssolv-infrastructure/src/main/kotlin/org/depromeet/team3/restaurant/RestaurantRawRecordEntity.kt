package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity

@Entity
@Table(
    name = "tb_restaurant_raw_record",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_restaurant_raw_job_row",
            columnNames = ["import_job_id", "row_no"],
        ),
    ],
    indexes = [
        Index(name = "idx_restaurant_raw_source_key", columnList = "source_type, source_key"),
        Index(name = "idx_restaurant_raw_import_job", columnList = "import_job_id"),
    ],
)
class RestaurantRawRecordEntity(
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

    @Column(name = "row_no", nullable = false)
    var rowNumber: Long = 0,

    @Column(name = "raw_hash", nullable = false, length = 64)
    var rawHash: String = "",

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    var rawPayload: String = "",
) : BaseTimeEntity()
