# Compounding Engineering — .claude 워크플로우 성장 기록

> 작성일: 2026-04-13
> 목적: 매 작업이 다음 작업을 더 잘 수행할 수 있는 인프라를 한 층씩 쌓는다.
> 참고: https://www.stdy.blog/growing-custom-workflow/

---

## 핵심 원리

```text
일반 방식:   작업 → 결과 → 끝          (매번 0에서 시작)
컴파운딩:    작업 → 결과 + [축적]  →   다음 작업이 더 빠름
```

**"무엇이 쌓이는가"** 기준으로 계층을 나눈다:

| 계층 | 역할 | 쌓이는 것 |
|---|---|---|
| **Memory** | 과거 경험 보존 | 교정 내역, 결정 근거 |
| **Skills** | 도메인 지식 영구화 | 패턴, 컨벤션, 예제 코드 |
| **Hooks** | 반복 검증 자동화 | 품질 게이트 |
| **Commands** | 반복 절차 추상화 | 워크플로우 템플릿 |

---

## 2026-04-13 기준 성숙도 점검

```
Memory     ██░░░░░░░░  (20%) ← 가장 약한 고리
Skills     ██████░░░░  (60%) ← auth/observability/notification 공백
Commands   ██████░░░░  (60%) ← deploy/logs 없음
Hooks      ████████░░  (80%) ← commit 메시지 검증 없음
```

### 발견된 공백

**Memory 공백**
- feedback 타입 메모리 거의 없음
- 세션에서 교정된 패턴이 대화 종료 후 소멸
- infra_migration.md 1개가 전부

**Skills 공백**
- `/auth` 없음 — JWT, @UserId, Kakao/Apple OAuth 패턴
- `/observability` 없음 — Sentry, Micrometer, OTel 패턴
- `/notification` 없음 — FCM 발송, 트랜잭션-후-외부호출 규칙

**Commands 공백**
- `/deploy` 없음 — SSH + docker compose restart 매번 수동
- `/logs` 없음 — SSH + docker logs 매번 수동

**Hooks 공백**
- git commit 메시지 형식 검증 없음 (git-conventions skill이 있어도 강제 안 됨)

---

## 2026-04-13 적용 내역

### 추가된 Skills
- `.claude/skills/auth/SKILL.md` — JWT/OAuth/UserId 패턴
- `.claude/skills/observability/SKILL.md` — Sentry/Micrometer/OTel 패턴
- `.claude/skills/notification/SKILL.md` — FCM 발송 패턴

### 추가된 Commands
- `.claude/commands/deploy.md` — 인스턴스 서비스 재시작
- `.claude/commands/logs.md` — 인스턴스 로그 조회

### 추가된 Hooks
- `.claude/hooks/commit-msg-check.sh` — Conventional Commits 형식 검증
- `settings.json` PreToolUse(Bash) — git commit 시 자동 실행

---

## 피드백 메모리 운영 원칙

> 이 섹션이 없으면 컴파운딩 루프가 닫히지 않는다.

### 언제 memory에 기록하는가

1. **같은 실수를 두 번 교정받았을 때** → skill 또는 feedback memory로 결정화
2. **설계 결정을 내릴 때** → DECISIONS.md에 ADR 추가
3. **예상치 못한 제약을 발견했을 때** → 해당 skill에 "주의사항" 추가
4. **패턴이 기존 skill과 충돌할 때** → skill 수정 + 이유 기록

### 기록 형식 (feedback memory)

```markdown
---
type: feedback
date: YYYY-MM-DD
context: 어떤 작업 중 발생했는가
mistake: 무엇을 잘못했는가
correction: 올바른 패턴은 무엇인가
skill: 어느 스킬에 반영했는가 (없으면 새 스킬 필요)
---
```

### Skill 업데이트 기준

- 같은 영역에서 교정이 2회 이상 반복 → skill에 "❌ 하지 말 것 / ✅ 올바른 방법" 섹션 추가
- 새 라이브러리/패턴 도입 → 해당 skill에 예제 코드 추가

---

## 다음 점검 체크리스트

다음 주기적 점검 시 확인할 항목:

- [ ] 지난 세션에서 교정받은 패턴이 있는가? → skill 또는 memory에 반영
- [ ] 자주 SSH 접속하는 패턴이 있는가? → commands로 추상화
- [ ] 같은 hook 오류가 반복되는가? → hook 조건 개선
- [ ] 새 도메인이 추가됐는가? → 해당 skill 필요한지 검토

---

## 2026-04-21 Phase 2: 회고 루프 도입

### 배경
- Phase 1 ([PR #181](https://github.com/depromeet/ssolv-server/pull/181)): harness + 기본 인프라
- Phase 1.5 ([PR #182](https://github.com/depromeet/ssolv-server/pull/182)): frontmatter 기반 auto-discovery

여기까지는 "쌓이는 인프라(뼈대)"를 준비한 단계. 하지만 **세션이 끝나면 관찰이 휘발**되는 구조 — 매 세션 교정·발견이 다음 세션에 전달되지 않음.

Phase 2는 **세션 회고 루프**를 추가해, 매 세션 종료 시 관찰이 memory / CLAUDE.md / thoughts/ / skills로 분배되도록 한다. 이로써 다음 세션은 "맨땅"이 아니라 "축적된 컨텍스트 위"에서 시작된다.

### 추가된 것
- `.claude/commands/retro.md` — 세션 회고 엔진
- `thoughts/retros/` — 세션별 회고록 (git 커밋)
- `thoughts/learning/resources.md` — 학습자료 누적 허브 (git 커밋)
- `CLAUDE.local.md` — 민감 정보(SSH 키, 인스턴스 IP, RDS 엔드포인트) 분리 (git-ignored)
- `CLAUDE.md` 정리 — 팀 공유 규칙만 남고 민감 정보는 로컬 파일 참조로 변경
- `.gitignore` — `CLAUDE.local.md` 추가

### 실행 원칙 (결정사항)

Phase 2 설계 시 4가지 결정사항을 사용자와 합의했다:

- **(a) `thoughts/` git 커밋함.** 팀 공유 + 미래 본인에게 공유. 민감 내용은 어차피 `CLAUDE.local.md` / memory로 분리되므로 회고록에 쓰이지 않음.
- **(b) `/retro`는 수동 호출 + Claude가 세션 중 리마인드 제안.** 자동 Stop hook은 노이즈 누적 위험이 커서 보류. 대신 리마인더 기준을 커맨드 문서에 명시.
- **(c) 신규 skill은 `skill-creator`로 자동 생성.** `find-skills`가 빈 결과 반환하면 바로 초안 생성. 이름·description은 사용자 확인 후.
- **(d) `CLAUDE.md` 수정은 diff 제안 → 사용자 승인 → Edit.** 팀 공유 파일이므로 자동 쓰기 금지. `CLAUDE.local.md`는 직접 Edit 허용.

### 참고자료
- [진정한 Claude Code 사용법 — Dr. Dan](https://babelai.tistory.com/42): 4단계 워크플로(research/plan/implement/validate) + `thoughts/` 구조 제안. 이 프로젝트엔 **회고 루프만 선별 도입**. 이유: token 비용, ssolv 규모 대비 4단계 워크플로는 오버킬. 필요 시 Phase 3에서 대형 작업 전용으로 재검토.
- humanlayer 방식의 `thoughts/{personal,shared,searchable}` 3-tier는 도입 안 함 (팀 2~3명에 과잉 구조).

### 다음 Phase 후보 (미정 · 필요 시 재검토)
- **Phase 3-a**: 세션 간 retro 상호 참조 — 누적된 회고록을 분석해 "반복된 실수 Top N" 추출 후 skill 고도화
- **Phase 3-b**: Plan 기반 워크플로 선별 도입 — 대형 리팩터링/신규 도메인 추가 시 research→plan→implement→validate 4단계 적용
- **Phase 3-c**: 커스텀 sub-agent 도입 — 현재는 built-in Explore로 충분하다 판단, 보류
