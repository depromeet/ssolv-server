# Batch / Scheduler Patterns

> `ssolv-batch` 모듈: 주기적 배경 작업 담당.
> Redis Streams Dead-Letter 처리, 장소 데이터 정리 등 API 모듈과 독립 실행.

---

## 모듈 구조

```text
ssolv-batch
└── scheduler/
    ├── PendingMessageScheduler    — Redis Streams dead-letter (XACK 폐기)
    ├── PlaceDataCleanupScheduler  — 30일 초과 장소 데이터 삭제 (Google ToS)
    └── CoroutineWatchdogManager   — 분산 락 + TTL 자동 연장
```

---

## 필수 규칙

```kotlin
// 1. 모든 스케줄러에 @Profile("!test") 필수
//    → 테스트 컨텍스트에서 스케줄러가 자동 실행되지 않도록 차단
@Component
@Profile("!test")
class SomeScheduler(...)

// 2. runBlocking으로 코루틴 진입 (스케줄러는 일반 스레드에서 호출됨)
@Scheduled(fixedDelay = 60_000)
fun run() {
    runBlocking {
        // suspend 함수 호출 가능
    }
}

// 3. 분산 환경 → CoroutineWatchdogManager로 락 획득 필수
//    → 동일 작업이 두 인스턴스에서 중복 실행되는 것을 방지
runBlocking {
    watchdogManager.executeWithLock(LOCK_KEY, initialTtlMillis = 10_000, extensionMillis = 10_000) {
        // 임계 구역
    }
}
```

---

## CoroutineWatchdogManager

Redis 분산 락 + 작업 중 TTL 자동 연장 패턴.

```kotlin
// 선언
@Component
class SomeScheduler(
    private val watchdogManager: CoroutineWatchdogManager,
) {
    companion object {
        private const val LOCK_KEY = "lock:<scheduler-name>"
    }

    @Scheduled(fixedDelay = 60_000)
    fun run() {
        runBlocking {
            watchdogManager.executeWithLock(
                lockKey = LOCK_KEY,
                initialTtlMillis = 10_000,    // 최초 락 TTL
                extensionMillis = 10_000,      // 갱신 주기마다 부여할 TTL
            ) {
                doWork()
            }
        }
    }
}
```

**내부 동작:**
- 락 획득 → 코루틴 Watchdog이 주기적으로 TTL 연장
- 작업 완료 → Lua 스크립트로 안전하게 락 해제 (내가 잡은 락만 해제)
- 락 획득 실패 시 → 작업 건너뜀 (다른 인스턴스가 처리 중)

---

## PendingMessageScheduler — Dead-Letter 처리

```text
deliveryCount 증가 시점:
  최초 XREADGROUP 배달: +1
  API 모듈 RecoveryScheduler XCLAIM 마다: +1

MAX_DELIVERY_COUNT = 4 → 최초 1회 + 복구 3회 후 XACK 폐기 + Sentry 캡처
```

**스트림 목록:** `meeting_calculation_stream`, `meeting_notification_stream`

새 Redis Stream 추가 시 `PendingMessageScheduler.streams`에 등록:
```kotlin
private val streams = listOf(
    RedisStreamConstants.MEETING_CALCULATION_STREAM to RedisStreamConstants.MEETING_CALCULATION_GROUP,
    RedisStreamConstants.MEETING_NOTIFICATION_STREAM to RedisStreamConstants.MEETING_NOTIFICATION_GROUP,
    // 새 스트림 추가
    RedisStreamConstants.NEW_STREAM to RedisStreamConstants.NEW_GROUP,
)
```

---

## PlaceDataCleanupScheduler — 장소 데이터 정리

Google Places API 약관(ToS) 준수: 30일 초과 캐시 데이터 삭제.

```kotlin
@Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시
fun cleanupStalePlaceData() {
    runBlocking {
        watchdogManager.executeWithLock(lockKey, 10_000, 10_000) {
            transactionTemplate.execute {
                val deletedCount = placeJpaRepository.deleteByUpdatedAtBefore(
                    LocalDateTime.now().minusDays(30)
                )
                logger.info("30일 경과 장소 데이터 삭제: {}건", deletedCount)
            }
        }
    }
}
```

**주의**: 트랜잭션이 필요한 DB 작업은 `transactionTemplate.execute {}` 안에서 실행.
스케줄러 메서드 자체에 `@Transactional` 사용 금지 (코루틴 컨텍스트 문제).

---

## 새 스케줄러 추가 체크리스트

- [ ] `@Component`, `@Profile("!test")` 선언
- [ ] `@Scheduled(fixedDelay = ...)` 또는 `@Scheduled(cron = "...")` 사용
- [ ] `runBlocking {}` 으로 코루틴 진입
- [ ] `CoroutineWatchdogManager.executeWithLock` 으로 분산 락 적용
- [ ] 예외 `catch` 후 `Sentry.captureException(e)` 호출
- [ ] MDC `REQUEST_ID` 설정 → 로그 트레이싱 가능하도록
- [ ] DB 작업은 `transactionTemplate.execute {}` 사용

---

## MDC 트레이싱 패턴

```kotlin
@Scheduled(fixedDelay = 60_000)
fun run() {
    val requestId = "scheduler-" + UUID.randomUUID().toString().substring(0, 8)
    MDC.put(MdcLoggingFilter.REQUEST_ID, requestId)
    try {
        runBlocking { /* 작업 */ }
    } finally {
        MDC.clear()  // 반드시 clear — 스레드 풀 재사용 시 오염 방지
    }
}
```
