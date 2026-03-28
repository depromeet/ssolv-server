# new-domain

새로운 도메인을 프로젝트 컨벤션에 맞게 생성합니다.

## 사용법

```
/new-domain <도메인명>
```

예: `/new-domain notification`

## 실행 절차

다음 단계를 순서대로 수행합니다. 각 단계마다 기존 도메인(meeting, auth 등)의 파일 구조를 참고합니다.

### 1. ssolv-api-core — 컨트롤러 + 서비스 레이어

`ssolv-api-core/src/main/kotlin/org/depromeet/team3/{domain}/` 아래에 생성:

```
{domain}/
├── controller/
│   └── {Domain}Controller.kt        # @RestController, @Tag, @Operation
├── application/
│   └── {feature}/
│       └── {Feature}Service.kt      # @Service, suspend operator fun invoke
├── command/
│   └── Create{Domain}Command.kt     # 서비스 입력 파라미터 묶음
├── dto/
│   ├── {Domain}Response.kt
│   └── Create{Domain}Request.kt     # @field:NotBlank 등 검증 어노테이션
└── exception/
    └── {Domain}Exception.kt         # DpmException 서브클래스
```

### 2. ssolv-global-utils — ErrorCode 추가

`ErrorCode.kt`에 도메인 전용 에러 코드 추가:
```kotlin
// {Domain} ({X}001-{X}099)
{DOMAIN}_NOT_FOUND("{X}001", "{도메인} 없음", 404),
```

### 3. ssolv-domain — 도메인 모델 + Repository 인터페이스

`ssolv-domain/src/main/kotlin/org/depromeet/team3/{domain}/` 아래에 생성:
```
{domain}/
├── {Domain}.kt                  # 순수 도메인 모델 (JPA 어노테이션 없음)
└── {Domain}Repository.kt        # 인터페이스만 정의
```

### 4. ssolv-infrastructure — JPA Entity + 어댑터 구현

`ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/{domain}/` 아래에 생성:
```
{domain}/
├── {Domain}Entity.kt            # @Entity, @Table, @Column
├── {Domain}JpaRepository.kt     # JpaRepository<{Domain}Entity, Long>
├── {Domain}Query.kt             # {Domain}Repository 구현체 (@Repository)
└── {Domain}Mapper.kt            # DomainMapper<{Domain}, {Domain}Entity> (@Component)
```

### 5. 검증

생성 완료 후:
```bash
./gradlew :ssolv-api-core:build -x test
```

빌드 성공 확인 후:
```bash
./gradlew :ssolv-api-core:test --tests "org.depromeet.team3.{domain}.*"
```
