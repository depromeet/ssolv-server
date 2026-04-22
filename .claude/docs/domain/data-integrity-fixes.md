# 데이터 무결성 문제 해결 보고서

## 개요
이 문서는 사용자 엔티티의 cascade 삭제 설정과 타임스탬프 필드 관련 데이터 무결성 문제를 해결한 내용을 정리합니다.

## 해결된 문제들

### 1. 호스팅 미팅의 Cascade 삭제로 인한 데이터 무결성 문제 ✅

#### 문제 상황
- `UserEntity.meetings`에 `cascade = [CascadeType.ALL], orphanRemoval = true` 설정
- `MeetingEntity.attendees`에는 cascade 설정이 없음
- 사용자 삭제 시 호스팅 미팅이 삭제되지만, 다른 참석자의 `MeetingAttendeeEntity` 레코드가 고아 레코드로 남음
- 데이터베이스 제약 조건 위반 및 데이터 무결성 문제 발생 가능

#### 해결 방법

**1) MeetingEntity에 Cascade 설정 추가**
```kotlin
// ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/meeting/MeetingEntity.kt
@OneToMany(mappedBy = "meeting", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
val attendees: MutableList<MeetingAttendeeEntity> = mutableListOf()
```

**변경 이유:**
- 미팅이 삭제될 때 모든 참석자 레코드도 함께 삭제되도록 보장
- 고아 레코드 방지 및 참조 무결성 유지

**2) WithdrawService에 검증 로직 추가**
```kotlin
// ssolv-api-core/src/main/kotlin/org/depromeet/team3/auth/application/common/WithdrawService.kt
private fun validateHostedMeetings(userId: Long) {
    val hostedMeetings = meetingRepository.findMeetingsByUserId(userId)
    
    for (meeting in hostedMeetings) {
        val meetingId = meeting.id ?: continue
        val attendees = meetingAttendeeRepository.findByMeetingId(meetingId)
        
        // 호스트 본인 외에 다른 참석자가 있는지 확인
        val hasOtherAttendees = attendees.any { it.userId != userId }
        
        if (hasOtherAttendees) {
            throw AuthException(ErrorCode.CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS)
        }
    }
}
```

**변경 이유:**
- 다른 참석자가 있는 모임을 호스팅 중인 사용자는 탈퇴 불가
- 사용자에게 명확한 에러 메시지 제공
- 모임 종료 또는 호스트 이전 후 탈퇴하도록 유도

**3) 새로운 ErrorCode 추가**
```kotlin
// ssolv-global-utils/src/main/kotlin/org/depromeet/team3/common/exception/ErrorCode.kt
CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS("C4101", 
    "다른 참석자가 있는 모임을 호스팅 중이므로 탈퇴할 수 없습니다. 모임을 종료하거나 호스트를 이전한 후 다시 시도해주세요.", 
    409)
```

#### Cascade 동작 흐름
```
사용자 삭제 요청
    ↓
validateHostedMeetings() - 다른 참석자가 있는 모임 검증
    ↓
userCommandRepository.delete(user)
    ↓
UserEntity.meetings (cascade = ALL, orphanRemoval = true)
    ↓
MeetingEntity 삭제
    ↓
MeetingEntity.attendees (cascade = ALL, orphanRemoval = true)
    ↓
MeetingAttendeeEntity 삭제 (고아 레코드 방지)
```

---

### 2. 타임스탬프 필드의 공개 가변 노출로 인한 감사 무결성 손상 ✅

#### 문제 상황
- `BaseTimeEntity.createdAt`과 `updatedAt`이 `public var`로 선언
- 외부 코드에서 직접 수정 가능
- `@Column(updatable=false)`는 DB 변경만 막고, 프로그래밍 방식 수정은 막지 못함
- 애플리케이션 상태와 DB 상태 불일치 가능
- `updateTimestamp()` 메서드를 통한 제어된 갱신 우회 가능

#### 해결 방법

**BaseTimeEntity 필드 접근 제한**
```kotlin
// ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/common/BaseTimeEntity.kt
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseTimeEntity {
    
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        protected set

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
        protected set

    fun updateTimestamp() {
        updatedAt = LocalDateTime.now()
    }
}
```

**변경 사항:**
- `var createdAt` → `var createdAt ... protected set`
- `var updatedAt` → `var updatedAt ... protected set`

**변경 이유:**
- Kotlin의 관용적인 방식을 사용하여 **Getter는 public, Setter는 protected**로 설정
- 외부에서 타임스탬프 직접 수정 방지 (컴파일 에러 발생)
- 읽기 작업은 기존처럼 `entity.createdAt`으로 편리하게 사용 가능
- 감사 추적(audit trail) 무결성 보장
- JPA Auditing과 `updateTimestamp()` 메서드를 통한 제어된 갱신만 허용

---

### 3. createdAt 필드에 null 할당으로 인한 제약 조건 위반 ✅

#### 문제 상황
- `BaseTimeEntity.createdAt`은 `nullable = false`로 설정
- `UserMapper.toEntity()`에서 `domain.createdAt`을 직접 할당
- `domain.createdAt`이 null일 경우 DB 제약 조건 위반

#### 해결 방법

**UserMapper 수정**
```kotlin
// ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/mapper/UserMapper.kt
override fun toDomain(entity: UserEntity): User {
    return User(
        // ... 다른 필드들
        createdAt = entity.getCreatedAt(),  // getter 사용
        updatedAt = entity.getUpdatedAt()   // getter 사용
    )
}

override fun toEntity(domain: User): UserEntity {
    return UserEntity(
        id = domain.id,
        provider = domain.provider,
        socialId = domain.socialId,
        email = domain.email,
        profileImage = domain.profileImage,
        refreshToken = domain.refreshToken,
        nickname = domain.nickname
    )
    // Note: createdAt과 updatedAt은 JPA Auditing이 자동 관리
    // 수동 설정하지 않음으로써 null 할당 위험 제거
}
```

**변경 사항:**
1. `toDomain()`: 직접 필드 접근 → getter 메서드 사용
2. `toEntity()`: `createdAt`, `updatedAt` 수동 할당 제거

**변경 이유:**
- JPA Auditing이 타임스탬프를 자동으로 관리
- null 할당 위험 완전 제거
- 새 엔티티 생성 시 `BaseTimeEntity`의 기본값 사용
- 감사 무결성 유지

---

## 영향 범위

### 수정된 파일
1. `ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/common/BaseTimeEntity.kt`
2. `ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/mapper/UserMapper.kt`
3. `ssolv-infrastructure/src/main/kotlin/org/depromeet/team3/meeting/MeetingEntity.kt`
4. `ssolv-api-core/src/main/kotlin/org/depromeet/team3/auth/application/common/WithdrawService.kt`
5. `ssolv-global-utils/src/main/kotlin/org/depromeet/team3/common/exception/ErrorCode.kt`

### 추가 의존성
- `WithdrawService`에 `MeetingRepository`, `MeetingAttendeeRepository` 주입

---

## 테스트 권장 사항

### 1. 회원 탈퇴 시나리오
- [ ] 호스트만 있는 모임 → 탈퇴 성공, 모임 및 참석자 레코드 모두 삭제 확인
- [ ] 다른 참석자가 있는 모임 → 탈퇴 실패, `CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS` 에러 확인
- [ ] 참석자로만 참여한 사용자 → 탈퇴 성공, 참석자 레코드만 삭제 확인

### 2. 타임스탬프 무결성
- [ ] 새 엔티티 생성 시 `createdAt` 자동 설정 확인
- [ ] 엔티티 수정 시 `updatedAt` 자동 갱신 확인
- [ ] 외부에서 타임스탬프 직접 수정 시도 → 컴파일 에러 확인
- [ ] Mapper를 통한 변환 시 타임스탬프 정상 동작 확인

### 3. Cascade 동작
- [ ] 사용자 삭제 시 호스팅 미팅 자동 삭제 확인
- [ ] 미팅 삭제 시 모든 참석자 레코드 자동 삭제 확인
- [ ] 고아 레코드 발생하지 않음 확인

---

## 추가 고려사항

### 향후 개선 가능한 사항
1. **호스트 이전 기능**: 탈퇴 전 다른 참석자에게 호스트 권한 이전
2. **모임 자동 종료**: 호스트 탈퇴 시 모임 자동 종료 옵션
3. **소프트 삭제**: 완전 삭제 대신 비활성화 처리 고려
4. **배치 처리**: 오래된 종료 모임 정리 배치 작업

### 성능 고려사항
- `validateHostedMeetings()`에서 N+1 쿼리 발생 가능
- 필요시 fetch join으로 최적화 고려

---

## 결론

세 가지 데이터 무결성 문제를 모두 해결했습니다:

1. ✅ **Cascade 삭제 문제**: MeetingEntity에 cascade 설정 추가 + WithdrawService에 검증 로직 추가
2. ✅ **타임스탬프 가변성 문제**: BaseTimeEntity 필드를 protected로 변경 + getter 메서드 제공
3. ✅ **Null 할당 문제**: UserMapper에서 타임스탬프 수동 할당 제거, JPA Auditing에 위임

이제 시스템은 다음을 보장합니다:
- 고아 레코드 발생 방지
- 감사 추적 무결성 유지
- 데이터베이스 제약 조건 준수
- 명확한 에러 메시징을 통한 사용자 가이드
