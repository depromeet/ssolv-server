
<img width="3072" height="1500" alt="image" src="https://github.com/user-attachments/assets/b721f491-c663-4ec4-88a2-37292b326327" />


## Tech Stack

| Category | Stack |
|---|---|
| Language | Kotlin 1.9.25, Java 21 |
| Framework | Spring Boot 3.4.9, Spring Cloud 2024.0.1 |
| Database | MySQL 8.0.43, Redis 7 |
| ORM | Spring Data JPA / Hibernate, Kotlin-JDSL 3.8.0 |
| Auth | JWT, OAuth2 (Kakao, Apple) |
| HTTP Client | Ktor Client 2.3.12 |
| Push | Firebase FCM |
| Observability | Micrometer, OpenTelemetry, Sentry, Prometheus |
| Build | Gradle 8 (Kotlin DSL), Jib |
| IaC | Terraform 1.x (EC2, RDS, EIP, Route53) |

---


## Infrastructure

<img width="1996" height="970" alt="infrastructure diagram" src="https://github.com/user-attachments/assets/7c6a6f0c-e5c3-42ec-818c-9830010502bb" />


```
[ Instance A ] ap-northeast-2a / EIP: 3.34.32.206
  ├── nginx (80/443, Let's Encrypt)
  └── app-server (Spring Boot, -Xmx400m)

[ Instance B ] ap-northeast-2c / EIP: 52.79.62.33
  ├── nginx (80/443 — api + registry)
  ├── app-server (Spring Boot, -Xmx400m)
  ├── redis:7-alpine
  ├── registry (registry.ssolv.site)
  └── alloy + node/nginx/redis-exporter

[ RDS ] MySQL 8.0.43 / db.t3.micro / private subnet
[ Route53 ] api.ssolv.site — Multivalue Answer + health-check based failover
```

**IaC**: Terraform (`terraform/`) — `app_instance_count` 변수로 단일↔멀티 서버 전환

```bash
cd terraform
terraform plan
terraform apply
```

> 인프라 설계 배경 및 의사결정: `.claude/infra/DECISIONS.md`

---


## CI/CD

| Workflow | Trigger | Description |
|---|---|---|
| **CI** | PR → `dev` | Build + Test + Jacoco coverage + SonarQube |
| **CD** | Push → `main` | Jib image build → Rolling deploy (A → B) |

**배포 흐름 (무중단 롤링)**
```
main push
  └── Jib build & push → registry.ssolv.site
        └── Deploy Instance A
              └── health check 통과 확인
                    └── Deploy Instance B
```

> Instance A를 먼저 배포하고 healthy 상태 확인 후 B를 배포합니다.
> Route53 Multivalue Answer가 A 헬스체크 실패를 감지하는 동안 B가 트래픽을 처리합니다.

---


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
                  ├──▶ ssolv-api-common ──▶ ssolv-domain        ──▶ ssolv-global-utils
ssolv-api-place ──┘                     ──▶ ssolv-infrastructure ──▶ ssolv-domain
```
