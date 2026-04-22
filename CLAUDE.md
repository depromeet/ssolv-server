# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

**ssolv** is a meeting place recommendation platform API built with Kotlin/Spring Boot 3, MySQL, and Redis. It helps users coordinate meeting locations by aggregating survey responses and recommending optimal places via Google Places API.

## Essential Commands

```bash
./gradlew installGitHooks      # (최초 1회) 로컬 git pre-commit + pre-push 설치
./gradlew build -x test        # Compile project
./gradlew test                 # Execute test suite
./gradlew harness              # ktlint + 전체 테스트 (pre-push와 동일)
./gradlew ktlintCheck          # 스타일 검증만
./gradlew ktlintFormat         # 스타일 자동 수정
./gradlew test jacocoTestReport --continue --stacktrace  # Tests + coverage report
./gradlew :ssolv-api-core:test --tests "org.depromeet.team3.SomeTest"  # Single test class
./gradlew :ssolv-api-place:test --tests "org.depromeet.team3.SomeTest"
```

## Harness (코드 품질 가드레일)

전체 설계와 훅 동작은 **[.claude/docs/workflow/harness-and-compounding-design.md](.claude/docs/workflow/harness-and-compounding-design.md)** 참조.

요약:

- **pre-commit**: staged `.kt`/`.kts` 에 `ktlintFormat` 자동 교정 + 재스테이지 (1~3초). 검증 아니라 교정만.
- **pre-push**: `./gradlew harness` — ktlint(리포팅) + 전체 테스트(실패 시 차단).
- **CI**: PR 생성 시 ktlintCheck + build + test + SonarCloud.
- **긴급 우회**: `git commit --no-verify` / `git push --no-verify` (권장하지 않음).
- **ktlint 정책**: 항상 `ignoreFailures=true` (리포팅 전용). CI 의 `ktlintCheck` 가 최종 게이트.

설치: `./gradlew installGitHooks` (repo clone 후 1회). Worktree 사용 시에도 한 번만 — git hooks는 main repo `.git/hooks/` 에 설치되어 모든 worktree가 공유함.

## Skills (auto-discovery)

모든 skill은 `.claude/skills/<name>/SKILL.md`에 frontmatter `description`을 가지며, Claude는 현재 작업 컨텍스트와 description을 매칭하여 관련 skill을 **탐색**합니다.

> ⚠️ **중요 — "탐색" ≠ "자동 로드"**: description 매칭으로 후보가 보여도 skill 본문은 자동 주입되지 않습니다. 규칙성이 강한 작업을 시작하기 전에 반드시 `Skill` 도구로 해당 skill 본문을 로드해야 하며, description만 보고 규칙을 추측하지 말아야 합니다 (PR #183 참조). 핵심 불변 규칙은 description이 아니라 **아래 Critical Rules + 훅 차단**으로 이중화되어 있습니다.

| Skill | 언제 쓰이나 |
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

## Critical Rules (훅이 차단하는 규칙)

아래 규칙은 `.claude/hooks/` 가 **exit 2 로 차단**합니다 — 실수로 작성해도 반영되지 않습니다.

- **모듈 import 방향**: `ssolv-api-core` ↔ `ssolv-api-place` 상호 참조 금지. `ssolv-domain` → infrastructure/JPA 금지.
- **`@Value` 직접 주입 금지**: `@ConfigurationProperties` 클래스 사용.
- **`@AuthenticationPrincipal` 직접 사용 금지**: `@UserId` 커스텀 어노테이션 사용.
- **컨트롤러 응답**: 모든 응답은 `DpmApiResponse<T>` 로 감쌀 것.
- **커밋 메시지**: Conventional Commits 형식 + 영문만.

차단 정책 기준: **아키텍처/보안 계약 위반 → 차단**, **문서 품질 미비(@Tag, @Operation 등) → 경고만**.

## Other Always-rules (코드 리뷰에서 확인)

훅이 강제하지 않는 코드 컨벤션. Controller 의 `@Tag` / `@Operation` 누락만 `controller-annotation-check.sh` 가 경고로 알려주고, 나머지는 리뷰/PR 에서 확인한다.

- `DpmException` 서브클래스 + `ErrorCode` 로 에러 처리
- 서비스 메서드는 `suspend fun`
- 모든 엔드포인트에 `@Operation` / `@Tag` Swagger 문서 (훅이 경고)
- `operator fun invoke(...)` 패턴을 단일 책임 서비스에 사용
- 블로킹 I/O는 `withContext(Dispatchers.IO)`

## Tech Foundation

Kotlin 1.9.25 + Java 21, Spring Boot 3.4.9, JPA/Hibernate, Kotlin-JDSL 3.8.0, JWT + OAuth2 (Kakao, Apple), Ktor Client (Google Places, OAuth), Firebase FCM, Redis, Micrometer + OpenTelemetry + Sentry.

## Architecture

See `/architecture` skill for full module dependency graph and layer rules.

## CI/CD

- **CI** (`.github/workflows/ci-test.yml`): PR to `dev` — build + tests + Jacoco
- **CD** (`.github/workflows/cd-deploy.yml`): push to `main` — Jib → EC2 via SSH

## Language Conventions

- **Commit messages**: 영문 (Conventional Commits, `commit-msg-check.sh` 훅이 강제)
- **PR·이슈 본문**: 한국어 (`.github/*_TEMPLATE` 준수)
- **CLAUDE.md·skill 문서**: 한국어 중심, 코드·명령·어노테이션은 영문
- **코드 주석**: 원칙적으로 영문. 한국어 비즈니스 용어가 꼭 필요한 경우만 예외

## Post-Task Follow-up Guidelines

커밋/푸시 후에는 **CI/CD가 처리할 수 없는 경우에만** 후속 조치를 언급한다.

수동 후속 필요:
- CD가 `--no-deps` 로 특정 서비스만 재시작 → 다른 서비스도 재시작 필요할 수 있음
- 새로 추가된 서비스가 CD 스크립트에 아직 없음 → 수동 `docker compose up`
- `.env` 값 추가·변경
- `terraform apply` 필요
- DB 마이그레이션·수동 작업 필요

## Production Server SSH Access

접속 자격증명(SSH 키 경로, 인스턴스 IP, RDS 엔드포인트)은 `CLAUDE.local.md` 에 있습니다 — 개인 전용, git-ignored.

팀원에 따라 SSH 키 경로가 다를 수 있으니, 새 멤버는 본인 환경에 맞춰 `CLAUDE.local.md` 를 생성합니다.

## Terraform (IaC)

ssolv 인프라 전체 규칙 + 감사 체크리스트는 **[`/iac-audit`](.claude/commands/iac-audit.md)** 커맨드 문서에 있습니다. `.tf` 저장 시 경량 감사는 `iac-security-check.sh` 훅이 자동 실행합니다. 새 인프라 결정은 `.claude/infra/DECISIONS.md` 에 ADR로 기록합니다.

## Historical Context

- `.claude/infra/DECISIONS.md` — 인프라 ADR (진행형)
- `.claude/infra/archive/` — 완료된 마이그레이션 로그
