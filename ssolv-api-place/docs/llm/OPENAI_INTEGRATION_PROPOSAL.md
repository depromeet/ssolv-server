# OpenAI 통합 제안서 (프로덕션 안전)

## 📋 요약

**목표**: 기존 랭킹 로직은 절대 변경하지 않고, LLM을 사용하여 리뷰 요약 정보만 추가

**원칙**:
- ✅ LLM은 **표시용 정보 생성**만 담당
- ❌ LLM은 **랭킹 결정에 절대 사용하지 않음**
- ✅ 모든 LLM 호출은 **캐싱** 필수
- ✅ LLM 실패 시 **기존 동작 유지** (Fallback)

---

## 1. 현재 프로젝트 구조 분석

### 핵심 흐름
```
ExecutePlaceSearchService.search()
  ↓
1. 키워드 기반 Google Places API 검색
2. 가중치 기반 점수 계산 및 정렬 (기존 로직 유지)
3. PlacesSearchResponse.PlaceItem 생성
  ↓
현재: topReview만 포함 (단일 리뷰)
추가: LLM 요약 정보 (oneLine, pros, cons, bestFor)
```

### 통합 지점
**`ExecutePlaceSearchService.search()` 메서드 내부**
- `PlaceItem` 생성 직전에 LLM 호출
- 비동기 처리로 응답 시간 영향 최소화
- 실패 시 기존 `PlaceItem` 그대로 반환

---

## 2. 아키텍처 설계

### 디렉토리 구조
```
ssolv-infrastructure/
  src/main/kotlin/org/depromeet/team3/
    llm/
      client/
        OpenAIClient.kt              # OpenAI API 클라이언트
        OpenAIClientConfiguration.kt # RestClient 설정
      service/
        ReviewSummaryService.kt     # 리뷰 요약 서비스 (메인 로직)
        ReviewSummaryCache.kt        # 캐싱 로직
      model/
        ReviewSummaryRequest.kt      # LLM 요청 DTO
        ReviewSummaryResponse.kt     # LLM 응답 DTO
      properties/
        OpenAIApiProperties.kt      # 설정 프로퍼티
```

### 의존성 추가
```kotlin
// ssolv-infrastructure/build.gradle.kts
dependencies {
    // OpenAI는 직접 HTTP 호출 (라이브러리 의존성 최소화)
    // 기존 RestClient 사용 (이미 httpclient5 있음)
}
```

---

## 3. 구현 상세

### 3.1 DTO 설계

```kotlin
// ReviewSummaryRequest.kt
package org.depromeet.team3.llm.model

data class ReviewSummaryRequest(
    val restaurantName: String,
    val rating: Double?,
    val reviewCount: Int?,
    val reviews: List<ReviewInput>  // 최대 30개
) {
    data class ReviewInput(
        val text: String,
        val rating: Double
    )
}

// ReviewSummaryResponse.kt
package org.depromeet.team3.llm.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OpenAI 응답 JSON 구조
 * 반드시 이 구조만 허용 (JSON Schema 검증)
 */
data class ReviewSummaryResponse(
    @JsonProperty("oneLine")
    val oneLine: String,              // 한 줄 설명
    
    @JsonProperty("pros")
    val pros: List<String>,           // 장점 리스트 (최대 5개)
    
    @JsonProperty("cons")
    val cons: List<String>,           // 단점 리스트 (최대 3개)
    
    @JsonProperty("bestFor")
    val bestFor: List<String>         // 추천 상황 (예: "데이트", "가족모임")
) {
    companion object {
        fun empty(): ReviewSummaryResponse = ReviewSummaryResponse(
            oneLine = "",
            pros = emptyList(),
            cons = emptyList(),
            bestFor = emptyList()
        )
    }
}
```

### 3.2 OpenAI 클라이언트

```kotlin
// OpenAIClient.kt
package org.depromeet.team3.llm.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.depromeet.team3.llm.model.ReviewSummaryRequest
import org.depromeet.team3.llm.model.ReviewSummaryResponse
import org.depromeet.team3.llm.properties.OpenAIApiProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration

@Component
class OpenAIClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: OpenAIApiProperties
) {
    private val logger = KotlinLogging.logger { }

    companion object {
        private const val TIMEOUT_SECONDS = 5L
        private const val MAX_RETRIES = 1
    }

    /**
     * 리뷰 요약 생성
     * 
     * @param request 리뷰 요약 요청
     * @return ReviewSummaryResponse (실패 시 null)
     */
    suspend fun summarizeReviews(request: ReviewSummaryRequest): ReviewSummaryResponse? {
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)) {
                    callOpenAI(request)
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "OpenAI API 타임아웃: restaurant=${request.restaurantName}" }
            null
        } catch (e: RestClientException) {
            logger.warn(e) { "OpenAI API 호출 실패: restaurant=${request.restaurantName}" }
            null
        } catch (e: Exception) {
            logger.error(e) { "OpenAI API 예상치 못한 오류: restaurant=${request.restaurantName}" }
            null
        }
    }

    private suspend fun callOpenAI(request: ReviewSummaryRequest): ReviewSummaryResponse {
        val prompt = buildPrompt(request)
        val requestBody = buildRequestBody(prompt)

        val response = restClient.post()
            .uri("/v1/chat/completions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(requestBody)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("OpenAI 응답이 null입니다")

        return parseResponse(response)
    }

    private fun buildPrompt(request: ReviewSummaryRequest): String {
        val reviewsText = request.reviews.take(30).joinToString("\n") { review ->
            "평점 ${review.rating}: ${review.text}"
        }

        return """
        다음은 "${request.restaurantName}" 식당의 리뷰 정보입니다.
        평점: ${request.rating ?: "정보 없음"} / 리뷰 수: ${request.reviewCount ?: 0}개
        
        리뷰 목록:
        $reviewsText
        
        다음 JSON 형식으로만 응답해주세요. 다른 텍스트는 포함하지 마세요.
        {
          "oneLine": "이 식당을 한 줄로 설명하는 문장 (50자 이내)",
          "pros": ["장점1", "장점2", "장점3"],
          "cons": ["단점1", "단점2"],
          "bestFor": ["추천 상황1", "추천 상황2"]
        }
        
        주의사항:
        - 반드시 유효한 JSON만 반환하세요
        - pros는 최대 5개, cons는 최대 3개, bestFor는 최대 3개
        - 모든 필드는 필수입니다
        - 한국어로 작성하세요
        """.trimIndent()
    }

    private fun buildRequestBody(prompt: String): Map<String, Any> {
        return mapOf(
            "model" to properties.model,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to "당신은 맛집 리뷰를 분석하는 전문가입니다. 반드시 JSON 형식으로만 응답하세요."
                ),
                mapOf(
                    "role" to "user",
                    "content" to prompt
                )
            ),
            "temperature" to 0.3,  // 일관성 있는 응답
            "max_tokens" to 500,   // 비용 절감
            "response_format" to mapOf("type" to "json_object")  // JSON 강제
        )
    }

    private fun parseResponse(response: String): ReviewSummaryResponse {
        // 1. JSON 추출 (마크다운 코드 블록 제거)
        val cleanedResponse = response
            .replace("```json", "")
            .replace("```", "")
            .trim()

        // 2. JSON 파싱
        val parsed = try {
            objectMapper.readValue(cleanedResponse, Map::class.java)
        } catch (e: Exception) {
            // OpenAI 응답에서 choices[0].message.content 추출 시도
            val choices = objectMapper.readTree(response)
                .get("choices")?.get(0)?.get("message")?.get("content")?.asText()
                ?: throw IllegalArgumentException("OpenAI 응답 형식이 올바르지 않습니다")

            objectMapper.readValue(choices, Map::class.java)
        }

        // 3. DTO 변환 및 검증
        return ReviewSummaryResponse(
            oneLine = (parsed["oneLine"] as? String)?.take(100) ?: "",
            pros = ((parsed["pros"] as? List<*>)?.mapNotNull { it as? String })?.take(5) ?: emptyList(),
            cons = ((parsed["cons"] as? List<*>)?.mapNotNull { it as? String })?.take(3) ?: emptyList(),
            bestFor = ((parsed["bestFor"] as? List<*>)?.mapNotNull { it as? String })?.take(3) ?: emptyList()
        )
    }
}
```

### 3.3 리뷰 요약 서비스

```kotlin
// ReviewSummaryService.kt
package org.depromeet.team3.llm.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import org.depromeet.team3.llm.client.OpenAIClient
import org.depromeet.team3.llm.model.ReviewSummaryRequest
import org.depromeet.team3.llm.model.ReviewSummaryResponse
import org.depromeet.team3.place.model.PlaceDetailsResponse
import org.springframework.stereotype.Service

@Service
class ReviewSummaryService(
    private val openAIClient: OpenAIClient,
    private val reviewSummaryCache: ReviewSummaryCache
) {
    private val logger = KotlinLogging.logger { }

    /**
     * 장소의 리뷰를 요약
     * 
     * @param googlePlaceId Google Place ID (캐싱 키)
     * @param placeDetails Place Details 응답 (리뷰 포함)
     * @return ReviewSummaryResponse (실패 시 empty)
     */
    suspend fun summarize(
        googlePlaceId: String,
        placeDetails: PlaceDetailsResponse
    ): ReviewSummaryResponse = supervisorScope {
        // 1. 캐시 확인
        val cached = reviewSummaryCache.get(googlePlaceId)
        if (cached != null) {
            logger.debug { "캐시 히트: placeId=$googlePlaceId" }
            return@supervisorScope cached
        }

        // 2. 리뷰 데이터 준비
        val reviews = placeDetails.reviews?.take(30) ?: emptyList()
        if (reviews.isEmpty()) {
            logger.debug { "리뷰 없음: placeId=$googlePlaceId" }
            return@supervisorScope ReviewSummaryResponse.empty()
        }

        // 3. LLM 호출 (비동기, 실패해도 기존 로직 영향 없음)
        val summary = try {
            val request = ReviewSummaryRequest(
                restaurantName = placeDetails.displayName?.text ?: "식당",
                rating = placeDetails.rating,
                reviewCount = placeDetails.userRatingCount,
                reviews = reviews.map { review ->
                    ReviewSummaryRequest.ReviewInput(
                        text = review.text?.text ?: "",
                        rating = review.rating
                    )
                }
            )

            openAIClient.summarizeReviews(request)
        } catch (e: Exception) {
            logger.warn(e) { "리뷰 요약 실패: placeId=$googlePlaceId" }
            null
        }

        // 4. 결과 처리
        val result = summary ?: ReviewSummaryResponse.empty()
        
        // 5. 캐시 저장 (empty도 저장하여 재호출 방지)
        reviewSummaryCache.put(googlePlaceId, result)

        result
    }
}
```

### 3.4 캐싱 구현

```kotlin
// ReviewSummaryCache.kt
package org.depromeet.team3.llm.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.depromeet.team3.llm.model.ReviewSummaryResponse
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class ReviewSummaryCache {
    // place_id를 키로 사용 (Google Place ID)
    private val cache: Cache<String, ReviewSummaryResponse> = Caffeine.newBuilder()
        .maximumSize(10_000)  // 최대 10,000개 캐시
        .expireAfterWrite(30, TimeUnit.DAYS)  // 30일 TTL
        .recordStats()  // 통계 수집
        .build()

    fun get(googlePlaceId: String): ReviewSummaryResponse? {
        return cache.getIfPresent(googlePlaceId)
    }

    fun put(googlePlaceId: String, summary: ReviewSummaryResponse) {
        cache.put(googlePlaceId, summary)
    }

    fun getStats() = cache.stats()
}
```

### 3.5 설정 프로퍼티

```kotlin
// OpenAIApiProperties.kt
package org.depromeet.team3.llm.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "api.openai")
data class OpenAIApiProperties(
    var apiKey: String = "",
    var baseUrl: String = "https://api.openai.com",
    var model: String = "gpt-4o-mini",  // 비용 효율적인 모델
    var enabled: Boolean = false  // 기능 활성화 플래그
)
```

### 3.6 RestClient 설정

```kotlin
// OpenAIClientConfiguration.kt
package org.depromeet.team3.llm.client

import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.depromeet.team3.llm.properties.OpenAIApiProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@ConditionalOnProperty(prefix = "api.openai", name = ["api-key"])
@EnableConfigurationProperties(OpenAIApiProperties::class)
class OpenAIClientConfiguration(
    private val properties: OpenAIApiProperties
) {
    @Bean
    fun openAIRestClient(): RestClient {
        return RestClient.builder()
            .requestFactory(openAIHttpRequestFactory())
            .baseUrl(properties.baseUrl)
            .build()
    }

    @Bean
    fun openAIHttpRequestFactory(): ClientHttpRequestFactory {
        val httpClient = HttpClients.custom()
            .setConnectionManager(openAIConnectionManager())
            .build()

        return HttpComponentsClientHttpRequestFactory(httpClient).apply {
            setConnectTimeout(Duration.ofSeconds(3))
            setReadTimeout(Duration.ofSeconds(5))
            setConnectionRequestTimeout(Duration.ofSeconds(1))
        }
    }

    @Bean
    fun openAIConnectionManager(): PoolingHttpClientConnectionManager {
        return PoolingHttpClientConnectionManager().apply {
            maxTotal = 5
            defaultMaxPerRoute = 2
        }
    }
}
```

### 3.7 통합 지점

```kotlin
// ExecutePlaceSearchService.kt 수정
// 기존 코드는 그대로 유지하고, PlaceItem 생성 시에만 추가

val items = savedEntities.mapNotNull { entity ->
    runCatching {
        val googleId = entity.googlePlaceId ?: return@mapNotNull null
        val placeDbId = entity.id ?: return@mapNotNull null
        val likeInfo = likesMap[googleId] ?: PlaceLikeInfo(0, false)

        // LLM 요약 정보 가져오기 (비동기, 실패해도 계속 진행)
        val summary = if (properties.openai.enabled && entity.googlePlaceId != null) {
            // Place Details 조회 (리뷰 포함)
            val placeDetails = placeQuery.getPlaceDetails(entity.googlePlaceId)
            if (placeDetails != null) {
                reviewSummaryService.summarize(entity.googlePlaceId, placeDetails)
            } else {
                ReviewSummaryResponse.empty()
            }
        } else {
            ReviewSummaryResponse.empty()
        }

        PlacesSearchResponse.PlaceItem(
            placeId = placeDbId,
            name = entity.name ?: "",
            // ... 기존 필드들 ...
            likeCount = likeInfo.likeCount,
            isLiked = likeInfo.isLiked,
            // 새로 추가되는 필드
            summary = PlacesSearchResponse.PlaceItem.Summary(
                oneLine = summary.oneLine,
                pros = summary.pros,
                cons = summary.cons,
                bestFor = summary.bestFor
            )
        )
    }.onFailure { e ->
        logger.warn("장소 응답 변환 실패: googleId=${entity.googlePlaceId}, error=${e.message}")
    }.getOrNull()
}
```

### 3.8 응답 DTO 확장

```kotlin
// PlacesSearchResponse.kt 수정
data class PlaceItem(
    // ... 기존 필드들 ...
    val summary: Summary? = null  // LLM 요약 정보 (옵셔널)
) {
    // ... 기존 내부 클래스들 ...
    
    data class Summary(
        val oneLine: String,
        val pros: List<String>,
        val cons: List<String>,
        val bestFor: List<String>
    )
}
```

---

## 4. 설정 파일

### application.yml
```yaml
api:
  openai:
    api-key: ${OPENAI_API_KEY:}
    base-url: https://api.openai.com
    model: gpt-4o-mini  # 또는 gpt-3.5-turbo (더 저렴)
    enabled: ${OPENAI_ENABLED:false}  # 기본값 false (안전장치)
```

---

## 5. 에러 처리 및 Fallback 전략

### 5.1 다층 Fallback
```
1. LLM 호출 시도
   ↓ 실패
2. 캐시 확인 (이전 성공 결과)
   ↓ 없음
3. ReviewSummaryResponse.empty() 반환
   ↓
4. PlaceItem.summary = null (옵셔널 필드)
   ↓
5. 클라이언트는 summary가 null이면 표시하지 않음
```

### 5.2 JSON 검증
```kotlin
// OpenAIClient.kt의 parseResponse 메서드에서
private fun parseResponse(response: String): ReviewSummaryResponse {
    return try {
        // 1. JSON 추출
        val jsonContent = extractJsonContent(response)
        
        // 2. 파싱
        val parsed = objectMapper.readValue(jsonContent, Map::class.java)
        
        // 3. 필수 필드 검증
        require(parsed.containsKey("oneLine")) { "oneLine 필드 누락" }
        require(parsed.containsKey("pros")) { "pros 필드 누락" }
        require(parsed.containsKey("cons")) { "cons 필드 누락" }
        require(parsed.containsKey("bestFor")) { "bestFor 필드 누락" }
        
        // 4. DTO 변환
        ReviewSummaryResponse(
            oneLine = (parsed["oneLine"] as? String)?.take(100) ?: "",
            pros = ((parsed["pros"] as? List<*>)?.mapNotNull { it as? String })?.take(5) ?: emptyList(),
            cons = ((parsed["cons"] as? List<*>)?.mapNotNull { it as? String })?.take(3) ?: emptyList(),
            bestFor = ((parsed["bestFor"] as? List<*>)?.mapNotNull { it as? String })?.take(3) ?: emptyList()
        )
    } catch (e: Exception) {
        logger.warn(e) { "JSON 파싱 실패, 빈 응답 반환" }
        ReviewSummaryResponse.empty()
    }
}

private fun extractJsonContent(response: String): String {
    // OpenAI 응답에서 JSON 추출
    return when {
        // choices[0].message.content 형식
        response.contains("\"content\"") -> {
            val jsonNode = objectMapper.readTree(response)
            jsonNode.get("choices")?.get(0)?.get("message")?.get("content")?.asText()
                ?: throw IllegalArgumentException("content 필드 없음")
        }
        // 직접 JSON
        response.trim().startsWith("{") -> response.trim()
        // 마크다운 코드 블록
        else -> response
            .replace("```json", "")
            .replace("```", "")
            .trim()
    }
}
```

### 5.3 타임아웃 처리
- **5초 타임아웃**: LLM 호출이 5초 초과 시 즉시 실패 처리
- **비동기 처리**: LLM 호출이 메인 응답 시간에 영향 없도록
- **Circuit Breaker 패턴**: 연속 실패 시 일정 시간 호출 중단 (선택적)

---

## 6. 비용 관리

### 6.1 예상 비용 (gpt-4o-mini 기준)
- **입력 토큰**: ~500 토큰/요청 (리뷰 30개 기준)
- **출력 토큰**: ~200 토큰/요청
- **비용**: $0.15/1M 입력 토큰, $0.60/1M 출력 토큰
- **요청당 비용**: 약 $0.0002 (0.02원)

### 6.2 비용 절감 전략
1. **캐싱**: place_id 기반 30일 캐싱
2. **리뷰 제한**: 최대 30개만 전송
3. **모델 선택**: gpt-4o-mini (gpt-4o의 1/10 비용)
4. **배치 처리**: 여러 장소를 한 번에 처리 (선택적)

### 6.3 모니터링
```kotlin
// ReviewSummaryCache에 통계 추가
fun getStats(): CacheStats {
    return cache.stats().apply {
        logger.info {
            "캐시 통계: 히트율=${hitRate()}, 미스율=${missRate()}, " +
            "크기=${cache.estimatedSize()}"
        }
    }
}
```

---

## 7. 테스트 전략

### 7.1 단위 테스트
```kotlin
// ReviewSummaryServiceTest.kt
@Test
fun `LLM 실패 시 빈 응답 반환`() = runTest {
    // Given
    val mockClient = mock<OpenAIClient> {
        onBlocking { summarizeReviews(any()) } doReturn null
    }
    val service = ReviewSummaryService(mockClient, mockCache)
    
    // When
    val result = service.summarize("place_id", placeDetails)
    
    // Then
    assertEquals(ReviewSummaryResponse.empty(), result)
}
```

### 7.2 통합 테스트
- 실제 OpenAI API 호출 (테스트용 API 키)
- JSON 파싱 검증
- 캐싱 동작 확인

---

## 8. 배포 전 체크리스트

- [ ] `api.openai.enabled=false`로 시작 (기본값)
- [ ] 환경변수 `OPENAI_API_KEY` 설정
- [ ] 캐시 TTL 확인 (30일)
- [ ] 타임아웃 설정 확인 (5초)
- [ ] Fallback 동작 확인 (LLM 실패 시 빈 응답)
- [ ] 비용 모니터링 설정
- [ ] 로깅 레벨 설정 (WARN 이상)

---

## 9. 절대 하지 말아야 할 것

### ❌ 금지 사항

1. **랭킹 로직에 LLM 사용 금지**
   ```kotlin
   // ❌ 절대 이렇게 하지 마세요
   val aiScore = llmService.calculateScore(place)
   val finalScore = baseScore + aiScore  // NO!
   ```

2. **LLM 결과를 필수 필드로 만들지 않기**
   ```kotlin
   // ❌ 이렇게 하지 마세요
   val summary: Summary  // 필수 필드
   
   // ✅ 이렇게 하세요
   val summary: Summary? = null  // 옵셔널 필드
   ```

3. **동기 처리로 메인 응답 지연시키지 않기**
   ```kotlin
   // ❌ 이렇게 하지 마세요
   val summary = reviewSummaryService.summarize(...)  // 동기 대기
   
   // ✅ 이렇게 하세요
   val summary = async { reviewSummaryService.summarize(...) }.await()  // 비동기
   ```

4. **캐싱 없이 LLM 호출하지 않기**
   ```kotlin
   // ❌ 절대 이렇게 하지 마세요
   val summary = openAIClient.summarizeReviews(request)  // 캐시 없음
   ```

5. **에러 발생 시 전체 응답 실패시키지 않기**
   ```kotlin
   // ❌ 이렇게 하지 마세요
   val summary = reviewSummaryService.summarize(...) ?: throw Exception()
   
   // ✅ 이렇게 하세요
   val summary = reviewSummaryService.summarize(...) ?: ReviewSummaryResponse.empty()
   ```

---

## 10. 점진적 롤아웃 전략

### Phase 1: 기능 비활성화 상태로 배포
- 코드는 배포하되 `enabled=false`
- 인프라 검증

### Phase 2: 소규모 테스트
- 특정 meetingId에만 활성화
- 모니터링 및 비용 확인

### Phase 3: 전체 활성화
- 모든 요청에 적용
- 지속적 모니터링

---

## 11. 모니터링 지표

1. **성공률**: LLM 호출 성공 비율
2. **응답 시간**: 평균 LLM 응답 시간
3. **캐시 히트율**: 캐시 사용률
4. **비용**: 일일/월별 OpenAI 사용 비용
5. **에러율**: JSON 파싱 실패율

---

## 결론

이 설계는:
- ✅ **기존 로직 보존**: 랭킹 로직은 절대 변경하지 않음
- ✅ **프로덕션 안전**: Fallback, 캐싱, 타임아웃 모두 포함
- ✅ **비용 효율적**: 캐싱, 저렴한 모델 사용
- ✅ **점진적 도입**: enabled 플래그로 단계적 활성화
- ✅ **최소 변경**: 기존 코드 영향 최소화

**핵심 원칙**: LLM은 "장식"일 뿐, 핵심 로직은 기존대로 유지
