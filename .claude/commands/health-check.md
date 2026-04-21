---
description: Run a full health check on a production instance (containers, nginx, Redis, disk) via SSH.
argument-hint: "[instance]"
allowed-tools: Bash
---

프로덕션 인스턴스의 전체 상태를 확인합니다.

## 사용법

```text
/health-check [instance]
```

- `instance`: `a`, `b`, 또는 생략 (기본값: 양쪽 모두 확인)

예시:
- `/health-check` — A, B 모두 확인
- `/health-check a` — Instance A만 확인
- `/health-check b` — Instance B만 확인

## 실행 절차

아래 순서대로 확인한다.

### 1. Spring Actuator 헬스체크

```bash
# Instance A — nginx 경유 (도메인)
curl -s https://api.ssolv.site/actuator/health | python3 -m json.tool

# Instance B — 직접 포트
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@52.79.62.33 \
  "curl -s http://localhost:8080/actuator/health | python3 -m json.tool"
```

**정상 응답:**
```json
{"status": "UP", "components": {"db": {"status": "UP"}, "redis": {"status": "UP"}}}
```

### 2. 컨테이너 상태 확인

```bash
# Instance A
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"

# Instance B
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@52.79.62.33 \
  "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"
```

### 3. 최근 에러 로그 확인 (이상 징후 시)

```bash
# Instance A app-server 에러 로그
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "docker logs app-server --tail 50 2>&1 | grep -E 'ERROR|WARN|Exception'"

# Instance B app-server 에러 로그
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@52.79.62.33 \
  "docker logs app-server --tail 50 2>&1 | grep -E 'ERROR|WARN|Exception'"
```

### 4. Route53 DNS 상태 확인 (선택)

```bash
# DNS 조회 결과 확인
dig api.ssolv.site +short

# Route53 헬스체크는 약 30초 주기로 업데이트됨
# 상태 확인은 AWS 콘솔 또는 terraform output 참고
```

## 이상 상태 대응

| 증상 | 조치 |
|---|---|
| `status: DOWN` | `/logs <service>` 로 에러 확인 후 `/deploy <service>` |
| 컨테이너 `Exited` | `/deploy <service> <instance>` 로 재시작 |
| DB 연결 실패 | RDS 엔드포인트 상태 확인, `.env` DB_URL 검증 |
| Redis 연결 실패 | Instance B의 redis 컨테이너 상태 확인 |
