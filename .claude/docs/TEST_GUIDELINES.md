# Testing Guidelines

> ssolv 서버의 테스트 작성 기준과 멀티모듈 구조에서의 테스트 전략을 정의합니다.

---

## 목차

1. [테스트 원칙](#1-테스트-원칙)
2. [모듈별 테스트 전략](#2-모듈별-테스트-전략)
3. [테스트 레이어별 패턴](#3-테스트-레이어별-패턴)
4. [MySQL Testcontainers 설정](#4-mysql-testcontainers-설정)
5. [메타 어노테이션](#5-메타-어노테이션)
6. [Fixture 작성 규칙](#6-fixture-작성-규칙)
7. [코루틴 테스트 패턴](#7-코루틴-테스트-패턴)
8. [커버리지 & 정적 분석](#8-커버리지--정적-분석)
9. [실행 명령어](#9-실행-명령어)

---

## 1. 테스트 원칙

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

### Spring Context 재사용 원칙

Spring Boot 테스트에서 Context 생성 비용은 매우 크다.
**동일한 Context 설정을 공유하도록 메타 어노테이션을 반드시 사용한다.**

```
❌ 각 테스트 클래스에 @SpringBootTest, @Import 직접 선언
✅ 사전 정의된 @IntegrationTest, @UnitTest 메타 어노테이션 사용
```

---

## 2. 모듈별 테스트 전략

| 모듈 | 허용 테스트 종류 | Spring Context | DB |
|------|----------------|---------------|-----|
| `ssolv-domain` | 순수 단위 테스트만 | 금지 | 불필요 |
| `ssolv-global-utils` | 순수 단위 테스트만 | 금지 | 불필요 |
| `ssolv-api-common` | 단위 + testFixtures 제공 | 금지 | 불필요 |
| `ssolv-api-core` | 단위 + 통합 테스트 | 허용 | MySQL Testcontainers |
| `ssolv-api-place` | 단위 + 통합 테스트 | 허용 | MySQL Testcontainers |
| `ssolv-infrastructure` | 단위 + 외부 API mocking | 제한적 허용 | 불필요 |
| `ssolv-batch` | 통합 테스트 | 허용 | MySQL Testcontainers |

### 모듈 간 테스트 픽스처 공유

`ssolv-api-common`의 `testFixtures` sourceSet을 통해 공용 테스트 설정을 제공한다.

```kotlin
// ssolv-api-core/build.gradle.kts
testImplementation(testFixtures(project(":ssolv-api-common")))
```

```
ssolv-api-common/src/testFixtures/kotlin/
  ├── config/
  │   ├── TestSecurityConfig.kt          # 인증 비활성화
  │   └── TestUserIdArgumentResolver.kt  # @UserId 주입
  ├── fixture/
  │   ├── UserFixture.kt                 # UserEntity 팩토리
  │   ├── MeetingFixture.kt              # MeetingEntity 팩토리
  │   └── SurveyFixture.kt              # SurveyEntity 팩토리
  └── util/
      └── TestAuthHelper.kt              # SecurityContext 관리
```

---

## 3. 테스트 레이어별 패턴

### Domain Layer — 순수 단위 테스트

Spring 컨텍스트 없이 도메인 객체를 직접 생성하여 테스트한다.

```kotlin
class MeetingTest {

    @Test
    fun `마감된 미팅에 참여 시도 시 예외 발생`() {
        // given
        val closedMeeting = Meeting(isClosed = true, ...)

        // when & then
        assertThrows<MeetingException> {
            closedMeeting.validateCanJoin()
        }
    }
}
```

### Service Layer — Mockito 단위 테스트

의존성을 모두 Mock으로 처리하고 비즈니스 로직만 검증한다.
단순 CRUD 위임 서비스는 테스트 생략 가능하다.

```kotlin
@UnitTest
class CreateSurveyServiceTest {

    @Mock
    private lateinit var surveyRepository: SurveyRepository

    @InjectMocks
    private lateinit var createSurveyService: CreateSurveyService

    @Test
    fun `설문 생성 성공`() = runTest {
        // given
        val command = CreateSurveyCommand(meetingId = 1L, userId = 1L)
        whenever(surveyRepository.save(any())).thenReturn(SurveyFixture.create())

        // when
        val result = createSurveyService(command)

        // then
        assertThat(result.id).isNotNull()
    }
}
```

### Controller Layer — MockMvc 단위 테스트

`MockMvcBuilders.standaloneSetup()`으로 Spring Context 없이 컨트롤러만 테스트한다.
HTTP 상태 코드, 응답 JSON 구조(`$.data`, `$.error.code`), 입력 유효성 검증에 집중한다.

```kotlin
@UnitTest
class MeetingControllerTest {

    @Mock private lateinit var createMeetingService: CreateMeetingService
    @InjectMocks private lateinit var meetingController: MeetingController

    private val testUserIdResolver = TestUserIdArgumentResolver()
    private val mockMvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(meetingController)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(testUserIdResolver)
            .build()
    }

    @Test
    fun `POST /meetings 201 반환`() = runTest {
        // given
        testUserIdResolver.setTestUserId(1L)
        createMeetingService.stub {
            onBlocking { invoke(any()) }.doReturn(MeetingFixture.createResponse())
        }

        // when
        val mvcResult = mockMvc.perform(
            post("/api/v1/meetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "팀 회식", "attendeeCount": 5}""")
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        // then
        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").isNumber)
    }
}
```

### Repository Layer — Testcontainers 통합 테스트

Kotlin-JDSL 커스텀 쿼리와 복잡한 조인 쿼리는 실제 MySQL로 검증한다.
단순 `save()` / `findById()` 위임은 테스트 불필요.

```kotlin
@RepositoryTest
class MeetingRepositoryTest {

    @Autowired private lateinit var meetingRepository: MeetingRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `userId로 참여 중인 미팅 목록 조회`() {
        // given
        val user = entityManager.persist(UserFixture.createWithoutId())
        val meeting = entityManager.persist(MeetingFixture.createWithoutId(host = user))
        entityManager.persist(MeetingAttendeeFixture.createWithoutId(meeting = meeting, user = user))
        entityManager.flush()
        entityManager.clear()

        // when
        val result = meetingRepository.findAllByUserId(user.id!!)

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(meeting.id)
    }
}
```

---

## 4. MySQL Testcontainers 설정

MySQL 8.0 컨테이너를 사용하여 운영 환경과 동일한 조건에서 쿼리를 검증한다.

### 의존성 추가

```kotlin
// ssolv-api-common/build.gradle.kts (testFixtures)
testFixturesImplementation("org.testcontainers:testcontainers:1.19.8")
testFixturesImplementation("org.testcontainers:mysql:1.19.8")
testFixturesImplementation("org.testcontainers:junit-jupiter:1.19.8")

// ssolv-api-core/build.gradle.kts, ssolv-api-place/build.gradle.kts
testImplementation(testFixtures(project(":ssolv-api-common")))
testRuntimeOnly("com.mysql:mysql-connector-j")
// H2 의존성 제거
```

### 공용 컨테이너 설정 (ssolv-api-common/testFixtures)

컨테이너를 싱글턴으로 관리해 모든 통합 테스트가 하나의 컨테이너를 공유하도록 한다.

```kotlin
// ssolv-api-common/src/testFixtures/.../config/MySqlTestContainer.kt
object MySqlTestContainer {
    val instance: MySQLContainer<*> by lazy {
        MySQLContainer("mysql:8.0")
            .withDatabaseName("ssolv_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true)
            .also { it.start() }
    }
}
```

```kotlin
// ssolv-api-common/src/testFixtures/.../config/TestContainerConfig.kt
@TestConfiguration
class TestContainerConfig : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        val container = MySqlTestContainer.instance
        TestPropertyValues.of(
            "spring.datasource.url=${container.jdbcUrl}",
            "spring.datasource.username=${container.username}",
            "spring.datasource.password=${container.password}",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
        ).applyTo(context.environment)
    }
}
```

### application-test.yml (통합 테스트용)

```yaml
# ssolv-api-core/src/test/resources/application-test.yml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: true
    show-sql: true

  data:
    redis:
      host: localhost
      port: 6379

api:
  google:
    places:
      api-key: test-api-key
      base-url: http://localhost

logging:
  level:
    org.depromeet.team3: DEBUG
    org.springframework.data.redis: INFO
```

> `spring.datasource.*`는 `TestContainerConfig`에서 동적으로 주입되므로 yml에 작성하지 않는다.

---

## 5. 메타 어노테이션

Spring Context 구성이 동일해야 Context를 재사용할 수 있다.
직접 `@SpringBootTest`, `@ExtendWith`를 선언하지 말고 아래 메타 어노테이션을 사용한다.

### @UnitTest — Mockito 단위 테스트

```kotlin
// ssolv-api-common/src/testFixtures/.../annotation/UnitTest.kt
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(MockitoExtension::class)
annotation class UnitTest
```

**사용 대상:** Service, Controller (standalone MockMvc), 도메인 객체 단위 검증

### @IntegrationTest — Spring 통합 테스트

```kotlin
// ssolv-api-common/src/testFixtures/.../annotation/IntegrationTest.kt
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = [TestContainerConfig::class])
@Import(TestSecurityConfig::class)
annotation class IntegrationTest
```

**사용 대상:** 전체 플로우 검증이 필요한 E2E 테스트 (현재 `NotificationIntegrationTest` 등)

### @RepositoryTest — JPA 레포지토리 테스트

```kotlin
// ssolv-api-common/src/testFixtures/.../annotation/RepositoryTest.kt
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

## 6. Fixture 작성 규칙

### 위치

모든 픽스처는 `ssolv-api-common/testFixtures` 아래에 중앙화한다.
모듈 내 분산된 `TestDataFactory` 파일들은 픽스처로 통합 이전한다.

```
ssolv-api-common/src/testFixtures/kotlin/.../fixture/
  ├── UserFixture.kt
  ├── MeetingFixture.kt
  ├── MeetingAttendeeFixture.kt
  ├── SurveyFixture.kt
  ├── SurveyCategoryFixture.kt
  └── StationFixture.kt
```

### 메서드 네이밍 규칙

| 메서드 | 용도 | id 포함 여부 |
|--------|------|-------------|
| `create(id = 1L, ...)` | 메모리 단위 테스트 | 포함 (기본값 제공) |
| `createWithoutId(...)` | DB 저장 통합 테스트 | 미포함 (DB auto-increment) |

```kotlin
object MeetingFixture {

    fun create(
        id: Long = 1L,
        name: String = "테스트 모임",
        attendeeCount: Int = 5,
        isClosed: Boolean = false,
        hostUser: UserEntity = UserFixture.create(id = 99L),
        station: StationEntity = StationFixture.create()
    ) = MeetingEntity(
        id = id,
        name = name,
        attendeeCount = attendeeCount,
        isClosed = isClosed,
        hostUser = hostUser,
        station = station
    )

    fun createWithoutId(
        name: String = "테스트 모임",
        attendeeCount: Int = 5,
        isClosed: Boolean = false,
        hostUser: UserEntity = UserFixture.createWithoutId(),
        station: StationEntity = StationFixture.createWithoutId()
    ) = MeetingEntity(
        id = null,
        name = name,
        attendeeCount = attendeeCount,
        isClosed = isClosed,
        hostUser = hostUser,
        station = station
    )
}
```

### 기존 TestDataFactory 정리 방향

아래 파일들은 픽스처로 통합 후 제거한다.

- `ssolv-api-core/.../common/util/TestEntityFactory.kt`
- `ssolv-api-core/.../auth/util/TestDataFactory.kt`
- `ssolv-api-core/.../meeting/util/MeetingTestDataFactory.kt`
- `ssolv-api-core/.../survey/util/SurveyTestDataFactory.kt`
- 기타 각 도메인별 `*TestDataFactory.kt`

---

## 7. 코루틴 테스트 패턴

ssolv의 서비스는 `suspend fun`을 사용하므로 테스트에서 반드시 올바른 패턴을 적용한다.

### Service 단위 테스트 — runTest

```kotlin
@Test
fun `미팅 참여 성공`() = runTest {
    // given
    val command = JoinMeetingCommand(userId = 1L, inviteToken = "valid-token")
    whenever(meetingRepository.findByInviteToken("valid-token"))
        .thenReturn(MeetingFixture.create())

    // when
    val result = joinMeetingService(command)

    // then
    assertThat(result.meetingId).isEqualTo(1L)
}
```

### Controller 단위 테스트 — asyncDispatch

Spring MVC는 코루틴을 비동기로 처리하므로 두 단계로 검증한다.

```kotlin
@Test
fun `POST /meetings/join 200 반환`() = runTest {
    // given
    ...

    // when — 비동기 시작 확인
    val mvcResult = mockMvc.perform(post("/api/v1/meetings/join")...)
        .andExpect(request().asyncStarted())
        .andReturn()

    // then — 비동기 결과 검증
    mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.data").exists())
}
```

### 주의사항

```kotlin
// ❌ runTest 없이 suspend mock stub 사용 — 테스트가 실제로 실행되지 않음
@Test
fun `잘못된 패턴`() {
    service.stub { onBlocking { invoke(any()) }.doReturn(...) }
    // suspend 호출 없이 검증
}

// ✅ runTest 내에서 호출
@Test
fun `올바른 패턴`() = runTest {
    service.stub { onBlocking { invoke(any()) }.doReturn(...) }
    val result = service(command)
    assertThat(result).isNotNull()
}
```

---

## 8. 커버리지 & 정적 분석

### Jacoco 커버리지 제외 대상

구조체 클래스는 비즈니스 로직이 없으므로 커버리지 측정에서 제외한다.

| 패턴 | 이유 |
|------|------|
| `**/*Config*` | Spring 설정 클래스 |
| `**/*Dto*`, `**/*Request*`, `**/*Response*` | 데이터 전달 객체 |
| `**/*Entity*` | JPA 매핑 클래스 |
| `**/*Exception*`, `**/*ErrorCode*` | 예외 정의 클래스 |
| `**/*Application*` | 진입점 클래스 |
| `**/Q*.*` | Kotlin-JDSL / QueryDSL 생성 파일 |

### SonarQube 분석 제외

위 목록과 동일한 패턴을 `sonar.exclusions`에도 적용한다 (루트 `build.gradle.kts` 관리).

### 커버리지 목표

| 대상 | 목표 |
|------|------|
| Service 레이어 (복잡 로직) | 80% 이상 |
| Domain 레이어 | 90% 이상 |
| Controller 레이어 | 주요 흐름 커버 |

---

## 9. 실행 명령어

```bash
# 전체 테스트 실행
./gradlew test

# 커버리지 포함 전체 실행
./gradlew test jacocoTestReport --continue --stacktrace

# 모듈별 테스트
./gradlew :ssolv-api-core:test
./gradlew :ssolv-api-place:test
./gradlew :ssolv-domain:test

# 특정 클래스만 실행
./gradlew :ssolv-api-core:test --tests "org.depromeet.team3.meeting.application.JoinMeetingServiceTest"
./gradlew :ssolv-api-core:test --tests "org.depromeet.team3.auth.controller.AuthControllerTest"

# 테스트 실패 시 상세 로그 출력
./gradlew :ssolv-api-core:test --info
```

> Testcontainers를 사용하는 통합 테스트는 로컬에 Docker가 실행 중이어야 한다.
> CI 환경(GitHub Actions)에서는 `docker` 서비스가 자동으로 제공된다.