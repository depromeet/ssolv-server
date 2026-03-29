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
- [ ] 신규 AWS 계정 생성 및 IAM 설정
- [ ] Terraform 로컬 환경 설치 (`brew install terraform`)

### Phase 1: Terraform 기반 설계
- [ ] `modules/network` — VPC, 서브넷, IGW, 보안그룹
- [ ] `modules/compute` — EC2 (var로 1대↔2대 전환 가능하게)
- [ ] `modules/database` — RDS MySQL 8.0.43
- [ ] `modules/cache` — Redis (EC2 내 컨테이너 유지)
- [ ] `modules/registry` — 자체 registry or ECR 결정
- [ ] `environments/prod` — 실제 변수 연결

### Phase 2: 신규 계정 인프라 구축
- [ ] Terraform으로 신규 계정에 네트워크 생성
- [ ] EC2 인스턴스 2대 프로비저닝
- [ ] RDS 스냅샷 → 신규 계정 복원
- [ ] 보안그룹 규칙 (22, 80, 443, 6379, 3306, 4317/4318)

### Phase 3: 앱 배포 및 검증
- [ ] 각 EC2에 docker-compose 파일 배포
- [ ] nginx 설정 이전 (nginx-app.conf, nginx-cicd.conf)
- [ ] registry에 이미지 push 및 pull 확인
- [ ] 헬스체크 통과 확인

### Phase 4: CD 파이프라인 업데이트
- [ ] GitHub Actions secrets 신규 계정용으로 교체
  - `EC2_HOST` (새 IP들)
  - `EC2_SSH_KEY` (새 키)
  - `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`
- [ ] 멀티 서버 배포 전략 (rolling update)
- [ ] Firebase service account 이전

### Phase 5: DNS 컷오버
- [ ] api.ssolv.site → 새 EC2 IP로 변경
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
| Redis | ElastiCache 아닌 EC2 컨테이너 유지 |
| Registry | 자체 registry 유지 (registry.ssolv.site) |
| IaC 도구 | Terraform |
| 인스턴스 타입 | A: t3.micro / B: t3.small (JVM 힙 축소로 micro 운용) |

---

## 세션 간 공유 메모

- 현재 SSH 키: `/Users/parkmineum/dpm-server/depromeet-secret.pem`
- 현재 EC2 IP: `13.125.182.175` (구 계정, 참조용)
- CD 파이프라인: `.github/workflows/cd-deploy.yml`
- 모니터링: `docker-compose.monitoring.yml` (Alloy + Exporters)
