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

## Commit Message Language

All commit messages must be written in **English**. No Korean in commit messages.

## 후속 작업 안내 원칙

코드 변경 후 커밋/푸시 완료 시, **CI/CD만으로 반영되지 않는 작업이 있을 때만** 후속 작업을 안내한다.
CI/CD로 자동 반영되는 경우엔 별도 안내 없이 종료한다.

후속 작업이 필요한 대표 케이스:
- CD가 `--no-deps`로 특정 서비스만 재시작하기 때문에 다른 서비스도 재시작해야 할 때
- 서비스 최초 추가라 CD 스크립트에 없어서 수동 `docker compose up` 필요할 때
- `.env` 값 추가/변경이 필요할 때
- Terraform apply가 필요할 때
- DB 마이그레이션 등 수동 작업이 필요할 때

## 운영 서버 SSH 접속

운영 환경 작업(서버 상태 확인, 컨테이너 재시작, 로그 조회, .env 수정 등)이 필요하면 **사용자에게 묻지 말고 직접 SSH 접속해서 처리**한다.

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

**자주 쓰는 SSH 명령 패턴:**
```bash
# Instance A/B 접속
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33

# 컨테이너 재시작 (Instance B 예시)
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33 "
  cd ~/17th-team3-Server
  docker compose --project-directory ~/17th-team3-Server -f deploy/docker-compose.instance-b.yml up -d --no-deps --force-recreate <service>
"

# 로그 확인
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 "docker logs app-server --tail 100"
```

> IP가 바뀌었을 경우 `cd terraform && terraform output`으로 최신 IP를 먼저 확인한다.

## 진행 중인 작업

현재 AWS 계정 이전 + 멀티 서버 마이그레이션 진행 중.
워크로드 및 진행 상황: `.claude/infra/WORKFLOW.md`
인프라 의사결정 기록: `.claude/infra/DECISIONS.md`

## Terraform 작성 규칙 (IaD)

> ssolv 인프라 구성 요소: **EC2 + 탄력적 IP + RDS(MySQL) + Route53** 사용.
> ALB, S3, ElastiCache, CloudFront는 사용하지 않는다. 해당 리소스 코드를 생성하지 말 것.
> Route53은 헬스체크 기반 DNS Failover 용도로만 사용한다 (Multivalue Answer 라우팅).

### 리소스별 필수 설정

**EC2 (`aws_instance`)**
- 인스턴스 A: `instance_type = "t3.micro"` (nginx + app-server 전용, JVM `-Xmx400m`)
- 인스턴스 B: `instance_type = "t3.small"` (app-server + redis + registry + alloy + exporters)
- `http_tokens = "required"` — IMDSv2 강제 (보안 필수)
- `encrypted = true` — EBS 루트 볼륨 암호화
- `instance_type`과 `ami`는 변수로 분리, 하드코딩 금지
- EC2 수는 반드시 `app_instance_count` 변수로만 제어

**탄력적 IP (`aws_eip`)**
- EC2 인스턴스에 `aws_eip_association`으로 연결
- EIP는 EC2와 별도 리소스로 분리 (재사용 가능하도록)

**RDS (`aws_db_instance`)**
- `engine = "mysql"`, `engine_version = "8.0.43"` 고정
- `publicly_accessible = false` — 퍼블릭 접근 금지
- `storage_encrypted = true`
- `deletion_protection = true`
- `backup_retention_period >= 1`
- `db_subnet_group_name`은 반드시 private subnet으로 구성된 서브넷 그룹 사용

**보안그룹 (`aws_security_group`)**
- 3306(MySQL), 6379(Redis)는 `source_security_group_id`로 EC2 보안그룹만 허용 — CIDR 직접 지정 금지
- egress는 `0.0.0.0/0` 허용 (의도된 설정)
- 인바운드 0.0.0.0/0은 80, 443, 22만 허용

**공통**
- 모든 리소스에 `tags` 블록 필수: `{ Project = "ssolv", ManagedBy = "terraform" }`
- 민감 값(비밀번호, 키 등)은 `var` 또는 AWS Secrets Manager 참조 — 하드코딩 절대 금지

### 자동 감사
- `.tf` 파일 수정 시 PostToolUse Hook이 자동으로 보안 감사 실행
- 온디맨드 전체 감사: `/iac-audit` 슬래시 커맨드
- 새로운 인프라 결정은 `.claude/infra/DECISIONS.md`에 ADR 형식으로 기록
