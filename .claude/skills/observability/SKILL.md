---
name: observability
description: Use when instrumenting code with Sentry error reporting, Micrometer metrics (Prometheus registry), or OpenTelemetry tracing. Also covers MDC-based logging (request_id, user_id), propagating OpenTelemetry Context across coroutine dispatcher switches, and Grafana/Prometheus dashboard config. Trigger on `span`, `meter`, `Sentry.capture`, `withTracingContext`, or logging/metrics related edits.
---

# Observability Patterns

## 구성 요소

| 도구 | 역할 | 설정 위치 |
|---|---|---|
| **Sentry** | 예외 추적 + 성능 트랜잭션 | `ssolv-api-common: SentryConfig` |
| **Micrometer** | JVM/앱 메트릭 수집 | actuator + Prometheus endpoint |
| **OpenTelemetry** | 분산 트레이싱 | `withTracingContext()` 유틸 |
| **Grafana Alloy** | 메트릭 수집 에이전트 | Instance B 컨테이너 |

---

## Sentry

### 자동 캡처 (이미 설정됨)

`GlobalExceptionHandler`에서 모든 `DpmException` 및 예상치 못한 예외를 자동으로 캡처한다.
새 예외 핸들러를 추가할 때는 반드시 `Sentry.captureException(e)`를 포함한다.

```kotlin
@ExceptionHandler(SomeDpmException::class)
fun handleSomeDpmException(e: SomeDpmException): ResponseEntity<DpmApiResponse<Unit>> {
    logger.warn("...")
    io.sentry.Sentry.captureException(e)   // ← 반드시 포함
    return ResponseEntity.status(...).body(...)
}
```

### 수동 캡처

```kotlin
// 예외 캡처
io.sentry.Sentry.captureException(e)

// 메시지 캡처 (예외 없는 경고)
io.sentry.Sentry.captureMessage("Max retry exceeded for message: ${entry.id}")

// 사용자 컨텍스트는 SentryConfig.sentryUserProvider()가 자동 주입
// → SecurityContext의 JwtAuthenticationToken.principal (userId) 포함
```

### /actuator 트랜잭션 필터링

`SentryConfig.beforeSendTransactionCallback()`이 `/actuator` 경로를 자동 드롭한다. 
헬스체크 노이즈가 Sentry에 쌓이지 않는다.

### 주의사항

```kotlin
// ❌ 예외를 삼키면서 Sentry에도 안 보내는 패턴
} catch (e: Exception) {
    logger.warn("...")
    // Sentry.captureException 누락 → 운영 중 오류 감지 불가
}

// ✅ 올바른 패턴
} catch (e: Exception) {
    logger.warn("...")
    Sentry.captureException(e)
    // 또는 throw로 GlobalExceptionHandler에 위임
}
```

---

## OpenTelemetry 트레이싱

비즈니스 로직이 있는 서비스에서 `withTracingContext()`로 감싼다.

```kotlin
// ssolv-api-common: org.depromeet.team3.common.util.withTracingContext
suspend fun send(meetingId: Long, userId: Long) = withTracingContext() {
    // 이 블록 내 작업이 OTel 스팬으로 기록됨
    val result = someRepository.findById(meetingId)
    // ...
}
```

단순 CRUD 서비스에는 불필요. 외부 API 호출 + 다중 DB 쿼리가 포함된 복합 작업에 사용한다.

---

## Micrometer 메트릭

현재 `/actuator/prometheus` 엔드포인트로 Grafana Alloy가 스크래핑한다.

### 커스텀 메트릭 추가 패턴

```kotlin
@Service
class SomeService(
    private val meterRegistry: MeterRegistry,
) {
    private val counter = meterRegistry.counter("ssolv.some.event.total", "type", "example")

    suspend operator fun invoke(...) {
        // 비즈니스 로직
        counter.increment()
    }
}
```

메트릭 이름 규칙: `ssolv.{domain}.{action}.{unit}` (예: `ssolv.meeting.created.total`)

---

## 로그 수준 가이드

```kotlin
private val logger = KotlinLogging.logger { }

logger.debug { "..." }   // 개발 디버깅용, 운영에서는 출력 안 됨
logger.info { "..." }    // 주요 비즈니스 이벤트 (서비스 시작, 초기화)
logger.warn { "..." }    // 예상 가능한 오류 (비즈니스 예외, 검증 실패)
logger.error { "..." }   // 예상치 못한 오류 (GlobalExceptionHandler의 catch-all)
```

**주의**: Redis Stream Consumer의 예외는 `catch` 후 `Sentry.captureException(e)`로 처리하고,
ACK를 보내지 않아 PEL 재시도 큐에 남긴다. `logger.error`가 아닌 `Sentry.captureException`이 기본.

---

## 운영 환경 로그 조회

```bash
# 빠른 조회는 /logs 커맨드 사용
/logs app-server-a --tail 200
/logs app-server-b --tail 200
```

Sentry 이슈 분석은 `/iac-audit`처럼 자동화된 scheduled task가 매일 09:00에 실행됨.
(ADR-021 참고)
