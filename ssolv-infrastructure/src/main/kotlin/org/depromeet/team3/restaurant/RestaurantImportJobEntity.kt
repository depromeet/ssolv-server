package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import java.time.LocalDateTime

@Entity
@Table(
    name = "tb_restaurant_import_job",
    indexes = [
        Index(name = "idx_restaurant_import_job_month", columnList = "import_month"),
        Index(name = "idx_restaurant_import_job_run", columnList = "run_key"),
        Index(name = "idx_restaurant_import_job_status", columnList = "status"),
    ],
)
class RestaurantImportJobEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "import_month", nullable = false, length = 7)
    var importMonth: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    var sourceType: RestaurantSourceType = RestaurantSourceType.GYEONGGI,

    @Column(name = "source_object_key", nullable = false, length = 1000)
    var sourceObjectKey: String = "",

    @Column(name = "run_key", nullable = false, length = 1000)
    var runKey: String = "",

    @Column(name = "local_file_path", length = 1000)
    var localFilePath: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: RestaurantImportStatus = RestaurantImportStatus.REGISTERED,

    @Column(name = "started_at")
    var startedAt: LocalDateTime? = null,

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @Column(name = "total_count", nullable = false)
    var totalCount: Long = 0,

    @Column(name = "valid_count", nullable = false)
    var validCount: Long = 0,

    @Column(name = "invalid_count", nullable = false)
    var invalidCount: Long = 0,

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    var failureReason: String? = null,
) : BaseTimeEntity()
