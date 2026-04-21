---
description: Audit Terraform (.tf) files against ssolv IaC rules (encryption, IMDSv2, SG source references, required tags).
argument-hint: "[path]"
allowed-tools: Read, Glob, Grep, Bash
---

Terraform 파일에 대해 IaC 보안 감사를 수행해줘.

## 감사 대상
현재 디렉토리 또는 지정된 .tf 파일 전체

## 체크리스트

### 1. 암호화
- [ ] RDS `storage_encrypted = true` 여부
- [ ] EBS 볼륨 `encrypted = true` 여부
- [ ] S3 버킷 서버사이드 암호화 설정 여부

### 2. 네트워크 보안
- [ ] RDS가 퍼블릭 서브넷에 배치되지 않았는지 (`publicly_accessible = false`)
- [ ] 민감 포트(3306, 6379)가 EC2 보안그룹에서만 접근 가능한지
- [ ] SSH(22) 포트가 특정 IP로 제한되었는지 (전체 오픈이면 경고)

### 3. 설정 누락
- [ ] 모든 리소스에 `tags` 포함 여부
- [ ] RDS `deletion_protection = true` 여부
- [ ] RDS `backup_retention_period` 1 이상 여부
- [ ] EC2 IMDSv2 강제 (`http_tokens = "required"`) 여부
- [ ] 보안그룹 egress 규칙 명시 여부

### 4. 콘솔 → Terraform 이전 누락 검증
- [ ] ssolv.yaml 기준으로 누락된 리소스 없는지 확인
  - VPC, 서브넷, IGW, 라우팅 테이블
  - EC2, EIP, 보안그룹
  - RDS, DB 서브넷 그룹
  - KMS 키 (RDS, ACM용)

### 5. 단일↔멀티 전환 안전성
- [ ] `app_instance_count` 변수로 EC2 수 제어되는지
- [ ] 단일 서버 시 인스턴스 B 의존 서비스(redis, registry) 처리 방안 있는지

## 출력 형식
각 항목별로 ✅ 통과 / ⚠️ 경고 / ❌ 실패로 표시하고, 실패/경고 항목은 수정 코드 제안 포함.
