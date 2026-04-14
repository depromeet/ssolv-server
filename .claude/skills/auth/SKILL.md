# Auth Patterns

## 핵심 원칙

- `@AuthenticationPrincipal`을 **절대 직접 사용하지 않는다**. 항상 `@UserId` 커스텀 어노테이션을 사용한다.
- Auth 관련 예외는 반드시 `AuthException(ErrorCode.XXX)` 형태로 던진다.
- OAuth 외부 API 호출은 `ssolv-infrastructure` 레이어의 Ktor Client에서 수행한다.

---

## @UserId 어노테이션

`ssolv-api-common: org.depromeet.team3.common.annotation.UserId`

```kotlin
// 인증 필수 엔드포인트
suspend fun getProfile(@UserId userId: Long): DpmApiResponse<ProfileResponse>

// 인증 선택적 엔드포인트 (비회원도 접근 가능)
suspend fun getPublicData(@UserId userId: Long?): DpmApiResponse<PublicResponse>

// 미팅 ID 파라미터 (초대 토큰 또는 직접 ID 모두 수용)
suspend fun getMeeting(@MeetingId meetingId: Long): DpmApiResponse<MeetingResponse>
```

**내부 동작**: `JwtAuthenticationToken`에서 `principal`(userId: Long?)을 추출.
`SecurityContextHolder` → `JwtAuthenticationToken.getUserId()` 경로.

---

## JWT 토큰 구조

```text
accessToken  — Authorization: Bearer {token}
refreshToken — 요청 바디 (RefreshTokenRequest.refreshToken)
```

토큰 갱신 엔드포인트: `POST /api/v1/auth/reissue-token`

---

## 소셜 로그인 흐름

### Kakao

```
프론트엔드 → 카카오 인가코드 획득
→ GET /api/v1/auth/kakao-login?code={code}&redirect_uri={uri}
→ KakaoLoginService.login(KakaoLoginCommand)
→ KakaoOAuthClient (Ktor) → 카카오 토큰 교환 → 프로필 조회
→ 사용자 찾기/생성 → JWT 발급 → LoginResponse 반환
```

### Apple

```
프론트엔드 → 애플 인가코드 + user(JSON) 획득
→ POST /api/v1/auth/apple-login?code={code}&user={userJson}
→ AppleOAuthService.login(AppleLoginCommand)
→ AppleOAuthClient (Ktor) → JWT 검증 (JWKS) → 사용자 정보 파싱
→ 사용자 찾기/생성 → JWT 발급 → LoginResponse 반환
```

**주의**: `user` 파라미터는 **최초 로그인 시에만** 애플이 전달한다. 재로그인 시 null.

---

## ErrorCode 범위 (OAuth 도메인)

| 코드 | 의미 |
|---|---|
| O001 | 카카오 인가코드 교환 실패 |
| O002 | 카카오 사용자 정보 조회 실패 |
| O007 | 다른 소셜 수단으로 이미 가입된 이메일 (409 Conflict) |
| O011 | 애플 JWT 서명 검증 실패 |
| O012 | 애플 JWKS 조회 실패 |
| O018 | 애플 인가코드 만료 |
| O019 | 애플 sub 불일치 |

---

## 로그아웃 / 탈퇴 패턴

```kotlin
// 로그아웃: refreshToken 무효화
suspend fun logout(@UserId userId: Long): DpmApiResponse<LogoutResponse>
// LogoutResponse.kakaoLogoutUrl 이 있으면 프론트에서 리다이렉트 필요

// 탈퇴: 소셜 연동 해제 + 사용자 삭제
suspend fun withdraw(@UserId userId: Long): DpmApiResponse<Unit>
// 외부 소셜 unlink 호출 → 트랜잭션 내 사용자/관련 데이터 삭제
```

---

## 테스트에서 인증 처리

컨트롤러 단위 테스트에서 `@UserId`를 주입하려면 `TestUserIdArgumentResolver`를 등록한다.

```kotlin
private val testUserIdResolver = TestUserIdArgumentResolver()

private val mockMvc: MockMvc by lazy {
    MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(testUserIdResolver)
        .build()
}

@Test
fun `인증된 사용자 요청`() = runTest {
    testUserIdResolver.setTestUserId(1L)   // userId 주입
    // ...
}

@Test
fun `인증 없는 요청`() = runTest {
    testUserIdResolver.setTestUserId(null) // 비회원
    // ...
}
```

통합 테스트에서는 `TestSecurityConfig`가 `@IntegrationTest`에 포함되어 있어 별도 설정 불필요.

---

## 데모 로그인 (앱스토어 심사용)

```kotlin
POST /api/v1/auth/demo-login
// 소셜 로그인 없이 고정 데모 계정으로 JWT 발급
// 프로덕션에서도 활성화되어 있음 — 심사 통과 후 비활성화 여부 검토 필요
```

---

## Swagger 문서화 체크리스트

인증 관련 신규 엔드포인트 추가 시:
- [ ] `@ApiResponse(responseCode = "401", description = "인증 실패")`  명시
- [ ] 소셜 오류 코드(O0XX) 해당하는 `@ApiResponse` 추가
- [ ] `@Parameter(hidden = true)`는 `@UserId`에 이미 포함됨 — 중복 선언 불필요
