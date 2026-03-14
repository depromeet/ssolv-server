# 장소 추천 데이터 Redis 정규화 및 캐싱 전략

이 문서는 구글 지도 API 약관(최대 30일 보관) 준수, 데이터 저장 효율화, 그리고 **실시간 좋아요 기반 랭킹**을 위한 **Redis 기반의 장소 리스트 정규화 및 캐싱(MGET/ZSET) 구조**에 대해 설명합니다.

---

## ⚡ 핵심 요약: "비동기 계산(Stream) + 정규화 캐싱(Redis) + 실시간 랭킹(ZSET)"

현재 시스템은 **`비동기 Consumer`가 데이터를 수집/계산하고, `Redis`를 통해 실시간 랭킹과 상세 정보를 즉시 제공**하는 구조입니다.

### 1. 처리 흐름 (Flow)
1.  **비동기 처리 (`PlaceSearchConsumer`)**: 설문 완료 시 Redis Stream을 통해 계산 요청을 수신합니다.
2.  **중복 확인 및 계산**: `ExecutePlaceSearchService`가 실행되어 설문 기반 가중치와 기존 좋아요를 합산하여 초기 **종합 점수(score)**를 계산합니다.
3.  **정규화 저장**: 
    *   **랭킹(Local)**: `meeting:places:{meetingId}` 키에 `ZSET` 구조로 `{placeId: score}`를 저장합니다.
    *   **상세정보(Global)**: `place:details:{placeId}` 키에 장소 상세 JSON을 저장합니다 (TTL 30일).
4.  **실시간 좋아요**: 사용자가 좋아요를 누르면 Redis Set (`meeting:{mId}:place:{pId}:likes`)에 유저 ID가 추가/삭제되며, 동시에 ZSET의 score가 `ZINCRBY`로 실시간 업데이트됩니다.
5.  **2단계 조회 (MGET)**: 조회 시 `ZREVRANGE`로 상위 10개 ID를 가져오고, `MGET`으로 상세 정보를 한 번에 가져와 실시간 좋아요 상태와 병합합니다.

### 2. 주요 이점
*   **실시간성**: 좋아요 클릭 즉시 별도의 DB 재계산 없이 랭킹이 바뀝니다.
*   **중복 제거**: 동일 장소 데이터는 Redis 전체에서 단 하나만 존재하여 메모리를 절약합니다 (Global Cache).
*   **약관 준수**: 상세 정보에 30일 TTL을 적용하여 Google Places API의 데이터 보관 정책을 자동으로 준수합니다.
*   **성능**: `ZREVRANGE` (O(log N))와 `MGET` (O(N)) 조합으로 극도의 읽기 성능을 보장합니다.

---

## 2. 해결 방향: Redis 기반 정규화 저장

### 🔑 2.1 공용 공간 - Place 원본 캐시 (Global)

장소의 상세 정보는 모임과 무관하게, 자신의 DB 내부 ID(`placeId`)를 이름표로 달아서 캐싱합니다.

```
Key:   place:details:{placeId}
Value: { "placeId": 101, "name": "쉑쉑버거", ... } (상세 정보 JSON)
TTL:   30일 (2,592,000초)
```

### 🔑 2.2 개별 공간 - Meeting 랭킹 캐시 (Local)

각 모임은 정렬된 상태를 유지하기 위해 `Sorted Set (ZSET)`을 사용합니다.

```
Key:   meeting:places:{meetingId}
Member: placeId
Score: 설문 가중치 점수 + (좋아요 수 * 보너스 점수)
TTL:   7일
```

### 🔑 2.3 실시간 좋아요 저장소

누가 어느 장소에 좋아요를 눌렀는지 관리하며, 중복 좋아요를 방지합니다.

```
Key:   meeting:{meetingId}:place:{placeId}:likes
Member: userId (Set 구조)
TTL:   30일 (상세 정보와 동기화)
```

---

## 3. 데이터 흐름 (Data Flow)

### 3.1 저장 시나리오
1.  설문 결과 분석 및 후보지 선정
2.  **종합 점수 산출**: `설문 가중치(0~1.0) * 100 + (기존 좋아요 수 * 50)`
3.  **Redis ZADD**: `ZADD meeting:places:123 150.5 101` (101번 장소에 150.5점 부여)
4.  **Redis SET (Details)**: 상세 정보를 `place:details:101`에 저장 (TTL 30일)

### 3.2 조회 시나리오
1.  **랭킹 조회**: `ZREVRANGE meeting:places:123 0 9` -> 상위 10개 ID 획득
2.  **상세정보 조회**: `MGET place:details:101 place:details:205 ...`
3.  **실시간 정보 병합**: 각 ID에 대해 `SCARD`로 좋아요 총수, `SISMEMBER`로 로그인 유저의 좋아요 여부 확인 후 병합 응답

### 3.3 좋아요 토글 시나리오 (Atomic 연산)
1.  **Set 업데이트**: `SADD` (성공 시 신규 좋아요) 또는 `SREM` (성공 시 좋아요 취소)
2.  **ZSET 업데이트**: 신규 좋아요면 `ZINCRBY {key} 50 {placeId}`, 취소면 `ZINCRBY {key} -50 {placeId}`
3.  **데이터 성격**: 실시간성과 성능을 위해 Redis를 단독 저장소로 활용하며, DB 동기화는 수행하지 않습니다.

---

## 4. API 약관 및 영속화 가이드

*   **Google API 약관**: 상세 정보(`place:details:*`)에 30일 TTL을 적용하여 자동 삭제되도록 합니다. 
*   **자가 복구**: Redis 캐시가 만료(Miss)되어도 DB(`tb_place`)에 기본 장소 정보가 남아있으므로, 조회 시 실시간으로 Redis를 다시 채울 수 있습니다.
*   **좋아요 정책**: 좋아요 데이터는 Redis Set을 통해 관리하며, 모임의 활발한 실시간 랭킹 반영을 위해 DB 영속화 없이 Redis의 TTL(30일) 정책에 따릅니다.

---

## 5. 성능 지표 및 테스트 결과

*   **읽기 성능**: Redis MGET 기반으로 대량 조회 시에도 DB 부하 없이 안정적인 Latency (평균 10ms 미만) 확인.
*   **랭킹 정확도**: 좋아요 클릭과 동시에 `ZREVRANGE` 결과 순서가 즉시 변하는 실시간성 검증 완료.
*   **동시성**: Redis의 원자적(Atomic) 연산을 사용하여 좋아요 동시 요청 시에도 데이터 정합성 유지.
