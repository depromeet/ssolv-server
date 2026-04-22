# 맛집 좋아요(Like) 전체 플로우 정리

## 개요

모임 내 맛집 좋아요 기능은 **고트래픽에서도 동시성을 보장**하고, **실시간으로 모든 참여자에게 반영**되도록 설계되었습니다.
DB 대신 Redis를 핵심 저장소로 사용하며, Lua 스크립트에 의한 원자적 처리와 SSE(Server-Sent Events) + Redis Pub/Sub를 결합한 Push 방식으로 동작합니다.

---

## 1. 핵심 설계 원칙

| 관심사 | 해결 방법 |
|---|---|
| 동시성 (Race Condition) | Redis Lua 스크립트 (원자적 실행) |
| 실시간 전파 | Redis Pub/Sub + SSE |
| DB 부하 방지 (핫패스) | Redis TTL 우선 재사용, DB Fallback |
| 데이터 만료 | 모임 `endAt` 기반 동적 TTL |
| 초기 진입 시 최신 상태 | Redis 최신 데이터 기반 목록 조회 |

---

## 2. 전체 플로우

### 2-1. 사용자 접속 시 (최초 로딩)

처음 접속했거나 SSE 이벤트를 놓친 사용자가 화면을 열 때는 **일반 조회 API(GET)**를 통해 항상 최신 상태를 받아옵니다.

```
사용자 접속
    │
    ▼
GET /api/places/search?meetingId=1
    │
    ▼
ExecutePlaceSearchService.search()
    │
    ├── Redis에서 기존 검색 결과 캐시 조회 (meetingPlaceSearchService.find)
    │       └── 캐시 있음 → 즉시 반환
    │
    └── 캐시 없음 → Google Places API 호출 후 결과 저장
            │
            ▼
        buildLikesMapFromRedis()
            └── Redis SCARD(좋아요 수) + SISMEMBER(내가 눌렀는지) 조회
                    │
                    ▼
                최신 likeCount + isLiked + 랭킹 점수로 재정렬된 목록 반환
```

### 2-2. SSE 연결

화면 로딩 직후 클라이언트는 SSE 채널을 열어 이후의 실시간 업데이트를 수신할 준비를 합니다.

```
GET /api/likes/events?meetingId=1
    │
    ▼
PlaceLikeSseService.subscribe(meetingId)
    │
    ├── SseEmitter 생성 및 emitters[meetingId] 목록에 추가
    │
    ├── Redis Pub/Sub: "meeting:updates:1" 채널 구독 (처음 구독자가 생길 때만)
    │
    └── 클라이언트에 "connected" 이벤트 전송 (연결 유지 ping)
```

### 2-3. 좋아요 토글 핫패스

가장 빈번하게 발생하는 이 경로에서 DB 접근을 최소화하고, 모든 처리를 Redis에서 완결합니다.

```
사용자 A: 좋아요 버튼 클릭
    │
    ▼
POST /api/likes/{placeId}?meetingId=1
    │
    ▼
PlaceLikeService.toggle(meetingId, userId, placeId)
    │
    ├── [1] Redis TTL 우선 확인
    │       redisTemplate.getExpire(meetingKey)
    │       └── TTL 있음(> 0) → 기존 TTL 재사용 (DB 조회 없음!)
    │       └── TTL 없음(≤ 0) → meetingQuery.findById() 로 endAt 기반 TTL 계산 (DB Fallback)
    │
    ├── [2] Lua 스크립트 원자적 실행 (핵심!)
    │       ┌──────────────────────────────────────────────┐
    │       │ KEYS[1] = likeKey    (meeting:1:place:101:likes) │
    │       │ KEYS[2] = meetingKey (meeting:places:1)          │
    │       │ ARGV[1] = userId                                  │
    │       │ ARGV[2] = placeId                                 │
    │       │ ARGV[3] = bonus (50.0)                            │
    │       │ ARGV[4] = ttl (동적 계산된 초 단위 값)            │
    │       │                                                    │
    │       │ SADD likeKey userId  → 처음 누름                  │
    │       │   └── 추가됨 (isLiked=1) → ZINCRBY +50           │
    │       │   └── 이미 있음 → SREM (취소, isLiked=0) → ZINCRBY -50│
    │       │                                                    │
    │       │ EXPIRE likeKey ttl                                │
    │       │ EXPIRE meetingKey ttl                             │
    │       │ SCARD likeKey → 최신 likeCount 반환              │
    │       └──────────────────────────────────────────────┘
    │
    └── [3] Redis Pub/Sub으로 이벤트 발행
            channel: "meeting:updates:1"
            payload: {"placeId": 101, "likeCount": 15}  ← JSON 직렬화
```

### 2-4. 실시간 브로드캐스팅 (SSE Push)

Redis Pub/Sub 메시지가 도착하면, 해당 모임에 접속해 있는 **모든** 사용자에게만 정밀하게 이벤트를 전달합니다.

```
Redis "meeting:updates:1" 채널에 메시지 도착
    │
    ▼
PlaceLikeSseService.MessageListener 수신
    │
    ▼
broadcast(meetingId=1, payload='{"placeId":101,"likeCount":15}')
    │
    ├── emitters[1] = [SseEmitter(A), SseEmitter(B), SseEmitter(C)]
    │
    ├── A에게 SSE 이벤트 전송 (event: placeUpdate)
    ├── B에게 SSE 이벤트 전송 (event: placeUpdate)
    └── C에게 SSE 이벤트 전송 (event: placeUpdate)
            │
            ▼
    클라이언트에서 101번 장소의 likeCount를 15로 즉시 교체
    (추가 API 호출 없음!)
```

---

## 3. Redis 데이터 구조

| 키 | 자료구조 | 설명 |
|---|---|---|
| `meeting:{meetingId}:place:{placeId}:likes` | **Set** | 해당 장소에 좋아요를 누른 userId 집합 |
| `meeting:places:{meetingId}` | **Sorted Set** | 모임 내 장소별 랭킹 점수 (score = weightedScore + likeScore) |

---

## 4. 동시성 보장 원리

Redis는 **싱글 스레드**로 동작하며, Lua 스크립트는 **원자적(Atomic)으로 실행**됩니다.
즉, 스크립트가 실행되는 동안 다른 Redis 명령어는 절대 끼어들 수 없습니다.

```
사용자 100명이 0.1초 내 동시에 좋아요 클릭
    │
    ▼
Redis: Lua 스크립트 100개를 내부 큐에서 순서대로 처리
    
    [Script #1] SADD → ZINCRBY → EXPIRE → SCARD (완료) → 다음
    [Script #2] SADD → ZINCRBY → EXPIRE → SCARD (완료) → 다음
    ...
    [Script #100] ... (완료)
    
→ Race Condition 없음, 데이터 누락 없음
```

**기존 DB 트랜잭션 방식과 비교:**

| | DB 트랜잭션 + Lock | Redis Lua 스크립트 |
|---|---|---|
| 처리 방식 | 비관적 락(Pessimistic Lock) | Redis 싱글 스레드 + 원자적 실행 |
| 속도 | 느림 (커넥션 풀 경쟁, I/O) | 빠름 (인메모리, 나노초 단위) |
| 동시성 보장 | 보장 | **보장** |
| 고트래픽 내구성 | 커넥션 풀 고갈 위험 | 안정적 |

---

## 5. 데이터 만료 (Dynamic TTL)

좋아요 데이터는 모임이 끝나면 자동으로 정리되어야 합니다.
하드코딩된 7일 TTL 대신, **모임의 실제 종료 시간(`endAt`)을 기준으로 동적으로 계산**합니다.

```kotlin
// TTL 계산 우선순위
1. Redis의 meetingKey에 이미 TTL이 설정된 경우 → 재사용 (DB 조회 생략)
2. TTL이 없거나 만료된 경우 → DB에서 meeting.endAt 조회 후 Duration 계산
3. meeting이 이미 만료됐거나 endAt을 알 수 없는 경우 → Fallback: 7일

TTL(초) = Duration.between(now, meeting.endAt).seconds
```

---

## 6. 개선 이력

| 버전 | 내용 |
|---|---|
| `v1` | DB 기반 좋아요 저장, 무거운 조회 API 반복 폴링 |
| `v2` | Redis Lua 스크립트 도입, 동시성 + 원자성 보장 |
| `v3` | SSE + Redis Pub/Sub 도입, 조회 API 반복 호출 제거 |
| `v4` | SSE 이벤트 Payload에 `likeCount` 포함, 완전한 실시간 업데이트 달성 |
| `v5` | 핫패스 DB 조회 제거, Redis TTL 재사용 + Fallback 전략 도입 |
| `v6` | 하드코딩 TTL(7일) 제거, 모임 `endAt` 기반 동적 TTL 적용 |
