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
