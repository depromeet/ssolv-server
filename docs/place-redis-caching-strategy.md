# 장소 추천 데이터 Redis 정규화 및 캐싱 전략 (Plan 1)

이 문서는 구글 지도 API 약관(최대 30일 보관) 준수 및 데이터 저장 효율화를 위한 **Redis 기반의 장소 리스트 정규화 및 캐싱(MGET) 구조**에 대한 작업 가이드와 테스트 코드 작성 방안을 정리합니다.

---

## ⚡ 핵심 요약: "비동기 계산(Stream) + 정규화 캐싱(Redis)"

현재 시스템은 **`비동기 Consumer`가 빵을 미리 굽고, `사용자`는 진열대(Redis)에서 빵을 즉시 집어가는** 구조입니다.

### 1. 처리 흐름 (Flow)
1.  **비동기 처리 (`PlaceSearchConsumer`)**: 설문 완료 시 Redis Stream을 통해 계산 요청을 수신합니다.
2.  **중복 확인 및 계산**: `ExecutePlaceSearchService`가 실행되며, 먼저 Redis에 이미 계산된 결과가 있는지 확인합니다. 없으면 구글 API 검색 후 베스트 10개를 선정(Selection)하여 **'모임의 정답'으로 박제**합니다.
3.  **정규화 저장**: 결정된 10개 식당의 **ID 리스트**와 각 식당의 **상세 정보(JSON)**를 Redis에 분리하여 저장합니다.
4.  **2단계 조회 (MGET)**: 사용자가 조회 시, Redis에서 ID 10개를 먼저 꺼내고(`LRANGE`), 그 ID들로 상세 정보 10개를 한 번에(`MGET`) 가져옵니다.

### 2. 왜 데이터 정규화인가?
*   **중복 제거**: '쉑쉑버거 강남점'이 100개 모임에서 추천되어도, 상세 정보는 Redis에 **딱 1개**만 보관하여 메모리를 절약합니다.
*   **일관성 보장**: 처음 계산된 '정답 목록'을 캐싱하므로, 구글 검색 결과가 실시간으로 변해도 모든 팀원이 동일한 추천 리스트를 보게 됩니다.
*   **성능 최적화**: 무거운 API 연산이나 DB 다건 조회가 아닌, Redis 내부 ID 참조를 통한 `MGET`으로 **0.1초 이내** 응답을 보장합니다.

---

## 2. 배경 및 문제점

기존에는 모임별로 검색된 10개의 구글 장소 상세 정보(JSON)를 RDBMS의 `tb_meeting_place_searches` 테이블에 통째로 직렬화(`search_result_json`)하여 저장했습니다.

이 방식에는 다음과 같은 구조적인 문제가 있었습니다.

| 문제 | 내용 |
|---|---|
| **데이터 중복** | A모임과 B모임 모두 '강남역 쉑쉑버거'가 추천될 경우, 동일한 JSON 데이터가 두 행에 중복 저장 |
| **약관 대응 번거로움** | 구글 API 약관상 30일 이후 데이터를 삭제해야 해, 별도 배치 스케줄러(`MeetingPlaceSearchCleanupScheduler`)를 운영해야 했음 |
| **성능** | 좋아요 집계 등 실시간 정보 반영 시 JSON 전체를 역직렬화(Deserialize) → 수정 → 재직렬화(Serialize)하는 과정이 불필요하게 무거움 |

---

## 2. 해결 방향: Redis 기반 정규화 저장

장소 상세 데이터를 DB에서 걷어내고, **Redis를 메인 저장소로 활용**하며 "공용 공간"과 "개별 공간"을 분리하는 정규화 전략을 적용합니다.

### 🔑 2.1 공용 공간 - Place 원본 캐시 (Global)

장소의 상세 정보는 모임과 **무관하게, 자신의 DB 내부 ID(`placeId`)를 이름표로 달아서 딱 1번만** 저장합니다.

```
Key:   place:details:{placeId}
Value: { "name": "쉑쉑버거", "address": "강남대로...", "rating": 4.5, "photos": ["url..."], ... }
TTL:   30일 (2,592,000초) → 구글 API 약관 자동 준수
```

> 강남역 기반 모임이 100개가 생겨도, 쉑쉑버거 데이터는 Redis에 **딱 1개**만 존재합니다.

### 🔑 2.2 개별 공간 - Meeting 검색 결과 캐시 (Local)

모임방에는 무거운 상세 정보 대신, 해당 모임에서 추천된 장소의 **ID(이름표) 목록만** 가볍게 저장합니다.

```
Key:   meeting:places:{meetingId}
Value: [ "placeId_1", "placeId_2", ..., "placeId_10" ]  (Redis List)
TTL:   7일 (모임 유지 기간에 맞게 설정)
```

##### 예시
```
Key: meeting:places:A팀모임_001
Value: [ "101", "205" ]   ←  쉑쉑버거(101), 마라탕(205) ID만 보관

Key: meeting:places:B팀모임_002
Value: [ "101", "388" ]   ←  쉑쉑버거(101), 초밥(388) ID만 보관
```

---

## 3. `placeId`의 정체와 영속화(DB)의 역할

Redis에 저장되는 `placeId`는 **우리 서버의 RDBMS(`tb_place`) Primary Key**입니다. 구글 문자열 ID(`ChIJ...`)와 다릅니다.

### 3.1 ID 관계 구조

| 구분 | 식별자 | 저장 위치 |
|------|--------|---------|
| 구글 내부 ID | `googlePlaceId` (ex. `ChIJabc...`) | `tb_place.google_place_id` |
| **우리 서버 내부 PK** | `placeId` (ex. `101`) | **`tb_place.id`** ← Redis 키에 사용하는 값 |
| 모임↔장소 매핑 | `meeting_id + place_id` | **`tb_meeting_places`** (영구 저장) |

### 3.2 영속화 시점

구글 검색 직후 → `PlaceQuery.savePlacesFromTextSearch()` 에서 장소들이 `tb_place` 테이블에 **UPSERT**(신규면 INSERT, 기존이면 30일 이내 데이터는 스킵)됩니다.

이때 "어떤 모임에서 어느 10개 식당이 추천 받았는지"는 `tb_meeting_places` 테이블에 `meeting_id ↔ place_id` 매핑으로 **영구적으로** 남습니다. 이 테이블이 Cache Miss 발생 시 자가 복구의 열쇠가 됩니다.

> 즉, 수천 글자의 무거운 "검색 결과 JSON 덩어리"는 DB에서 제거하지만,
> **"이 모임에서 어떤 식당 10개가 추천되었는가"에 대한 뼈대(placeId 매핑)는 DB에 안전하게 영구 보존**됩니다.

---

## 4. 데이터 흐름 (Data Flow)

### 4.1 저장 시나리오 (검색 완료 직후)

```
구글 Places Text Search API 결과(10개 장소)
           ↓
1. tb_place에 장소 UPSERT            ← DB 영속화 (placeId 확보)
2. tb_meeting_places 매핑 저장       ← meeting_id ↔ place_id 영구 기록
3. meeting:places:{meetingId}       ← Redis List: placeId 목록 (TTL 7일)
4. place:details:{placeId}         ← Redis String: 각 장소 JSON (TTL 30일)
```

### 4.2 조회 시나리오 - Cache All Hit (정상 케이스)

```
클라이언트: "A팀 모임 장소 리스트 주세요"
      ↓
1. Redis: LRANGE meeting:places:A팀모임_001 0 -1
   → ["101", "205", ...]  (placeId 10개 획득)
      ↓
2. Redis: MGET place:details:101 place:details:205 ...
   → ["{JSON 쉑쉑버거}", "{JSON 마라탕}", ...]  (상세 정보 10개 한 방에 획득)
      ↓
3. 좋아요 정보만 DB에서 실시간 조회 후 병합
      ↓
클라이언트: 장소 리스트 10개 응답
```

> **DB 쿼리는 좋아요 조회 1건뿐**입니다. N+1 문제가 구조적으로 제거됩니다.

### 4.3 조회 시나리오 - Partial Cache Miss (30일 만료 케이스)

```
클라이언트: "A팀 모임 장소 리스트 주세요"
      ↓
1. Redis: LRANGE meeting:places:A팀모임_001 0 -1
   → ["101", "205", ...]  (placeId 목록 - 아직 살아있음)
      ↓
2. Redis: MGET place:details:101 place:details:205 ...
   → ["{JSON 쉑쉑버거}", null, ...]  ← 205(마라탕)만 30일 지나 null!
      ↓
3. null인 placeId 목록 추출: [205]
      ↓
4. DB: SELECT * FROM tb_place WHERE id IN (205)  ← placeId 기반 쿼리
   → 마라탕 엔티티 정보 획득
      ↓
5. 마라탕 JSON 조립 → Redis SET place:details:205 ... EX 2592000  (TTL 30일 재갱신)
      ↓
클라이언트: 장소 리스트 10개 응답 (누락 없이 완전한 데이터)
```

> Cache Miss가 발생해도 구글 API를 다시 호출하지 않고,
> **우리 DB(`tb_place`)에서 placeId로 직접 조회하여 자가 복구**합니다.
> DB 영속화가 여전히 필요한 이유입니다.

---

## 5. 제거된 레거시 구성

Redis 기반 전환 과정에서 다음 클래스 및 테이블들이 완전히 삭제되었습니다.

| 삭제된 구성 | 이유 |
|---|---|
| `MeetingPlaceSearchEntity` / `tb_meeting_place_searches` 테이블 | 무거운 JSON 전체를 DB에 저장하는 구조 자체를 제거 |
| `MeetingPlaceSearchRepository` (JPA) | 위 엔티티가 사라졌으므로 레포지토리도 불필요 |
| `MeetingPlaceSearchCleanupScheduler` (배치) | Redis TTL이 30일 자동 파기를 담당하므로 스케줄러 불필요 |

---

## 6. 실제 구현 코드 (`MeetingPlaceSearchService`)

### 6.1 저장 (save)

```kotlin
suspend fun save(meetingId: Long, result: PlacesSearchResponse) = withContext(coroutineDispatchers.VT) {
    val meetingKey = "meeting:places:$meetingId"

    // --- 개별 공간(Local): 모임에는 placeId 목록만 저장 ---
    redisTemplate.delete(meetingKey)
    val placeIds = result.items.map { it.placeId.toString() }

    if (placeIds.isNotEmpty()) {
        redisTemplate.opsForList().rightPushAll(meetingKey, *placeIds.toTypedArray())
        redisTemplate.expire(meetingKey, 7, TimeUnit.DAYS)
    }

    // --- 공용 공간(Global): 장소 상세 JSON은 placeId 키로 독립 캐싱 ---
    result.items.forEach { item ->
        val placeKey = "place:details:${item.placeId}"
        val itemToCache = item.copy(likeCount = 0, isLiked = false) // 실시간 좋아요는 캐시에 포함하지 않음
        val json = objectMapper.writeValueAsString(itemToCache)
        redisTemplate.opsForValue().set(placeKey, json, 30, TimeUnit.DAYS) // 구글 약관: 30일 TTL
    }
}
```

### 6.2 조회 (find) - MGET + Cache Miss 자가 복구

```kotlin
suspend fun find(meetingId: Long): PlacesSearchResponse? = withContext(coroutineDispatchers.VT) {
    val meetingKey = "meeting:places:$meetingId"

    // 1단계: 모임의 placeId 목록 조회
    val placeIds = redisTemplate.opsForList().range(meetingKey, 0, -1)
    if (placeIds.isNullOrEmpty()) return@withContext null

    // 2단계: MGET으로 상세 정보 일괄 조회 (N+1 방지 핵심)
    val placeKeys = placeIds.map { "place:details:$it" }
    val cachedJsons = redisTemplate.opsForValue().multiGet(placeKeys) ?: return@withContext null

    val items = mutableListOf<PlacesSearchResponse.PlaceItem>()
    val missingPlaceIds = mutableListOf<Long>()
    val missingIndices = mutableListOf<Int>()

    // 3단계: Hit / Miss 분리
    for (i in placeIds.indices) {
        val json = cachedJsons[i]
        if (!json.isNullOrBlank()) {
            items.add(objectMapper.readValue(json, PlacesSearchResponse.PlaceItem::class.java))
        } else {
            // Cache Miss → placeholder 삽입 후 나중에 채움
            missingPlaceIds.add(placeIds[i].toLong())
            missingIndices.add(i)
            items.add(PlacesSearchResponse.PlaceItem(placeId = -1L, ...))
        }
    }

    // 4단계: Cache Miss 자가 복구 - placeId로 DB 조회 후 Redis 재적재
    if (missingPlaceIds.isNotEmpty()) {
        val missingEntities = placeQuery.findByIds(missingPlaceIds) // SELECT * FROM tb_place WHERE id IN (...)
        val missingEntityMap = missingEntities.associateBy { it.id }

        for ((listIdx, dbId) in missingPlaceIds.withIndex()) {
            val entity = missingEntityMap[dbId] ?: continue
            val recoveredItem = entity.toPlaceItem() // Entity → DTO 변환
            items[missingIndices[listIdx]] = recoveredItem

            // 복원한 항목을 Redis에 TTL 30일로 재저장
            val json = objectMapper.writeValueAsString(recoveredItem)
            redisTemplate.opsForValue().set("place:details:$dbId", json, 30, TimeUnit.DAYS)
        }
    }

    val finalItems = items.filter { it.placeId != -1L }
    if (finalItems.isEmpty()) return@withContext null

    PlacesSearchResponse(finalItems)
}
```

---

## 7. 테스트 코드 작성 가이드

`MockK`를 활용하여 **Cache Hit / Partial Miss** 두 케이스를 중점적으로 검증합니다.

### 7.1 Cache All Hit - DB 조회 없음 검증

```kotlin
@Test
@DisplayName("Cache All Hit - Redis만으로 응답, DB 조회 없음")
fun `모든 장소 캐시가 살아있을 때 DB 조회가 발생하지 않는다`() {
    // given
    every { redisTemplate.opsForList().range(any(), any(), any()) } returns listOf("101", "205")
    every { redisTemplate.opsForValue().multiGet(any()) } returns listOf(
        """{"placeId":101,"name":"쉑쉑버거","address":"강남대로...","likeCount":0,"isLiked":false}""",
        """{"placeId":205,"name":"마라탕","address":"강남대로...","likeCount":0,"isLiked":false}"""
    )

    // when
    val result = service.find(meetingId = 1L)

    // then
    assertThat(result?.items).hasSize(2)
    verify(exactly = 0) { placeQuery.findByIds(any()) } // DB 조회 없어야 함!
}
```

### 7.2 Partial Cache Miss - 누락된 placeId로만 DB 조회 및 Redis 재적재 검증

```kotlin
@Test
@DisplayName("Partial Cache Miss - 누락된 placeId로만 DB 조회 후 Redis 재적재")
fun `일부 캐시가 만료되었을 때 누락된 건만 DB에서 복구하고 Redis를 갱신한다`() {
    // given
    every { redisTemplate.opsForList().range(any(), any(), any()) } returns listOf("101", "205", "388")
    every { redisTemplate.opsForValue().multiGet(any()) } returns listOf(
        """{"placeId":101,"name":"쉑쉑버거",...}""",
        null,   // ← 205(마라탕) 30일 만료!
        """{"placeId":388,"name":"초밥",...}"""
    )
    every { placeQuery.findByIds(listOf(205L)) } returns listOf(maratangEntity)
    every { redisTemplate.opsForValue().set(any(), any(), 30, TimeUnit.DAYS) } just Runs

    // when
    val result = service.find(meetingId = 1L)

    // then
    assertThat(result?.items).hasSize(3)
    // 누락된 1건(205)만 DB 조회
    verify(exactly = 1) { placeQuery.findByIds(listOf(205L)) }
    // 복원된 데이터가 TTL 30일로 Redis에 재적재되었는지 검증
    verify(exactly = 1) { redisTemplate.opsForValue().set("place:details:205", any(), 30, TimeUnit.DAYS) }
    // 이미 캐시가 살아있는 101, 388은 DB 조회 안 함
    verify(exactly = 0) { placeQuery.findByIds(match { it.contains(101L) || it.contains(388L) }) }
}
```

---

## 8. 향후 작업 (좋아요 기능 개선)

현재 좋아요 정보는 기존과 동일하게 RDBMS(`tb_place_likes`) 기반으로 동작합니다. 추후 아래 방식으로 개선 예정입니다.

| 구분 | 현재 | 향후 |
|---|---|---|
| 좋아요 저장 | `tb_place_likes` INSERT/DELETE | Redis `SADD` / `SREM` |
| 좋아요 카운트 | DB `COUNT` 쿼리 | Redis `SCARD` (O(1)) |
| 랭킹 반영 | 매번 정렬 후 응답 | ZSET `ZINCRBY`로 실시간 score 관리 |
| 데이터 만료 | 별도 스케줄러 필요 | Redis TTL 자동 파기 |
| 키 구조 | - | `meeting:{meetingId}:place:{placeId}:likes` (Set, 유저 ID 집합) |
