# OpenAI 호출량 최적화 전략: DB 캐싱 및 배치 처리

## 📊 현재 상황 분석

### 호출 패턴
- **검색 시**: 최대 10개 장소 반환
- **LLM 호출 필요**: 각 장소마다 1회 (최대 10회/검색)
- **중복 가능성**: 같은 장소가 여러 검색에서 반복 등장
- **기존 캐싱**: PlaceEntity에 30일 캐싱 전략 이미 존재

### 문제점
1. **인메모리 캐싱만으로는 부족**
   - 서버 재시작 시 캐시 손실
   - 여러 서버 인스턴스 간 캐시 공유 불가
   - 메모리 제한 (10,000개 제한)

2. **실시간 호출 비용**
   - 검색 요청마다 최대 10회 LLM 호출
   - 같은 장소를 여러 사용자가 검색하면 중복 호출

---

## 🎯 최적화 전략

### 1. DB 기반 영구 캐싱 (필수)

**이유**:
- ✅ 서버 재시작 후에도 캐시 유지
- ✅ 여러 서버 인스턴스 간 자동 공유
- ✅ 영구 저장으로 비용 절감
- ✅ 기존 `PlaceEntity` 패턴과 일관성

### 2. 2단계 캐싱 (인메모리 + DB)

**구조**:
```
1차: 인메모리 캐시 (Caffeine) - 빠른 접근
  ↓ 미스
2차: DB 캐시 (PlaceEntity) - 영구 저장
  ↓ 미스
3차: OpenAI API 호출
```

**간단히 말하면**:
- **DB에 영구 저장**: 맞습니다. `PlaceEntity`에 LLM 요약 정보를 저장합니다.
- **인메모리 캐시는 성능 최적화**: DB 조회는 느리니까(수십 ms), 자주 쓰는 건 메모리에 올려서 빠르게(수 마이크로초) 접근합니다.

**실제 동작**:
1. 첫 번째 요청: DB에 없음 → OpenAI 호출 → **DB에 저장** + 인메모리에 저장
2. 두 번째 요청: 인메모리에 있음 → **인메모리에서 바로 반환** (DB 조회 안 함)
3. 서버 재시작 후: 인메모리 캐시 사라짐 → DB에서 조회 → 인메모리에 다시 올림

**결론**: 
- **핵심은 DB 저장**입니다. 인메모리는 성능 향상을 위한 보조 수단입니다.
- DB만 써도 되지만, 인메모리 캐시를 추가하면 DB 조회 횟수가 줄어서 더 빠릅니다.

### 3. 배치 처리 (선택적)

**목적**: 인기 장소를 미리 처리하여 실시간 호출 최소화

---

## 📐 DB 설계

### Option 1: PlaceEntity에 필드 추가 (권장)

**장점**:
- 기존 테이블 활용 (별도 테이블 불필요)
- 조인 없이 한 번에 조회
- 기존 30일 캐싱 전략과 일관성

**단점**:
- PlaceEntity가 약간 커짐 (하지만 TEXT 필드라 영향 적음)

#### 스키마 변경

```sql
ALTER TABLE tb_place 
ADD COLUMN llm_summary_one_line VARCHAR(200) NULL,
ADD COLUMN llm_summary_pros TEXT NULL,
ADD COLUMN llm_summary_cons TEXT NULL,
ADD COLUMN llm_summary_best_for TEXT NULL,
ADD COLUMN llm_summary_updated_at DATETIME NULL;

-- 인덱스 (선택적, 조회 최적화)
CREATE INDEX idx_llm_summary_updated_at ON tb_place(llm_summary_updated_at);
```

#### Entity 수정

```kotlin
// PlaceEntity.kt 수정
@Entity
@Table(name = "tb_place")
class PlaceEntity(
    // ... 기존 필드들 ...
    
    // LLM 요약 정보 (새로 추가)
    @Column(name = "llm_summary_one_line", length = 200)
    val llmSummaryOneLine: String? = null,
    
    @Column(name = "llm_summary_pros", columnDefinition = "TEXT")
    val llmSummaryPros: String? = null,  // JSON 배열 문자열: ["장점1", "장점2"]
    
    @Column(name = "llm_summary_cons", columnDefinition = "TEXT")
    val llmSummaryCons: String? = null,  // JSON 배열 문자열: ["단점1", "단점2"]
    
    @Column(name = "llm_summary_best_for", columnDefinition = "TEXT")
    val llmSummaryBestFor: String? = null,  // JSON 배열 문자열: ["데이트", "가족모임"]
    
    @Column(name = "llm_summary_updated_at")
    val llmSummaryUpdatedAt: LocalDateTime? = null
) : BaseTimeEntity()
```

**JSON 저장 방식**:
- `pros`, `cons`, `bestFor`는 JSON 배열 문자열로 저장
- Jackson으로 직렬화/역직렬화
- 예: `["맛있음", "친절함", "깨끗함"]`

### Option 2: 별도 테이블 (비권장)

**이유**: 조인이 필요하고 복잡도만 증가

---

## 🔄 캐싱 로직 개선

### ReviewSummaryCache 개선

```kotlin
// ReviewSummaryCache.kt
package org.depromeet.team3.llm.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.depromeet.team3.llm.model.ReviewSummaryResponse
import org.depromeet.team3.place.PlaceEntity
import org.depromeet.team3.place.PlaceJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@Component
class ReviewSummaryCache(
    private val placeJpaRepository: PlaceJpaRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger { }
    
    // 1차 캐시: 인메모리 (빠른 접근)
    private val memoryCache: Cache<String, ReviewSummaryResponse> = Caffeine.newBuilder()
        .maximumSize(1_000)  // 최대 1,000개 (DB 캐시가 있으므로 작게)
        .expireAfterWrite(1, TimeUnit.HOURS)  // 1시간 TTL
        .recordStats()
        .build()

    /**
     * 캐시에서 요약 정보 조회 (2단계)
     * 1. 인메모리 캐시 확인
     * 2. DB 캐시 확인
     */
    fun get(googlePlaceId: String): ReviewSummaryResponse? {
        // 1차: 인메모리 캐시
        val memoryCached = memoryCache.getIfPresent(googlePlaceId)
        if (memoryCached != null) {
            logger.debug { "인메모리 캐시 히트: placeId=$googlePlaceId" }
            return memoryCached
        }

        // 2차: DB 캐시
        val placeEntity = placeJpaRepository.findByGooglePlaceId(googlePlaceId)
        if (placeEntity != null && placeEntity.llmSummaryOneLine != null) {
            val dbCached = convertFromEntity(placeEntity)
            if (dbCached != null) {
                // 인메모리 캐시에도 저장
                memoryCache.put(googlePlaceId, dbCached)
                logger.debug { "DB 캐시 히트: placeId=$googlePlaceId" }
                return dbCached
            }
        }

        logger.debug { "캐시 미스: placeId=$googlePlaceId" }
        return null
    }

    /**
     * 캐시에 요약 정보 저장 (2단계)
     * 1. 인메모리 캐시 저장
     * 2. DB 캐시 저장
     */
    fun put(googlePlaceId: String, summary: ReviewSummaryResponse) {
        // 1차: 인메모리 캐시
        memoryCache.put(googlePlaceId, summary)

        // 2차: DB 캐시
        val placeEntity = placeJpaRepository.findByGooglePlaceId(googlePlaceId)
        if (placeEntity != null) {
            val updatedEntity = placeEntity.copy(
                llmSummaryOneLine = summary.oneLine.take(200),
                llmSummaryPros = objectMapper.writeValueAsString(summary.pros),
                llmSummaryCons = objectMapper.writeValueAsString(summary.cons),
                llmSummaryBestFor = objectMapper.writeValueAsString(summary.bestFor),
                llmSummaryUpdatedAt = LocalDateTime.now()
            )
            placeJpaRepository.save(updatedEntity)
            logger.debug { "DB 캐시 저장: placeId=$googlePlaceId" }
        } else {
            logger.warn { "PlaceEntity 없음, DB 캐시 저장 실패: placeId=$googlePlaceId" }
        }
    }

    /**
     * PlaceEntity에서 ReviewSummaryResponse로 변환
     */
    private fun convertFromEntity(entity: PlaceEntity): ReviewSummaryResponse? {
        return try {
            ReviewSummaryResponse(
                oneLine = entity.llmSummaryOneLine ?: "",
                pros = entity.llmSummaryPros?.let { 
                    objectMapper.readValue(it, Array<String>::class.java).toList() 
                } ?: emptyList(),
                cons = entity.llmSummaryCons?.let { 
                    objectMapper.readValue(it, Array<String>::class.java).toList() 
                } ?: emptyList(),
                bestFor = entity.llmSummaryBestFor?.let { 
                    objectMapper.readValue(it, Array<String>::class.java).toList() 
                } ?: emptyList()
            )
        } catch (e: Exception) {
            logger.warn(e) { "PlaceEntity에서 ReviewSummaryResponse 변환 실패: placeId=${entity.googlePlaceId}" }
            null
        }
    }

    /**
     * 캐시 통계
     */
    fun getStats() = memoryCache.stats()
}
```

### PlaceJpaRepository 확장

```kotlin
// PlaceJpaRepository.kt 수정
package org.depromeet.team3.place

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface PlaceJpaRepository : JpaRepository<PlaceEntity, Long> {
    // 기존 메서드들
    fun findByGooglePlaceIdIn(googlePlaceIds: List<String>): List<PlaceEntity>
    fun deleteByUpdatedAtBefore(dateTime: LocalDateTime): Int
    
    // 새로 추가되는 메서드들
    fun findByGooglePlaceId(googlePlaceId: String): PlaceEntity?
    
    // LLM 요약이 없는 장소 조회 (배치 처리용)
    @Query("""
        SELECT p FROM PlaceEntity p 
        WHERE p.llmSummaryOneLine IS NULL 
        AND p.isDeleted = false
        AND p.userRatingsTotal > 0
        ORDER BY p.userRatingsTotal DESC, p.rating DESC
    """)
    fun findPlacesWithoutLLMSummary(@Param("limit") limit: Int): List<PlaceEntity>
    
    // LLM 요약이 오래된 장소 조회 (재생성용, 30일 이상 경과)
    @Query("""
        SELECT p FROM PlaceEntity p 
        WHERE p.llmSummaryOneLine IS NOT NULL 
        AND (p.llmSummaryUpdatedAt IS NULL OR p.llmSummaryUpdatedAt < :threshold)
        AND p.isDeleted = false
        ORDER BY p.userRatingsTotal DESC
    """)
    fun findPlacesWithStaleLLMSummary(
        @Param("threshold") threshold: LocalDateTime,
        @Param("limit") limit: Int
    ): List<PlaceEntity>
}
```

**주의**: Spring Data JPA의 `@Query`에서 `limit` 파라미터는 직접 사용할 수 없으므로, 서비스 레이어에서 처리:

```kotlin
// 실제 사용 시
fun findPlacesWithoutLLMSummary(limit: Int): List<PlaceEntity> {
    return placeJpaRepository.findAll { root, query, cb ->
        cb.and(
            cb.isNull(root.get<PlaceEntity>("llmSummaryOneLine")),
            cb.isFalse(root.get<PlaceEntity>("isDeleted")),
            cb.greaterThan(root.get<PlaceEntity>("userRatingsTotal"), 0)
        )
    }.sortedByDescending { it.userRatingsTotal }
     .sortedByDescending { it.rating }
     .take(limit)
}
```

또는 더 간단하게:

```kotlin
@Query("""
    SELECT p FROM PlaceEntity p 
    WHERE p.llmSummaryOneLine IS NULL 
    AND p.isDeleted = false
    AND p.userRatingsTotal > 0
    ORDER BY p.userRatingsTotal DESC, p.rating DESC
""")
fun findPlacesWithoutLLMSummary(): List<PlaceEntity>

// 서비스에서
fun findPlacesWithoutLLMSummary(limit: Int): List<PlaceEntity> {
    return placeJpaRepository.findPlacesWithoutLLMSummary().take(limit)
}
```

---

## ⚙️ 배치 처리 구현

### 배치 스케줄러

```kotlin
// LLMSummaryBatchScheduler.kt
package org.depromeet.team3.batch.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.depromeet.team3.llm.service.ReviewSummaryService
import org.depromeet.team3.place.PlaceJpaRepository
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.client.GooglePlacesClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * LLM 요약 정보를 배치로 미리 생성하는 스케줄러
 * 
 * - 실행 주기: 매일 새벽 2시
 * - 처리 대상: LLM 요약이 없는 인기 장소 (평점 높고 리뷰 많은 순)
 * - 처리 개수: 최대 50개 (비용 제어)
 */
@Component
@ConditionalOnProperty(prefix = "api.openai", name = ["enabled"], havingValue = "true")
class LLMSummaryBatchScheduler(
    private val placeJpaRepository: PlaceJpaRepository,
    private val placeQuery: PlaceQuery,
    private val reviewSummaryService: ReviewSummaryService
) {
    private val logger = KotlinLogging.logger { }
    
    companion object {
        private const val BATCH_SIZE = 50  // 한 번에 처리할 최대 개수
        private const val CONCURRENT_REQUESTS = 5  // 동시 처리 개수 (비용 제어)
        private const val DELAY_BETWEEN_BATCHES_MS = 1000L  // 배치 간 지연 (1초)
    }

    @Scheduled(cron = "0 0 2 * * *")  // 매일 새벽 2시
    @Transactional(readOnly = true)
    fun generateLLMSummaries() = runBlocking {
        logger.info { "LLM 요약 배치 처리 시작" }
        
        try {
            // 처리 대상 조회
            val placesToProcess = placeJpaRepository.findPlacesWithoutLLMSummary(BATCH_SIZE)
            
            if (placesToProcess.isEmpty()) {
                logger.info { "처리할 장소 없음" }
                return@runBlocking
            }
            
            logger.info { "처리 대상: ${placesToProcess.size}개" }
            
            // 배치로 처리 (동시성 제어)
            val batches = placesToProcess.chunked(CONCURRENT_REQUESTS)
            
            batches.forEachIndexed { batchIndex, batch ->
                logger.info { "배치 ${batchIndex + 1}/${batches.size} 처리 중 (${batch.size}개)" }
                
                supervisorScope {
                    val results = batch.map { place ->
                        async(Dispatchers.IO) {
                            processPlace(place)
                        }
                    }.awaitAll()
                    
                    val successCount = results.count { it }
                    logger.info { "배치 ${batchIndex + 1} 완료: 성공 $successCount/${batch.size}" }
                }
                
                // 배치 간 지연 (API Rate Limit 방지)
                if (batchIndex < batches.size - 1) {
                    delay(DELAY_BETWEEN_BATCHES_MS)
                }
            }
            
            logger.info { "LLM 요약 배치 처리 완료: 총 ${placesToProcess.size}개" }
            
        } catch (e: Exception) {
            logger.error(e) { "LLM 요약 배치 처리 중 오류 발생" }
        }
    }

    /**
     * 단일 장소 처리
     */
    private suspend fun processPlace(place: PlaceEntity): Boolean {
        return try {
            val googlePlaceId = place.googlePlaceId ?: return false
            
            // Place Details 조회 (리뷰 포함)
            val placeDetails = placeQuery.getPlaceDetails(googlePlaceId)
            if (placeDetails == null || placeDetails.reviews.isNullOrEmpty()) {
                logger.debug { "리뷰 없음, 스킵: placeId=$googlePlaceId" }
                return false
            }
            
            // LLM 요약 생성
            val summary = reviewSummaryService.summarize(googlePlaceId, placeDetails)
            
            // 빈 응답이 아니면 성공
            summary.oneLine.isNotBlank()
            
        } catch (e: Exception) {
            logger.warn(e) { "장소 처리 실패: placeId=${place.googlePlaceId}" }
            false
        }
    }
}
```

### GooglePlacesClient에 getPlaceDetails 추가

```kotlin
// GooglePlacesClient.kt에 메서드 추가
suspend fun getPlaceDetails(placeId: String): PlaceDetailsResponse? = withContext(Dispatchers.IO) {
    retryWithExponentialBackoff(
        operation = "Place Details 조회",
        operationDetail = "placeId=$placeId"
    ) {
        try {
            withTimeout(apiTimeoutMillis) {
                val request = mapOf(
                    "name" to "places/$placeId",
                    "languageCode" to "ko"
                )
                
                val fieldMask = listOf(
                    "id",
                    "displayName",
                    "formattedAddress",
                    "rating",
                    "userRatingCount",
                    "reviews",
                    "photos",
                    "priceRange",
                    "addressDescriptor",
                    "location"
                ).joinToString(",")

                googlePlacesRestClient.post()
                    .uri("/v1/places/$placeId")
                    .header("X-Goog-Api-Key", googlePlacesApiProperties.apiKey)
                    .header("X-Goog-FieldMask", fieldMask)
                    .retrieve()
                    .body(PlaceDetailsResponse::class.java)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Place Details 조회 실패: placeId=$placeId" }
            null
        }
    }
}
```

### PlaceQuery에 getPlaceDetails 추가

```kotlin
// PlaceQuery.kt에 메서드 추가
suspend fun getPlaceDetails(googlePlaceId: String): PlaceDetailsResponse? {
    return try {
        googlePlacesClient.getPlaceDetails(googlePlaceId)
    } catch (e: Exception) {
        logger.warn(e) { "Place Details 조회 실패: placeId=$googlePlaceId" }
        null
    }
}
```

---

## 📊 호출량 최적화 효과

### 시나리오 분석

**기존 방식 (인메모리 캐싱만)**:
- 검색 100회 × 평균 8개 장소 = 800회 LLM 호출
- 캐시 히트율 50% 가정 → 400회 실제 호출

**개선 방식 (DB 캐싱 + 배치)**:
- 배치로 인기 장소 50개 미리 처리
- 검색 100회 × 평균 8개 장소 = 800회 조회
- DB 캐시 히트율 80% 가정 → 160회 실제 호출
- **75% 비용 절감** (400회 → 160회)

### 캐시 히트율 예상

1. **인기 장소**: 90%+ 히트율 (배치로 미리 처리)
2. **일반 장소**: 60-70% 히트율 (사용자 검색 시 생성)
3. **전체 평균**: 75-85% 히트율 예상

---

## 🔧 설정 추가

### application.yml

```yaml
api:
  openai:
    # ... 기존 설정 ...
    
    # 배치 처리 설정
    batch:
      enabled: true
      batch-size: 50
      concurrent-requests: 5
      schedule: "0 0 2 * * *"  # 매일 새벽 2시
```

---

## 📋 마이그레이션 계획

### 1단계: DB 스키마 변경
```sql
-- 마이그레이션 스크립트
ALTER TABLE tb_place 
ADD COLUMN llm_summary_one_line VARCHAR(200) NULL,
ADD COLUMN llm_summary_pros TEXT NULL,
ADD COLUMN llm_summary_cons TEXT NULL,
ADD COLUMN llm_summary_best_for TEXT NULL,
ADD COLUMN llm_summary_updated_at DATETIME NULL;
```

### 2단계: Entity 수정
- `PlaceEntity`에 필드 추가
- `PlaceJpaRepository`에 메서드 추가

### 3단계: 캐싱 로직 개선
- `ReviewSummaryCache`를 2단계 캐싱으로 변경

### 4단계: 배치 스케줄러 추가
- `LLMSummaryBatchScheduler` 구현

### 5단계: 점진적 활성화
- 배치 스케줄러 활성화
- 모니터링 및 비용 확인

---

## ⚠️ 주의사항

### 1. DB 저장 공간
- TEXT 필드 3개 추가 (약 1KB/장소)
- 10,000개 장소 기준 약 10MB 추가
- **영향도: 낮음**

### 2. 배치 처리 비용
- 매일 50개 처리 × $0.0002 = $0.01/일
- 월 약 $0.30
- **비용: 매우 낮음**

### 3. 동시성 제어
- 배치 처리 시 동시 요청 수 제한 (5개)
- API Rate Limit 방지

### 4. 데이터 일관성
- 배치와 실시간 요청이 동시에 발생할 수 있음
- DB 트랜잭션으로 충돌 방지

---

## ✅ 최종 권장사항

### 필수 구현
1. ✅ **DB 캐싱**: PlaceEntity에 필드 추가
2. ✅ **2단계 캐싱**: 인메모리 + DB

### 선택적 구현
3. ⚠️ **배치 처리**: 트래픽이 많을 때만 (선택적)

### 구현 우선순위
1. **Phase 1**: DB 캐싱만 구현 (가장 중요)
2. **Phase 2**: 배치 처리 추가 (트래픽 증가 시)

---

## 📈 모니터링 지표

### 확인할 지표
1. **DB 캐시 히트율**: DB에서 조회 성공 비율
2. **인메모리 캐시 히트율**: 인메모리에서 조회 성공 비율
3. **실제 LLM 호출 수**: 일일 실제 API 호출 횟수
4. **배치 처리 성공률**: 배치로 처리된 장소 비율
5. **비용**: 일일/월별 OpenAI 사용 비용

### 목표
- **전체 캐시 히트율**: >80%
- **실제 LLM 호출**: 기존 대비 70% 이상 감소
- **비용**: 월 $10 이하 (소규모 서비스 기준)
