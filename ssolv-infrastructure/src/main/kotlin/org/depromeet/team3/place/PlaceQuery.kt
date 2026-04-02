package org.depromeet.team3.place

import org.depromeet.team3.place.client.GooglePlacesClient
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Repository
@ConditionalOnProperty(prefix = "api.google.places", name = ["api-key"])
class PlaceQuery(
    private val googlePlacesClient: GooglePlacesClient,
    private val placeJpaRepository: PlaceJpaRepository,
    private val transactionTemplate: TransactionTemplate,
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

    suspend fun savePlacesFromTextSearch(
        places: List<PlacesTextSearchResponse.Place>
    ): List<PlaceEntity> {
        if (places.isEmpty()) return emptyList()

        return transactionTemplate.execute {
            val googlePlaceIds = places.map { it.id }
            val existingPlaces = placeJpaRepository.findByGooglePlaceIdIn(googlePlaceIds)
                .associateBy { it.googlePlaceId }

            val entities = places.map { place ->
                val existing = existingPlaces[place.id]
                val lastUpdated = existing?.updatedAt ?: LocalDateTime.MIN

                // 30일 이내 데이터가 있고 필수 정보(link)가 있으면 업데이트 스킵 (비용 최적화 및 구글 약관 준수)
                if (existing != null && 
                    existing.link != null && 
                    lastUpdated.isAfter(LocalDateTime.now().minusDays(30))) {
                    return@map existing
                }

                PlaceEntity(
                    id = existing?.id,
                    googlePlaceId = existing?.googlePlaceId ?: place.id,
                    name = org.depromeet.team3.place.util.PlaceFormatter.extractKoreanName(place.displayName.text),
                    address = place.formattedAddress,
                    latitude = place.location?.latitude ?: existing?.latitude,
                    longitude = place.location?.longitude ?: existing?.longitude,
                    rating = place.rating ?: existing?.rating ?: 0.0,
                    userRatingsTotal = place.userRatingCount ?: existing?.userRatingsTotal ?: 0,
                    openNow = place.currentOpeningHours?.openNow ?: existing?.openNow,
                    // 리스트 검색 정보로 엔티티 생성, 상세 정보는 나중에 채워짐
                    photos = place.photos?.take(5)?.joinToString(",") { it.name } ?: existing?.photos,
                    isDeleted = existing?.isDeleted ?: false,
                    // 장소 이름을 이용해 네이버 플레이스 링크 생성
                    link = org.depromeet.team3.place.util.PlaceFormatter.generateNaverPlaceLink(
                        org.depromeet.team3.place.util.PlaceFormatter.extractKoreanName(place.displayName.text)
                    ),
                    weekdayText = existing?.weekdayText,
                    topReviewRating = existing?.topReviewRating,
                    topReviewText = existing?.topReviewText,
                    priceRangeStart = existing?.priceRangeStart,
                    priceRangeEnd = existing?.priceRangeEnd,
                    addressDescriptor = existing?.addressDescriptor
                )
            }

            placeJpaRepository.saveAll(entities).toList()
        } ?: emptyList()
    }

    // Google Place ID 목록으로 Place 엔티티 조회
    suspend fun findByGooglePlaceIds(googlePlaceIds: List<String>): List<PlaceEntity> {
        return placeJpaRepository.findByGooglePlaceIdIn(googlePlaceIds)
    }

    // DB ID 목록으로 Place 엔티티 조회
    suspend fun findByIds(ids: List<Long>): List<PlaceEntity> {
        return placeJpaRepository.findAllById(ids)
    }
}
