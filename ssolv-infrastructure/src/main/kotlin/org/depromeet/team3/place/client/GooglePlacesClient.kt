package org.depromeet.team3.place.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.place.model.PlaceDetailsResponse
import org.depromeet.team3.place.model.PlacesTextSearchRequest
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import kotlin.random.Random

import org.depromeet.team3.common.util.RetryUtil

@Component
@ConditionalOnProperty(prefix = "api.google.places", name = ["api-key"])
class GooglePlacesClient(
    private val googlePlacesRestClient: RestClient,
    private val googlePlacesApiProperties: GooglePlacesApiProperties,
) {

    private val logger = KotlinLogging.logger { GooglePlacesClient::class.java.name }
    
    // API 호출 타임아웃 설정 (5초)
    private val apiTimeoutMillis = 5_000L

    /**
     * 텍스트 검색
     */
    suspend fun textSearch(
        query: String, 
        maxResults: Int = 10,
        latitude: Double? = null,
        longitude: Double? = null,
        radius: Double = 3000.0
    ): PlacesTextSearchResponse = withContext(Dispatchers.IO) {
        RetryUtil.retryWithExponentialBackoff(
            operation = "텍스트 검색",
            logger = logger,
            operationDetail = "query=$query"
        ) {
            withTimeout(apiTimeoutMillis) {
                val locationBias = if (latitude != null && longitude != null) {
                    PlacesTextSearchRequest.LocationBias(
                        circle = PlacesTextSearchRequest.LocationBias.Circle(
                            center = PlacesTextSearchRequest.LocationBias.Circle.Center(
                                latitude = latitude,
                                longitude = longitude
                            ),
                            radius = radius
                        )
                    )
                } else null
                
                val request = PlacesTextSearchRequest(
                    textQuery = query,
                    languageCode = "ko",
                    maxResultCount = maxResults,
                    locationBias = locationBias
                )

                val response = googlePlacesRestClient.post()
                    .uri("/v1/places:searchText")
                    .header("X-Goog-Api-Key", googlePlacesApiProperties.apiKey)
                    .header("X-Goog-FieldMask", buildTextSearchFieldMask())
                    .body(request)
                    .retrieve()
                    .body(PlacesTextSearchResponse::class.java)
            
                response ?: throw PlaceSearchException(
                    errorCode = ErrorCode.PLACE_API_RESPONSE_NULL,
                    detail = mapOf("query" to query)
                )
            }
        }
    }

    /**
     * 사진 데이터 조회
     */
    suspend fun fetchPhoto(photoName: String, maxHeightPx: Int = 1000, maxWidthPx: Int = 1000): ByteArray? = withContext(Dispatchers.IO) {
        RetryUtil.retryWithExponentialBackoff(
            operation = "사진 데이터 조회",
            logger = logger,
            operationDetail = "photoName=$photoName"
        ) {
            withTimeout(apiTimeoutMillis) {
                googlePlacesRestClient.get()
                    .uri { uriBuilder ->
                        uriBuilder.path("/v1/{photoName}/media")
                            .queryParam("maxHeightPx", maxHeightPx)
                            .queryParam("maxWidthPx", maxWidthPx)
                            .queryParam("key", googlePlacesApiProperties.apiKey)
                            .build(photoName)
                    }
                    .retrieve()
                    .body(ByteArray::class.java)
            }
        }
    }

    /**
     * 장소 상세 정보 조회
     */
    suspend fun getPlaceDetails(placeId: String): PlaceDetailsResponse? = withContext(Dispatchers.IO) {
        RetryUtil.retryWithExponentialBackoff(
            operation = "장소 상세 조회",
            logger = logger,
            operationDetail = "placeId=$placeId"
        ) {
            withTimeout(apiTimeoutMillis) {
                googlePlacesRestClient.get()
                    .uri("/v1/places/$placeId")
                    .header("X-Goog-Api-Key", googlePlacesApiProperties.apiKey)
                    .header("X-Goog-FieldMask", buildDetailsFieldMask())
                    .retrieve()
                    .body(PlaceDetailsResponse::class.java)
            }
        }
    }

    /**
     * Text Search용 Field Mask
     */
    private fun buildTextSearchFieldMask(): String {
        return listOf(
            "places.id",
            "places.displayName",
            "places.formattedAddress",
            "places.rating",
            "places.userRatingCount",
            "places.photos",
            "places.location",
            "places.types",
            "places.currentOpeningHours"
        ).joinToString(",")
    }

    private fun buildDetailsFieldMask(): String {
        return listOf(
            "id",
            "displayName",
            "formattedAddress",
            "rating",
            "userRatingCount",
            "location",
            "types"
        ).joinToString(",")
    }
}
