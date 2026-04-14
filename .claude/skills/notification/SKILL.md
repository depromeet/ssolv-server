# Notification Patterns (FCM)

## 개요

FCM 푸시 알림은 `ssolv-infrastructure: FcmClient`를 통해 발송한다.
알림 발송은 항상 **트랜잭션 커밋 이후**에 수행한다 — 트랜잭션 롤백이 발생해도 알림이 발송되는 것을 방지.

---

## FcmClient 사용 패턴

```kotlin
// 단일 발송
fcmClient.send(
    token = deviceToken,
    title = "알림 제목",
    body = "알림 내용",
    data = mapOf("type" to "MEETING_RESULT_READY", "meetingToken" to token)
)

// 멀티캐스트 발송 (여러 디바이스)
fcmClient.sendMulticast(
    tokens = validTokens,        // List<String> — 유효한 FCM 토큰만
    title = "알림 제목",
    body = "알림 내용",
    data = mapOf("path" to "/meetings/$meetingToken/result/overview")
)
```

---

## 트랜잭션-후 발송 패턴

```kotlin
@Service
class SomeNotificationService(
    private val someRepository: SomeRepository,
    private val deviceTokenQueryRepository: DeviceTokenQueryRepository,
    private val fcmClient: FcmClient,
    private val transactionTemplate: TransactionTemplate,
    private val stringRedisTemplate: StringRedisTemplate,
) {
    suspend fun send(targetId: Long) = withTracingContext() {
        // 1. 멱등성 체크 (중복 발송 방지)
        val idempotencyKey = "sent:notification:SOME_EVENT:$targetId"
        val isNew = stringRedisTemplate.opsForValue()
            .setIfAbsent(idempotencyKey, "true", Duration.ofHours(1)) ?: false
        if (!isNew) return@withTracingContext

        // 2. 트랜잭션 내에서 데이터 조회
        val data = transactionTemplate.execute {
            runBlocking { someRepository.findById(targetId) }
        } ?: return@withTracingContext

        // 3. 알림 대상 토큰 조회 (알림 수신 동의한 사용자만)
        val tokens = transactionTemplate.execute {
            runBlocking {
                deviceTokenQueryRepository.findValidTokensByUserIds(
                    userIds = listOf(data.userId),
                    isNotificationEnabled = true
                )
            }
        } ?: emptyList()

        if (tokens.isEmpty()) return@withTracingContext

        // 4. 트랜잭션 커밋 이후 — FCM 발송 (트랜잭션 바깥)
        fcmClient.sendMulticast(tokens = tokens, title = "...", body = "...", data = mapOf(...))
    }
}
```

**핵심**: DB 조회는 `transactionTemplate.execute { runBlocking { ... } }` 안에서,
FCM 발송은 그 바깥에서 수행한다.

---

## 디바이스 토큰 관리

```kotlin
// 토큰 등록 (앱 시작 시 호출)
POST /api/v1/notifications/device-token
// RegisterDeviceTokenRequest(token: String, platform: String)

// 토큰 삭제 (로그아웃/탈퇴 시)
DELETE /api/v1/notifications/device-token
// DeleteDeviceTokenRequest(token: String)
```

**DeviceTokenQueryRepository**는 `isNotificationEnabled = true`인 토큰만 조회한다.
유저가 알림을 끈 경우 자동으로 제외됨.

---

## Redis Stream 기반 비동기 알림

알림 발송이 메인 플로우와 분리되어야 할 때 Redis Stream을 사용한다.
(예: 미팅 결과 계산 완료 후 전체 참석자에게 알림)

```
설문 완료 → meeting_calculation_stream 이벤트 발행
→ PlaceSearchConsumer → 장소 추천 완료
→ meeting_notification_stream 이벤트 발행
→ MeetingNotificationConsumer → SendMeetingResultNotificationService.send()
```

Stream 상수는 `ssolv-domain: RedisStreamConstants` 참고.
Consumer 구현 패턴은 `/async-processing` 스킬 참고.

---

## 멱등성 (중복 발송 방지)

같은 이벤트로 알림이 두 번 발송되지 않도록 Redis `setIfAbsent`로 잠금한다.

```kotlin
val key = "sent:notification:{EVENT_TYPE}:{entityId}:{userId}"
val acquired = stringRedisTemplate.opsForValue()
    .setIfAbsent(key, "true", Duration.ofHours(1)) ?: false
if (!acquired) return  // 이미 처리됨
```

TTL은 이벤트 성격에 따라 조정. 미팅 결과 알림은 1시간으로 충분.

---

## data 페이로드 규칙

앱에서 딥링크 처리를 위해 `data` 맵에 아래 키를 포함한다:

```kotlin
mapOf(
    "type" to "MEETING_RESULT_READY",           // 알림 유형 식별자
    "meetingToken" to meetingToken,             // 초대 토큰 또는 meetingId
    "path" to "/meetings/$meetingToken/result/overview"  // 앱 내 이동 경로
)
```

---

## 테스트 처리

통합 테스트에서 FCM 클라이언트는 반드시 Mock으로 대체한다 (실제 FCM 호출 금지).

```kotlin
@IntegrationTest
class NotificationIntegrationTest {
    @MockitoBean private lateinit var fcmClient: FcmClient   // ← Mock 필수
    // or
    @MockkBean private lateinit var fcmClient: FcmClient     // Mockk 사용 시
}
```

단위 테스트에서:
```kotlin
@UnitTest
class SendMeetingResultNotificationServiceTest {
    @Mock private lateinit var fcmClient: FcmClient
    @Mock private lateinit var deviceTokenQueryRepository: DeviceTokenQueryRepository
    
    @Test
    fun `알림 수신 비동의 사용자는 FCM 호출 안 함`() = runTest {
        whenever(deviceTokenQueryRepository.findValidTokensByUserIds(any(), any()))
            .thenReturn(emptyList())
        
        service.send(meetingId = 1L, userId = 1L)
        
        verifyNoInteractions(fcmClient)  // FCM 호출 없어야 함
    }
}
```
