package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity

@Entity
@Table(
    name = "tb_restaurant_invalid_record",
    indexes = [
        Index(name = "idx_restaurant_invalid_job", columnList = "import_job_id"),
    ],
)
class RestaurantInvalidRecordEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "import_job_id", nullable = false)
    var importJobId: Long = 0,

    @Column(name = "row_no", nullable = false)
    var rowNumber: Long = 0,

    @Column(name = "failure_reason", nullable = false, length = 500)
    var failureReason: String = "",

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    var rawPayload: String = "",
) : BaseTimeEntity()
