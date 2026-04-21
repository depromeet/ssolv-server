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

### ktlint baseline 상태 (2026-04-21 기준)
- 현재 위반 2,708건 / 269개 파일 (대부분 자동 수정 가능)
- Phase 1: ktlint `ignoreFailures=true` 고정 (baseline 고려) / 테스트는 `!isHarness`
- **후속 cleanup PR**에서 `./gradlew ktlintFormat`으로 일괄 정리 후 ktlint도 `!isHarness`로 전환 예정
- 새 코드 작성 시 수동으로 `./gradlew ktlintFormat` 실행 권장

## Mandatory Pre-Task Protocol

Load relevant skills before coding:
- `/api-patterns` for controller/endpoint work
- `/architecture` for design decisions or adding new domains
- `/testing` for writing or modifying tests
- `/async-processing` for Redis Stream or async task work
- `/git-conventions` for commits, branches, PRs, and issues
- `/auth` for JWT, OAuth, @UserId patterns
- `/observability` for Sentry, Micrometer, OpenTelemetry patterns
- `/notification` for FCM push notification patterns
- `/place` for ssolv-api-place module (Google Places, Redis ranking, SSE like)
- `/domain-model` for Domain/Entity separation, Mapper, JDSL, ErrorCode, @ConfigurationProperties
- `/batch` for scheduler, CoroutineWatchdogManager, dead-letter patterns

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

## Commit Message Language

All commit messages must be written in **English**. No Korean in commit messages.

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

For production operations (checking server status, restarting containers, reading logs, editing `.env`, etc.), **SSH in directly without asking the user**.

```
SSH Key  : /Users/parkmineum/.ssh/gdg-cicd-key.pem
User     : ubuntu

Instance A (t3.micro  — nginx + app-server)
  Public IP  : 3.34.32.206
  Private IP : 10.1.0.43 (ap-northeast-2a / 10.1.0.0/24)

Instance B (t3.small — app-server + redis + registry + monitoring)
  Public IP  : 52.79.62.33
  Private IP : 10.1.1.160 (ap-northeast-2c / 10.1.1.0/24)

RDS Endpoint : ssolv-mysql.cvosykk4qy21.ap-northeast-2.rds.amazonaws.com
```

**Common SSH patterns:**
```bash
# Connect to Instance A / B
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33

# Restart a container (Instance B example)
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33 "
  cd ~/17th-team3-Server
  docker compose --project-directory ~/17th-team3-Server -f deploy/docker-compose.instance-b.yml up -d --no-deps --force-recreate <service>
"

# Tail logs
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 "docker logs app-server --tail 100"
```

> If IPs have changed, run `cd deploy/terraform && terraform output` to get the latest values first.

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
