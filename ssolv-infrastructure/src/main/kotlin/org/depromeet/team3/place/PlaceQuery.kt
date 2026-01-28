package org.depromeet.team3.place

import org.depromeet.team3.place.client.GooglePlacesClient
import org.depromeet.team3.place.model.PlaceDetailsResponse
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
@ConditionalOnProperty(prefix = "api.google.places", name = ["api-key"])
class PlaceQuery(
    private val googlePlacesClient: GooglePlacesClient,
    private val placeJpaRepository: PlaceJpaRepository
) {

    /**
     * 텍스트 검색
     */
    suspend fun textSearch(
        query: String,
        maxResults: Int = 10,
        latitude: Double? = null,
        longitude: Double? = null,
        radius: Double = 3000.0
    ): PlacesTextSearchResponse {
        return googlePlacesClient.textSearch(query, maxResults, latitude, longitude, radius)
    }

    @Transactional
    suspend fun savePlacesFromTextSearch(
        places: List<PlacesTextSearchResponse.Place>
    ): List<PlaceEntity> {
        if (places.isEmpty()) return emptyList()

        val googlePlaceIds = places.map { it.id }
        val existingPlaces = placeJpaRepository.findByGooglePlaceIdIn(googlePlaceIds)
            .associateBy { it.googlePlaceId }

        val entities = places.map { place ->
            val existing = existingPlaces[place.id]
            val lastUpdated = existing?.updatedAt ?: LocalDateTime.MIN

            // 30일 이내 데이터가 있으면 업데이트 스킵 (비용 최적화 및 구글 약관 준수)
            if (existing != null && lastUpdated.isAfter(LocalDateTime.now().minusDays(30))) {
                return@map existing
            }

            PlaceEntity(
                id = existing?.id,
                googlePlaceId = existing?.googlePlaceId ?: place.id,
                name = place.displayName?.text ?: existing?.name ?: "Unknown",
                address = place.formattedAddress ?: existing?.address ?: "",
                latitude = place.location?.latitude ?: existing?.latitude,
                longitude = place.location?.longitude ?: existing?.longitude,
                rating = place.rating ?: existing?.rating ?: 0.0,
                userRatingsTotal = place.userRatingCount ?: existing?.userRatingsTotal ?: 0,
                openNow = place.currentOpeningHours?.openNow ?: existing?.openNow,
                link = existing?.link,
                weekdayText = existing?.weekdayText,
                topReviewRating = existing?.topReviewRating,
                topReviewText = existing?.topReviewText,
                priceRangeStart = existing?.priceRangeStart,
                priceRangeEnd = existing?.priceRangeEnd,
                addressDescriptor = existing?.addressDescriptor,
                llmSummary = existing?.llmSummary,
                llmReason = existing?.llmReason,
                photos = place.photos?.take(5)?.joinToString(",") { it.name } ?: existing?.photos,
                isDeleted = existing?.isDeleted ?: false
            )
        }

        return placeJpaRepository.saveAll(entities).toList()
    }

    // Google Place ID 목록으로 Place 엔티티 조회
    fun findByGooglePlaceIds(googlePlaceIds: List<String>): List<PlaceEntity> {
        return placeJpaRepository.findByGooglePlaceIdIn(googlePlaceIds)
    }

    // 장소 상세 조회
    suspend fun getPlaceDetails(googlePlaceId: String): PlaceDetailsResponse? {
        return googlePlacesClient.getPlaceDetails(googlePlaceId)
    }

    // 사진 데이터 조회
    suspend fun fetchPhoto(photoName: String): ByteArray? {
        return googlePlacesClient.fetchPhoto(photoName)
    }

    /**
     * LLM 관련 정보 (요약, 랜드마크, 추천 사유) 통합 업데이트
     */
    @Transactional
    fun updateLlmData(
        googlePlaceId: String,
        summary: String? = null,
        landmarks: String? = null,
        reason: String? = null
    ) {
        bulkUpdateLlmData(mapOf(googlePlaceId to LlmUpdateData(summary, landmarks, reason)))
    }

    /**
     * LLM 관련 정보 일괄 업데이트
     */
    @Transactional
    fun bulkUpdateLlmData(updateDataMap: Map<String, LlmUpdateData>) {
        if (updateDataMap.isEmpty()) return

        val googlePlaceIds = updateDataMap.keys.toList()
        val entities = placeJpaRepository.findByGooglePlaceIdIn(googlePlaceIds)

        val updatedEntities = entities.map { entity ->
            val data = updateDataMap[entity.googlePlaceId] ?: return@map entity
            PlaceEntity(
                id = entity.id,
                googlePlaceId = entity.googlePlaceId,
                name = entity.name,
                address = entity.address,
                latitude = entity.latitude,
                longitude = entity.longitude,
                rating = entity.rating,
                userRatingsTotal = entity.userRatingsTotal,
                openNow = entity.openNow,
                photos = entity.photos,
                isDeleted = entity.isDeleted,
                link = entity.link,
                weekdayText = entity.weekdayText,
                topReviewRating = entity.topReviewRating,
                topReviewText = entity.topReviewText,
                priceRangeStart = entity.priceRangeStart,
                priceRangeEnd = entity.priceRangeEnd,
                addressDescriptor = data.landmarks ?: entity.addressDescriptor,
                llmSummary = data.summary ?: entity.llmSummary,
                llmReason = data.reason ?: entity.llmReason
            )
        }

        placeJpaRepository.saveAll(updatedEntities)
    }

    data class LlmUpdateData(
        val summary: String? = null,
        val landmarks: String? = null,
        val reason: String? = null
    )
}