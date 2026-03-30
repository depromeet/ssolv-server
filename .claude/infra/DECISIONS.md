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

## ADR-010: IAM 미사용 — 루트 Access Key 방식

- **날짜**: 2026-03-31
- **결정**: 신규 AWS 계정에서 IAM 사용자/역할 없이 루트 계정 Access Key로 Terraform 인증
- **근거**: 마이그레이션 한정 작업으로 IAM 설정 복잡도 제거. 단기 프로젝트 특성상 단순성 우선.
- **트레이드오프**: 보안 베스트 프랙티스(최소 권한 원칙) 미준수. 키 유출 시 전체 계정 노출 위험.
- **완화**: Access Key는 `.env` 또는 환경변수로만 관리, 코드에 하드코딩 절대 금지.

---

## ADR-011: RDS 스냅샷 없이 신규 생성 + 코드 레벨 초기화

- **날짜**: 2026-03-31
- **결정**: 기존 DB 데이터 이전 없이 신규 RDS 생성. 필수 마스터 데이터는 ApplicationRunner로 자동 삽입.
- **근거**: 기존 사용자 데이터 의미 없음 (마이그레이션 전 데이터). 스냅샷 크로스 계정 이전 절차 생략으로 단순화.
- **구현**:
  - `SurveyCategoryInitializer` — 설문 카테고리 마스터 데이터 (이미 구현됨)
  - `StationInitializer` — 지하철역 좌표 데이터 (이미 구현됨)
- **적용 조건**: `count() > 0`이면 스킵 (멱등성 보장)

---

## ADR-012: DNS — 가비아 수동 변경

- **날짜**: 2026-03-31
- **결정**: Route53 없이 가비아에서 직접 A 레코드 변경
- **근거**: 기존 도메인이 가비아 관리. Route53 전환 시 추가 비용 + 마이그레이션 복잡도 증가.
- **변경 대상**: `api.ssolv.site` → 인스턴스 A EIP, `registry.ssolv.site` → 인스턴스 B EIP
- **타이밍**: Terraform apply 후 EIP 확인 시 즉시 변경 가능

---

## ADR-013: HTTPS — Let's Encrypt 재발급 (인스턴스 B)

- **날짜**: 2026-03-31
- **결정**: 새 서버에서 certbot으로 Let's Encrypt 인증서 재발급. 인스턴스 B에서 두 도메인 모두 처리.
- **근거**: 기존 인증서를 새 서버로 복사하는 것보다 재발급이 더 안전하고 간단. Nginx 설정도 같이 정리 가능.
- **구현**: `docker-compose.cicd-infra.yml`의 nginx-cicd가 443/80 처리. certbot standalone 또는 webroot 방식.

---

## ADR-014: 멀티서버 부하분산 — 양 인스턴스 모두 트래픽 서빙

- **날짜**: 2026-03-31
- **결정**: 인스턴스 A, B 모두 nginx + app-server를 올려 트래픽 분산 (기존 멀티 WAS 구조 유지)
- **근거**: 인스턴스 B를 인프라 전용으로 쓰면 A 단일 장애 포인트 문제 발생. B의 여유 자원 활용.
- **트래픽 구조**: 가비아 DNS는 인스턴스 A만 가리킴 (단일 IP). A의 nginx에서 B로 upstream 프록시.
  - 또는: 두 도메인 각각 다른 IP로 분리 (선택 사항, 추후 결정)
- **내부 통신**: 인스턴스 A의 app-server → 인스턴스 B의 Redis (B의 사설 IP:6379)

---

## ADR-015: Terraform State — 로컬 파일

- **날짜**: 2026-03-31
- **결정**: S3 미사용으로 `terraform.tfstate` 로컬 파일로 관리
- **근거**: S3는 인프라 정책상 미사용. 1인 운영 환경에서 원격 state 불필요.
- **주의**: `terraform.tfstate`는 `.gitignore`에 추가 필수 (민감 정보 포함 가능).

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

---

## ADR-008: 인스턴스 타입 — t3.micro(A) + t3.small(B) 혼합

- **날짜**: 2026-03-29
- **결정**: 인스턴스 A는 t3.micro, 인스턴스 B는 t3.small 사용
- **근거**:
  - 현재 app-server 실제 메모리 사용량: 357~484MB (limit 768MB)
  - ALB 도입 시 ~$18/월 추가 비용 발생 → t3.small 2대($30/월)보다 오히려 비쌈
  - JVM 힙을 `-Xmx400m`으로 축소하면 인스턴스 A(nginx + app-server)의 총 메모리가 ~650MB → t3.micro(1GB) 내 운용 가능
  - 인스턴스 B는 app-server 외 redis, registry, alloy, exporters 다수 운영으로 t3.small 유지 필수
- **인스턴스별 구성**:
  - A (t3.micro / 1GB): nginx + app-server (`-Xms128m -Xmx400m`)
  - B (t3.small / 2GB): nginx + app-server + redis + registry + alloy + exporters
- **트레이드오프**: 인스턴스 A에서 트래픽 급증 시 OOM 위험. 모니터링 알림 필수.
- **복원 조건**: 인스턴스 A 메모리 사용률 80% 초과 시 t3.small 업그레이드 검토.

---

## ADR-009: app-server JVM 힙 축소

- **날짜**: 2026-03-29
- **결정**: 인스턴스 A의 app-server JVM 힙을 768MB → 400MB로 축소
- **근거**: t3.micro(1GB) 운용을 위한 메모리 확보. 실제 사용량(357~484MB) 기준 400MB limit은 피크 커버 가능.
- **설정**: `docker-compose.prod.yml` 인스턴스 A 서버에 `JAVA_OPTS: "-Xms128m -Xmx400m"` 적용
- **모니터링**: Grafana에서 JVM heap 사용률 알림 설정 필요.
