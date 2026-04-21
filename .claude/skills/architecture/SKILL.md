---
name: architecture
description: Use when making multi-module design decisions, adding a new domain, or deciding where code should live across ssolv-api-common / ssolv-api-core / ssolv-api-place / ssolv-batch / ssolv-domain / ssolv-infrastructure / ssolv-global-utils. Defines the module dependency graph and forbidden import directions (ssolv-api-core ↔ ssolv-api-place; ssolv-domain must not import from infrastructure or api-* modules). Trigger when crossing module boundaries or introducing a new top-level package.
---

# Architecture & Design Patterns

## 모듈 의존성 규칙

```
ssolv-api-core      (bootable JAR)  ─┐
ssolv-api-place     (bootable JAR)  ─┤→ ssolv-api-common
                                      ↓
                              ssolv-domain
                              ssolv-infrastructure
                              ssolv-global-utils
                              ssolv-batch
```

**절대 금지:**
- `ssolv-api-core` ↔ `ssolv-api-place` 상호 참조
- `ssolv-domain`이 `ssolv-infrastructure`에 의존
- `ssolv-global-utils`가 다른 모듈에 의존

## 레이어 구조 (도메인 내부)

```
{domain}/
├── controller/          # HTTP 진입점, 변환만 담당
├── application/         # 비즈니스 로직, @Service 클래스
│   └── {feature}/
│       └── {Feature}Service.kt
├── command/             # 서비스 입력 파라미터 묶음
├── dto/                 # 요청/응답 DTO
├── client/              # 외부 API 클라이언트 (Ktor)
├── properties/          # @ConfigurationProperties 클래스
└── exception/           # 도메인 예외 (DpmException 서브클래스)
```

JPA Entity, Repository 인터페이스는 `ssolv-domain`에 위치.
실제 JPA 구현체(Query 클래스, Mapper)는 `ssolv-infrastructure`에 위치.

## 도메인-인프라 분리 패턴

**Domain 레이어** — 순수 비즈니스 객체, 인프라 불가지론적

```kotlin
// ssolv-domain: 인터페이스만 정의
interface MeetingRepository {
    suspend fun save(meeting: Meeting): Meeting
    suspend fun findById(id: Long): Meeting?
    suspend fun findMeetingsByUserId(userId: Long): List<Meeting>
}
```

**Infrastructure 레이어** — JPA Entity + 어댑터 구현

```kotlin
// ssolv-infrastructure: JPA Entity (DB 매핑)
@Entity @Table(name = "meeting")
class MeetingEntity(
    @Id @GeneratedValue val id: Long? = null,
    val name: String,
    @ManyToOne val hostUser: UserEntity,
    // ...
)

// ssolv-infrastructure: Repository 구현체 (어댑터)
@Repository
class MeetingQuery(
    private val meetingMapper: MeetingMapper,
    private val meetingJpaRepository: MeetingJpaRepository,
) : MeetingRepository {
    override suspend fun findById(id: Long): Meeting? =
        meetingJpaRepository.findByIdOrNull(id)?.let { meetingMapper.toDomain(it) }
}

// ssolv-infrastructure: Entity ↔ Domain 변환
@Component
class MeetingMapper : DomainMapper<Meeting, MeetingEntity> {
    override fun toDomain(entity: MeetingEntity): Meeting = Meeting(
        id = entity.id!!,
        name = entity.name,
        hostUserId = entity.hostUser.id!!,
    )
    override fun toEntity(domain: Meeting): MeetingEntity = ...
}
```

## 서비스 패턴

단일 책임 서비스 + `operator fun invoke` 패턴을 사용한다.

```kotlin
@Service
class GetMeetingService(
    private val meetingRepository: MeetingRepository,
) {
    // invoke 연산자 오버로딩으로 자연스러운 호출 가능
    suspend operator fun invoke(userId: Long): List<MeetingResponse> =
        withContext(Dispatchers.IO) {
            meetingRepository.findMeetingsByUserId(userId)
                .map { it.toResponse() }
        }
}

// 컨트롤러에서 호출
val result = getMeetingService(userId)   // getMeetingService.invoke(userId) 아님
```

블로킹 IO는 항상 `withContext(Dispatchers.IO)`로 감싼다.

## 새 도메인 추가 체크리스트

1. `ssolv-api-core/src/main/kotlin/org/depromeet/team3/{domain}/` 디렉토리 생성
2. 하위 패키지: `controller/`, `application/`, `command/`, `dto/`, `exception/`
3. `ssolv-domain`에 Domain 모델 + Repository 인터페이스 추가
4. `ssolv-infrastructure`에 Entity + JpaRepository + Query(어댑터) + Mapper 추가
5. Infrastructure Bean을 Spring Context에 등록 (`@Repository`, `@Component`)
6. 도메인 예외 클래스 생성 (`DpmException` 서브클래스)
7. 필요한 `ErrorCode` 항목 추가

## 삭제 순서

연관관계가 있는 엔티티 삭제 시, **외래키 제약조건 위반을 막기 위해 자식 → 부모 순서**로 삭제.
트랜잭션 내에서 원자적으로 처리하고, 외부 서비스 호출(FCM, Redis)은 트랜잭션 커밋 이후 실행.

---

## ❌ / ✅ 과거 교훈 (Data Integrity — 2026-04-13)

> 출처: `.claude/docs/DATA_INTEGRITY_FIXES.md`

### Cascade 설정 — 자식 엔티티 누락 금지

```kotlin
// ❌ 부모에만 cascade, 자식에는 없음 → 고아 레코드 발생
@OneToMany(mappedBy = "user")
val meetings: MutableList<MeetingEntity>   // cascade 없음

@OneToMany(mappedBy = "meeting")           // cascade 없음 — AttendeeEntity 고아 레코드!
val attendees: MutableList<MeetingAttendeeEntity>

// ✅ 연쇄 삭제가 필요한 모든 관계에 cascade + orphanRemoval 명시
@OneToMany(mappedBy = "meeting", cascade = [CascadeType.ALL], orphanRemoval = true)
val attendees: MutableList<MeetingAttendeeEntity>
```

### BaseTimeEntity — 타임스탬프는 protected set

```kotlin
// ❌ public var → 외부에서 직접 수정 가능, 감사 추적 불가
var createdAt: LocalDateTime = LocalDateTime.now()

// ✅ protected set → getter는 public, setter는 JPA Auditing만 사용
var createdAt: LocalDateTime = LocalDateTime.now()
    protected set
```

### Mapper.toEntity() — JPA Auditing 관리 필드는 수동 할당 금지

```kotlin
// ❌ domain.createdAt 이 null 이면 DB not-null 제약 위반
override fun toEntity(domain: User): UserEntity = UserEntity(
    createdAt = domain.createdAt,  // null 위험!
)

// ✅ createdAt/updatedAt 은 생성자에서 제외 — JPA Auditing이 자동 관리
override fun toEntity(domain: User): UserEntity = UserEntity(
    id = domain.id,
    // createdAt, updatedAt 제외
)
```

### 탈퇴 전 비즈니스 검증 — 연관 데이터 무결성

사용자 삭제처럼 cascade 범위가 넓은 작업은 삭제 전에 반드시 비즈니스 규칙 검증:

```kotlin
// WithdrawService 패턴: 다른 참석자가 있는 모임 호스트는 탈퇴 불가
private fun validateHostedMeetings(userId: Long) {
    val hosted = meetingRepository.findMeetingsByUserId(userId)
    hosted.forEach { meeting ->
        val hasOtherAttendees = attendeeRepository
            .findByMeetingId(meeting.id!!)
            .any { it.userId != userId }
        if (hasOtherAttendees) throw AuthException(ErrorCode.CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS)
    }
}
```
