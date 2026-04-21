# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

**ssolv** is a meeting place recommendation platform API built with Kotlin/Spring Boot 3, MySQL, and Redis. It helps users coordinate meeting locations by aggregating survey responses and recommending optimal places via Google Places API.

## Essential Commands

```bash
./gradlew installGitHooks      # (최초 1회) 로컬 git pre-commit / pre-push 설치
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

Phase 1 정책: **ktlint는 리포팅 전용 (baseline 위반 때문), 테스트는 harness 경로에서 실패 전파.**

- **pre-commit**: `ktlintCheck` (~3초). 리포팅만 — 스타일 위반이 있어도 커밋 차단 없음 (`ignoreFailures=true`). 플러그인/리포팅 자체 오류 시에만 차단.
- **pre-push**: `./gradlew harness` (ktlint + 전체 테스트). **ktlint는 리포팅만, 테스트는 실패 시 푸시 차단** (`Test.ignoreFailures = !isHarness`).
- **CI**: PR 생성 시 ktlintCheck + build + test + SonarCloud 실행 (ktlint 위반은 차단하지 않음).
- **긴급 우회**: `git push --no-verify` (권장하지 않음).

설치: `./gradlew installGitHooks` (repo clone 후 1회).

### ktlint 정책
- ktlint는 **항상 리포팅 전용** (`ignoreFailures=true`). CI(PR)에서 검증하므로 로컬 push 블로킹 불필요.
- 새 코드 작성 시 수동으로 `./gradlew ktlintFormat` 실행 권장

## Skills (auto-discovery)

모든 skill은 `.claude/skills/<name>/SKILL.md`에 frontmatter `description`을 가지며, Claude는 현재 작업 컨텍스트와 description을 매칭하여 관련 skill을 **탐색**합니다. 아래는 수동 확인용 요약표입니다 — 작업 성격이 일치하면 해당 skill이 탐색 후보에 올라옵니다.

> ⚠️ **중요 — "탐색" ≠ "자동 로드"**: description 매칭으로 후보가 보인다고 해서 skill 본문이 컨텍스트에 자동 주입되지 않습니다. Claude는 git/PR/배포 같은 규칙성이 강한 작업을 **시작하기 전에 반드시 `Skill` 도구로 해당 skill 본문을 로드**해야 하며, description만 보고 규칙을 추측하지 말아야 합니다 (과잉 일반화로 프로젝트 컨벤션을 위반한 사례 있음 — PR #183 참조).

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

> description이 없던 과거 구조에서는 `/skill-name` 수동 호출에 의존했으나, 현재는 `ls .claude/skills/*/SKILL.md`로 전부 frontmatter를 확인할 수 있습니다. 새 skill 추가 시 반드시 `name` + `description` frontmatter를 포함하세요 (설명이 구체적일수록 트리거 정확도 ↑).

## Critical Rules

**Never:** Import across modules in the wrong direction (see architecture skill). Cross-domain communication must go through repository interfaces.

**Always:** Wrap responses in `DpmApiResponse`, use `DpmException` subclasses with `ErrorCode` for errors, use `suspend fun` for service methods, add `@Operation`/`@Tag` Swagger docs on every endpoint, use `@UserId` custom annotation (not `@AuthenticationPrincipal` directly).

## Code Standards

Use `operator fun invoke(...)` pattern for single-responsibility services. Match existing coroutine patterns (`withContext(Dispatchers.IO)` for blocking I/O). Use `@ConfigurationProperties` for new configuration groups — never inject properties directly with `@Value`.

## Tech Foundation

Kotlin 1.9.25 + Java 21, Spring Boot 3.4.9, JPA/Hibernate for persistence, Kotlin-JDSL 3.8.0 for complex queries, JWT + OAuth2 (Kakao, Apple) for auth, Ktor Client for external HTTP (Google Places, OAuth), Firebase FCM for push notifications, Redis for caching and async streams, Micrometer + OpenTelemetry + Sentry for observability.

## Architecture

See `/architecture` skill for full module dependency graph and layer rules.

## CI/CD

- **CI** (`.github/workflows/ci-test.yml`): triggered on PRs to `dev`; runs build + tests + Jacoco
- **CD** (`.github/workflows/cd-deploy.yml`): triggered on push to `main`; builds Jib images → deploys to EC2 via SSH

## Language Conventions

- **Commit messages**: 영문만 (Conventional Commits 형식, `commit-msg-check.sh` hook이 강제).
- **PR 본문·이슈 본문**: 한국어 (`.github/PULL_REQUEST_TEMPLATE.md` 및 `.github/ISSUE_TEMPLATE/*.md` 템플릿 준수).
- **CLAUDE.md·skill 문서 본문**: 한국어 중심, 코드·명령·프레임워크명·어노테이션은 영문 유지.
- **코드 주석**: 원칙적으로 영문. 한국어 비즈니스 용어가 꼭 필요한 경우만 예외.

## Post-Task Follow-up Guidelines

After completing a commit/push, only surface follow-up actions when something **cannot be handled by CI/CD alone**.
If CI/CD covers it automatically, finish without further comment.

Cases that require manual follow-up:
- CD uses `--no-deps` to restart a specific service — other services may also need restarting
- A newly added service is not yet in the CD script — requires manual `docker compose up`
- `.env` values need to be added or changed
- `terraform apply` is required
- DB migrations or other manual operations are needed

## Production Server SSH Access

실제 접속 자격증명(SSH 키 경로, 인스턴스 Public/Private IP, RDS 엔드포인트, 공통 SSH 명령 예시)은 **`CLAUDE.local.md`** 에 있습니다 — 개인 전용, git-ignored.

Claude Code는 `CLAUDE.md`와 `CLAUDE.local.md`를 모두 자동 로드하므로, 운영 작업 시 로컬 파일의 정보를 그대로 사용하세요. 팀원에 따라 SSH 키 경로가 다를 수 있으니, 팀에 합류한 새 멤버는 본인 환경에 맞춰 `CLAUDE.local.md`를 생성해야 합니다.

## Ongoing Work

AWS account migration + multi-server migration complete.
Workload and progress: `.claude/infra/WORKFLOW.md`
Infrastructure decision records: `.claude/infra/DECISIONS.md`

## Terraform Authoring Rules (IaC)

> ssolv infrastructure: **EC2 + Elastic IP + RDS (MySQL) + Route53** only.
> Do NOT create resources for ALB, S3, ElastiCache, or CloudFront.
> Route53 is used exclusively for health-check-based DNS failover (Multivalue Answer routing).

### Required Settings per Resource

**EC2 (`aws_instance`)**
- Instance A: `instance_type = "t3.micro"` (nginx + app-server only, JVM `-Xmx400m`)
- Instance B: `instance_type = "t3.small"` (app-server + redis + registry + alloy + exporters)
- `http_tokens = "required"` — enforce IMDSv2 (security requirement)
- `encrypted = true` — encrypt EBS root volume
- `instance_type` and `ami` must be variables — no hardcoding
- Instance count must be controlled solely via the `app_instance_count` variable

**Elastic IP (`aws_eip`)**
- Associate with EC2 instances via `aws_eip_association`
- EIP must be a separate resource from EC2 (for reusability)

**RDS (`aws_db_instance`)**
- `engine = "mysql"`, `engine_version = "8.0.43"` — fixed
- `publicly_accessible = false` — no public access
- `storage_encrypted = true`
- `deletion_protection = true`
- `backup_retention_period >= 1`
- `db_subnet_group_name` must use a subnet group composed of private subnets only

**Security Groups (`aws_security_group`)**
- Ports 3306 (MySQL) and 6379 (Redis) must use `source_security_group_id` referencing the EC2 SG — no direct CIDR
- Egress: allow `0.0.0.0/0` (intentional)
- Inbound `0.0.0.0/0` allowed only on ports 80, 443, 22

**General**
- All resources must include a `tags` block: `{ Project = "ssolv", ManagedBy = "terraform" }`
- Sensitive values (passwords, keys) must use `var` or AWS Secrets Manager — never hardcode

### Automated Audit
- PostToolUse Hook runs a security audit automatically when any `.tf` file is modified
- On-demand full audit: `/iac-audit` slash command
- New infrastructure decisions must be recorded as ADRs in `.claude/infra/DECISIONS.md`
