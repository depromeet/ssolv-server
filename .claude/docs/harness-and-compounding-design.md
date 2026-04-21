# Harness & Compounding Engineering 설계 문서

> 최종 수정: 2026-04-22
> 목적: ssolv 프로젝트의 코드 품질 게이트(Harness)와 세션 간 지식을 축적하는 컴파운딩 엔지니어링 워크플로우 전체 설계를 단일 문서로 정리한다.

---

## 1. Harness 전체 아키텍처

Harness는 **3개 계층**으로 구성된다. 계층마다 블로킹 강도가 다르다.

```
[로컬 작업] ──────────────────────────────────────────────────────► [원격]
     │
     ▼
 pre-commit (git hook)         ktlintCheck — 리포팅 전용, 차단 없음
     │
     ▼
 pre-push (git hook)           ktlint(리포팅) + 전체 테스트 — 테스트 실패 시 push 차단
     │
     ▼
 CI (GitHub Actions)           ktlintCheck + build + test + SonarCloud
     │
     ▼
 CD (GitHub Actions)           Jib 이미지 빌드 → EC2 배포
```

### 1.1 Gradle harness 태스크

`./gradlew harness` 는 pre-push 단계와 동일하게 동작하는 로컬 실행 단축키다.

```kotlin
// build.gradle.kts (루트)
tasks.register("harness") {
    dependsOn("ktlintCheck", "test")
}
```

- **ktlint**: `ignoreFailures = true` → 위반 있어도 태스크 자체는 통과. 스타일 리포트만 출력.
- **test**: `Test.ignoreFailures = !isHarness` → harness 경로에서는 테스트 실패가 빌드 실패로 전파.
- `isHarness` 플래그: Gradle property `harness=true`로 활성화되며, `installGitHooks`가 생성한 pre-push hook이 이 플래그를 자동으로 주입.

### 1.2 Git Hooks (로컬)

`./gradlew installGitHooks` 가 `.git/hooks/` 에 심볼릭 링크 또는 스크립트를 생성한다.

| Hook | 실행 시점 | 실행 내용 | 차단 여부 |
|---|---|---|---|
| `pre-commit` | `git commit` 전 | `./gradlew ktlintCheck` | ❌ 차단 없음 (`ignoreFailures=true`) |
| `pre-push` | `git push` 전 | `./gradlew harness` (ktlint + test) | ✅ 테스트 실패 시 push 차단 |

긴급 우회: `git push --no-verify` (권장하지 않음)

---

## 2. Claude Code Hook 아키텍처

Claude Code 세션 중 툴 호출 이벤트에 반응하는 훅이 별도로 존재한다.
설정 파일: `.claude/settings.json`

### 2.1 settings.json 전체 구조

```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Bash",       "hooks": [commit-msg-check.sh] }
    ],
    "PostToolUse": [
      { "matcher": "Edit|Write", "hooks": [post-edit-dispatch.sh] },
      { "matcher": "Bash",       "hooks": [post-push-retro-nudge.sh] }
    ],
    "Stop": [
      { "matcher": "",           "hooks": [test-changed-modules.sh] }
    ]
  }
}
```

### 2.2 훅 트리거 흐름

```
Claude 툴 호출
    │
    ├─ Bash 호출 전 ────────────► commit-msg-check.sh
    │                              └─ git commit 명령일 때만 검증
    │                                 Conventional Commits + 영문 강제
    │
    ├─ Edit|Write 완료 후 ──────► post-edit-dispatch.sh  (디스패처)
    │                              ├─ *.tf            → iac-security-check.sh
    │                              ├─ *Controller.kt  → controller-annotation-check.sh
    │                              │                  → value-injection-check.sh
    │                              │                  → module-import-check.sh
    │                              └─ *.kt / *.kts    → value-injection-check.sh
    │                                                 → module-import-check.sh
    │
    ├─ Bash 완료 후 ────────────► post-push-retro-nudge.sh
    │                              └─ git push 성공 시 /retro 리마인더 출력
    │
    └─ Claude 응답 완료 (Stop) ─► test-changed-modules.sh
                                   └─ .kt/.kts 변경 있을 때만 해당 모듈 테스트 실행
```

---

## 3. 각 훅 상세

### 3.1 commit-msg-check.sh

- **트리거**: `PreToolUse(Bash)` — `git commit` 명령 실행 전
- **역할**: 커밋 메시지 형식 사전 차단
- **검증 항목**:
  1. 한국어(비 ASCII) 포함 여부 → 영문 강제
  2. Conventional Commits 패턴 `type(scope): message` 형식
- **종료 코드**: `exit 2` 시 Claude가 툴 실행을 중단하고 오류 메시지 출력
- **우회 조건**: `--no-verify` 플래그 있으면 건너뜀

```
유효한 type: feat | fix | refactor | test | docs | perf | chore | build | ci | revert
```

### 3.2 post-edit-dispatch.sh (디스패처)

- **트리거**: `PostToolUse(Edit|Write)` — 파일 저장 직후
- **역할**: 4개 검증 훅을 매번 실행하는 대신, 파일 경로 패턴을 보고 필요한 훅만 선택 실행

이전 구조에서 훅 4개를 전부 실행하면 프로세스 4개 스폰 + JSON 파싱 4회가 발생했다. 디스패처 패턴으로 단일 프로세스 실행 후 내부에서 라우팅한다.

```bash
case "$FILE_PATH" in
    *.tf)          → iac-security-check.sh
    *Controller.kt)→ controller-annotation-check.sh + value-injection-check.sh + module-import-check.sh
    *.kt|*.kts)    → value-injection-check.sh + module-import-check.sh
esac
```

### 3.3 controller-annotation-check.sh

- **트리거**: 디스패처 경유 — `*Controller.kt` 저장 시
- **검증 항목**:
  1. 클래스 레벨 `@Tag` 누락
  2. `@Operation` 누락 (최소 1개)
  3. `@AuthenticationPrincipal` 직접 사용 (`@UserId` 로 교체 필요)
  4. `DpmApiResponse` 래핑 누락
- **종료 코드**: 경고는 `exit 0` 으로 계속 진행 (차단 없음, 경고만 출력)

### 3.4 value-injection-check.sh

- **트리거**: 디스패처 경유 — `*.kt` 저장 시 (테스트 파일 제외)
- **검증 항목**: `@Value(...)` 직접 주입 패턴 탐지
- **올바른 대안**: `@ConfigurationProperties` 클래스 생성
- **종료 코드**: 위반 시 `exit 2` → Claude 실행 중단

### 3.5 module-import-check.sh

- **트리거**: 디스패처 경유 — `*.kt` 저장 시 (테스트 파일 제외)
- **금지된 import 방향**:

```
ssolv-api-core  ↔  ssolv-api-place          (상호 참조 금지)
ssolv-domain       ssolv-infrastructure      (domain → infra 금지)
ssolv-domain       ssolv-api-*               (domain → api 금지)
ssolv-domain       jakarta.persistence       (JPA 어노테이션 금지)
```

- **종료 코드**: 위반 시 `exit 2` → Claude 실행 중단

### 3.6 iac-security-check.sh

- **트리거**: 디스패처 경유 — `*.tf` 저장 시
- **검증 항목**:
  1. RDS: `storage_encrypted`, `deletion_protection`, `publicly_accessible = false`
  2. EC2: IMDSv2 (`http_tokens = "required"`)
  3. EBS: `encrypted = false` 명시 감지
  4. Security Group: 3306/6379 포트 `0.0.0.0/0` 노출
  5. `tags` 블록 누락
- **종료 코드**: 경고는 `exit 0` (차단 없음, 경고 출력)

### 3.7 post-push-retro-nudge.sh

- **트리거**: `PostToolUse(Bash)` — `git push` 성공 완료 후
- **역할**: `/retro` 실행을 권유하는 stdout 메시지 출력
- **제외 조건**: `--delete`, `--dry-run` 플래그가 있거나 push exit code ≠ 0

### 3.8 test-changed-modules.sh

- **트리거**: `Stop` (Claude 응답 완료 시)
- **역할**: 세션 중 변경된 모듈만 선택적으로 테스트 실행
- **로직**:
  - `.kt/.kts` 변경 없으면 건너뜀
  - 공유 모듈(`api-common`, `domain`, `infrastructure`, `global-utils`, `batch`) 변경 시 두 서비스 모두 테스트
  - `ssolv-api-core` 변경 → core만, `ssolv-api-place` 변경 → place만

---

## 4. Compounding Engineering 레이어 구조

매 세션이 다음 세션을 더 빠르게 만드는 **4개 계층**으로 이루어진다.

```
Memory     — 과거 경험 보존      (교정 내역, 결정 근거)
Skills     — 도메인 지식 영구화  (패턴, 컨벤션, 예제 코드)
Hooks      — 반복 검증 자동화   (품질 게이트)
Commands   — 반복 절차 추상화   (워크플로우 템플릿)
```

### 4.1 Memory

경로: `/Users/parkmineum/.claude/projects/.../memory/`

세션 간 지속되는 4가지 타입:

| 타입 | 내용 | 예시 |
|---|---|---|
| `user` | 사용자 역할/선호/배경 | "Kotlin 시니어, React 초보" |
| `feedback` | 교정받은 접근 방식 규칙 | "DB 테스트는 mock 금지 — 실 DB 사용" |
| `project` | 진행 중인 작업·결정 | "AWS 계정 이전 완료" |
| `reference` | 외부 시스템 위치 포인터 | "Linear 프로젝트 INGEST = 파이프라인 버그" |

`MEMORY.md` 는 인덱스 파일 — 각 항목은 1줄, 150자 이내.

### 4.2 Skills

경로: `.claude/skills/<name>/SKILL.md`

각 SKILL.md는 `name` + `description` frontmatter를 반드시 포함해야 한다. 이 두 필드가 있어야 Claude가 현재 작업 컨텍스트와 자동 매칭한다.

현재 등록된 Skills (11개):

| Skill | 트리거 조건 |
|---|---|
| `api-patterns` | `*Controller.kt` 작성/수정, 신규 API 라우트 |
| `architecture` | 모듈 간 경계 설계, 신규 도메인 추가 |
| `testing` | `src/test/`, `src/testFixtures/` 하위 작업 |
| `async-processing` | Redis Streams 프로듀서/컨슈머, 코루틴 디스패처 전환 |
| `git-conventions` | 커밋/브랜치/PR/이슈 작성 |
| `auth` | JWT, Kakao/Apple OAuth, `@UserId` |
| `observability` | Sentry, Micrometer, OpenTelemetry, MDC |
| `notification` | FCM 푸시 알림 |
| `place` | `ssolv-api-place` 모듈 (Google Places, Redis ZSET, SSE) |
| `domain-model` | 도메인/엔티티 분리, Mapper, JDSL, `@ConfigurationProperties` |
| `batch` | `ssolv-batch` 모듈 스케줄러, dead-letter |

> **중요**: description 매칭으로 후보가 보인다고 해서 skill 본문이 자동 주입되지 않는다. Claude는 `Skill` 도구로 명시적 로드를 해야 한다. description만 보고 규칙을 추측하면 컨벤션 위반 사례가 생긴다 (PR #183 참조).

### 4.3 Hooks

현재 등록된 Hooks:

| Hook 파일 | 이벤트 | 역할 | 차단 |
|---|---|---|---|
| `commit-msg-check.sh` | PreToolUse(Bash) | Conventional Commits + 영문 강제 | ✅ exit 2 |
| `post-edit-dispatch.sh` | PostToolUse(Edit\|Write) | 경로 기반 훅 라우터 | — |
| `iac-security-check.sh` | (dispatcher) | `.tf` 보안 감사 | ❌ 경고만 |
| `controller-annotation-check.sh` | (dispatcher) | `@Tag`/`@Operation`/`DpmApiResponse` 검증 | ❌ 경고만 |
| `value-injection-check.sh` | (dispatcher) | `@Value` 직접 주입 금지 | ✅ exit 2 |
| `module-import-check.sh` | (dispatcher) | 금지된 모듈 import 방향 차단 | ✅ exit 2 |
| `post-push-retro-nudge.sh` | PostToolUse(Bash) | push 후 `/retro` 리마인더 | ❌ 안내만 |
| `test-changed-modules.sh` | Stop | 변경 모듈 자동 테스트 | ✅ 테스트 실패 시 |

### 4.4 Commands

경로: `.claude/commands/<name>.md`

현재 등록된 Commands (7개):

| Command | 역할 |
|---|---|
| `new-domain` | 신규 도메인 스캐폴딩 |
| `iac-audit` | Terraform 전체 보안 감사 |
| `deploy` | EC2 인스턴스 서비스 재시작 |
| `logs` | EC2 인스턴스 로그 조회 |
| `health-check` | 프로덕션 헬스 체크 |
| `env-update` | `.env` 키-값 업데이트 |
| `retro` | 세션 회고 엔진 |

---

## 5. 세션 회고 루프 (Phase 2)

push 성공 후 `/retro` 가 권유된다. 회고는 세션 중 발생한 관찰을 4개 저장소로 분배한다:

```
세션 관찰
    │
    ├─ 사용자/조직/프로젝트 사실  → memory/ (user·project·reference 타입)
    ├─ 교정·발견된 갭            → memory/ (feedback 타입) + skill 업데이트
    ├─ 패턴·결정사항             → .claude/thoughts/retros/<date>.md (git 커밋)
    └─ 팀 공유 규칙 변경         → CLAUDE.md (diff 제안 → 사용자 승인 → Edit)
```

`CLAUDE.md` 는 팀 공유 파일이므로 자동 쓰기 금지 — `/retro` 내부에서도 diff 제안 후 승인 후 Edit.

---

## 6. 확장 가이드

### 새 Hook 추가

1. `.claude/hooks/<name>.sh` 작성
2. `stdin` 으로 JSON 읽어서 필요한 필드 추출
3. `exit 0` = 통과, `exit 2` = 차단 (Claude 실행 중단)
4. 파일 경로 기반 검증이라면 `post-edit-dispatch.sh` case 절에 라우팅 추가
5. 새 이벤트 타입이면 `settings.json` 에 matcher + command 추가

```bash
# stdin에서 file_path 추출하는 표준 패턴
FILE_PATH=$(cat | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get('tool_input', {}).get('file_path', ''))
except:
    print('')
" 2>/dev/null)
```

### 새 Skill 추가

1. `.claude/skills/<name>/SKILL.md` 생성
2. frontmatter에 `name` + `description` 반드시 포함
3. `description` 은 구체적일수록 자동 트리거 정확도가 올라감
4. CLAUDE.md 스킬 표에 항목 추가

```markdown
---
name: my-skill
description: Use when working on X — covers Y, Z patterns
---
```

### 새 Command 추가

1. `.claude/commands/<name>.md` 생성
2. frontmatter에 `description` + `allowed-tools` + `argument-hint` 포함
3. CLAUDE.md 커맨드 표에 항목 추가

---

## 7. 현재 성숙도 (2026-04-22 기준)

```
Memory     ████░░░░░░  (40%) ← feedback 타입 축적 진행 중
Skills     ████████░░  (80%) ← 11개 완비, frontmatter 기반 auto-discovery 동작
Commands   ████████░░  (80%) ← 7개, retro 루프 완비
Hooks      ████████░░  (80%) ← 디스패처 패턴으로 성능 최적화 완료
```

### 다음 Phase 후보 (미정)

- **Phase 3-a**: 세션 간 retro 상호 참조 — 반복된 실수 Top N 추출 후 skill 고도화
- **Phase 3-b**: Plan 기반 워크플로 선별 도입 — 대형 리팩터링 전 research→plan→implement→validate 4단계
- **Phase 3-c**: 세션 시작 시 선제 `/retro` 제안 — 마지막 실행 시각 + 커밋 누적 추적
- **Phase 3-d**: memory ↔ `CLAUDE.md` 충돌 자동 감지 Hook
- **Phase 3-e**: 커스텀 sub-agent 도입
