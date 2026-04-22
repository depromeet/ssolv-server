# Harness & Compounding Engineering 설계 문서

> 최종 수정: 2026-04-22
> 목적: ssolv 프로젝트의 코드 품질 게이트(Harness)와 세션 간 지식을 축적하는 컴파운딩 엔지니어링 워크플로우 전체 설계를 단일 문서로 정리한다.

---

## 1. Harness 전체 아키텍처

Harness는 **2개 로컬 계층 + CI** 로 구성된다.

```
[로컬 작업] ──────────────────────────────────────────────────────► [원격]
     │
     ▼
 (pre-commit 없음 — 과거에는 ktlintCheck 였으나 리포팅 중복이라 제거)
     │
     ▼
 pre-push (git hook)           ./gradlew harness
                                 = ktlint(리포팅) + 전체 테스트
                                 테스트 실패 시 push 차단
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

- **ktlint**: `ignoreFailures = true` 로 고정 → 위반 있어도 태스크 자체는 통과. 스타일 리포트만 출력.
  - 이유: dev 브랜치 직접 push 시 harness가 막히지 않도록 한 의도적 정책. baseline 위반이 있기 때문에 차단으로 돌리면 개발이 멈춘다. **CI에서 리포팅을 확인하는 것이 정답.**
- **test**: `Test.ignoreFailures = !isHarness` → harness 경로에서만 테스트 실패가 빌드 실패로 전파.
- `isHarness` 플래그: `gradle.startParameter.taskNames.any { it == "harness" || it.endsWith(":harness") }` — 정확 매칭으로 오작동 방지.

### 1.2 Git Hooks (로컬)

`./gradlew installGitHooks` 가 `.git/hooks/` 에 훅 스크립트를 복사한다. 복사 전에 **`.githooks/` 에 더 이상 존재하지 않는 관리 대상 훅(예: 과거의 pre-commit)을 hooks 디렉토리에서 정리**한다 — 정책이 바뀐 뒤에도 오래된 훅이 남아있는 사태를 막기 위함.

| Hook | 실행 시점 | 실행 내용 | 차단 여부 |
|---|---|---|---|
| `pre-push` | `git push` 전 | `./gradlew harness` (ktlint + test) | ✅ 테스트 실패 시 push 차단 |

긴급 우회: `git push --no-verify` (권장하지 않음)

### 1.3 Worktree에서의 git hooks 동작

`git worktree add` 로 만든 작업 트리에서도 git은 **원본 repo의 `.git/hooks/` 를 공유**한다 (`git rev-parse --git-path hooks` 로 확인 가능). 즉:

- **`./gradlew installGitHooks` 는 clone 직후 단 1회만 실행하면 된다** — worktree마다 다시 설치할 필요 없음.
- 반대로, 어떤 worktree에서 `.githooks/` 를 수정한 뒤 `installGitHooks` 를 재실행하면 **모든 worktree가 즉시 새 훅을 공유**한다.

---

## 2. Claude Code Hook 아키텍처

Claude Code 세션 중 툴 호출 이벤트에 반응하는 훅. 설정 파일: `.claude/settings.json`

### 2.1 settings.json 구조

```json
{
  "hooks": {
    "PreToolUse":  [ { "matcher": "Bash",       "hooks": [commit-msg-check.sh] } ],
    "PostToolUse": [ { "matcher": "Edit|Write", "hooks": [post-edit-dispatch.sh] },
                     { "matcher": "Bash",       "hooks": [post-push-retro-nudge.sh] } ]
  }
}
```

> **Stop hook 제거됨**: 과거에는 `test-changed-modules.sh` 가 매 응답마다 변경 모듈을 테스트했으나, **pre-push harness와 100% 중복**이면서 읽기 전용 턴에도 Gradle daemon이 깨어나고, 타임아웃 5분까지 세션이 블로킹되는 UX 문제로 제거했다. 테스트 검증은 **pre-push 단일 지점**에 위임한다.

### 2.2 훅 트리거 흐름

```
Claude 툴 호출
    │
    ├─ Bash 호출 전 ────────────► commit-msg-check.sh
    │                              └─ early-exit: "git commit" 문자열이 없으면 즉시 종료
    │                                 (python3 프로세스 스폰 비용 회피)
    │                                 └─ Conventional Commits + 영문 강제
    │
    ├─ Edit|Write 완료 후 ──────► post-edit-dispatch.sh  (디스패처)
    │                              ├─ *.tf            → iac-security-check.sh
    │                              ├─ *Controller.kt  → controller-annotation-check.sh
    │                              │                  → value-injection-check.sh
    │                              │                  → module-import-check.sh
    │                              └─ *.kt / *.kts    → value-injection-check.sh
    │                                                 → module-import-check.sh
    │
    └─ Bash 완료 후 ────────────► post-push-retro-nudge.sh
                                   └─ early-exit: "git push" 문자열이 없으면 즉시 종료
                                      └─ /retro 리마인더 출력
```

### 2.3 Bash 훅 오버헤드 대책

`PreToolUse(Bash)` / `PostToolUse(Bash)` 는 **모든 Bash 호출**에 트리거된다 — `ls`, `cat`, `gradlew` 를 포함해 세션 당 수백 번. 따라서 훅 스크립트는 반드시 **쉘 레벨 early-exit** 를 먼저 실행한 뒤에만 python3 파싱으로 넘어간다:

```bash
if ! printf '%s' "$TOOL_INPUT" | grep -q '"git commit'; then
    exit 0
fi
# ↓ 여기서부터 python3 파싱 ↓
```

이 가드가 없으면 세션 전반에 걸쳐 수 초 단위의 누적 지연이 발생한다.

---

## 3. 각 훅 상세

### 3.1 commit-msg-check.sh
- **트리거**: `PreToolUse(Bash)` — `git commit` 명령 실행 전
- **차단 정책**: ✅ `exit 2` — 한국어 포함 / Conventional Commits 위반 시 차단
- **우회 조건**: `--no-verify` 플래그 또는 훅에서 `git commit` 매칭 실패 시 즉시 종료

### 3.2 post-edit-dispatch.sh (디스패처)
- **역할**: 파일 경로 패턴을 보고 필요한 훅만 선택 실행 — 단일 프로세스에서 라우팅하여 훅 4개 중복 실행을 방지

### 3.3 controller-annotation-check.sh
- **차단 정책 (H7 결정)**:
  - ✅ **차단 (exit 2)** — 아키텍처/계약 위반
    - `@AuthenticationPrincipal` 직접 사용 → `@UserId` 로 교체
    - `DpmApiResponse` 래핑 누락
  - ⚠️ **경고만 (exit 0)** — 문서 품질 미비
    - `@Tag` 누락
    - `@Operation` 누락

### 3.4 value-injection-check.sh
- **차단 정책**: ✅ `exit 2` — `@Value` 직접 주입 금지 (`@ConfigurationProperties` 로 대체)

### 3.5 module-import-check.sh
- **차단 정책**: ✅ `exit 2` — 아래 방향 위반 시 차단
  - `ssolv-api-core` ↔ `ssolv-api-place` (상호 참조)
  - `ssolv-domain` → `ssolv-infrastructure` / `ssolv-api-*` / JPA 어노테이션

### 3.6 iac-security-check.sh
- **차단 정책**: ⚠️ 경고만. 상세 감사는 `/iac-audit` 커맨드로.

### 3.7 post-push-retro-nudge.sh
- **역할**: `git push` 성공 시 `/retro` 리마인더 출력 (non-blocking)

### 3.8 차단 vs 경고의 정책 기준

일관성을 위해 아래 기준을 따른다:

| 위반 유형 | 처리 |
|---|---|
| 아키텍처 경계 위반 (모듈 import 방향) | ✅ 차단 |
| 보안 계약 위반 (`@AuthenticationPrincipal`, `@Value`) | ✅ 차단 |
| API 응답 계약 위반 (`DpmApiResponse` 누락) | ✅ 차단 |
| 커밋 메시지 컨벤션 | ✅ 차단 |
| IaC 보안 권고 | ⚠️ 경고 (상세는 `/iac-audit`) |
| 문서 품질 미비 (`@Tag`, `@Operation`) | ⚠️ 경고 |

**기준 한 줄 요약**: "나중에 고치면 된다" = 경고. "이 상태로 merge되면 안 된다" = 차단.

---

## 4. Compounding Engineering 레이어 구조

매 세션이 다음 세션을 더 빠르게 만드는 **4개 계층**.

```
Memory     — 과거 경험 보존      (교정 내역, 결정 근거)
Skills     — 도메인 지식 영구화  (패턴, 컨벤션, 예제 코드)
Hooks      — 반복 검증 자동화   (품질 게이트)
Commands   — 반복 절차 추상화   (워크플로우 템플릿)
```

### 4.1 Memory

경로: `~/.claude/projects/<project-slug>/memory/` (사용자별 홈 디렉토리, git 추적 안 됨)

| 타입 | 내용 |
|---|---|
| `user` | 사용자 역할/선호/배경 |
| `feedback` | 교정받은 접근 방식 규칙 |
| `project` | 진행 중인 작업·결정 |
| `reference` | 외부 시스템 위치 포인터 |

### 4.2 Skills (11개)

`api-patterns`, `architecture`, `testing`, `async-processing`, `git-conventions`, `auth`, `observability`, `notification`, `place`, `domain-model`, `batch`

### 4.3 Skill auto-discovery의 한계 (F5)

Claude가 description 매칭으로 skill을 **탐색**하지만, 본문은 `Skill` 툴로 **명시 로드**해야만 컨텍스트에 들어온다. 따라서:

- **핵심 불변 규칙은 description 기반 자동 트리거에만 의존하지 말 것.** 훅 차단 + CLAUDE.md inline 명시로 이중화한다.
- Skills는 "참고 자료"이지 "강제 수단"이 아니다. 강제는 훅이 한다.

### 4.4 Hooks

위 3절 참조.

### 4.5 Commands (7개)

`new-domain`, `iac-audit`, `deploy`, `logs`, `health-check`, `env-update`, `retro`

---

## 5. 세션 회고 루프 (Phase 2)

push 성공 후 `/retro` 가 권유된다. 회고는 세션 중 관찰을 분배한다:

```
세션 관찰
    │
    ├─ 사용자/조직/프로젝트 사실  → memory/ (user·project·reference)
    ├─ 교정·발견된 갭            → memory/ (feedback) + skill 업데이트
    ├─ 패턴·결정사항             → .claude/thoughts/retros/<date>.md (git 커밋)
    └─ 팀 공유 규칙 변경         → CLAUDE.md (diff 제안 → 승인 → Edit)
```

### 5.1 루프 효과성 — 정직한 현재 평가 (F4)

2026-04-22 기준:

- `.claude/thoughts/retros/` 는 `.gitkeep` 외에 비어 있음 → `/retro` 가 아직 한 번도 유효 실행되지 않았거나, 실행했지만 축적할 관찰이 없었음
- feedback 타입 memory는 1개 (`branch_instruction`) — Phase 2 도입 후 유의미한 누적 없음
- 즉 **"컴파운딩 루프는 인프라만 깔렸고 데이터는 아직 쌓이지 않은 상태"** 라고 정직하게 기록해둔다. 다음 retro 때 **최소 1건의 feedback memory를 생성하는 것**이 Phase 2 실효성의 첫 증거가 된다. 3회 연속 retro에서 새로운 memory가 하나도 안 나오면 루프 설계 자체를 재점검한다.

---

## 6. 폴더 구조

```
.claude/
├── commands/           # 슬래시 커맨드 7개
├── docs/
│   ├── workflow/       # 워크플로우/메타 문서 (이 파일, compounding-engineering.md)
│   └── domain/         # 도메인 구현 가이드 (place-*, otel-instrumentation 등)
├── hooks/              # PreToolUse/PostToolUse 훅 스크립트
├── infra/
│   ├── DECISIONS.md    # 진행형 ADR
│   └── archive/        # 완료된 마이그레이션 로그
├── skills/             # 11개 skill (frontmatter 기반 auto-discovery)
├── thoughts/
│   ├── learning/       # 학습 자료 누적
│   └── retros/         # 세션별 회고록 (git 커밋)
├── settings.json       # 팀 공유 hook 설정
└── settings.local.json # 개인 설정 (gitignored)
```

**.gitignore 주의**: `.claude/settings.local.json` 과 `.claude/**/*.local.json` 은 git-ignored다. 개인 SSH 키 경로·서버 IP·MCP UUID 등이 들어가므로 절대 커밋하지 않는다.

---

## 7. 확장 가이드

### 새 Hook 추가

1. `.claude/hooks/<name>.sh` 작성
2. **첫 줄에 쉘 레벨 early-exit** (Bash matcher 훅일 경우 필수)
3. `stdin` 으로 JSON 읽기
4. `exit 0` = 통과, `exit 2` = 차단
5. 파일 경로 기반 검증이라면 `post-edit-dispatch.sh` case 절에 라우팅 추가
6. 차단/경고 기준은 3.8절 정책에 맞춤

### 새 Skill 추가

1. `.claude/skills/<name>/SKILL.md` 생성
2. frontmatter에 `name` + `description` 필수
3. description은 구체적일수록 자동 트리거 정확도 ↑
4. 핵심 규칙이라면 description에 의존하지 말고 **훅 차단 + CLAUDE.md inline** 으로 이중화

### 새 Command 추가

1. `.claude/commands/<name>.md` 생성
2. frontmatter에 `description` + `allowed-tools` + `argument-hint`

---

## 8. 현재 성숙도 (2026-04-22 기준)

```
Memory     ████░░░░░░  (40%) ← 인프라 완비, 데이터 누적 진행 중
Skills     ████████░░  (80%) ← 11개 완비, frontmatter auto-discovery 동작
Commands   ████████░░  (80%) ← 7개
Hooks      █████████░  (90%) ← 디스패처 패턴 + early-exit로 오버헤드 최소화
```

### 최근 정리 내역 (이 문서와 함께 적용)

- pre-commit ktlint 훅 제거 — 리포팅 전용이라 3번 실행이 의미 없었음
- Stop hook (`test-changed-modules.sh`) 제거 — pre-push와 중복
- Bash 훅 2개에 쉘 레벨 early-exit 추가
- `controller-annotation-check` 차단/경고 기준 정책화
- `isHarness` 플래그를 정확 매칭으로 강화
- docs 폴더를 `workflow/` + `domain/` 으로 분리
- `.claude/infra/WORKFLOW.md` 를 `archive/2026-03-aws-migration.md` 로 이동
- `CLAUDE.md` 슬림화 — terraform 규칙은 `/iac-audit` 커맨드 문서로 이동

### 다음 Phase 후보

- **Phase 3-a**: retro 상호 참조 — 반복된 실수 Top N 추출 후 skill 고도화
- **Phase 3-b**: Plan 기반 워크플로 대형 작업에 선별 도입
- **Phase 3-c**: 세션 시작 시 선제 `/retro` 제안
- **Phase 3-d**: memory ↔ `CLAUDE.md` 충돌 자동 감지 Hook
- **Phase 3-e**: 커스텀 sub-agent 도입
