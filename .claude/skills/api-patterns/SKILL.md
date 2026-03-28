# API Patterns

## Response Wrapper

모든 엔드포인트는 `DpmApiResponse<T>`로 응답을 감싸야 한다.

```kotlin
// ssolv-global-utils: org.depromeet.team3.common.response.DpmApiResponse
data class DpmApiResponse<T>(
    val data: T? = null,
    val error: ErrorResponse? = null,
)

// 사용 방법
fun someEndpoint(): DpmApiResponse<SomeResponse> {
    val result = someService(params)
    return DpmApiResponse.ok(result)      // 데이터 있을 때
}

fun deleteEndpoint(): DpmApiResponse<Unit> {
    someService.delete(id)
    return DpmApiResponse.ok()            // 데이터 없을 때
}
```

## 컨트롤러 구조

```kotlin
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/meetings")
@Tag(name = "Meeting", description = "미팅 관련 API")
class MeetingController(
    private val createMeetingService: CreateMeetingService,
    private val getMeetingService: GetMeetingService,
) {
    @PostMapping
    @Operation(summary = "미팅 생성", description = "새로운 미팅을 생성합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "404", description = "사용자 없음 - USER_NOT_FOUND"),
    )
    suspend fun createMeeting(
        @UserId userId: Long,                          // JWT에서 userId 추출
        @Valid @RequestBody request: CreateMeetingRequest,
    ): DpmApiResponse<MeetingResponse> {
        return DpmApiResponse.ok(createMeetingService(userId, request))
    }
}
```

## 사용자 인증 추출

`@AuthenticationPrincipal`을 직접 사용하지 말 것. 항상 커스텀 `@UserId` 어노테이션을 사용한다.

```kotlin
// 인증 필요한 엔드포인트
suspend fun someEndpoint(@UserId userId: Long): DpmApiResponse<SomeResponse>

// 인증 선택적인 엔드포인트
suspend fun someEndpoint(@UserId userId: Long?): DpmApiResponse<SomeResponse>

// 미팅 ID 해석 (초대 토큰 또는 직접 ID)
suspend fun someEndpoint(@MeetingId meetingId: Long): DpmApiResponse<SomeResponse>
```

## 예외 처리

`RuntimeException`이나 일반 예외를 던지지 말 것. 반드시 도메인별 `DpmException` 서브클래스를 사용한다.

```kotlin
// ErrorCode: ssolv-global-utils: org.depromeet.team3.common.exception.ErrorCode
// DpmException: ssolv-global-utils: org.depromeet.team3.common.exception.DpmException

// 도메인 예외 정의
class MeetingException(
    errorCode: ErrorCode,
    detail: Map<String, Any?>? = null,
) : DpmException(errorCode, detail)

// 던지는 방법
throw MeetingException(ErrorCode.MEETING_NOT_FOUND)
throw MeetingException(ErrorCode.INVALID_REQUEST, mapOf("field" to "name", "value" to name))
```

## 요청 유효성 검사

```kotlin
// 요청 DTO에 Jakarta Validation 어노테이션 사용
data class CreateMeetingRequest(
    @field:NotBlank(message = "미팅 이름은 필수입니다")
    val name: String,

    @field:Size(max = 50)
    val description: String?,
)

// 컨트롤러에서 @Valid로 트리거
suspend fun create(@Valid @RequestBody request: CreateMeetingRequest)
```

## Command 객체

컨트롤러 → 서비스 간 파라미터가 많을 경우 Command 객체로 묶는다.

```kotlin
// command 패키지에 위치
data class CreateMeetingCommand(
    val userId: Long,
    val name: String,
    val locationId: Long,
)

// 컨트롤러에서 변환
val command = CreateMeetingCommand(userId, request.name, request.locationId)
createMeetingService(command)
```

## Swagger 문서화 체크리스트

새 엔드포인트 추가 시:
- [ ] 컨트롤러 클래스에 `@Tag` 존재
- [ ] 메서드에 `@Operation(summary, description)` 추가
- [ ] 에러 케이스에 `@ApiResponse` 명시 (404/409 등)
- [ ] 경로/쿼리 파라미터에 `@Parameter(description)` 추가
- [ ] 요청 DTO에 `@Valid` 추가
