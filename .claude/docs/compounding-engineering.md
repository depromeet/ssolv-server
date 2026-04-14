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
