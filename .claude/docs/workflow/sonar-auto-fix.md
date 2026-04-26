# Sonar Auto-Fix Workflow

SonarCloud 결과를 GitHub Issue 체크리스트로 큐잉하고, 승인된 그룹만 Claude 가
자동 수정하여 PR 을 여는 두 번째 컴파운딩 루프.

전체 설계 배경: `.claude/docs/workflow/harness-and-compounding-design.md` 의 후속.

## 구성 요소

| 파일 | 역할 |
|---|---|
| `.claude/sonar-allowlist.yml` | 룰별 자동화 안전성 분류 (`auto_safe` / `auto_with_review` / `manual_only`) + 공통 필터 |
| `.claude/scripts/sonar-fetch.py` | SonarCloud API 조회 + 필터 + (rule × module) 그룹핑 + Markdown/JSON 렌더 |
| `.github/workflows/sonar-issue.yml` | 매주 월요일 09:00 KST · GitHub Issue 갱신 |
| `.github/workflows/sonar-fix.yml` | `/sonar-fix run` 코멘트 → 그룹별 PR 자동 생성 (Claude Code Action) |

## 분류 규칙

- **🟢 auto_safe**: 기계적 치환·미사용 제거 등. PR 자동 머지까지.
- **🟡 auto_with_review**: 의미 변경 가능성 있음. PR 만 자동 생성, 사람 리뷰 후 머지.
- **🔴 manual_only**: 도메인 맥락 필수. Issue 에 정보만 노출, 작업 안 함.

추가 안전장치 (`sonar-fetch.py` 가 자동 적용):
- effort > 30min → 제외
- 생성 후 7일 미만 → 제외 (작성자 직접 수정 기회)
- assignee 있으면 → 제외
- 테스트 코드 (`/test/`, `/testFixtures/`) → 제외
- BUG 타입은 `auto_safe` 룰이라도 강제 `auto_with_review` 로 격상

## 작업 단위

PR 1개 = `(분류 × 룰 × 모듈)` 1조합. 같은 파일을 건드리는 그룹은 직렬 처리.

## 활성화 절차

1. **로컬 테스트** (필수 secret 없이도 작동)
   ```bash
   curl -s "https://sonarcloud.io/api/issues/search?componentKeys=parkmineum_17th-team3-server&types=CODE_SMELL,BUG&severities=MAJOR,MINOR&statuses=OPEN&ps=500" > /tmp/sonar.json
   python3 .claude/scripts/sonar-fetch.py --input /tmp/sonar.json --output markdown
   ```

2. **Issue 자동 생성** (`sonar-issue.yml`)
   - `SONAR_TOKEN` repo secret 등록 (private 프로젝트만 필요)
   - 첫 실행: `gh workflow run sonar-issue.yml`

3. **PR 자동 생성** (`sonar-fix.yml`) — 기본은 dry-run
   - `ANTHROPIC_API_KEY` secret 등록
   - `SONAR_FIX_ACTIVE` repo variable 을 `true` 로 설정
   - 그 전까지는 코멘트가 와도 dry-run notice 만 남기고 PR 안 만듦

## 운영 루프

```
월요일 09:00 KST
  ↓
sonar-issue.yml → SonarCloud API → 그룹핑 → Issue 갱신
  ↓
담당자 체크박스 선택 → 코멘트 `/sonar-fix run`
  ↓
sonar-fix.yml: 권한 체크 → 매트릭스 빌드 → 그룹별 작업
  ├─ Claude 가 파일만 수정 → 로컬 harness 실행
  ├─ ✅ 성공: PR 생성 (🟢 auto-merge / 🟡 review-required)
  └─ ❌ 실패: Issue 에 escalate 코멘트
  ↓
PR 이 ci.yml 통과 → 머지 → 다음 사이클
```

## 컴파운딩 회수

같은 룰이 반복적으로 New Code 에 등장하면 → `.claude/hooks/` 차단 훅 초안 PR
을 별도로 제안한다. (`@Value` → `value-injection-check.sh` 승격 사례의 일반화)

## 알려진 한계

- SonarCloud API 의 `assignee` 가 GitHub 핸들과 달라 누가 잡았는지 자동 매핑 불가 — 현재는 unassigned 만 처리.
- `kotlin:S6518` 같은 룰은 `mutableMap` 의 `put`/`set` 의미 차이가 있어, 단순 치환이 아닌 경우 Claude 가 검출해야 함.
- `SONAR_FIX_ACTIVE=true` 로 켠 직후 첫 실행은 매뉴얼 트리거(`workflow_dispatch`)로 해보고 안전 확인 후 코멘트 트리거에 의존할 것.
