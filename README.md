
<img width="100%" alt="image" src="https://github.com/user-attachments/assets/b721f491-c663-4ec4-88a2-37292b326327" />


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

**IaC**: Terraform (`deploy/terraform/`)

---


## Claude Architecture

Claude Code를 단순 코드 생성 도구가 아닌 **팀의 코드 품질 루프에 통합된 에이전트**로 운영합니다.

<img width="100%" alt="claude architecture" src="docs/assets/claude-architecture.png" />

| 레이어 | 구성 요소 | 역할 |
|---|---|---|
| **기준/규칙** | `CLAUDE.md` | 프로젝트 컨벤션, 금지 패턴, 아키텍처 규칙을 Claude가 참조 |
| **실행** | Skills / Commands | 도메인별 작업 단위 — API 패턴, 테스트, 인증, 배포 등 |
| **로컬 Harness** | pre-commit / pre-push Hook | ktlint 검증 + 전체 테스트. 위반 시 푸시 차단 |
| **Remote Harness** | CI (GitHub Actions) | Lint · Build · Test · SonarQube 분석 |
| **Fix-Feedback Loop** | Sonar → Issue → PR | SonarQube 이슈를 GitHub Issue로 수집 → 담당자 승인 → Claude가 Worktree별 자동 수정 PR 생성 |
| **Compounding Layer** | Memory · Skill · Hook · Command | 유의미한 결과(실수 포함)를 축적해 다음 판단에 반영 |

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
