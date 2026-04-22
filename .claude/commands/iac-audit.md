---
description: Audit Terraform (.tf) files against ssolv IaC rules (encryption, IMDSv2, SG source references, required tags).
argument-hint: "[path]"
allowed-tools: Read, Glob, Grep, Bash
---

Terraform 파일에 대해 IaC 보안 감사를 수행해줘.

## 감사 대상
현재 디렉토리 또는 지정된 .tf 파일 전체

---

## ssolv IaC 인벤토리 (이 외 리소스는 생성 금지)

- **EC2** + Elastic IP
- **RDS (MySQL 8.0.43)**
- **Route53** — health-check 기반 DNS failover (Multivalue Answer routing) 용도로만 사용

ALB, S3, ElastiCache, CloudFront 는 도입하지 않는다.

---

## 리소스별 필수 설정

### EC2 (`aws_instance`)
- Instance A: 기본값 `t3.micro` (nginx + app-server only, JVM `-Xmx400m`)
- Instance B: 기본값 `t3.small` (app-server + redis + registry + alloy + exporters)
- `http_tokens = "required"` — IMDSv2 강제 (보안 요구사항)
- 루트 EBS `encrypted = true`
- `instance_type`, `ami` 는 반드시 변수로 주입 — 리소스 내 리터럴 하드코딩 금지 (변수 `default` 에는 위 기본값 허용)
- 인스턴스 수는 `app_instance_count` 변수로만 제어

### Elastic IP (`aws_eip`)
- `aws_eip_association` 으로 EC2에 연결
- EC2와 분리된 리소스로 선언 (재사용성 확보)

### RDS (`aws_db_instance`)
- `engine = "mysql"`, `engine_version = "8.0.43"` (고정)
- `publicly_accessible = false`
- `storage_encrypted = true`
- `deletion_protection = true`
- `backup_retention_period >= 1`
- `db_subnet_group_name` 은 **private 서브넷으로만 구성된** 서브넷 그룹을 사용

### Security Groups (`aws_security_group`)
- 3306 (MySQL), 6379 (Redis) 는 `source_security_group_id` 로 EC2 SG 참조 — CIDR 직접 허용 금지
- Egress: `0.0.0.0/0` 허용 (의도됨)
- Inbound `0.0.0.0/0` 는 80, 443, 22 포트에서만 허용

### 공통
- 모든 리소스에 `tags` 블록 필수: `{ Project = "ssolv", ManagedBy = "terraform" }`
- 민감 값(비밀번호, 키)은 `var` 또는 AWS Secrets Manager — 하드코딩 금지

---

## 체크리스트 (감사 시 아래 순서로 확인)

### 1. 암호화
- [ ] RDS `storage_encrypted = true`
- [ ] EBS 볼륨 `encrypted = true`

### 2. 네트워크 보안
- [ ] RDS `publicly_accessible = false` 및 private 서브넷 배치
- [ ] 3306/6379 포트가 `source_security_group_id` 기반으로만 허용되는지
- [ ] 22 포트가 특정 IP로 제한되었는지 (전체 오픈이면 경고)

### 3. 설정 누락
- [ ] 모든 리소스에 `tags`
- [ ] RDS `deletion_protection = true`
- [ ] RDS `backup_retention_period >= 1`
- [ ] EC2 `http_tokens = "required"` (IMDSv2)
- [ ] 보안그룹 egress 규칙 명시

### 4. 콘솔 → Terraform 이전 누락 검증
- [ ] ssolv.yaml 기준 누락 리소스 없음
  - VPC, 서브넷, IGW, 라우팅 테이블
  - EC2, EIP, 보안그룹
  - RDS, DB 서브넷 그룹
  - KMS 키 (RDS, ACM용)

### 5. 단일↔멀티 전환 안전성
- [ ] `app_instance_count` 변수로 EC2 수 제어
- [ ] 단일 서버 시 인스턴스 B 의존 서비스(redis, registry) 처리 방안 존재

---

## 자동화 연계

- `PostToolUse(Edit|Write)` → `post-edit-dispatch.sh` → `iac-security-check.sh` 가 `.tf` 저장 시 경량 감사를 자동 실행
- 새 인프라 결정사항은 `.claude/infra/DECISIONS.md` 에 ADR로 기록

## 출력 형식
각 항목별로 ✅ 통과 / ⚠️ 경고 / ❌ 실패로 표시. 실패·경고 항목은 수정 코드 제안 포함.
