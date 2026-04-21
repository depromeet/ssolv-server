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

## 브랜치 네이밍

```
{type}/#{issue-number}
```

예시:
- `feat/#123`
- `fix/#456`
- `refactor/#169`

## PR 작성

`.github/PULL_REQUEST_TEMPLATE.md` 양식을 따른다:

```markdown
## 🎋 이슈 및 작업중인 브랜치

- closes #{issue-number}

## 🔑 주요 내용

- 변경 사항 1
- 변경 사항 2

## Check List

- [ ] Assignees 등록을 하였나요?
- [ ] 라벨(Label) 등록을 하였나요?
- [ ] PR 머지하기 전 반드시 CI가 정상적으로 작동하는지 확인해주세요!
```

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
