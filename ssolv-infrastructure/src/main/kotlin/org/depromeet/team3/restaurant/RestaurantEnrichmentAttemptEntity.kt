package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import java.time.LocalDateTime

@Entity
@Table(
    name = "tb_restaurant_enrichment_attempt",
    indexes = [
        Index(name = "idx_restaurant_attempt_restaurant", columnList = "restaurant_id"),
        Index(name = "idx_restaurant_attempt_status", columnList = "response_status"),
    ],
)
class RestaurantEnrichmentAttemptEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "restaurant_id", nullable = false)
    var restaurantId: Long = 0,

    @Column(name = "requested_query", nullable = false, length = 1000)
    var requestedQuery: String = "",

    @Column(name = "response_status", nullable = false, length = 100)
    var responseStatus: String = "",

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    var failureReason: String? = null,

    @Column(name = "attempted_at", nullable = false)
    var attemptedAt: LocalDateTime = LocalDateTime.now(),
) : BaseTimeEntity()
