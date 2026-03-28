# 인프라 의사결정 기록 (ADR)

> 이 파일은 인프라 설계 과정에서 내린 주요 결정과 그 근거를 기록합니다.
> Claude와의 대화에서 도출된 판단도 포함합니다.

---

## ADR-001: IaC 도구 선택 — Terraform

- **날짜**: 2026-03-29
- **결정**: CDK 대신 Terraform 사용
- **근거**:
  - AWS 계정 이전 시 provider만 교체하면 되는 이식성
  - `count` / `for_each`로 단일↔멀티 서버 전환이 변수 하나로 가능
  - CDK migrate로 기존 CloudFormation → Terraform 역설계 가능
- **트레이드오프**: TypeScript CDK 대비 타입 안전성 낮음. 단순성 우선.

---

## ADR-002: CloudFront 제거

- **날짜**: 2026-03-29
- **결정**: 신규 계정에서 CloudFront 미사용
- **근거**: 현재 API 서버 특성상 캐싱 이점이 제한적. 아키텍처 단순화 우선.
- **복원 조건**: 정적 자산 서빙 또는 글로벌 트래픽 발생 시 재도입 검토.

---

## ADR-003: ALB 미사용 — Nginx가 앞단 담당

- **날짜**: 2026-03-29
- **결정**: Application Load Balancer 없이 각 EC2 앞단에 Nginx 배치
- **근거**:
  - 비용 절감 (ALB 월 $16~)
  - 이미 Nginx 설정 존재 (nginx-app.conf)
  - 멀티 서버 전환 시에도 Nginx로 충분히 처리 가능
- **트레이드오프**: ALB의 자동 헬스체크/타겟 관리 포기. Nginx 수동 관리 필요.

---

## ADR-004: Redis — ElastiCache 미사용, EC2 컨테이너 유지

- **날짜**: 2026-03-29
- **결정**: 인스턴스 B에서 Redis 컨테이너로 운영
- **근거**: 현재 100MB maxmemory 수준의 소규모 사용. ElastiCache 비용 대비 효용 낮음.
- **복원 조건**: Redis 메모리 사용량 급증 또는 고가용성 요구 시 ElastiCache 전환 검토.

---

## ADR-005: 자체 Docker Registry 유지

- **날짜**: 2026-03-29
- **결정**: ECR 대신 registry.ssolv.site 자체 registry 유지
- **근거**: 기존 CI/CD 파이프라인과의 호환성. ECR 전환 시 GitHub Actions 대규모 수정 필요.
- **트레이드오프**: registry 컨테이너 장애 시 배포 불가. 인스턴스 B에 의존성 집중.

---

## ADR-006: Claude를 IaC 정적 분석기로 활용

- **날짜**: 2026-03-29
- **결정**: Terraform 코드 작성 시 Claude가 보안 감사관 역할 수행
- **근거**:
  - 수동 콘솔 구성 → Terraform 이전 시 설정 누락 방지
  - tflint/checkov 대비 컨텍스트 인식 가능 (프로젝트 특성 반영)
  - 배포 전 자동 검증으로 프로덕션 장애 예방
- **구현**:
  - `settings.json` PostToolUse Hook: .tf 파일 수정 시 자동 감사
  - `/iac-audit` 슬래시 커맨드: 온디맨드 전체 감사
  - `CLAUDE.md` IaC 규칙: 매 세션 자동 로드
- **감사 제외 항목**: 인바운드 0.0.0.0/0 (의도된 설정)

---

## ADR-007: 단일↔멀티 서버 전환 전략

- **날짜**: 2026-03-29
- **결정**: `app_instance_count` 변수 하나로 서버 수 제어
- **근거**: 롤백 시나리오 대비. 멀티 서버 운영 중 장애 시 즉시 단일 서버로 복귀 가능해야 함.
- **구현**: `terraform.tfvars`에서 `app_instance_count = 1` 또는 `2`로 전환.
