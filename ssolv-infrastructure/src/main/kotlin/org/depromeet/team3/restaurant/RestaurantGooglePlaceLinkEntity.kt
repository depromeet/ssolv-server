package org.depromeet.team3.restaurant

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import java.time.LocalDateTime

@Entity
@Table(
    name = "tb_restaurant_google_place_link",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_restaurant_google_link_restaurant", columnNames = ["restaurant_id"]),
        UniqueConstraint(name = "uk_restaurant_google_link_place", columnNames = ["google_place_id"]),
    ],
    indexes = [
        Index(name = "idx_restaurant_google_match_status", columnList = "match_status"),
    ],
)
class RestaurantGooglePlaceLinkEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "restaurant_id", nullable = false)
    var restaurantId: Long = 0,

    @Column(name = "google_place_id", nullable = false, length = 500)
    var googlePlaceId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 30)
    var matchStatus: RestaurantGoogleMatchStatus = RestaurantGoogleMatchStatus.MATCHED,

    @Column(name = "match_score", nullable = false)
    var matchScore: Double = 0.0,

    @Column(name = "distance_meter")
    var distanceMeter: Double? = null,

    @Column(name = "name_similarity", nullable = false)
    var nameSimilarity: Double = 0.0,

    @Column(name = "matched_at", nullable = false)
    var matchedAt: LocalDateTime = LocalDateTime.now(),
) : BaseTimeEntity()
