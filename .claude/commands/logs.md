프로덕션 인스턴스의 컨테이너 로그를 조회합니다.

## 사용법

```text
/logs <service> [instance] [options]
```

- `service`: 컨테이너 이름 (예: `app-server`, `nginx`, `redis`)
- `instance`: `a` 또는 `b` (생략 시 두 인스턴스 모두 조회)
- `options`: `--tail N` (기본값: 100), `--since 30m`, `--grep <keyword>`

예시:
- `/logs app-server a` — Instance A의 app-server 최근 100줄
- `/logs app-server b --tail 200` — Instance B의 app-server 최근 200줄
- `/logs app-server --since 30m` — 양쪽 모두, 최근 30분 로그
- `/logs nginx a --grep ERROR` — Instance A nginx 에러 로그만

## 실행 절차

### 기본 로그 조회 (--tail)

```bash
# Instance A
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "docker logs <service> --tail <N> 2>&1"

# Instance B
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33 \
  "docker logs <service> --tail <N> 2>&1"
```

### 시간 기반 조회 (--since)

```bash
# 최근 30분 로그
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "docker logs <service> --since 30m 2>&1"
```

### 키워드 필터 (--grep)

```bash
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "docker logs <service> --tail 500 2>&1 | grep '<keyword>'"
```

### 에러 집중 조회 (빠른 진단용)

```bash
# 양쪽 인스턴스에서 ERROR/WARN 로그 동시 조회
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "echo '=== Instance A ===' && docker logs app-server --tail 200 2>&1 | grep -E 'ERROR|WARN|Exception'"

ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33 \
  "echo '=== Instance B ===' && docker logs app-server --tail 200 2>&1 | grep -E 'ERROR|WARN|Exception'"
```

## 자동 복구 / 헬스체크 로그

```bash
# Instance A/B 공통 — 자동 복구 스크립트 로그
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "tail -50 /var/log/ssolv-health-recovery.log"

# Instance A 전용 — 메모리 모니터링 로그
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "tail -50 /var/log/ssolv-memory-check.log"
```

## 컨테이너 상태 확인

```bash
# 전체 컨테이너 상태
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"

ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33 \
  "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"
```

## 인스턴스 IP 변경 시

IP가 바뀐 경우 먼저 확인 후 실행:
```bash
cd /Users/parkmineum/17th-team3-Server/terraform && terraform output
```
