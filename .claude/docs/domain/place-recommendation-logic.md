# 장소 추천 알고리즘 상세 설명 (Place Recommendation Logic)

> `ssolv-api-place` 모듈의 핵심 알고리즘 및 데이터 적재 전략을 설명합니다.  

---

## 🏗️ 전체 아키텍처 흐름

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
[결과 Redis 적재 및 식당 마스터 정보 RDB 저장]
```

---

## 💾 데이터 적재 정책 (Storage Strategy)

우리 시스템은 데이터의 성격에 따라 **RDB와 Redis를 분리하여 사용**합니다.

### 1. 식당 마스터 정보 (Permanent - RDB)
*   **저장소**: MariaDB `tb_place` 테이블
*   **적재 내용**: 식당 고유 ID(PlaceID), 이름, 주소, 위경도, 구글 평점, **네이버 지도 링크** 등.
*   **전략**: 구글 API로부터 가져온 모든 식당 정보는 RDB에 영구 적재됩니다. 이는 운영 정책(30일)에 구애받지 않고 마스터 데이터로서 관리됩니다.

### 2. 모임별 정렬 결과 (Temporary - Redis)
*   **저장소**: Redis ZSET (`meeting:places:{meetingId}`)
*   **적재 내용**: 특정 모임의 설문 결과에 따라 정렬된 **식당 ID 리스트**와 그 **최종 점수(Score)**.
*   **전략**: 
    *   **30일 TTL(만료 시간)** 을 적용하여 Redis의 자동 삭제 기능을 활용합니다. 
    *   별도의 RDB 히스토리 관리 로직을 두지 않으며, Redis에서 데이터가 삭제(만료)되면 실제 요청 시점에 해당 모임에 대한 추천 순위를 **처음부터 다시 계산(Re-request)** 합니다.

### 3. 상세 정보 캐시 (Cache - Redis)
*   **저장소**: Redis String (`place:details:{id}`)
*   **전략**: API 응답 속도 향상을 위해 식당 상세 정보를 캐싱하며, 30일 TTL을 가집니다.

---

## ⚙️ 상세 단계별 로직

### 1단계: 설문 집계 (`GetSurveyAggregateService`)
참여자들의 설문 응답을 BRANCH/LEAF 단위로 집계합니다. LEAF 선택 시 부모 BRANCH도 자동으로 득표 처리됩니다.

### 2단계: 검색 키워드 선택 (`SelectSurveyKeywordsService`)
득표 데이터를 기반으로 최대 5개의 검색 키워드("강남역 초밥 맛집" 등)와 가중치를 생성합니다. 

### 3단계: Google Places API 호출
*   **API**: Google Places Text Search v1
*   **제약 조건**: **`locationRestriction`** 을 사용하여 역 기준 반경 **3km 이내** 결과만 강제로 가져옵니다.

### 4단계: 최종 채점 및 정렬 (Ranking)
다양한 지표를 합산하여 최종 점수를 산출합니다.
$$\text{Score} = \text{SurveyScore} + \text{LikeScore} + \text{GoogleScore} + \text{DistanceScore} + \text{ProximityBoost} + \text{CategoryMatchBoost}$$

1.  **설문 가중치 (Survey Score)**: 카테고리 득표율 기반 (최대 100점)
2.  **팀원 좋아요 (Like Score)**: 로그 스케일 적용 ( $\ln(\text{Count} + 1) \times 50$ )
3.  **구글 신뢰도 점수 (Google Score)**: 별점과 리뷰 수 기반 ( $\text{Rating} \times \ln(\text{UserRatingsTotal} + 1) \times 2$ )
4.  **거리 점수 (Distance Score)**: 5km 이내일 때 가까울수록 높은 점수 (0~100점)
5.  **도보권 보너스 (Proximity Boost)**: 역 기준 **1km 이내**일 때 **50점** 추가 가점
6.  **카테고리 매칭 (Category Match Boost)**: 구글 장소 타입(`types`)이 검색 키워드와 일치할 경우 **50점** 추가 가점
7.  **엄격한 필터링**: 역 기준 **반경 5km**를 초과하는 장소는 결과에서 원천 배제합니다.

---

## 🔄 재사용 흐름 (Lifecycle)

1.  **최초 조회**: (Redis Miss) → 추천 로직 실행 → 식당 정보 RDB 저장 → 결과 리스트 Redis 적재(30일 TTL) → 결과 반환.
2.  **반복 조회**: (Redis Hit) → Redis에서 정렬 리스트 로드 → 좋아요 실시간 결합(Redis SSET/SCARD) → 결과 반환.
3.  **만료(30일 경과)**: (Redis Expired) → Redis 리스트 소멸 → 다음 요청 시 **Step 1(최초 조회)** 과정을 통해 다시 계산 및 재적재.
