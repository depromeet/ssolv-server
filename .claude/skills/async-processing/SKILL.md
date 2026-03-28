# Async Processing (Redis Streams)

## 개요

장소 추천 검색은 비용이 큰 작업이므로 **Redis Streams**로 비동기 처리한다.

흐름:
1. 미팅 참석자가 설문을 완료하면 `meeting_calculation_stream`에 이벤트 발행
2. `PlaceSearchConsumer`가 스트림을 소비하여 Google Places API 호출 + 결과 캐싱
3. 처리 실패 시 ACK 없이 PEL에 남김 → `PendingMessageScheduler`가 최대 3회 재시도

## 스트림 상수

```kotlin
// ssolv-domain: org.depromeet.team3.common.RedisStreamConstants
object RedisStreamConstants {
    const val MEETING_CALCULATION_STREAM = "meeting_calculation"
    const val MEETING_CALCULATION_GROUP = "meeting_calculation_group"
    const val MEETING_NOTIFICATION_STREAM = "meeting_notification"
    const val MEETING_NOTIFICATION_GROUP = "meeting_notification_group"
}
```

## Consumer 구현 패턴

```kotlin
@Component
class SomeConsumer(
    private val someService: SomeService,
    private val stringRedisTemplate: StringRedisTemplate,
) : StreamListener<String, MapRecord<String, String, String>> {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onMessage(message: MapRecord<String, String, String>) {
        val id = message.value["someId"]?.toLongOrNull() ?: return

        scope.launch(Dispatchers.IO) {
            try {
                someService.execute(id)
                // 성공 시에만 ACK
                stringRedisTemplate.opsForStream<String, String>()
                    .acknowledge(RedisStreamConstants.SOME_GROUP, message)
            } catch (e: Exception) {
                // 실패 시 ACK 없음 → PEL에 남아 스케줄러가 재시도
                Sentry.captureException(e)
            }
        }
    }
}
```

## Stream Config 패턴

```kotlin
@Configuration
@Profile("!test")   // 테스트 환경에서는 스트림 비활성화
class SomeStreamsConfig(
    private val someConsumer: SomeConsumer,
    private val stringRedisTemplate: StringRedisTemplate,
) {
    @Bean
    fun someStreamSubscription(
        redisConnectionFactory: RedisConnectionFactory,
    ): Subscription {
        val streamKey = RedisStreamConstants.SOME_STREAM
        val groupName = RedisStreamConstants.SOME_GROUP
        val consumerName = "app_server_${UUID.randomUUID()}"  // 다중 인스턴스 지원

        // 컨슈머 그룹이 없으면 생성
        try {
            stringRedisTemplate.opsForStream<String, String>().createGroup(streamKey, groupName)
        } catch (e: Exception) {
            if (e.message?.contains("BUSYGROUP") != true) {
                logger.warn("Failed to create consumer group: ${e.message}")
            }
        }

        val options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
            .builder()
            .pollTimeout(Duration.ofSeconds(1))
            .build()

        val container = StreamMessageListenerContainer.create(redisConnectionFactory, options)
        val subscription = container.receive(
            Consumer.from(groupName, consumerName),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
            someConsumer,
        )
        container.start()
        return subscription
    }
}
```

## 중복 처리 방지 (멱등성)

같은 메시지가 여러 번 처리되지 않도록 Redis `setIfAbsent`로 락을 건다.

```kotlin
val lockKey = "processing:$meetingId"
val acquired = stringRedisTemplate.opsForValue()
    .setIfAbsent(lockKey, "1", Duration.ofMinutes(5))

if (acquired != true) return  // 이미 처리 중
```

## 재시도 스케줄러 패턴

```kotlin
@Component
class PendingMessageScheduler(
    private val stringRedisTemplate: StringRedisTemplate,
    private val someService: SomeService,
) {
    private val MAX_RETRY_COUNT = 3L

    @Scheduled(fixedDelay = 60000)  // 1분마다 실행
    fun retryPendingMessages() {
        val pending = stringRedisTemplate.opsForStream<String, String>()
            .pending(
                RedisStreamConstants.SOME_STREAM,
                Consumer.from(RedisStreamConstants.SOME_GROUP, "*"),
                Range.unbounded(),
                10L,
            )

        pending?.forEach { entry ->
            if (entry.totalDeliveryCount >= MAX_RETRY_COUNT) {
                // 최대 재시도 초과 → 데드레터 처리 또는 로깅
                stringRedisTemplate.opsForStream<String, String>()
                    .acknowledge(RedisStreamConstants.SOME_GROUP, entry)
                Sentry.captureMessage("Max retry exceeded for message: ${entry.id}")
            }
        }
    }
}
```

## 이벤트 발행

```kotlin
// 스트림에 메시지 발행
stringRedisTemplate.opsForStream<String, String>().add(
    StreamRecords.newRecord()
        .`in`(RedisStreamConstants.MEETING_CALCULATION_STREAM)
        .ofMap(mapOf("meetingId" to meetingId.toString()))
)
```

## 주의사항

- Stream Config에 반드시 `@Profile("!test")` 적용 — 테스트 환경에서는 Redis 연결 없이 실행
- 컨슈머 이름에 UUID 사용 — 동일 스트림을 여러 인스턴스가 소비할 때 작업 분산
- 외부 API 호출(Google Places) 실패는 예외로 처리하고 재시도에 맡김
- 자세한 추천 알고리즘: `docs/place-recommendation-logic.md` 참고
