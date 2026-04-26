---
name: git-conventions
description: Use when creating commits, branches, pull requests, or GitHub issues. Covers Conventional Commits format (English only — enforced by commit-msg-check.sh hook), branch naming, PR/issue templates, and merge etiquette. Trigger any time a git/gh command is about to run or when drafting a PR description.
---

# Git Conventions

## 커밋 메시지

**Conventional Commits** 형식을 사용한다.

```
type(scope): 메시지
```

### 타입

| 타입 | 사용 시점 |
|---|---|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 기능 변경 없는 코드 개선 |
| `test` | 테스트 추가/수정 |
| `docs` | 문서 작성/수정 |
| `perf` | 성능 개선 |
| `chore` | 빌드/설정/의존성 변경 |
| `build` | 빌드 시스템 변경 |

### 스코프 (선택)

변경된 도메인/기능을 괄호 안에 명시한다.

```
feat(place): implement dynamic Redis TTL based on meeting endAt
fix(withdrawal): only block withdrawal for active meetings
refactor(place): enhance fault tolerance using supervisorScope
test(place): fix MeetingPlaceSearchServiceTest failure
```

스코프 없이 전체 프로젝트에 영향을 줄 때:
```
refactor: remove all benchmark dependencies from production source
feat: migrate external clients from WebClient to Ktor Client
```

### 주의

- `type :` (콜론 앞 공백) 형식 사용 금지 — `type:` 또는 `type(scope):` 사용
- 메시지는 영문 소문자로 시작, 마침표 없음
- 제목은 72자 이내

## 작업 시작 순서 (중요)

PR 작성 전에 **반드시 이슈를 먼저 만든다** — 브랜치 네이밍이 이슈 번호를 요구하기 때문.

```
1. gh issue create  → 이슈 번호 확보 (예: #187)
2. git checkout -b feat/#187  → 이슈 번호로 브랜치
3. 작업 → commit → push
4. gh pr create  → PR 본문에 "Close #187" 또는 "Resolves #187"
```

이슈 없이 작업이 시작된 경우(예: 핫픽스): 작업 도중에라도 이슈를 만들고 브랜치명을 맞춘다. 이미 push 한 PR 이라도 다음 PR 부터는 이 순서를 지킨다.

## 브랜치 네이밍

```
{type}/#{issue-number}
```

예시:
- `feat/#123`
- `fix/#456`
- `refactor/#169`

❌ 안티 패턴: `feat/sonar-auto-fix-workflow` (이슈 번호 없이 설명형 슬러그) — PR #186 회고 참조

## PR 작성

`.github/PULL_REQUEST_TEMPLATE.md` 양식은 **무시하고** 아래 형식을 사용한다. 가치 중심 서술이 핵심 — "무엇을 바꿨는가"보다 "왜 이 변경이 필요했는가"를 먼저 전달한다.

### 언어 규칙

- **PR 제목**: 영문 (Conventional Commits 와 동일 규약 — `type(scope): message`, 소문자 시작, 72자 이내)
- **PR 본문**: **한국어로 작성** (헤딩은 영문 `## Summary` / `## Test plan` 유지, 본문 내용은 한국어)
- **커밋 메시지**: 영문 (commit-msg-check.sh 훅이 강제)
- **이슈 본문**: 한국어

### 본문 형식

```markdown
## Summary

<변경의 맥락과 목적을 1~2문장의 한국어로>

### <변경 묶음 1 제목> (커밋이 여러 개거나 주제가 나뉠 때만 ### 사용)

- 핵심 변경 사항과 이유 (한국어)
- 설계 결정이 있으면 why를 포함

### <변경 묶음 2 제목>

- ...

## Test plan

- [x] 실제로 확인한 항목만 체크
- [x] `./gradlew harness` 통과 여부
- [x] CI 통과 여부

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

### 작성 원칙

- `## Summary` 바로 아래에 변경의 핵심을 먼저 — 리뷰어가 첫 문단만 읽어도 PR의 가치를 알 수 있어야 함
- 커밋이 1개거나 주제가 단일하면 `###` 없이 `## Summary` 아래 바로 bullets
- 커밋이 여러 개이거나 주제가 나뉠 때만 `###` 서브섹션 사용
- 설계 결정이 있으면 **(a) / (b) / ...** 형식으로 명시 (왜 다른 방법이 아닌지 포함)
- Test plan은 실제로 검증한 것만 — 형식적 체크리스트 금지
- 헤딩은 영문 그대로(`## Summary`, `## Test plan`), 본문 텍스트만 한국어

**PR 머지 대상**: `dev` 브랜치 (CI는 `dev` 브랜치 PR에서 트리거됨)

## 이슈 작성

두 가지 템플릿 중 선택:

**기능 구현 이슈** (`.github/ISSUE_TEMPLATE/기능-구현-이슈-템플릿.md`):
```markdown
## 어떤 기능인가요?
> 추가하려는 기능에 대해 간결하게 설명

## 작업 상세 내용
- [ ] 세부 작업 1
- [ ] 세부 작업 2

## 참고할만한 자료(선택)
```

**수정/리팩토링 이슈** (`.github/ISSUE_TEMPLATE/수정---리팩토링-관련-이슈-템플릿.md`):
동일한 구조, 수정/개선 작업에 사용.
