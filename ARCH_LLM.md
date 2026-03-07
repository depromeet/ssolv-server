# LLM Architecture 및 데이터 흐름 요약

본 문서에서는 현재 프로젝트의 LLM 연동 구조와 데이터 처리 방식에 대해 설명합니다.

## 1. LLM 클라이언트 추상화

특정 모델(Gemini)에 의존하지 않도록 `LlmClient` 인터페이스를 통해 추상화되어 있습니다.

```kotlin
interface LlmClient {
    suspend fun chat(prompt: String): String?
    suspend fun chatWithJsonResponse(prompt: String): String?
}
```

### 1.1 구현체: GeminiLlmClient

Spring 3.2+의 `RestClient`를 사용하여 Google Gemini API를 호출합니다.

```kotlin
@Component
class GeminiLlmClient(
    private val geminiProperties: GeminiProperties,
    private val geminiRestClient: RestClient
) : LlmClient {
    
    override suspend fun chatWithJsonResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = GeminiRequest(
                contents = listOf(
                    GeminiRequest.Content(
                        parts = listOf(GeminiRequest.Part(text = prompt))
                    )
                ),
                generationConfig = GeminiRequest.GenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.1
                )
            )

            val response = geminiRestClient.post()
                .uri { it.path("/v1beta/models/${geminiProperties.model}:generateContent")
                    .queryParam("key", geminiProperties.apiKey)
                    .build() 
                }
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body<GeminiResponse>()
                
            response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: Exception) {
            logger.error(e) { "Gemini JSON 호출 실패: ${e.message}" }
            null
        }
    }
}
```

### 1.2 설정 관리

`@ConfigurationProperties`를 통해 환경별 설정을 관리합니다.

```kotlin
@ConfigurationProperties(prefix = "api.gemini")
data class GeminiProperties(
    var apiKey: String = "",
    var baseUrl: String = "https://generativelanguage.googleapis.com",
    var model: String = "gemini-2.5-flash"
)
```

## 2. 데이터 흐름 및 필드 매핑

장소(Place) 데이터는 Google Places API를 기점으로 DB를 거쳐 사용자에게 전달됩니다.

### 2.1 Extraction (Google API -> Entity)

`PlaceQuery.savePlacesFromTextSearch()`에서 Google Places API 응답을 엔티티로 변환합니다.

```kotlin
@Transactional
suspend fun savePlacesFromTextSearch(
    places: List<PlacesTextSearchResponse.Place>
): List<PlaceEntity> {
    val googlePlaceIds = places.map { it.id }
    val existingPlaces = placeJpaRepository.findByGooglePlaceIdIn(googlePlaceIds)
        .associateBy { it.googlePlaceId }

    val entities = places.map { place ->
        val existing = existingPlaces[place.id]
        val lastUpdated = existing?.updatedAt ?: LocalDateTime.MIN

        // 30일 이내 데이터가 있으면 업데이트 스킵 (비용 최적화)
        if (existing != null && lastUpdated.isAfter(LocalDateTime.now().minusDays(30))) {
            return@map existing
        }

        PlaceEntity(
            id = existing?.id,
            googlePlaceId = existing?.googlePlaceId ?: place.id,
            name = place.displayName?.text ?: existing?.name ?: "Unknown",
            address = place.formattedAddress ?: existing?.address ?: "",
            rating = place.rating ?: existing?.rating ?: 0.0,
            photos = place.photos?.take(5)?.joinToString(",") { it.name } ?: existing?.photos,
            // ... 기타 필드 매핑
        )
    }

    return placeJpaRepository.saveAll(entities).toList()
}
```

### 2.2 Analysis (Entity -> LLM -> Entity)

`SearchPlaceLlmService`가 프롬프트를 생성하고 LLM 응답을 파싱합니다.

```kotlin
@Service
class SearchPlaceLlmService(
    private val llmClient: LlmClient,
    private val promptService: SearchPlaceLlmPromptService,
    private val objectMapper: ObjectMapper
) {
    suspend fun getPlaceLlmInfo(place: PlaceDetailsResponse): PlaceLlmAnalysisResult {
        val prompt = promptService.createPlaceInsightPrompt(place)
        val response = llmClient.chatWithJsonResponse(prompt)

        return try {
            response?.let { 
                objectMapper.readValue(it, PlaceLlmAnalysisResult::class.java)
            } ?: PlaceLlmAnalysisResult()
        } catch (e: Exception) {
            logger.warn(e) { "LLM 응답 파싱 실패: ${e.message}" }
            PlaceLlmAnalysisResult()
        }
    }
}
```

### 2.3 Response (Entity -> DTO)

최종적으로 `PlacesSearchResponse.PlaceItem`으로 변환되어 클라이언트에 전달됩니다.

## 3. 동기/비동기 및 자원 관리

### 3.1 Coroutine Scope 관리

`supervisorScope`를 사용하여 자식 코루틴의 실패가 전체 흐름에 영향을 주지 않도록 관리합니다.

```kotlin
suspend fun search(request: PlacesSearchRequest, plan: PlaceSearchPlan): PlacesSearchResponse = 
    withContext(MDCContext()) {
        supervisorScope {
            // 검색 로직
        }
    }
```

### 3.2 병렬 처리 (Parallel Processing)

여러 장소에 대한 LLM 분석을 병렬로 수행하여 성능을 최적화합니다.

```kotlin
suspend fun applyLlmDetails(
    items: List<PlacesSearchResponse.PlaceItem>,
    entities: List<PlaceEntity>,
    filteringResults: Map<String, PlaceLlmFilterResult>
): List<PlacesSearchResponse.PlaceItem> = supervisorScope {
    items.map { item ->
        async {
            runCatching {
                // LLM 분석 수행
                val details = placeQuery.getPlaceDetails(googlePlaceId) ?: return@runCatching item
                val llmResult = searchPlaceLlmService.getPlaceLlmInfo(details)
                // 결과 반영
            }.getOrElse { e ->
                logger.warn("장소 LLM 상세 정보 적용 실패: ${e.message}")
                item
            }
        }
    }.awaitAll()
}
```

### 3.3 Context 관리

- `withContext(MDCContext())`: 비동기 로그 추적(Tracing)을 유지
- `withContext(Dispatchers.IO)`: 블로킹 I/O 작업(DB 저장, HTTP 호출)을 전용 스레드 풀에서 실행

### 3.4 트랜잭션 관리

`@Transactional` 어노테이션을 통해 데이터 정합성을 보장하며, 코루틴 내에서도 트랜잭션 컨텍스트가 유지됩니다.

```kotlin
@Transactional
fun updateLlmData(
    googlePlaceId: String,
    summary: String? = null,
    landmarks: String? = null,
    reason: String? = null
) {
    placeJpaRepository.findByGooglePlaceId(googlePlaceId)?.let { entity ->
        val updated = entity.copy(
            llmSummary = summary ?: entity.llmSummary,
            addressDescriptor = landmarks ?: entity.addressDescriptor,
            llmReason = reason ?: entity.llmReason
        )
        placeJpaRepository.save(updated)
    }
}
```

## 4. 예외 및 지연 처리

### 4.1 재시도 로직 (Exponential Backoff)

Google Places API 호출 시 일시적 오류에 대비한 재시도 로직을 구현했습니다.

```kotlin
private suspend fun <T> retryWithExponentialBackoff(
    operation: String,
    operationDetail: String = "",
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    var delayMillis = 100L

    for (attempt in 0 until 3) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpClientErrorException) {
            val statusCode = e.statusCode.value()
            if (statusCode == 429 || statusCode in 500..504) {
                if (attempt < 2) {
                    val jitter = Random.nextLong(0, 100L)
                    delay(delayMillis + jitter)
                    delayMillis = minOf(delayMillis * 2, 2000L)
                }
            } else {
                throw e
            }
        }
    }
    
    throw lastException ?: Exception("Operation failed")
}
```

### 4.2 Timeout 설정

모든 외부 API 호출에 타임아웃을 설정하여 무한 대기를 방지합니다.

```kotlin
suspend fun textSearch(query: String): PlacesTextSearchResponse = 
    withContext(Dispatchers.IO) {
        retryWithExponentialBackoff("텍스트 검색", "query=$query") {
            withTimeout(5_000L) {
                // API 호출
            }
        }
    }
```

### 4.3 Graceful Degradation

LLM 분석 실패 시에도 기본 검색 결과는 제공됩니다.

```kotlin
suspend fun filterByCriteria(
    places: List<PlacesTextSearchResponse.Place>,
    criteria: String
): Map<String, PlaceLlmFilterResult> {
    if (geminiProperties.apiKey.isBlank()) return emptyMap()

    return try {
        val results = searchPlaceLlmService.filterCandidateByBasicInfo(places, criteria)
        results.associateBy { it.id }
    } catch (e: Exception) {
        logger.warn("LLM 필터링 실패: criteria={}, error={}", criteria, e.message)
        emptyMap()
    }
}
```

### 4.4 JSON Parsing Safety

LLM 응답이 유효하지 않은 JSON일 경우를 대비해 기본값을 반환합니다.

```kotlin
return try {
    response?.let { 
        objectMapper.readValue(it, PlaceLlmAnalysisResult::class.java)
    } ?: PlaceLlmAnalysisResult()
} catch (e: Exception) {
    logger.warn(e) { "LLM 응답 파싱 실패: ${e.message}" }
    PlaceLlmAnalysisResult()
}
```

## 5. 프롬프트 엔지니어링 전략

### 5.1 구조화된 JSON 응답 유도

프롬프트에 명확한 JSON 스키마를 제시하여 파싱 가능한 응답을 유도합니다.

```kotlin
fun createPlaceInsightPrompt(place: PlaceDetailsResponse): String {
    return """
    너는 장소 정보를 분석하여 사용자에게 유익한 정보를 제공하는 도우미야.
    다음 정보를 바탕으로 장소의 특징을 요약하고 주변 랜드마크를 분석해서 JSON 형식으로 응답해줘.

    [장소 정보]
    이름: ${place.displayName?.text}
    유형: ${place.types?.joinToString(", ")}
    주소: ${place.formattedAddress}
    평점: ${place.rating} (리뷰 ${place.userRatingCount}개)

    [응답 형식]
    {
      "summary": "장소의 특징을 한 문장으로 요약",
      "landmarks": ["주소 주위의 유명한 건물, 역, 명소 리스트 (최대 3개)"]
    }
    
    주의사항:
    - summary는 공백 포함 최대 50자, 문장 하나로 작성해.
    - landmarks는 누구나 알법한 유명한 장소나 대중교통역 위주로 골라줘.
    - 한국어로 응답해.
    - JSON 결과 외의 다른 설명은 하지 마.
    """.trimIndent()
}
```

### 5.2 Temperature 조정

JSON 응답이 필요한 경우 `temperature=0.1`로 설정하여 일관성 있는 응답을 유도합니다.

## 6. 성능 최적화 전략

### 6.1 캐싱 전략

- DB 레벨 캐싱: 30일 이내 업데이트된 장소는 재호출하지 않음
- LLM 결과 캐싱: `llmSummary`, `addressDescriptor` 등을 DB에 저장하여 재사용

### 6.2 비용 최적화

- Google Places API: 30일 캐싱으로 불필요한 호출 방지
- Gemini API: 필터링 단계에서 기본 정보만 사용하여 토큰 사용량 최소화
