# ssolv 인프라 마이그레이션 워크로드

> 목표: 단일 EC2 → 멀티 서버 + 신규 AWS 계정으로 Terraform 기반 마이그레이션
> 작성일: 2026-03-29

---

## 현재 아키텍처

```
[ EC2 단일 서버 ] ap-northeast-2 / t3.small / 30GB gp3
  ├── nginx-app (port 80) — 로드밸런서
  ├── app-server-1 (768MB, Spring Boot)
  ├── app-server-2 (768MB, Spring Boot)
  ├── redis:7-alpine (100MB max)
  ├── alloy + node/nginx/redis-exporter
  └── nginx-cicd (443) + registry (registry.ssolv.site)

[ RDS ] MySQL 8.0.43 / db.t3.micro / 20GB gp3 / 단일 AZ (ap-northeast-2b)
[ CloudFront ] → 이관 후 제거 예정
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
  ├── EIP: 13.125.182.175
  ├── Volume: 30GB gp3 (iops 3000)
  └── Security Group (depromeet-security-group):
      인바운드: 22, 80, 443, 900, 3306, 4430, 8080 (0.0.0.0/0)

RDS:
  ├── DB명: depromeet / User: depromeet
  ├── AZ: ap-northeast-2b (Private Subnet)
  ├── 퍼블릭 접근: 불가
  └── Security Group: EC2에서만 3306 접근 허용

IGW: depromeet-gateway
Route Tables: depromeet-public-routing / depromeet-private-routing
SSH Key: depromeet-secret (RSA)
```

## 목표 아키텍처

```
[ 인스턴스 A ] t3.micro / 1GB — 앱 서버 전용
  ├── nginx (앞단, 80/443)
  └── app-server (Spring Boot, -Xms128m -Xmx400m)

[ 인스턴스 B ] t3.small / 2GB — 앱 서버 + 인프라
  ├── nginx (앞단, 80/443)
  ├── app-server (Spring Boot)
  ├── redis:7-alpine (150MB limit)
  ├── registry (registry.ssolv.site)
  └── alloy + node/nginx/redis-exporter

[ RDS ] MySQL 8.0.43 / db.t3.micro — 신규 계정으로 스냅샷 이전
CloudFront 제거
```

---

## 작업 목록

### Phase 0: 준비
- [x] EC2에 Claude Code 설치 (`npm install -g @anthropic-ai/claude-code`)
- [x] ssolv.yaml CloudFormation 템플릿 확보 및 분석
- [x] 신규 AWS 계정 생성 완료 (IAM 미사용 — 루트 Access Key 방식)
- [ ] Terraform 로컬 환경 설치 (`brew install terraform`)
- [ ] 신규 계정 루트 Access Key 발급 및 로컬 환경변수 설정

### Phase 1: Terraform 기반 설계
- [x] `modules/network` — VPC, 서브넷, IGW, 보안그룹
- [x] `modules/compute` — EC2 A(t3.micro) + B(t3.small), EIP, 키페어
- [x] `modules/database` — RDS MySQL 8.0.43
- [x] `terraform/main.tf`, `variables.tf`, `outputs.tf`, `terraform.tfvars`
- [x] `terraform plan` 통과 — 20개 리소스, 에러 없음
- [x] `docker-compose.instance-a.yml` — nginx(HTTPS) + app-server(Xmx400m)
- [x] `docker-compose.instance-b.yml` — app-server + redis + registry + nginx-cicd + monitoring
- [x] `nginx-app-instance-a.conf.template` — envsubst로 INSTANCE_B_PRIVATE_IP 치환
- 참고: Redis, Registry는 EC2 내 컨테이너로 유지 (모듈 불필요)

### Phase 2: 신규 계정 인프라 구축
- [ ] `terraform apply` 실행 (cd terraform && terraform apply)
- [ ] outputs 확인: EIP A/B, B 사설 IP, RDS 엔드포인트

### Phase 3: 앱 배포 및 검증

**양 인스턴스 공통**
- [ ] docker, docker-compose 설치
- [ ] .env 파일 배포 (PROD_DB_ENDPOINT 신규 RDS 엔드포인트로 변경)
- [ ] ssolv-infrastructure repo clone 또는 파일 복사
- [ ] docker network create ssolv_prod_network

**인스턴스 B 먼저**
- [ ] docker network create ssolv_cicd_network ssolv_monitoring_network
- [ ] .htpasswd, firebase-service-account.json 배포
- [ ] certbot으로 registry.ssolv.site 인증서 발급 (가비아 DNS 변경 후)
- [ ] docker compose -f docker-compose.instance-b.yml up -d
- [ ] registry 헬스체크 확인 후 이미지 push

**인스턴스 A**
- [ ] .env에 INSTANCE_B_PRIVATE_IP 추가 (terraform output instance_b_private_ip)
- [ ] .env에 PROD_DB_ENDPOINT 추가
- [ ] nginx 설정 템플릿 치환: `envsubst '${INSTANCE_B_PRIVATE_IP}' < ssolv-infrastructure/nginx/nginx-app-instance-a.conf.template > ssolv-infrastructure/nginx/nginx-app-instance-a.conf`
- [ ] firebase-service-account.json 배포
- [ ] certbot으로 api.ssolv.site 인증서 발급 (가비아 DNS 변경 후)
- [ ] docker compose -f docker-compose.instance-a.yml up -d
- [ ] 헬스체크 통과 확인

### Phase 4: CD 파이프라인 업데이트
- [ ] GitHub Actions secrets 신규 계정용으로 교체
  - `EC2_HOST` (새 IP들)
  - `EC2_SSH_KEY` (새 키)
  - `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`
- [ ] 멀티 서버 배포 전략 (rolling update)
- [ ] Firebase service account 이전

### Phase 5: DNS 컷오버
- [ ] api.ssolv.site → 인스턴스 A EIP로 가비아 DNS 변경
- [ ] registry.ssolv.site → 인스턴스 B EIP로 가비아 DNS 변경
- [ ] CloudFront 제거
- [ ] 모니터링 정상 수집 확인 (Grafana, Sentry)

---

## 단일↔멀티 전환 전략

```hcl
# terraform.tfvars
app_instance_count = 2  # 1로 바꾸면 단일 서버로 복귀
```

---

## 주요 결정 사항

| 항목 | 결정 |
|------|------|
| CloudFront | 신규 계정에서 제거 |
| ALB | 사용 안 함 (Nginx가 앞단) |
| Redis | ElastiCache 아닌 EC2 컨테이너 유지 (인스턴스 B) |
| Registry | 자체 registry 유지 (registry.ssolv.site, 인스턴스 B) |
| IaC 도구 | Terraform (state: 로컬 파일) |
| 인스턴스 타입 | A: t3.micro / B: t3.small (JVM 힙 축소로 micro 운용) |
| IAM | 미사용 — 루트 Access Key 방식으로 Terraform 인증 |
| RDS 이전 | 스냅샷 없음 — 신규 RDS 생성 후 코드 레벨 초기화 (SurveyCategoryInitializer, StationInitializer) |
| DNS | 가비아에서 수동 변경 (EIP 발급 후) |
| HTTPS | Let's Encrypt 재발급 — 인스턴스 B에서 certbot 실행 |
| 부하분산 | A, B 모두 트래픽 서빙 (기존 멀티 WAS 구조 유지, Nginx upstream) |

---

## 세션 간 공유 메모

- 현재 SSH 키: `/Users/parkmineum/dpm-server/depromeet-secret.pem`
- 현재 EC2 IP: `13.125.182.175` (구 계정, 참조용)
- CD 파이프라인: `.github/workflows/cd-deploy.yml`
- 모니터링: `docker-compose.monitoring.yml` (Alloy + Exporters)
