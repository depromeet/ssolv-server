# Testing Guide

## 테스트 전략

- **단위 테스트 (Controller)**: MockMvc + Mockito. 컨트롤러 레이어만 독립적으로 검증.
- **통합 테스트 (Service/Use case)**: `@SpringBootTest` + H2 또는 실제 DB. 실제 Spring Context 로딩.
- 복잡한 비즈니스 로직에는 단위 테스트 추가.

## Controller 단위 테스트 패턴

```kotlin
@ExtendWith(MockitoExtension::class)
class MeetingControllerTest {

    @Mock private lateinit var createMeetingService: CreateMeetingService
    @Mock private lateinit var getMeetingService: GetMeetingService
    @InjectMocks private lateinit var meetingController: MeetingController

    private val testUserIdResolver = TestUserIdArgumentResolver(userId = 1L)

    private val mockMvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(meetingController)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(testUserIdResolver)
            .build()
    }

    @Test
    @DisplayName("미팅 생성 성공")
    fun `createMeeting - success`() = runTest {
        // given
        val request = CreateMeetingRequest(name = "팀 미팅", locationId = 1L)
        val response = MeetingResponse(id = 1L, name = "팀 미팅")
        onBlocking { createMeetingService(any(), any()) } doReturn response

        // when & then
        mockMvc.post("/api/v1/meetings") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(1L) }
        }
    }

    @Test
    @DisplayName("미팅 없음 404 반환")
    fun `getMeeting - not found`() = runTest {
        onBlocking { getMeetingService(any()) } doThrow MeetingException(ErrorCode.MEETING_NOT_FOUND)

        mockMvc.get("/api/v1/meetings/999")
            .andExpect { status { isNotFound() } }
    }
}
```

## 통합 테스트 패턴

```kotlin
@SpringBootTest(classes = [CoreApiApplication::class])
@ActiveProfiles("test")
@Transactional
class MeetingServiceIntegrationTest {

    @Autowired private lateinit var createMeetingService: CreateMeetingService
    @Autowired private lateinit var meetingJpaRepository: MeetingJpaRepository

    // 외부 서비스는 MockK Bean으로 대체
    @MockkBean private lateinit var fcmClient: FcmClient

    @Test
    fun `미팅 생성 후 DB에 저장됨`() = runBlocking {
        // given
        val command = CreateMeetingCommand(userId = 1L, name = "테스트 미팅", locationId = 1L)

        // when
        val result = createMeetingService(command)

        // then
        val saved = meetingJpaRepository.findById(result.id).orElseThrow()
        assertThat(saved.name).isEqualTo("테스트 미팅")
    }
}
```

## 코루틴 테스트

- 단위 테스트: `runTest { }` (kotlinx-coroutines-test)
- 통합 테스트: `runBlocking { }`
- `suspend` 함수 모킹: `onBlocking { ... } doReturn value` (Mockito-Kotlin)
- MockK로 suspend 함수 모킹: `coEvery { service.method() } returns value`

```kotlin
// Mockito-Kotlin: suspend 함수 stub
onBlocking { getMeetingService(userId) } doReturn listOf(meetingResponse)

// MockK: suspend 함수 stub
coEvery { getMeetingService(userId) } returns listOf(meetingResponse)
coVerify { getMeetingService(userId) }
```

## 테스트 유틸리티

- `TestEntityFactory` — 도메인 모델 테스트 객체 생성 (`ssolv-api-common/test`)
- `TestDataFactory` — DTO/Command 테스트 객체 생성 (`ssolv-api-common/test`)
- `TestUserIdArgumentResolver` — `@UserId` 어노테이션 목킹

## 테스트 커버리지 제외 대상

Jacoco가 자동 제외하는 항목 (직접 테스트 불필요):
- `**/Q*.*` — QueryDSL 생성 파일
- `**/*Application*`, `**/*Config*` — 설정 클래스
- `**/*Dto*`, `**/*Request*`, `**/*Response*` — DTO
- `**/*Entity*` — JPA Entity
- `**/*Exception*` — 예외 클래스

## 새 엔드포인트 테스트 체크리스트

- [ ] 성공 케이스 (정상 입력 → 기대 응답)
- [ ] 인증 실패 케이스 (토큰 없음 → 401)
- [ ] 유효성 검사 실패 케이스 (`@Valid` 위반 → 400)
- [ ] 비즈니스 예외 케이스 (리소스 없음 → 404 등)
- [ ] 응답 JSON 구조 검증 (`$.data`, `$.error`)
