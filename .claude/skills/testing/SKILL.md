---
name: testing
description: Use when writing or modifying tests — JUnit 5 + MockK + Kotlin coroutines test support, WebMvcTest slices, @SpringBootTest integration tests, Testcontainers for MySQL/Redis, fixture patterns under src/testFixtures, and Jacoco coverage conventions. Trigger on any file under src/test/ or src/testFixtures/, or when adding a new test for a new feature.
---

# Testing Guide

## 테스트 원칙

### 테스트해야 하는 것
- 도메인 비즈니스 규칙 및 불변 조건
- 복잡한 조건 분기가 있는 서비스 로직
- 외부 시스템 연동 (Kakao OAuth, Apple OAuth, Google Places, FCM)
- 예외 흐름 및 에러 응답 형식 (`DpmApiResponse` 구조 검증)

### 테스트하지 않아도 되는 것
- 단순 getter/setter
- DTO 매핑 (Request → Command, Entity → Response)
- `@ConfigurationProperties` 바인딩 클래스
- JPA Entity 필드 선언
- 단순 `save()` / `findById()` 위임 (비즈니스 로직 없는 CRUD)

### Spring Context 재사용 원칙
Spring Boot 테스트에서 Context 생성 비용은 매우 크다.
직접 `@SpringBootTest`, `@ExtendWith`를 선언하지 말고 아래 메타 어노테이션을 사용한다.

```
❌ 각 테스트 클래스에 @SpringBootTest, @Import 직접 선언
✅ 사전 정의된 @IntegrationTest, @UnitTest 메타 어노테이션 사용
```

---

## 모듈별 테스트 전략

| 모듈 | 허용 테스트 종류 | Spring Context | DB |
|---|---|---|---|
| `ssolv-domain` | 순수 단위 테스트만 | 금지 | 불필요 |
| `ssolv-global-utils` | 순수 단위 테스트만 | 금지 | 불필요 |
| `ssolv-api-common` | 단위 + testFixtures 제공 | 금지 | 불필요 |
| `ssolv-api-core` | 단위 + 통합 테스트 | 허용 | MySQL Testcontainers |
| `ssolv-api-place` | 단위 + 통합 테스트 | 허용 | MySQL Testcontainers |
| `ssolv-infrastructure` | 단위 + 외부 API mocking | 제한적 허용 | 불필요 |
| `ssolv-batch` | 통합 테스트 | 허용 | MySQL Testcontainers |

---

## 메타 어노테이션

`ssolv-api-common/src/testFixtures`에 정의되어 있다.

### `@UnitTest` — Mockito 단위 테스트

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(MockitoExtension::class)
annotation class UnitTest
```

**사용 대상:** Service, Controller (standalone MockMvc), 도메인 객체

### `@IntegrationTest` — Spring 통합 테스트

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = [TestContainerConfig::class])
@Import(TestSecurityConfig::class)
annotation class IntegrationTest
```

**사용 대상:** 전체 플로우 검증이 필요한 E2E 테스트 (`NotificationIntegrationTest` 등)

### `@RepositoryTest` — JPA Repository 테스트

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = [TestContainerConfig::class])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
annotation class RepositoryTest
```

**사용 대상:** Kotlin-JDSL 커스텀 쿼리, 복잡한 조인/집계 쿼리 검증

---

## 테스트 레이어별 패턴

### Controller 단위 테스트

suspend 컨트롤러는 `asyncDispatch()` 2단계 패턴을 반드시 사용한다.

```kotlin
@UnitTest
class MeetingControllerTest {

    @Mock private lateinit var createMeetingService: CreateMeetingService
    @InjectMocks private lateinit var meetingController: MeetingController

    private val testUserIdResolver = TestUserIdArgumentResolver()
    private val objectMapper = ObjectMapper()

    private val mockMvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(meetingController)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(testUserIdResolver)
            .build()
    }

    @Test
    fun `미팅 생성 성공 - 200`() = runTest {
        // given
        testUserIdResolver.setTestUserId(1L)
        createMeetingService.stub {
            onBlocking { invoke(any(), any()) }.doReturn(MeetingResponse(id = 1L, name = "팀 미팅"))
        }

        // when: suspend 컨트롤러는 2단계로 검증한다
        val mvcResult = mockMvc.perform(
            post("/api/v1/meetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateMeetingRequest(name = "팀 미팅")))
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(1L))
    }

    @Test
    fun `@Valid 위반 - 400`() {
        // 컨트롤러 진입 전 실패 → async 시작 안 됨 → asyncDispatch 불필요
        mockMvc.perform(
            post("/api/v1/meetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `비즈니스 예외 - 404`() = runTest {
        createMeetingService.stub {
            onBlocking { invoke(any(), any()) }.doThrow(MeetingException(ErrorCode.MEETING_NOT_FOUND))
        }

        val mvcResult = mockMvc.perform(
            post("/api/v1/meetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateMeetingRequest(name = "팀 미팅")))
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("M001"))  // ErrorCode 실제 값 사용
    }
}
```

### Service 단위 테스트

`@UnitTest` + `@Mock` + `@InjectMocks` 조합.
`TransactionTemplate`처럼 동작을 직접 제어해야 하는 의존성은 `@BeforeEach`에서 생성자 주입한다.

```kotlin
@UnitTest
@DisplayName("[SURVEY] 설문 생성 서비스 테스트")
class CreateSurveyServiceTest {

    @Mock private lateinit var surveyJpaRepository: SurveyJpaRepository
    @Mock private lateinit var meetingJpaRepository: MeetingJpaRepository

    private lateinit var createSurveyService: CreateSurveyService

    @BeforeEach
    fun setUp() {
        val transactionTemplate: TransactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        createSurveyService = CreateSurveyService(surveyJpaRepository, meetingJpaRepository, transactionTemplate)
    }

    @Test
    @DisplayName("설문을 성공적으로 생성한다")
    fun `설문을 성공적으로 생성한다`() = runTest {
        // given
        val attendee = MeetingAttendeeFixture.createEntity(id = 10L)
        whenever(meetingJpaRepository.findById(1L)).thenReturn(Optional.of(MeetingFixture.createEntity()))
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(any(), any())).thenReturn(false)

        // when
        createSurveyService.invoke(1L, 1L, SurveyRequestFixture.createRequest())

        // then
        verify(surveyJpaRepository).save(any())
    }

    @Test
    @DisplayName("이미 제출한 경우 예외가 발생한다")
    fun `중복 제출 시 예외가 발생한다`() = runTest {
        whenever(meetingJpaRepository.findById(1L)).thenReturn(Optional.of(MeetingFixture.createEntity()))
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(any(), any())).thenReturn(true)

        val exception = assertThrows<SurveyException> {
            createSurveyService.invoke(1L, 1L, SurveyRequestFixture.createMinimalRequest())
        }
        assertEquals(ErrorCode.SURVEY_ALREADY_SUBMITTED, exception.errorCode)
    }
}
```

### Repository 테스트

복잡한 Kotlin-JDSL 쿼리, 집계 쿼리만 작성한다. 단순 `save()` / `findById()`는 테스트 불필요.

```kotlin
@RepositoryTest
class MeetingJpaRepositoryTest {

    @Autowired private lateinit var meetingJpaRepository: MeetingJpaRepository
    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Test
    fun `호스트 ID로 미팅 목록을 조회한다`() {
        // given
        val user = userJpaRepository.save(UserFixture.createEntityWithoutId())
        val station = stationJpaRepository.save(StationFixture.createEntityWithoutId())
        meetingJpaRepository.save(MeetingFixture.createEntityWithoutId(hostUser = user, station = station))

        // when
        val result = meetingJpaRepository.findByHostUserId(user.id!!)

        // then
        assertThat(result).hasSize(1)
    }
}
```

### 통합 테스트

전체 플로우 검증이 필요한 경우에만 작성한다. 외부 서비스는 `@MockkBean` / `@MockitoBean`으로 대체한다.

```kotlin
@IntegrationTest
@Transactional
class SomeIntegrationTest {

    @Autowired private lateinit var someService: SomeService
    @Autowired private lateinit var someJpaRepository: SomeJpaRepository

    @MockkBean private lateinit var fcmClient: FcmClient  // 외부 HTTP 클라이언트 Mock

    @Test
    fun `전체 플로우 검증`() = runBlocking {
        // given — testFixtures로 DB에 직접 저장
        val user = userRepository.save(UserFixture.createEntityWithoutId())

        // when
        val result = someService.invoke(user.id!!)

        // then
        val saved = someJpaRepository.findById(result.id).orElseThrow()
        assertThat(saved.status).isEqualTo(ExpectedStatus)
    }
}
```

---

## Fixture 시스템

### 공용 Fixture 위치

`ssolv-api-common/src/testFixtures/kotlin/.../fixture/`에 중앙화한다.

```
fixture/
  ├── UserFixture.kt
  ├── MeetingFixture.kt
  ├── MeetingAttendeeFixture.kt
  ├── SurveyFixture.kt
  ├── SurveyCategoryFixture.kt
  └── StationFixture.kt
```

### 메서드 네이밍 규칙

| 메서드 | 반환 타입 | id | 용도 |
|---|---|---|---|
| `create()` | 도메인 모델 | 있음 | Repository 인터페이스 mock stub용 |
| `createEntity()` | JPA Entity | 있음 | DB 조회 결과 시뮬레이션 |
| `createEntityWithoutId()` | JPA Entity | null | `save()` 전 상태, 통합 테스트 DB 저장용 |

| 클래스 | `create()` | `createEntity()` | `createEntityWithoutId()` |
|---|---|---|---|
| `UserFixture` | `User` 도메인 | O | O (+ `createKakaoProfile()`, `createOAuthToken()`) |
| `MeetingFixture` | `Meeting` 도메인 | O | O |
| `MeetingAttendeeFixture` | — | O | O |
| `SurveyFixture` | — | O | O |
| `SurveyCategoryFixture` | — | O (level, parent 지정 가능) | O |
| `StationFixture` | — | O | O |

```kotlin
// 단위 테스트 — mock stub용
val meeting = MeetingFixture.create(id = 1L, isClosed = false)
whenever(meetingRepository.findById(1L)).thenReturn(meeting)

// 단위 테스트 — JPA mock stub용
val user = UserFixture.createEntity(id = 99L, nickname = "호스트")
whenever(userJpaRepository.findById(99L)).thenReturn(Optional.of(user))

// 통합 테스트 — DB 저장용
val user = userRepository.save(UserFixture.createEntityWithoutId())
val meeting = meetingJpaRepository.save(MeetingFixture.createEntityWithoutId(hostUser = user))
```

### 모듈 로컬 Fixture

DTO, Request/Response처럼 특정 모듈에서만 필요한 테스트 데이터는 `src/test/kotlin/.../fixture/`에 작성한다.

```kotlin
// ssolv-api-core/src/test/kotlin/.../survey/fixture/SurveyRequestFixture.kt
object SurveyRequestFixture {
    fun createRequest(selectedCategoryList: List<Long> = listOf(1L, 3L)): CreateSurveyRequest
    fun createMinimalRequest(): CreateSurveyRequest
    fun createEmptyRequest(): CreateSurveyRequest  // 경계값 테스트용
}
```

### 새 모듈에 testFixtures 추가

```kotlin
// build.gradle.kts
dependencies {
    testImplementation(testFixtures(project(":ssolv-api-common")))
}
```

---

## 코루틴 테스트

```kotlin
// 단위 테스트: runTest (가상 시간, 빠름)
@Test
fun `단위 테스트`() = runTest {
    service.stub { onBlocking { method(any()) }.doReturn(value) }
    val result = service.method(arg)
    assertThat(result).isNotNull()
}

// 통합 테스트: runBlocking (실제 시간)
@Test
fun `통합 테스트`() = runBlocking {
    val result = service.method(arg)
    assertThat(result).isNotNull()
}

// suspend 함수 예외 stub
service.stub {
    onBlocking { method(any()) }.doThrow(SomeException(ErrorCode.SOME_ERROR))
}

// 검증
verify(repository).save(any())
verifyNoInteractions(externalClient)
```

---

## 커버리지 제외 대상

Jacoco / SonarQube 모두 아래 패턴을 제외한다.

| 패턴 | 이유 |
|---|---|
| `**/Q*.*` | Kotlin-JDSL 생성 파일 |
| `**/*Application*`, `**/*Config*` | 설정 클래스 |
| `**/*Dto*`, `**/*Request*`, `**/*Response*` | 데이터 전달 객체 |
| `**/*Entity*` | JPA 매핑 클래스 |
| `**/*Exception*`, `**/*ErrorCode*` | 예외 정의 클래스 |

### 커버리지 목표

| 대상 | 목표 |
|---|---|
| Service 레이어 (복잡 로직) | 80% 이상 |
| Domain 레이어 | 90% 이상 |
| Controller 레이어 | 주요 흐름 커버 |

---

## 실행 명령어

```bash
# 전체 테스트
./gradlew test

# 커버리지 포함
./gradlew test jacocoTestReport --continue --stacktrace

# 모듈별
./gradlew :ssolv-api-core:test
./gradlew :ssolv-api-place:test

# 특정 클래스
./gradlew :ssolv-api-core:test --tests "org.depromeet.team3.survey.application.CreateSurveyServiceTest"

# 상세 로그
./gradlew :ssolv-api-core:test --info
```

> 통합 테스트(`@IntegrationTest`, `@RepositoryTest`)는 로컬에 Docker가 실행 중이어야 한다.

---

## 새 엔드포인트 체크리스트

**Controller (`@UnitTest`)**
- [ ] 성공 케이스 — 정상 입력 → `$.data` 구조 검증
- [ ] `@Valid` 위반 → 400 (asyncDispatch 없이)
- [ ] 비즈니스 예외 → HTTP status + `$.error.code` 검증
- [ ] `@UserId` 필요 시 — `testUserIdResolver.setTestUserId()` 설정

**Service (`@UnitTest`)**
- [ ] 성공 케이스 + `verify(repository).save(any())`
- [ ] 리소스 없음 → NOT_FOUND 예외
- [ ] 중복/충돌 예외
- [ ] 경계값 케이스 (한도 초과, 빈 입력 등)
- [ ] `assertThrows<XxxException>` + `assertEquals(ErrorCode.XXX, exception.errorCode)`
