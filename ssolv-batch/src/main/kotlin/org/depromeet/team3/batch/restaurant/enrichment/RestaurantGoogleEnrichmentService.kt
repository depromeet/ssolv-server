package org.depromeet.team3.batch.restaurant.enrichment

import org.depromeet.team3.batch.restaurant.config.RestaurantEnrichmentProperties
import org.depromeet.team3.batch.restaurant.support.RestaurantTextNormalizer
import org.depromeet.team3.place.client.GooglePlacesClient
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.depromeet.team3.restaurant.RestaurantEnrichmentAttemptEntity
import org.depromeet.team3.restaurant.RestaurantEnrichmentAttemptJpaRepository
import org.depromeet.team3.restaurant.RestaurantGoogleMatchStatus
import org.depromeet.team3.restaurant.RestaurantGooglePlaceLinkEntity
import org.depromeet.team3.restaurant.RestaurantGooglePlaceLinkJpaRepository
import org.depromeet.team3.restaurant.RestaurantMasterEntity
import org.depromeet.team3.restaurant.RestaurantMasterJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class RestaurantGoogleEnrichmentService(
    private val restaurantMasterJpaRepository: RestaurantMasterJpaRepository,
    private val linkJpaRepository: RestaurantGooglePlaceLinkJpaRepository,
    private val attemptJpaRepository: RestaurantEnrichmentAttemptJpaRepository,
    private val googlePlacesClient: GooglePlacesClient,
    private val normalizer: RestaurantTextNormalizer,
    private val properties: RestaurantEnrichmentProperties,
) {
    private val logger = LoggerFactory.getLogger(RestaurantGoogleEnrichmentService::class.java)

    suspend fun enrich(restaurantId: Long, requestedQuery: String? = null) {
        if (linkJpaRepository.existsByRestaurantId(restaurantId)) return

        val restaurant = restaurantMasterJpaRepository.findById(restaurantId).orElse(null)
            ?: throw IllegalArgumentException("restaurant not found: $restaurantId")
        val query = requestedQuery ?: "${restaurant.name} ${restaurant.address}"

        try {
            val response = googlePlacesClient.textSearch(
                query = query,
                maxResults = properties.maxResults,
                latitude = restaurant.latitude,
                longitude = restaurant.longitude,
                radius = properties.radiusMeter,
            )
            val best = response.places
                .orEmpty()
                .mapNotNull { candidate -> evaluate(restaurant, candidate) }
                .filter { it.isAccepted }
                .maxByOrNull { it.matchScore }

            if (best == null) {
                saveAttempt(restaurantId, query, "UNMATCHED", "No candidate passed validation")
                logger.info("Google Places 매칭 실패: restaurantId={}, query={}", restaurantId, query)
                return
            }

            linkJpaRepository.save(
                RestaurantGooglePlaceLinkEntity(
                    restaurantId = restaurantId,
                    googlePlaceId = best.place.id,
                    matchStatus = RestaurantGoogleMatchStatus.MATCHED,
                    matchScore = best.matchScore,
                    distanceMeter = best.distanceMeter,
                    nameSimilarity = best.nameSimilarity,
                    matchedAt = LocalDateTime.now(),
                ),
            )
            saveAttempt(restaurantId, query, "MATCHED", null)
            logger.info("Google Places 매칭 완료: restaurantId={}, googlePlaceId={}", restaurantId, best.place.id)
        } catch (e: Exception) {
            saveAttempt(restaurantId, query, "FAILED", e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun evaluate(restaurant: RestaurantMasterEntity, place: PlacesTextSearchResponse.Place): CandidateMatch? {
        val location = place.location ?: return null
        val distance = if (restaurant.latitude != null && restaurant.longitude != null) {
            normalizer.distanceMeter(
                restaurant.latitude!!,
                restaurant.longitude!!,
                location.latitude,
                location.longitude,
            )
        } else {
            null
        }
        val nameSimilarity = normalizer.similarity(restaurant.name, place.displayName.text)
        val typeMatched = place.types.orEmpty().any { type ->
            type in setOf("restaurant", "cafe", "food", "bakery", "meal_takeaway", "meal_delivery")
        }
        val addressMatched = place.formattedAddress.contains(restaurant.address.take(8))
        val distanceScore = distance?.let { (1.0 - (it / 100.0)).coerceIn(0.0, 1.0) } ?: 0.0
        val matchScore = nameSimilarity * 0.55 +
            distanceScore * 0.30 +
            (if (addressMatched) 0.10 else 0.0) +
            (if (typeMatched) 0.05 else 0.0)

        return CandidateMatch(
            place = place,
            nameSimilarity = nameSimilarity,
            distanceMeter = distance,
            matchScore = matchScore,
            isAccepted = nameSimilarity >= 0.8 &&
                (distance == null || distance <= 100.0) &&
                typeMatched,
        )
    }

    private fun saveAttempt(restaurantId: Long, query: String, status: String, failureReason: String?) {
        attemptJpaRepository.save(
            RestaurantEnrichmentAttemptEntity(
                restaurantId = restaurantId,
                requestedQuery = query,
                responseStatus = status,
                failureReason = failureReason,
                attemptedAt = LocalDateTime.now(),
            ),
        )
    }

    private data class CandidateMatch(
        val place: PlacesTextSearchResponse.Place,
        val nameSimilarity: Double,
        val distanceMeter: Double?,
        val matchScore: Double,
        val isAccepted: Boolean,
    )
}
