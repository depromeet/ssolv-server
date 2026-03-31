
## Infrastructure

```
[ Instance A ] t3.micro / ap-northeast-2a / EIP: 3.34.32.206
  ├── nginx (80/443, Let's Encrypt)
  └── app-server (Spring Boot, -Xmx400m)

[ Instance B ] t3.small / ap-northeast-2c / EIP: 52.79.62.33
  ├── nginx (80/443 — api + registry)
  ├── app-server (Spring Boot, -Xmx400m)
  ├── redis:7-alpine
  ├── registry (registry.ssolv.site)
  └── alloy + node/nginx/redis-exporter

[ RDS ] MySQL 8.0.43 / db.t3.micro / private subnet
[ Route53 ] api.ssolv.site — Multivalue Answer + 헬스체크 기반 failover
```

**IaC**: Terraform (`terraform/`) — `app_instance_count` 변수로 단일↔멀티 서버 전환

```bash
cd terraform
terraform plan
terraform apply
```

> 인프라 설계 배경 및 의사결정: `.claude/infra/DECISIONS.md`

---

## Tech Stack

| Category | Stack |
|---|---|
| Language | Kotlin 1.9.25, Java 21 |
| Framework | Spring Boot 3.4.9, Spring Cloud 2024.0.1 |
| Database | MySQL, Redis 7 |
| ORM | Spring Data JPA / Hibernate, Kotlin-JDSL 3.8.0 |
| Auth | JWT, OAuth2 (Kakao, Apple) |
| HTTP Client | Ktor Client 2.3.12 |
| Push | Firebase FCM |
| Observability | Micrometer, OpenTelemetry, Sentry, Prometheus |
| Build | Gradle 8 (Kotlin DSL), Jib |

## Multi-Modules

```
ssolv-server
├── ssolv-api-core        # 메인 API 서버 (인증, 미팅, 설문, 알림)
├── ssolv-api-place       # 장소 추천 서비스 (Google Places API 연동)
├── ssolv-api-common      # 공통 컨트롤러, 보안, 테스트 유틸
├── ssolv-domain          # 도메인 모델 및 Repository 인터페이스
├── ssolv-infrastructure  # JPA Entity, 외부 API 클라이언트, DB 어댑터
├── ssolv-global-utils    # 공통 응답/예외, Jackson 설정
└── ssolv-batch           # Redis Stream 기반 비동기 배치 처리
```

**의존 방향**
```
ssolv-api-core  ──┐
                  ├──▶ ssolv-api-common ──▶ ssolv-domain ──▶ ssolv-global-utils
ssolv-api-place ──┘                     ──▶ ssolv-infrastructure
```

## Getting Started

```bash
# 인프라 실행 (Redis)
docker compose -f docker-compose.prod.yml up -d redis

# API 서버 실행
./gradlew :ssolv-api-core:bootRun    # http://localhost:8080
./gradlew :ssolv-api-place:bootRun   # http://localhost:8081
```

## Commands

```bash
./gradlew build -x test                          # 빌드
./gradlew test                                   # 전체 테스트
./gradlew test jacocoTestReport --stacktrace     # 테스트 + 커버리지
./gradlew :ssolv-api-core:test --tests "org.depromeet.team3.SomeTest"  # 단일 테스트
```
