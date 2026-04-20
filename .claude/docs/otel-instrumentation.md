# OpenTelemetry 계측 — Google Places API 호출 구간

> 연관 PR: [#179](https://github.com/depromeet/ssolv-server/pull/179) · 연관 Issue: #178
> 연관 문서: `place-recommendation-logic.md`, `place-redis-caching-strategy.md`

## 왜 했나

Google Places API 호출 구간은 **외부 의존성 중 가장 비싼 구간**(타임아웃 5초, 재시도 3회, 키워드당 1회 호출 × 5키워드 병렬)인데, 메트릭(카운터/히스토그램)만 있고 **개별 요청 단위의 분포/상관관계를 추적할 수 없었다**.

- "P95 지연이 1.2초" → 어느 키워드? 재시도 때문? 세마포어 대기 때문?
- "429가 2건 발생" → 동일 모임에서? 동일 키워드에서?
- HTTP/2 전환을 고민 중이지만 **근거 데이터가 없음**

Trace 는 이 질문들에 답을 준다. 그리고 이 데이터가 **HTTP/2 전환 여부의 근거**가 된다.

## 무엇을 했나

### 생성된 Span 계층

```
place.google.fan_out                  (키워드 5개 병렬 호출 전체 — parent)
├── place.google.fetch                (키워드당 1개 — semaphore 포함)
│   └── google.places.textSearch     (실제 HTTP CLIENT span)
├── place.google.fetch
│   └── google.places.textSearch
└── ...
```

### Span Attributes

| Span | Attribute | 용도 |
|---|---|---|
| `google.places.textSearch` | `google.places.query`, `google.places.radius_m`, `google.places.max_results`, `google.places.has_location_bias` | 호출 파라미터 |
| `google.places.textSearch` | `http.status_code`, `http.protocol`, `google.places.result_count` | 응답 관측 |
| `place.google.fetch` | `semaphore.wait_ms`, `semaphore.global.available` | 세마포어 병목 관측 |
| `place.google.fan_out` | `place.keyword.count`, `place.selected.count`, `place.fallback.count` | 키워드 fallback 전략 관측 |

### Span Events

`google.places.textSearch` 에 재시도 발생 시 `retry` 이벤트 기록:
- `retry.attempt` (1,2)
- `retry.reason` (`ResponseException`, `TimeoutCancellationException`, ...)
- `retry.delay_ms` (지터 포함 실제 delay)

## 어떻게 동작하나

### 코드 위치

- [GooglePlacesClient.kt](ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/place/client/GooglePlacesClient.kt) — CLIENT span + retry event
- [ExecutePlaceSearchService.kt](ssolv-api-place/src/main/kotlin/org/depromeet/team3/place/application/execution/ExecutePlaceSearchService.kt) — fan_out / fetch span

### 코루틴 전파 패턴 (중요)

```kotlin
val tracingContext = Context.current().with(span).asContextElement()
withContext(tracingContext) {
    // 자식 span 은 자동으로 이 span 을 parent 로 인식
}
```

❌ 하지 말 것: `span.makeCurrent()` — ThreadLocal 기반이라 **Dispatcher 전환 시 유실됨**
✅ 해야 할 것: `asContextElement()` 로 코루틴 Context 에 전파

### 인프라 흐름

앱 → OTLP/HTTP `:4318` → Alloy (`otelcol.receiver.otlp`) → batch processor → Grafana Cloud Tempo

**이미 구축되어 있었음** — [alloy-config.alloy](ssolv-infrastructure/monitoring/alloy-config.alloy) 117–145 라인.
추가 인프라 작업 불필요. 앱에서 span 만 생성하면 됨.

### 샘플링

`application.yml` → `management.tracing.sampling.probability: 0.1` (10%).
Google API 호출은 양이 많지 않으니 필요하면 0.5~1.0 으로 올려도 됨.

## 어떻게 사용하나

### 1) Tempo Explore 에서 직접 TraceQL

**느린 호출 찾기**
```traceql
{ name="google.places.textSearch" && duration > 1s }
```

**특정 키워드의 호출 분포**
```traceql
{ span.google.places.query =~ "한식.*" }
```

**재시도 발생한 호출**
```traceql
{ name="google.places.textSearch" && event.name="retry" }
```

**429 Rate Limit 맞은 호출**
```traceql
{ span.http.status_code = 429 }
```

**세마포어에서 오래 대기한 호출**
```traceql
{ span.semaphore.wait_ms > 100 }
```

**모임 단위 fan-out 시간**
```traceql
{ name="place.google.fan_out" } | select(span.place.keyword.count, span.place.selected.count, duration)
```

### 2) Dashboard 사용

`google-places-tracing.json` — Grafana Cloud 대시보드로 자동 프로비저닝됨.

포함 패널:
- **Slow Traces** — `$slow_threshold_ms` (500/1000/2000/3000/5000) 변수로 slow 기준 조절
- **Retry Events** — 재시도 발생한 trace 목록
- **Error Traces** — 4xx/5xx 받은 호출
- **Fan-out** — 모임 단위 병렬 호출 요약
- **Latency P50/P95/P99** — Tempo span-metrics generator 가 자동 생성한 메트릭 기반
- **Call Rate by status_code** — status 분포 시계열

### 3) 기존 Prometheus 대시보드와 교차 확인

`google-places-api.json` (Prometheus) 와 `google-places-tracing.json` (Tempo) 는 **상호 보완**:

- Prometheus 대시보드: 집계된 트렌드 (분당 호출 수, 성공률, P95)
- Tempo 대시보드: **왜 그런지**의 근거 (어떤 키워드가 느린지, 어떤 재시도 이유가 많은지)

## 배포 후 검증

1. 앱 재배포 후 Grafana Cloud → Explore → Tempo
2. `{ name="google.places.textSearch" }` 로 최근 trace 확인
3. Span 이 보이면 성공. attribute 들이 모두 채워져 있는지 확인
4. Service Graph (Grafana Cloud 자동 생성) 에서 `ssolv-api-core` → Google API 엣지 확인

## 앞으로의 활용

이 계측을 **기반**으로 다음 판단들을 데이터로 할 수 있다:

- **HTTP/2 전환 판단** — `http.protocol` attribute 로 전/후 P95 비교
- **세마포어 한도 튜닝** — `semaphore.wait_ms` 분포 보고 global/per-request 조정
- **Circuit Breaker 도입 판단** — `event.retry.reason` 통계로 의미있는 실패 패턴 파악
- **키워드 선택 로직 개선** — `place.fallback.count` 로 fallback 빈도 관측
