# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

**ssolv** is a meeting place recommendation platform API built with Kotlin/Spring Boot 3, MySQL, and Redis. It helps users coordinate meeting locations by aggregating survey responses and recommending optimal places via Google Places API.

## Essential Commands

```bash
./gradlew build -x test       # Compile project
./gradlew test                 # Execute test suite
./gradlew test jacocoTestReport --continue --stacktrace  # Tests + coverage report
./gradlew :ssolv-api-core:test --tests "org.depromeet.team3.SomeTest"  # Single test class
./gradlew :ssolv-api-place:test --tests "org.depromeet.team3.SomeTest"
```

## Mandatory Pre-Task Protocol

Load relevant skills before coding:
- `/api-patterns` for controller/endpoint work
- `/architecture` for design decisions or adding new domains
- `/testing` for writing or modifying tests
- `/async-processing` for Redis Stream or async task work
- `/git-conventions` for commits, branches, PRs, and issues

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

## 진행 중인 작업

현재 AWS 계정 이전 + 멀티 서버 마이그레이션 진행 중.
워크로드 및 진행 상황: `.claude/WORKFLOW.md`
인프라 의사결정 기록: `.claude/DECISIONS.md`

## IaC 보안 감사 규칙

`.tf` 파일 작성 또는 수정 시 아래 규칙을 **항상** 적용한다.

**자동 감사 항목:**
- RDS: `storage_encrypted = true`, `deletion_protection = true`, `backup_retention_period >= 1`, `publicly_accessible = false`
- EC2: IMDSv2 강제 (`http_tokens = "required"`)
- 보안그룹: 민감 포트(3306, 6379)는 EC2 보안그룹 참조만 허용, 0.0.0.0/0 금지
- 모든 리소스에 `tags` 블록 포함
- EBS: `encrypted = true`

**감사 제외:** 인바운드 0.0.0.0/0 (의도된 설정으로 경고 생략)

**온디맨드 전체 감사:** `/iac-audit` 슬래시 커맨드 실행
**의사결정 추가:** 새로운 인프라 결정은 `.claude/DECISIONS.md`에 ADR 형식으로 기록
