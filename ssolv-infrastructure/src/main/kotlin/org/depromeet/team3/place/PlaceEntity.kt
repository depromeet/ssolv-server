package org.depromeet.team3.place

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity

@Entity
@Table(
    name = "tb_place",
    indexes = [
        Index(name = "idx_google_place_id", columnList = "google_place_id")
    ]
)
class PlaceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "google_place_id", unique = true, length = 500)
    val googlePlaceId: String? = null,
    
    @Column(nullable = false, length = 500)
    val name: String,
    
    @Column(nullable = false, length = 1000)
    val address: String,
    
    @Column(name = "latitude")
    val latitude: Double? = null,
    
    @Column(name = "longitude")
    val longitude: Double? = null,
    
    @Column(nullable = false)
    val rating: Double,
    
    @Column(name = "user_ratings_total", nullable = false)
    val userRatingsTotal: Int,
    
    @Column(name = "open_now")
    val openNow: Boolean? = null,
    
    @Column(name = "link", length = 1000)
    val link: String? = null,
    
    @Column(name = "weekday_text", columnDefinition = "TEXT")
    val weekdayText: String? = null,
    
    @Column(name = "top_review_rating")
    val topReviewRating: Double? = null,
    
    @Column(name = "top_review_text", columnDefinition = "TEXT")
    val topReviewText: String? = null,
    
    @Column(name = "price_range_start", length = 100)
    val priceRangeStart: String? = null,
    
    @Column(name = "price_range_end", length = 100)
    val priceRangeEnd: String? = null,
    
    @Column(name = "address_descriptor", length = 500)
    val addressDescriptor: String? = null,
    
    @Column(name = "llm_summary", length = 1000)
    val llmSummary: String? = null,

    @Column(name = "llm_reason", length = 1000)
    val llmReason: String? = null,

    @Column(name = "photos", columnDefinition = "TEXT")
    val photos: String? = null,

    @Column(name = "is_deleted", nullable = false)
    val isDeleted: Boolean = false
) : BaseTimeEntity()
