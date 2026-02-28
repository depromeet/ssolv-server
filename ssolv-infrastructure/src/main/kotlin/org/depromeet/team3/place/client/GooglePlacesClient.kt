package org.depromeet.team3.place.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.place.model.PlacesTextSearchRequest
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import kotlin.random.Random

@Component
@ConditionalOnProperty(prefix = "api.google.places", name = ["api-key"])
class GooglePlacesClient(
    private val googlePlacesRestClient: RestClient,
    private val googlePlacesApiProperties: GooglePlacesApiProperties,
) {

    private val logger = KotlinLogging.logger { GooglePlacesClient::class.java.name }
    
    // API 호출 타임아웃 설정 (5초)
    private val apiTimeoutMillis = 5_000L
    
    // 재시도 설정
    private val maxRetries = 3  // 최대 3번 시도 (초기 1번 + 재시도 2번)
    private val initialDelayMillis = 100L // 초기 지연 시간 (100ms)
    private val maxDelayMillis = 2000L // 최대 지연 시간 (2초)
    private val jitterMaxMillis = 100L // 지터 최대값 (0~100ms)

    /**
     * 지수 백오프 재시도 로직
     * 일시적 오류(429, 500-504, 네트워크 오류)에 대해서만 재시도
     */
    private suspend fun <T> retryWithExponentialBackoff(
        operation: String,
        operationDetail: String = "",
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var delayMillis = initialDelayMillis

        for (attempt in 0 until maxRetries) {
            try {
                return block()
            } catch (e: HttpClientErrorException) {
                val statusCode = e.statusCode.value()
                if (statusCode in listOf(401, 404)) {
                    throw e
                }
                if (statusCode == 429 || statusCode in 500..504) {
                    lastException = e
                    if (attempt < maxRetries - 1) {
                        val jitter = Random.nextLong(0, jitterMaxMillis)
                        val totalDelay = delayMillis + jitter
                        logger.warn(e) { 
                            "$operation 재시도 (${attempt + 1}/${maxRetries - 1}) - 상태코드: $statusCode, $operationDetail, ${totalDelay}ms 후 재시도 (지터: ${jitter}ms)" 
                        }
                        delay(totalDelay)
                        delayMillis = minOf(delayMillis * 2, maxDelayMillis)
                    }
                } else {
                    throw e
                }
            } catch (e: RestClientException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val jitter = Random.nextLong(0, jitterMaxMillis)
                    val totalDelay = delayMillis + jitter
                    logger.warn(e) { 
                        "$operation 재시도 (${attempt + 1}/${maxRetries - 1}) - 네트워크 오류: ${e.message}, $operationDetail, ${totalDelay}ms 후 재시도 (지터: ${jitter}ms)" 
                    }
                    delay(totalDelay)
                    delayMillis = minOf(delayMillis * 2, maxDelayMillis)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val jitter = Random.nextLong(0, jitterMaxMillis)
                    val totalDelay = delayMillis + jitter
                    logger.warn(e) { 
                        "$operation 재시도 (${attempt + 1}/${maxRetries - 1}) - 예외: ${e.javaClass.simpleName}, $operationDetail, ${totalDelay}ms 후 재시도 (지터: ${jitter}ms)" 
                    }
                    delay(totalDelay)
                    delayMillis = minOf(delayMillis * 2, maxDelayMillis)
                }
            }
        }

        logger.error(lastException) { "$operation 최종 실패 (${maxRetries - 1}회 재시도 후), $operationDetail" }
        when (val exception = lastException) {
            is HttpClientErrorException -> {
                throw PlaceSearchException(
                    ErrorCode.PLACE_API_ERROR,
                    detail = mapOf("statusCode" to exception.statusCode.value(), "detail" to operationDetail)
                )
            }
            is RestClientException -> {
                throw PlaceSearchException(
                    ErrorCode.PLACE_API_ERROR,
                    detail = mapOf("error" to exception.message, "detail" to operationDetail)
                )
            }
            else -> throw exception ?: PlaceSearchException(
                ErrorCode.PLACE_API_ERROR,
                detail = mapOf("detail" to "$operation 실패")
            )
        }
    }

    /**
     * 텍스트 검색
     */
    suspend fun textSearch(
        query: String, 
        maxResults: Int = 10,
        latitude: Double? = null,
        longitude: Double? = null,
        radius: Double = 3000.0
    ): PlacesTextSearchResponse = withContext(CoroutineDispatchers.VT) {
        retryWithExponentialBackoff(
            operation = "텍스트 검색",
            operationDetail = "query=$query"
        ) {
            try {
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
            } catch (e: TimeoutCancellationException) {
                logger.error(e) { "Google Places API 텍스트 검색 타임아웃: query=$query" }
                throw PlaceSearchException(
                    ErrorCode.PLACE_API_ERROR,
                    detail = mapOf("query" to query, "error" to "요청 타임아웃 (${apiTimeoutMillis}ms 초과)")
                )
            } catch (e: Exception) {
                throw e
            }
        }
    }

    /**
     * 사진 데이터 조회 (New API)
     */
    suspend fun fetchPhoto(photoName: String, maxHeightPx: Int = 1000, maxWidthPx: Int = 1000): ByteArray? = withContext(CoroutineDispatchers.VT) {
        retryWithExponentialBackoff(
            operation = "사진 데이터 조회",
            operationDetail = "photoName=$photoName"
        ) {
            try {
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
            } catch (e: Exception) {
                logger.error(e) { "Google Places API 사진 조회 실패: photoName=$photoName" }
                null
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
            "places.types"
        ).joinToString(",")
    }
}
