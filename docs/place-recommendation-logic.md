# 장소 추천 알고리즘 상세 설명 (Place Recommendation Logic)

> `ssolv-api-place` 모듈의 핵심 알고리즘을 설명합니다.  
> 참여자들의 설문 결과를 분석하여 최적의 식당 10개를 자동으로 도출하는 전 과정을 다룹니다.

---

## �️ 전체 아키텍처 흐름

```
[모든 참여자 설문 완료]
        ↓
[Redis Stream: meeting_calculation_stream 메시지 발행]
        ↓
[PlaceSearchConsumer에서 메시지 소비]
        ↓
[ExecutePlaceSearchService.execute(meetingId)]
        ↓
 ┌──────────────────────────────────────────────┐
 │  1단계: 설문 집계 (GetSurveyAggregateService) │
 └──────────────────────────────────────────────┘
        ↓
 ┌──────────────────────────────────────────────┐
 │  2단계: 키워드 선택 (SelectSurveyKeywords...)  │
 └──────────────────────────────────────────────┘
        ↓
 ┌──────────────────────────────────────────────┐
 │  3단계: Google Places API 병렬 호출            │
 └──────────────────────────────────────────────┘
        ↓
 ┌──────────────────────────────────────────────┐
 │  4단계: 최종 채점 및 정렬                       │
 └──────────────────────────────────────────────┘
        ↓
[결과 DB 저장 및 이후 조회 시 캐싱 제공]
```

---

## 1단계: 설문 집계 (`GetSurveyAggregateService`)

참여자들의 설문 응답을 **BRANCH(상위 카테고리)** 와 **LEAF(세부 카테고리)** 단위로 분류하여 득표수를 집계합니다.

### 카테고리 구조 (2-Level Hierarchy)

```
BRANCH (대분류)     LEAF (세분류)
────────────────────────────────
한식          →  불고기, 삼겹살, 비빔밥, 냉면 ...
일식          →  초밥·사시미, 라멘, 돈카츠 ...
양식          →  파스타, 피자, 스테이크 ...
중식          →  면류, 튀김·볶음류 ...
동남아 음식   →  베트남 음식, 태국 음식 ...
```

### 집계 규칙

- 참여자 1명 = 설문(`Survey`) 1개 = 여러 카테고리 선택 가능
- **LEAF를 선택하면 부모 BRANCH도 자동으로 득표**됩니다.
  - 예: "초밥·사시미" 선택 → LEAF "초밥·사시미" +1, BRANCH "일식" +1
- 동일 참여자가 같은 카테고리를 중복 선택한 경우 1표로 처리합니다.

### 집계 결과 구조 (`PlaceSurveySummary`)

| 필드 | 설명 |
|------|------|
| `totalRespondents` | 설문을 제출한 총 참여자 수 |
| `leafVotes` | `Map<LEAF 카테고리 ID, 득표수>` |
| `branchVotes` | `Map<BRANCH 카테고리 ID, 득표수>` |
| `stationCoordinates` | 모임 역의 위도/경도 (Google API 검색 위치 편향에 사용) |

---

## 2단계: 검색 키워드 선택 (`SelectSurveyKeywordsService`)

집계된 득표 데이터를 기반으로 **최대 5개**의 검색 키워드와 가중치를 생성합니다.  
키워드 형태: **"강남역 초밥 맛집"**

### ⚖️ 키워드 선택 우선순위 (순서대로 적용)

| 우선순위 | 조건 | 가중치(`weight`) |
|---------|------|-----------------|
| 1 | **전원 만장일치 LEAF**: 모든 참여자가 선택한 세부 카테고리 | `1.0` (최대) |
| 2 | **강한 지지 LEAF**: 득표율 ≥ 20%(`strongLeafSupportThreshold`) | `득표율` (0.2~1.0) |
| 3 | **BRANCH 다수결**: 상위 카테고리 득표율 ≥ 15%(`branchSupportThreshold`) | `득표율` |
| 4 | **보충 LEAF**: 5개 미달 시 득표율 ≥ 10%인 LEAF 순서대로 추가 | `득표율` |
| 5 | **기본 키워드(Fallback)**: 데이터 부족 시 | `0.1` (최소) |

### 📌 키워드 선택 임계값 상수

```kotlin
private val maxKeywordCount = 5         // 최대 키워드 수
private val minimalVoteThreshold = 0.1  // 최소 득표율 (10% 미만 제외)
private val strongLeafSupportThreshold = 0.2  // LEAF 강한 지지 기준 (20%)
private val branchSupportThreshold = 0.15 // BRANCH 포함 기준 (15%)
private val minimalKeywordWeight = 0.1  // 최소 가중치 (Fallback 시)
```

### 🔢 키워드 선택 예시

**예시 1**: 7명 중 한식 4명, 일식 2명, 양식 1명 → 한식 7표(BRANCH), 냉면 3표(LEAF), 초밥 2표(LEAF)

| 키워드 | 득표율 | 가중치 | 선택 이유 |
|--------|------|--------|----------|
| "강남역 냉면 맛집" | 3/7 = 43% | 0.43 | 강한 지지 LEAF |
| "강남역 한식 맛집" | 7/7 = 100% | 1.0 | BRANCH 다수결 |
| "강남역 초밥 맛집" | 2/7 = 29% | 0.29 | 강한 지지 LEAF |
| "강남역 일식 맛집" | 2/7 = 29% | 0.29 | BRANCH 다수결 |
| "강남역 스테이크 맛집" | 1/7 = 14% | 0.14 | 5개 보충 |

**예시 2**: 5명 모두 동일 카테고리(초밥) 선택

| 키워드 | 가중치 | 선택 이유 |
|--------|--------|----------|
| "강남역 초밥 맛집" | 1.0 | 전원 만장일치 LEAF |
| (4개 미달이므로 fallback) | ... | ... |

### 🏷️ Fallback 키워드
각 LEAF 키워드는 결과 수가 부족할 때 사용할 `fallbackKeyword`(부모 BRANCH 키워드)를 함께 가집니다.

```
"강남역 초밥·사시미 맛집" [LEAF]
        ↓ 결과 부족 시
"강남역 일식 맛집" [BRANCH fallback]
```

---

## 3단계: Google Places API 병렬 호출

### 🔑 API 요청 사양

| 항목 | 값 |
|------|----|
| API | Google Places Text Search v1 (`/v1/places:searchText`) |
| 키워드당 최대 요청 수 | `20개` (요금 구간 내 최대치) |
| 검색 반경 | 역 좌표 중심 `3km` 이내 (`locationBias`) |
| 언어 | `ko` (한국어 결과 우선) |
| 타임아웃 | `5초` (초과 시 예외 처리) |

### � 요청 받아오는 필드 (Field Mask)

```
places.id
places.displayName
places.formattedAddress
places.rating
places.userRatingCount
places.photos
places.location
places.types
```

### 🔄 재시도 정책 (Exponential Backoff)

일시적 오류에 대해 자동으로 재시도합니다.

| 항목 | 값 |
|------|----|
| 최대 재시도 횟수 | `3회` |
| 초기 대기 시간 | `100ms` |
| 최대 대기 시간 | `2,000ms` |
| 지터(Jitter) | `0~100ms` 랜덤 추가 (thundering herd 방지) |
| 재시도 대상 상태코드 | `429` (Rate Limit), `500~504` (서버 오류), 네트워크 예외 |
| 즉시 실패 상태코드 | `401` (인증 오류), `404` (없는 리소스) |

### 📦 키워드별 할당량 계산

총 10개 결과를 키워드 가중치에 **비례 배분**합니다.

```
예시: 키워드 3개, 가중치 [0.6, 0.3, 0.1]
  → 10 × 0.6 = 6개
  → 10 × 0.3 = 3개
  → 10 × 0.1 = 1개
  ─────────────
  합계: 10개

반올림 오차 발생 시 → 가중치 높은 키워드에 1개씩 추가 할당
```

### 🧹 결과 필터링 (Keyword Matching)

API 응답 결과 중 실제로 해당 카테고리와 관련 있는 장소만 남깁니다.  
장소의 **이름(displayName)** 또는 **타입(types)** 이 매칭 키워드를 포함해야 합니다.

#### 동의어(Synonym) 매핑으로 정확도 향상

| 카테고리 | 매칭 키워드 예시 |
|---------|----------------|
| 초밥·사시미 | 초밥, 스시, 사시미, sushi |
| 베트남 음식 | 베트남, 포, pho, banh mi |
| 파스타 | 이탈리안, Italian, pasta |
| 중식 면류 | 짜장, 짬뽕, 마라탕, chinese |
| 양식 | 스테이크, 이탈리안, western, italian |

---

## 4단계: 최종 채점 및 정렬

### 🏆 점수 산출 공식

수집된 후보 장소에 대해 종합 점수를 산출하여 내림차순 정렬합니다.

$$\text{Score} = (\text{Weight} \times 100) + (\ln(\text{LikeCount} + 1) \times 50) + \text{UserLikedBoost}$$

| 구성 요소 | 계산 | 최대 기여도 | 설명 |
|----------|------|-----------|------|
| **설문 가중치** | `weight × 100` | ~100점 | 가중치 1.0인 장소는 최대 100점 |
| **팀원 좋아요** | `ln(likeCount + 1) × 50` | ~이론상 무제한 | 로그 스케일 적용으로 독점 방지 |
| **본인 좋아요** | `100` (좋아요 누른 경우) | 100점 | 내가 가고 싶은 곳 최우선 |

> **로그 스케일을 사용하는 이유**: 좋아요 1개 ≫ 0개의 차별화는 크지만, 10개 ≫ 9개의 차별화는 작아야 하기 때문.  
> 참고: `ln(2)×50 ≈ 35`, `ln(6)×50 ≈ 90`, `ln(11)×50 ≈ 120`

### 📊 정렬 우선순위

1. **종합 점수(Score)** 내림차순
2. **좋아요 수(likeCount)** 내림차순 (점수 동점 시 타이브레이커)

---

## 5단계 : 결과 캐싱 및 재사용

### 캐싱 정책

- 검색 결과는 `tb_meeting_place_searches` 테이블에 JSON 형태로 직렬화하여 저장됩니다.
- 동일한 `meetingId`에 대한 두 번째 요청부터는 **API를 재호출하지 않고** 저장된 결과를 반환합니다.
- 단, 좋아요 수는 실시간 DB 조회로 항상 최신 상태를 유지합니다.

```
1번째 조회: Google API 호출 → 결과 저장 → 반환
2번째~ 조회: 저장된 결과 조회 + 좋아요 정보만 최신화 → 반환
```

---

## 6단계: 비동기 처리 파이프라인 및 장애 복구

### Redis Stream 기반 비동기 실행

장소 검색은 연산 비용이 높아 HTTP 응답과 분리하여 비동기로 처리합니다.

```
설문 완료 → meeting_calculation_stream 메시지 발행
                    ↓
         PlaceSearchConsumer (Redis Stream Listener)
                    ↓
         ExecutePlaceSearchService.execute()
```

### 장애 복구 흐름 (PendingMessageScheduler)

| 단계 | 처리 |
|------|------|
| 식당 검색 성공 | `XACK` → PEL(Pending Entries List)에서 제거 |
| 식당 검색 실패 | `XACK` 미호출 → PEL에 잔류 |
| 1분 초과 대기 메시지 | `PendingMessageScheduler`가 `retryCount` 증가 후 재발행 |
| `retryCount ≥ 3` | 폐기(XACK) 처리 + Sentry 에러 수집 |

---

## 핵심 상수 요약

| 상수 | 값 | 위치 | 설명 |
|------|----|------|------|
| `totalFetchSize` | `10` | `ExecutePlaceSearchService` | 최종 반환 장소 수 |
| `keywordFetchSize` | `20` | `ExecutePlaceSearchService` | 키워드당 Google API 요청 수 |
| `photoFallbackBuffer` | `5` | `ExecutePlaceSearchService` | 사진 부재 보정용 여분 슬롯 |
| `weightScoreMultiplier` | `100.0` | `ExecutePlaceSearchService` | 가중치 점수 배율 |
| `likeScoreMultiplier` | `50.0` | `ExecutePlaceSearchService` | 좋아요 점수 배율 |
| `maxKeywordCount` | `5` | `SelectSurveyKeywordsService` | 최대 키워드 수 |
| `minimalVoteThreshold` | `0.1` (10%) | `SelectSurveyKeywordsService` | 최소 득표율 |
| `apiTimeoutMillis` | `5,000ms` | `GooglePlacesClient` | API 타임아웃 |
| `maxRetries` | `3` | `GooglePlacesClient` | API 최대 재시도 횟수 |
| `MAX_RETRY_COUNT` | `3` | `PendingMessageScheduler` | Stream 메시지 최대 재처리 횟수 |
