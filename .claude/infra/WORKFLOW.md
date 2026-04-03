# ssolv 인프라 마이그레이션 워크로드

> 목표: 단일 EC2 → 멀티 서버 + 신규 AWS 계정으로 Terraform 기반 마이그레이션
> 작성일: 2026-03-29 / 완료일: 2026-03-31

---

## 구 아키텍처 (마이그레이션 전)

```
[ EC2 단일 서버 ] ap-northeast-2 / t3.small / 30GB gp3
  ├── nginx-app (port 80) — 로드밸런서
  ├── app-server-1 (768MB, Spring Boot)
  ├── app-server-2 (768MB, Spring Boot)
  ├── redis:7-alpine (100MB max)
  ├── alloy + node/nginx/redis-exporter
  └── nginx-cicd (443) + registry (registry.ssolv.site)

[ RDS ] MySQL 8.0.43 / db.t3.micro / 20GB gp3 / 단일 AZ (ap-northeast-2b)
[ CloudFront ] → 이관 후 제거 완료
```

### 구 계정 네트워크 상세 (ssolv.yaml 분석)

```
VPC: vpc-0619f2e27641372f4 / 10.0.0.0/16 (depromeet)
  ├── Public Subnet:    10.0.0.0/20   ap-northeast-2a  (depromeet-public-subnet)
  ├── Private Subnet:   10.0.128.0/20 ap-northeast-2a  (depromeet-private-subnet)
  └── Private Subnet 2: 10.0.144.0/20 ap-northeast-2b  (depromeet-private-subnet-2)

EC2:
  ├── Instance: t3.small / AMI: ami-010be25c3775061c9
  ├── Private IP: 10.0.14.101 (Public Subnet)
  ├── EIP: 13.125.182.175  ← 구 계정, 이미 정리됨
  ├── Volume: 30GB gp3 (iops 3000)
  └── Security Group (depromeet-security-group):
      인바운드: 22, 80, 443, 900, 3306, 4430, 8080 (0.0.0.0/0)

RDS:
  ├── DB명: depromeet / User: depromeet
  ├── AZ: ap-northeast-2b (Private Subnet)
  ├── 퍼블릭 접근: 불가
  └── Security Group: EC2에서만 3306 접근 허용

SSH Key: depromeet-secret (RSA) → 신규 계정에서도 동일 키 재사용
```

## 현재 아키텍처 (마이그레이션 완료)

```
[ 인스턴스 A ] t3.micro / ap-northeast-2a / EIP: 3.34.32.206
  ├── nginx (80/443, Let's Encrypt)
  └── app-server (Spring Boot, -Xms128m -Xmx400m)

[ 인스턴스 B ] t3.small / ap-northeast-2c / EIP: 52.79.62.33
  ├── nginx (80/443, Let's Encrypt — api + registry 동시 처리)
  ├── app-server (Spring Boot, -Xms128m -Xmx400m)
  ├── redis:7-alpine (150MB limit)
  ├── registry (registry.ssolv.site)
  └── alloy + node/nginx/redis-exporter

[ RDS ] MySQL 8.0.43 / db.t3.micro
  └── ssolv-mysql.cvosykk4qy21.ap-northeast-2.rds.amazonaws.com

[ Route53 ] api.ssolv.site — Multivalue Answer (A + B 헬스체크 연동)
  ├── A: 3.34.32.206 (인스턴스 A)
  └── B: 52.79.62.33 (인스턴스 B)
```

### 신규 계정 네트워크 상세

```
VPC: 10.1.0.0/16 (ssolv-vpc)
  ├── Public Subnet A:  10.1.0.0/24  ap-northeast-2a  (인스턴스 A)
  ├── Public Subnet C:  10.1.1.0/24  ap-northeast-2c  (인스턴스 B)
  ├── Private Subnet A: 10.1.128.0/24 ap-northeast-2a (RDS)
  └── Private Subnet B: 10.1.129.0/24 ap-northeast-2b (RDS 멀티AZ 대비)

인스턴스 A: t3.micro / Private IP: 10.1.0.43
인스턴스 B: t3.small / Private IP: 10.1.1.160

Security Group (ec2):
  인바운드: 22, 80, 443 (0.0.0.0/0) / 8080, 6379, 4317-4318, 5000 (self)
Security Group (rds):
  인바운드: 3306 (ec2 보안그룹만)
```

---

## 작업 목록

### Phase 0: 준비
- [x] EC2에 Claude Code 설치
- [x] ssolv.yaml CloudFormation 템플릿 확보 및 분석
- [x] 신규 AWS 계정 생성 완료 (IAM 미사용 — 루트 Access Key 방식)
- [x] Terraform 로컬 환경 설치
- [x] 신규 계정 루트 Access Key 발급 및 로컬 환경변수 설정

### Phase 1: Terraform 기반 설계
- [x] `modules/network` — VPC, 서브넷, IGW, 보안그룹
- [x] `modules/compute` — EC2 A(t3.micro) + B(t3.small), EIP, 키페어
- [x] `modules/database` — RDS MySQL 8.0.43
- [x] `modules/dns` — Route53 Hosted Zone + Multivalue Answer + 헬스체크
- [x] `terraform/main.tf`, `variables.tf`, `outputs.tf`
- [x] `terraform plan` 통과 — 에러 없음
- [x] `docker-compose.instance-a.yml` — nginx(HTTPS) + app-server(Xmx400m)
- [x] `docker-compose.instance-b.yml` — app-server + redis + registry + nginx + monitoring
- [x] `nginx-app-instance-a.conf` — 인스턴스 A 전용 (Route53이 부하분산 담당)
- [x] `nginx-instance-b.conf` — api + registry 통합 처리
- [x] `alloy-config.alloy` — 멀티서버 메트릭 수집 (instance-a/b 레이블 분리)

### Phase 2: 신규 계정 인프라 구축
- [x] `terraform apply` 완료
- [x] outputs 확인: EIP A(`3.34.32.206`) / B(`52.79.62.33`), B 사설 IP(`10.1.1.160`), RDS 엔드포인트

### Phase 3: 앱 배포 및 검증
- [x] docker, docker-compose 설치 (양 인스턴스)
- [x] .env 파일 배포
  - 공통: `PROD_DB_ENDPOINT`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD`
  - 인스턴스 A: `INSTANCE_B_PRIVATE_IP=10.1.1.160`
  - 인스턴스 B: `INSTANCE_A_PRIVATE_IP=10.1.0.43`
- [x] docker network 생성 (ssolv_prod_network, ssolv_cicd_network, ssolv_monitoring_network)
- [x] certbot DNS-01 챌린지로 SSL 인증서 발급
  - 인스턴스 A: `api.ssolv.site`
  - 인스턴스 B: `api.ssolv.site` + `registry.ssolv.site`
- [x] docker compose up (양 인스턴스)
- [x] `curl https://api.ssolv.site/actuator/health` 정상 응답 확인

### Phase 4: CD 파이프라인 업데이트
- [x] GitHub Actions secrets 신규 계정용으로 교체
  - `EC2_HOST` → `3.34.32.206`
  - `EC2_HOST_B` → `52.79.62.33`
  - `EC2_SSH_KEY` → depromeet-secret.pem 내용
  - `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`
- [x] 멀티 서버 rolling update 배포 전략 (A 먼저 → B)
- [x] `cd-deploy.yml` 업데이트 완료
- [x] CD 파이프라인 실제 트리거 후 배포 확인

### Phase 5: DNS 컷오버
- [x] 가비아 네임서버 → Route53 NS 4개로 변경
- [x] Route53 `api.ssolv.site` Multivalue Answer 등록 (A + B)
- [x] Route53 헬스체크 A/B 모두 Healthy 확인
- [x] `dig api.ssolv.site` — 두 IP 정상 응답 확인
- [ ] CloudFront 삭제 (구 계정 — 수동 작업 필요)
- [ ] 구 EC2 (`13.125.182.175`) 종료 (구 계정 — 수동 작업 필요)
- [x] 모니터링 정상 수집 확인 (Grafana, Sentry)

---

---

## Automation

The following automated tasks are active in production. No manual intervention needed unless noted.

| # | Automation | Mechanism | Schedule | Target |
|---|-----------|-----------|----------|--------|
| 1 | CD failure diagnostics | GitHub Actions `diagnose-on-failure` job | On deploy failure | A or B (whichever failed) |
| 2 | Health check + auto-restart | `health-recovery.sh` via crontab | Every 5 min | Instance A + B |
| 3 | Memory monitoring (t3.micro) | `memory-check.sh` via crontab | Every 5 min | Instance A only |
| 4 | Sentry issue analysis | Claude scheduled task | Daily 09:00 | ssolv Sentry project |
| 5 | Terraform drift detection | Claude scheduled task | Every Monday 10:00 | terraform/ |

### Log locations (on instances)
- `/var/log/ssolv-health-recovery.log` — health check events and restarts
- `/var/log/ssolv-memory-check.log` — memory alerts and diagnostics

### Scheduled task management
- View/run: Claude Code sidebar → "Scheduled" section
- Task files: `~/.claude/scheduled-tasks/{task-id}/SKILL.md`
- First run: click "Run now" in sidebar to pre-approve tool permissions

---

## 단일↔멀티 전환 전략

```hcl
# terraform.tfvars
app_instance_count = 2  # 1로 바꾸면 단일 서버(B만)로 복귀
```

---

## 주요 결정 사항

| 항목 | 결정 |
|------|------|
| CloudFront | 신규 계정에서 제거 |
| ALB | 사용 안 함 (Nginx가 앞단, Route53이 부하분산) |
| Redis | ElastiCache 아닌 EC2 컨테이너 유지 (인스턴스 B) |
| Registry | 자체 registry 유지 (registry.ssolv.site, 인스턴스 B) |
| IaC 도구 | Terraform (state: 로컬 파일, `.gitignore` 처리됨) |
| 인스턴스 타입 | A: t3.micro / B: t3.small |
| IAM | 미사용 — 루트 Access Key 방식으로 Terraform 인증 |
| RDS 이전 | 스냅샷 없음 — 신규 RDS 생성 후 코드 레벨 초기화 (SurveyCategoryInitializer, StationInitializer) |
| DNS | Route53 Multivalue Answer + 헬스체크 (가비아 NS → Route53 위임) |
| HTTPS | Let's Encrypt DNS-01 챌린지 (certbot-dns-route53) |
| 부하분산 | Route53 Multivalue Answer — A/B 헬스체크 기반 자동 failover |

---

## 세션 간 공유 메모

- SSH 키: `~/dpm-server/depromeet-secret.pem` (A/B 공통)
- 인스턴스 A EIP: `3.34.32.206` / 사설: `10.1.0.43`
- 인스턴스 B EIP: `52.79.62.33` / 사설: `10.1.1.160`
- RDS 엔드포인트: `ssolv-mysql.cvosykk4qy21.ap-northeast-2.rds.amazonaws.com`
- RDS 접근: SSH 터널링 필요 (private subnet) — 터널 호스트: `52.79.62.33`
- CD 파이프라인: `.github/workflows/cd-deploy.yml` (rolling update, A→B 순서)
- 모니터링: Alloy on 인스턴스 B (`docker-compose.instance-b.yml`)
