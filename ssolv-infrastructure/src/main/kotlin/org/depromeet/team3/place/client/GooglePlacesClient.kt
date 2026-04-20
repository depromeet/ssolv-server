package org.depromeet.team3.place.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.place.model.PlacesTextSearchRequest
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
@ConditionalOnProperty(prefix = "api.google.places", name = ["api-key"])
class GooglePlacesClient(
    private val httpClient: HttpClient,
    private val googlePlacesApiProperties: GooglePlacesApiProperties,
    private val meterRegistry: MeterRegistry,
) {

    private val logger = KotlinLogging.logger { GooglePlacesClient::class.java.name }
    private val tracer = GlobalOpenTelemetry.getTracer("ssolv.place.google", "1.0.0")

    // API 호출 타임아웃 설정 (5초)
    private val apiTimeoutMillis = 5_000L

    // 재시도 설정
    private val maxRetries = 3  // 최대 3번 시도 (초기 1번 + 재시도 2번)
    private val initialDelayMillis = 100L
    private val maxDelayMillis = 2000L
    private val jitterMaxMillis = 100L

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
            } catch (e: Exception) {
                lastException = e
                
                // 재시도 여부 결정: 5xx 에러, 429 에러, 또는 네트워크 관련 예외일 때만 재시도
                val isRetryable = when (e) {
                    is ResponseException -> {
                        e.response.status.value >= 500 || e.response.status.value == 429
                    }
                    is java.io.IOException -> true
                    is kotlinx.coroutines.TimeoutCancellationException -> true
                    is HttpRequestTimeoutException -> true
                    else -> false
                }

                if (isRetryable && attempt < maxRetries - 1) {
                    val jitter = Random.nextLong(0, jitterMaxMillis)
                    val totalDelay = delayMillis + jitter
                    logger.warn(e) {
                        "$operation 재시도 (${attempt + 1}/${maxRetries - 1}) - 에러: ${e.message}, $operationDetail, ${totalDelay}ms 후 재시도 (지터: ${jitter}ms)"
                    }
                    // 재시도 카운터
                    meterRegistry.counter("google.api.retry.count", "attempt", (attempt + 1).toString()).increment()
                    // 429 Rate Limit 카운터
                    if (e is ResponseException && e.response.status.value == 429) {
                        meterRegistry.counter("google.api.rate_limit.hit").increment()
                    }
                    // trace에 재시도 이벤트 기록 — per-request 재시도 패턴 분석용
                    Span.current().addEvent(
                        "retry",
                        Attributes.of(
                            AttributeKey.longKey("retry.attempt"), (attempt + 1).toLong(),
                            AttributeKey.stringKey("retry.reason"), e.javaClass.simpleName,
                            AttributeKey.longKey("retry.delay_ms"), totalDelay
                        )
                    )
                    delay(totalDelay)
                    delayMillis = minOf(delayMillis * 2, maxDelayMillis)
                } else {
                    break
                }
            }
        }

        logger.error(lastException) { "$operation 최종 실패 (${maxRetries - 1}회 재시도 후), $operationDetail" }
        throw lastException ?: PlaceSearchException(
            ErrorCode.PLACE_API_ERROR,
            detail = mapOf("detail" to "$operation 실패")
        )
    }

    /**
     * 텍스트 검색 (Ktor 버전)
     */
    suspend fun textSearch(
        query: String,
        maxResults: Int = 10,
        latitude: Double? = null,
        longitude: Double? = null,
        radius: Double = 3000.0
    ): PlacesTextSearchResponse {
        val span = tracer.spanBuilder("google.places.textSearch")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("google.places.query", query)
            .setAttribute("google.places.max_results", maxResults.toLong())
            .setAttribute("google.places.radius_m", radius)
            .setAttribute("google.places.has_location_bias", latitude != null && longitude != null)
            .startSpan()

        // 코루틴에서는 Context를 코루틴 ContextElement로 전달해야 스레드 전환에도 유지됨
        val tracingContext = Context.current().with(span).asContextElement()

        return try {
            withContext(tracingContext) {
                retryWithExponentialBackoff(
                    operation = "텍스트 검색",
                    operationDetail = "query=$query"
                ) {
                    try {
                        withTimeout(apiTimeoutMillis) {
                            val locationBias = if (latitude != null && longitude != null) {
                                PlacesTextSearchRequest.LocationBias(
                                    circle = PlacesTextSearchRequest.Circle(
                                        center = PlacesTextSearchRequest.Circle.Center(
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

                            val response = httpClient.post("${googlePlacesApiProperties.baseUrl}/v1/places:searchText") {
                                header("X-Goog-Api-Key", googlePlacesApiProperties.apiKey)
                                header("X-Goog-FieldMask", buildTextSearchFieldMask())
                                contentType(ContentType.Application.Json)
                                setBody(request)
                            }
                            span.setAttribute("http.status_code", response.status.value.toLong())
                            span.setAttribute("http.protocol", response.version.toString())
                            response.body<PlacesTextSearchResponse>().also { parsed ->
                                span.setAttribute("google.places.result_count", (parsed.places?.size ?: 0).toLong())
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.error(e) { "Google Places API 텍스트 검색 타임아웃: query=$query" }
                        throw PlaceSearchException(
                            ErrorCode.PLACE_API_ERROR,
                            detail = mapOf("query" to query, "error" to "요청 타임아웃 (${apiTimeoutMillis}ms 초과)")
                        )
                    } catch (e: Exception) {
                        if (e is ResponseException) {
                            val body = e.response.body<String>()
                            logger.error { "Google Places API 오류 (${e.response.status}): $body" }
                            throw PlaceSearchException(
                                ErrorCode.PLACE_API_ERROR,
                                detail = mapOf("statusCode" to e.response.status.value, "body" to body)
                            )
                        }
                        if (e is PlaceSearchException) throw e
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            span.end()
        }
    }

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
            "places.googleMapsUri"
        ).joinToString(",")
    }
}
