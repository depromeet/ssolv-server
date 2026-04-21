---
name: domain-model
description: Use when creating or modifying domain data classes (in ssolv-domain) vs JPA entities (in ssolv-infrastructure) and their Mappers. Covers Kotlin-JDSL 3.8.0 complex queries, `ErrorCode` enum patterns, `@ConfigurationProperties` groups (never `@Value` direct injection), and keeping domain logic free of Spring/JPA imports. Trigger on any `*Entity.kt`, `*Mapper.kt`, `*Query.kt`, domain data class, or new `@ConfigurationProperties` class.
---

# Domain Model Patterns

> 이 프로젝트는 **도메인 모델과 JPA 엔티티를 완전히 분리**한다.
> `ssolv-domain`의 `data class`가 비즈니스 진실의 근거(source of truth)이며,
> `ssolv-infrastructure`의 `Entity`는 DB 매핑 전용이다.

---

## 계층 구조

```
ssolv-domain           ← 비즈니스 규칙, 인터페이스 정의, 예외
ssolv-infrastructure   ← JPA Entity, Repository 구현체, Mapper
ssolv-api-common       ← 공통 어노테이션, 필터, 보안 설정
ssolv-global-utils     ← ErrorCode, DpmException, DpmApiResponse
```

---

## Domain vs Entity

### 도메인 (ssolv-domain)

```kotlin
// data class — 불변, 비즈니스 메서드 포함 가능
data class Meeting(
    val id: Long? = null,
    val name: String,
    val hostUserId: Long,
    val isClosed: Boolean = false,
    val endAt: LocalDateTime? = null,
    override val createdAt: LocalDateTime? = null,
    override val updatedAt: LocalDateTime? = null,
) : BaseTimeDomain(createdAt, updatedAt) {

    // 비즈니스 규칙은 도메인 메서드로
    fun close(): Meeting = copy(isClosed = true, endAt = LocalDateTime.now())
}
```

### JPA Entity (ssolv-infrastructure)

```kotlin
// class — 가변, JPA 어노테이션 전용, 비즈니스 로직 금지
@Entity
@Table(name = "tb_meetings")
class MeetingEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var name: String = "",

    // 연관관계: cascade + orphanRemoval 필수
    @OneToMany(mappedBy = "meeting", cascade = [CascadeType.ALL], orphanRemoval = true)
    val attendees: MutableList<MeetingAttendeeEntity> = mutableListOf(),
) : BaseTimeEntity()
```

**핵심 규칙:**
- Entity에 비즈니스 메서드 작성 금지
- Domain에 `@Entity`, `@Column` 등 JPA 어노테이션 금지
- `@OneToMany`는 반드시 `cascade = [CascadeType.ALL], orphanRemoval = true`

---

## BaseTimeEntity vs BaseTimeDomain

| | `BaseTimeEntity` (infrastructure) | `BaseTimeDomain` (domain) |
|---|---|---|
| 위치 | `ssolv-infrastructure` | `ssolv-domain` |
| 목적 | JPA Auditing 연동 | 도메인 객체 시간 필드 |
| `createdAt` visibility | `protected set` — **외부에서 직접 설정 불가** | `val` (read-only) |
| 관리 주체 | JPA `@EntityListeners(AuditingEntityListener)` | Mapper에서 entity → domain 변환 시 전달 |

```kotlin
// ❌ 절대 금지 — Mapper에서 createdAt 수동 설정
override fun toEntity(domain: User): UserEntity {
    return UserEntity(
        createdAt = domain.createdAt  // ← 금지! JPA Auditing이 관리
    )
}

// ✅ 올바른 방식 — createdAt/updatedAt 생략
override fun toEntity(domain: User): UserEntity {
    return UserEntity(
        id = domain.id,
        email = domain.email,
        // createdAt, updatedAt 필드는 작성하지 않음
    )
}
```

---

## DomainMapper 패턴

모든 Mapper는 `DomainMapper<D, E>` 인터페이스를 구현한다.

```kotlin
// ssolv-infrastructure: org.depromeet.team3.mapper
interface DomainMapper<D, E> {
    fun toDomain(entity: E): D
    fun toEntity(domain: D): E
}

@Component
class UserMapper : DomainMapper<User, UserEntity> {

    override fun toDomain(entity: UserEntity): User {
        return User(
            id = entity.id,
            email = entity.email,
            createdAt = entity.createdAt,   // entity → domain은 그대로 전달
            updatedAt = entity.updatedAt,
        )
    }

    override fun toEntity(domain: User): UserEntity {
        return UserEntity(
            id = domain.id,
            email = domain.email,
            // createdAt, updatedAt 절대 포함하지 않음
        )
    }
}
```

---

## CommandRepository / QueryRepository 분리

도메인에서 쓰기/읽기 Repository를 분리하여 정의한다.

```kotlin
// ssolv-domain에 인터페이스 정의
interface UserCommandRepository {
    suspend fun save(user: User): User
    suspend fun delete(id: Long)
}

interface UserQueryRepository {
    suspend fun findById(id: Long): User?
    suspend fun findByEmail(email: String): User?
}

// ssolv-infrastructure에 구현체
@Repository
class UserCommandRepositoryImpl(
    private val userJpaRepository: UserJpaRepository,
    private val userMapper: UserMapper,
) : UserCommandRepository {
    override suspend fun save(user: User): User {
        return userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user)))
    }
}

@Repository
class UserQueryRepositoryImpl(
    private val userJpaRepository: UserJpaRepository,
    private val userMapper: UserMapper,
) : UserQueryRepository {
    override suspend fun findById(id: Long): User? {
        return userJpaRepository.findById(id).map { userMapper.toDomain(it) }.orElse(null)
    }
}
```

---

## Kotlin-JDSL 복잡 쿼리

JpaRepository만으로 표현하기 어려운 동적 조건 쿼리는 `KotlinJdslJpqlExecutor`를 사용한다.

```kotlin
@Repository
class SurveyCategoryQuery(
    private val mapper: SurveyCategoryMapper,
    private val jpaRepository: SurveyCategoryJpaRepository, // JpaRepository + KotlinJdslJpqlExecutor
) : SurveyCategoryRepository {

    override suspend fun existsByNameAndParentId(name: String, parentId: Long?): Boolean {
        val results = jpaRepository.findAll {
            select(entity(SurveyCategoryEntity::class))
                .from(entity(SurveyCategoryEntity::class))
                .where(
                    and(
                        path(SurveyCategoryEntity::name).eq(name),
                        path(SurveyCategoryEntity::isDeleted).eq(false),
                        if (parentId == null) {
                            path(SurveyCategoryEntity::parent).isNull()
                        } else {
                            path(SurveyCategoryEntity::parent).path(SurveyCategoryEntity::id).eq(parentId)
                        }
                    )
                )
        }
        return results.filterNotNull().isNotEmpty()
    }
}
```

JDSL 사용 시 JpaRepository에 `KotlinJdslJpqlExecutor` 상속 추가 필요:
```kotlin
interface SurveyCategoryJpaRepository :
    JpaRepository<SurveyCategoryEntity, Long>,
    KotlinJdslJpqlExecutor
```

---

## ErrorCode 도메인별 범위

새 ErrorCode 추가 시 아래 범위를 지켜야 한다.

| 접두어 | 도메인 | 예시 |
|---|---|---|
| `C001~C099` | 공통 클라이언트 오류 | `INVALID_REQUEST` |
| `C4xx` | 공통 HTTP 상태 기반 오류 | `C404`, `C409` |
| `S001~S099` | 서버 내부 오류 | `INTERNAL_SERVER_ERROR` |
| `O001~O099` | OAuth (Kakao/Apple) | `KAKAO_AUTH_FAILED` |
| `J001~J099` | JWT 토큰 | `REFRESH_TOKEN_MISMATCH` |
| `T001~T099` | 초대 토큰 | `INVALID_INVITE_TOKEN` |
| `N001~N099` | 알림 | `NOTIFICATION_NOT_FOUND` |
| `P001~P099` | Place (Google Places) | `PLACE_API_ERROR` |

```kotlin
// 도메인 예외 정의 패턴 (ssolv-domain 또는 ssolv-global-utils)
class MeetingException(
    errorCode: ErrorCode,
    detail: Map<String, Any?>? = null,
) : DpmException(errorCode, detail)

// 던지는 방법
throw MeetingException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to id))
```

---

## @ConfigurationProperties 규칙

`@Value`로 프로퍼티를 직접 주입하지 않는다. 반드시 `@ConfigurationProperties` 클래스를 만든다.

```kotlin
// ❌ 금지
@Value("\${jwt.secret}") private lateinit var secret: String

// ✅ 올바른 방법
@Component
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    var secret: String = "",
    var accessTokenValidity: Long = 3600000,
)
```
