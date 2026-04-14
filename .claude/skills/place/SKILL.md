# Place Module (ssolv-api-place)

> 상세 문서: `.claude/docs/place-recommendation-logic.md`, `place-redis-caching-strategy.md`, `place-like-flow.md`

## 모듈 개요

`ssolv-api-place`는 독립 bootable JAR. `ssolv-api-core`와 절대 상호 참조 금지.
공유 모듈(`ssolv-api-common`, `ssolv-domain`, `ssolv-infrastructure`)을 통해서만 통신.

---

## 장소 추천 흐름 (핵심)

```
설문 완료 → Redis Stream(meeting_calculation_stream) 발행
→ PlaceSearchConsumer 소비
→ ExecutePlaceSearchService.execute(meetingId)
  ├─ 1. GetSurveyAggregateService      — 설문 카테고리 집계
  ├─ 2. SelectSurveyKeywordsService    — 검색 키워드 최대 5개 선택
  ├─ 3. Google Places Text Search API  — 역 기준 반경 3km, 병렬 호출
  └─ 4. 최종 채점 + Redis 저장
```

### 채점 공식

```
Score = SurveyScore(0~100)
      + LikeScore(ln(count+1) × 50)
      + GoogleScore(rating × ln(userRatingsTotal+1) × 2)
      + DistanceScore(0~100, 5km 이내)
      + ProximityBoost(역 1km 이내 → +50)
      + CategoryMatchBoost(구글 types 일치 → +50)

필터: 역 기준 5km 초과 장소 원천 배제
```

---

## Redis 데이터 구조

| 키 패턴 | 자료구조 | TTL | 용도 |
|---|---|---|---|
| `meeting:places:{meetingId}` | ZSET | 7일 (동적) | 모임별 장소 랭킹 점수 |
| `place:details:{placeId}` | String | 30일 | 장소 상세 JSON (Google API 약관) |
| `meeting:{mId}:place:{pId}:likes` | Set | 30일 | 좋아요 userId 집합 |
| `place_search_lock:{meetingId}` | String | 10초 | Thundering Herd 분산 락 |

**Google API 약관**: `place:details:*` 30일 TTL로 자동 준수. 직접 TTL 제거 금지.

---

## 좋아요(Like) 핫패스

1. **TTL 우선 확인** — Redis `getExpire(meetingKey)` > 0 이면 DB 조회 없이 재사용
2. **Lua 스크립트 원자적 실행** — SADD/SREM + ZINCRBY + EXPIRE를 단일 트랜잭션으로 처리
3. **Redis Pub/Sub 발행** — `meeting:updates:{meetingId}` 채널
4. **SSE 브로드캐스트** — 해당 모임 구독자에게만 `placeUpdate` 이벤트 전송

```kotlin
// 동적 TTL 계산 패턴
val ttl = when {
    existingTtl > 0 -> existingTtl   // Redis TTL 재사용 (DB 조회 생략)
    else -> meeting.endAt?.let {     // DB Fallback
        Duration.between(LocalDateTime.now(), it).seconds.coerceAtLeast(1)
    } ?: Duration.ofDays(7).seconds  // Fallback: 7일
}
```

---

## Cache Stampede 방어 (Double-Check Lock)

```kotlin
// 1차 확인
if (redisExists(meetingKey)) return getCached(meetingKey)

// 락 획득 시도 (TTL 10초 — Google API 타임아웃 5초 기준)
val locked = redisTemplate.opsForValue()
    .setIfAbsent("place_search_lock:$meetingId", "1", Duration.ofSeconds(10))
    ?: false

if (!locked) {
    // Exponential Backoff 후 캐시 재확인
    return waitAndGetCached(meetingKey)
}

// 2차 확인 (Double-Check)
if (redisExists(meetingKey)) return getCached(meetingKey)

// 실제 Google API 호출
return executeSearch(meetingId)
```

Watchdog 불필요: Google API 타임아웃이 5초로 명확하기 때문에 고정 10초 락으로 충분.

---

## 조회 패턴 (2단계 MGET)

```kotlin
// 1. 랭킹 상위 10개 ID 조회 (O(log N))
val placeIds = redisTemplate.opsForZSet()
    .reverseRange("meeting:places:$meetingId", 0, 9)

// 2. 상세 정보 일괄 조회 (O(N))
val details = redisTemplate.opsForValue()
    .multiGet(placeIds.map { "place:details:$it" })

// 3. 실시간 좋아요 상태 병합
val result = placeIds.zip(details).map { (placeId, detail) ->
    val likeCount = redisTemplate.opsForSet()
        .size("meeting:$meetingId:place:$placeId:likes") ?: 0L
    val isLiked = redisTemplate.opsForSet()
        .isMember("meeting:$meetingId:place:$placeId:likes", userId.toString()) ?: false
    detail.copy(likeCount = likeCount, isLiked = isLiked)
}
```

---

## 데이터 재계산 생명주기

```
최초 조회: Redis Miss → 추천 로직 실행 → RDB 저장 + Redis 적재(7일 TTL)
반복 조회: Redis Hit → ZREVRANGE + MGET + 좋아요 병합 → 즉시 반환
만료(7일 경과): Redis Expired → 다음 요청 시 최초 조회 과정 반복
```

**RDB(`tb_place`)**: 식당 마스터 정보 영구 보관. Google API 약관과 무관하게 유지.
**Redis**: 모임별 랭킹 + 캐시만. 만료되면 RDB 기반으로 재계산 가능.

---

## SSE 구독 해제 처리

```kotlin
// SseEmitter 타임아웃/에러 시 반드시 emitters 목록에서 제거
emitter.onTimeout { emitters[meetingId]?.remove(emitter) }
emitter.onError { emitters[meetingId]?.remove(emitter) }
```

제거하지 않으면 죽은 Emitter로 브로드캐스트 시 에러 누적.

---

## 테스트 주의사항

- Google Places API → `@MockitoBean GooglePlacesClient`로 대체 필수
- Redis Stream Consumer → `@Profile("!test")` Config에 포함됨 (자동 비활성화)
- SSE 테스트 → `MockMvc`의 async 모드 사용 + `text/event-stream` Content-Type 검증
