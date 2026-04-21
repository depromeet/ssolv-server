---
description: Session retrospective — extract learnings from current conversation and distribute them into memory, CLAUDE.md, thoughts/retros, thoughts/learning, and skills. Core engine of Compounding Engineering loop. Invoke at session end or major milestones.
allowed-tools: Bash, Read, Write, Edit, Glob, Grep, Skill, ToolSearch
argument-hint: "[optional: focus area, e.g. 'infra' or 'frontend-pain']"
---

# /retro — 세션 회고 루프

## 목적

이번 세션에서 드러난 사용자·조직·프로젝트 사실, 업무 스타일, 프롬프팅 습관, 지식 공백, 반복 패턴을 추출해 **컴파운딩 인프라에 축적**합니다. 다음 세션이 더 빠르게 시작되도록.

> Bootstrapping + Compounding 엔진입니다. 매 세션 종료 또는 큰 마일스톤 완료 시점에 실행하세요.

---

## 실행 흐름

### 1단계. 추출 (Claude가 자동)

현재 세션 transcript를 훑어 **5가지 항목 후보**를 뽑습니다. 각 후보는 "세션에서 실제로 드러난 증거(발화·행동)"가 있어야 합니다.

| # | 항목 | 추출 기준 |
|---|---|---|
| 1 | 사용자/조직/프로젝트 사실 | 코드·git으로 알 수 없는 것만 (팀 구성, 마감일, 외부 제약, 개인 역할·책임) |
| 2 | 업무 스타일 · 협업 방식 | 반복된 지시 패턴, 선호 응답 길이, 의사결정 스타일, 호출 주기 |
| 3 | 프롬프팅 습관 관찰 | 자주 누락되는 맥락, 불명확했던 요청, 개선 여지 |
| 4 | 학습자료 후보 | 사용자가 모른다고 한 영역, Claude가 설명을 길게 해야 했던 주제 |
| 5 | 스킬 갭 | `find-skills` 실행 후 이번 세션 주제에 맞는 외부 skill 존재 여부. 없으면 신규 skill 필요 |

**추출 규칙:**
- 저 세션이 아닌 **이번 세션**에서 드러난 것만.
- "확실하지 않으면 후보에 올리지 말 것" — 사용자를 피곤하게 만들지 않는다.
- 같은 항목이 이미 memory에 있으면 **업데이트 후보**로 표시.

---

### 2단계. 사용자 확인 (대화형)

각 후보를 한 항목씩 보여주며 **승인받습니다**:

```
## 추출 결과 — 후보 N개

### 1. 사용자/조직/프로젝트 사실
- [a] ssolv는 Depromeet 17기 팀 프로젝트이고 박민음이 백엔드 단독 주도 중
- [b] PR #182가 방금 dev에 머지됨 — Phase 1.5 완료
...

각 항목을 저장할까요? (a/b/c... 또는 "전부" / "skip")
```

**승인받은 것만** 3단계로. skip된 건 **회고록에만** 기록(노이즈 필터 로그 용도).

---

### 3단계. 저장 (승인된 것만)

| 항목 | 저장 위치 | 방식 |
|---|---|---|
| 1. 사실 | `~/.claude/projects/-Users-parkmineum-17th-team3-Server/memory/{user,project,reference}_*.md` | frontmatter 포함 파일 생성 + `MEMORY.md` 인덱스 한 줄 추가 |
| 2. 업무 스타일 (팀 공유) | `CLAUDE.md` | **diff 제안만** → 사용자 승인 → Edit |
| 2. 업무 스타일 (개인용) | `CLAUDE.local.md` | 직접 Edit 가능 |
| 3. 프롬프팅 습관 | `thoughts/retros/YYYY-MM-DD.md` | 날짜별 회고록 (같은 날 2회 이상이면 append) |
| 4. 학습자료 | `thoughts/learning/resources.md` | 카테고리별 append. 이유 한 줄 + 날짜 필수 |
| 5a. find-skills 매칭 성공 | 회고록에 참조 기록 | Skill 호출만 |
| 5b. 신규 skill 필요 | `skill-creator` 자동 호출 → `.claude/skills/<new-name>/SKILL.md` | **자동 생성** (frontmatter 포함) |

#### 저장 세부 규칙

**Memory 파일 포맷** (항목 1):
```markdown
---
name: <짧은 이름>
description: <한 줄 — 미래 매칭용>
type: user | project | reference | feedback
---

<본문>
```
생성 후 `MEMORY.md`에 `- [제목](파일명.md) — 한 줄 요약` 추가.

**CLAUDE.md 편집 원칙** (항목 2 — 팀 공유):
- 자동 write 금지.
- diff 형태로 출력 → "적용해주세요" 응답 받은 경우에만 Edit 실행.
- 섹션 중복 금지 — 기존 섹션 업데이트 우선.

**thoughts/retros/ 회고록 포맷**:
```markdown
# Retro — YYYY-MM-DD

## 세션 요약
<1~2줄>

## 이번 세션에서 쌓인 것
- Memory: ...
- Skills: ...
- Learning links: ...
- CLAUDE.md 제안: ...

## 프롬프팅 습관 관찰
- <개선점 N>: <근거 발화·행동>

## 다음 세션 시작 시 참고
- <있으면>

## Skipped candidates (노이즈 로그)
- <사용자가 저장 거부한 후보들 — 같은 것 또 뽑지 않도록>
```

**skill-creator 사용** (항목 5b):
- `Skill` 도구로 `anthropic-skills:skill-creator` 호출
- 프로젝트 맥락 전달: "ssolv, Kotlin/Spring Boot 3, 이번 세션 주제 = ..."
- 생성 위치는 `.claude/skills/<name>/SKILL.md`
- frontmatter에 `name` + `description` 필수

---

### 4단계. 마무리 보고

```
## /retro 완료

- Memory: +N개
  - user_xxx.md
  - project_yyy.md
- CLAUDE.md 제안: N건 (승인 대기 중)
- thoughts/retros/YYYY-MM-DD.md: 기록 완료
- thoughts/learning/resources.md: +N개 링크 추가
- Skills: +N개 생성 (이름 목록)
- Skipped: N건 (회고록에 로그)

다음 세션 시작 시: 이 회고록을 먼저 참조하도록 Claude에게 안내할지 제안합니다.
```

---

## Claude에게 — 세션 중 리마인더 기준

다음 신호가 감지되면 **사용자에게 `/retro` 실행을 제안**하세요:

- 큰 마일스톤 완료 직후 (PR 머지, 기능 완성, 배포)
- 사용자 교정이 세션 내 3회 이상 반복 (패턴 축적 필요)
- 세션이 길어져 토픽이 명확히 전환될 때
- 사용자가 "다음에 이어서", "오늘 여기까지" 류 마무리 발화를 할 때
- `/context`가 50% 이상 차올랐을 때

제안 문구 예: **"지금 `/retro` 한 번 돌릴 타이밍 같은데, 돌릴까요?"**

강제 호출 금지. 사용자가 "ㄴㄴ" 하면 계속 진행.

---

## 금지 사항

- ❌ 민감 정보(SSH 키 경로, 토큰, 개인 경로)를 `thoughts/`·커밋 대상 memory에 저장 금지 → `CLAUDE.local.md`에만.
- ❌ 확신 없는 후보를 사용자 확인 없이 저장.
- ❌ `CLAUDE.md`에 자동 write (항상 diff 제안 → 승인 후 Edit).
- ❌ 동일 항목을 매 세션 중복 저장 (memory 기존 항목 체크 후 업데이트 우선).
- ❌ 회고록을 너무 길게 (5~15줄 요약 수준, 대화 transcript 복붙 금지).
