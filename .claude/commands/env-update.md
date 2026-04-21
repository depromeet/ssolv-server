---
description: Update a key=value in the production `.env` file via SSH and restart affected services.
argument-hint: "<key> <value> [instance]"
allowed-tools: Bash
---

프로덕션 인스턴스의 `.env` 파일을 수정하고 영향받는 서비스를 재시작합니다.

## 사용법

```text
/env-update <key> <value> [instance]
```

- `key`: 변경할 환경변수 키 (예: `GOOGLE_PLACES_API_KEY`)
- `value`: 새 값
- `instance`: `a`, `b`, 또는 생략 (기본값: 양쪽 모두)

예시:
- `/env-update GOOGLE_PLACES_API_KEY <new-key>` — 양쪽 모두 업데이트
- `/env-update JWT_SECRET <new-secret> a` — Instance A만

## .env 파일 위치

```
Instance A: ~/17th-team3-Server/.env
Instance B: ~/17th-team3-Server/.env  (별도 파일)
```

## 현재 .env 키 목록

```text
# DB
DEFAULT_SCHEMA, DB_USERNAME, DB_PASSWORD
PROD_DB_USERNAME, PROD_DB_PASSWORD, PROD_DB_ENDPOINT, PROD_DB_NAME

# 네트워크
INSTANCE_B_PRIVATE_IP

# 레지스트리
REGISTRY_USERNAME, REGISTRY_PASSWORD, REGISTRY_HTTP_SECRET

# 인증
JWT_SECRET
KAKAO_CLIENT_ID, CLIENT_ID, CLIENT_SECRET
APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_CLIENT_ID, APPLE_PRIVATE_KEY

# 외부 API
GOOGLE_PLACES_API_KEY

# Firebase
FIREBASE_SERVICE_ACCOUNT (파일 마운트)
```

## 실행 절차

### 1. 현재 값 확인

```bash
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@3.34.32.206 \
  "grep '^<KEY>=' ~/17th-team3-Server/.env"
```

### 2. 값 업데이트

```bash
# Instance A
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@3.34.32.206 "
  cd ~/17th-team3-Server
  sed -i 's|^<KEY>=.*|<KEY>=<NEW_VALUE>|' .env
  echo '--- 변경 후 확인 ---'
  grep '^<KEY>=' .env
"

# Instance B
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@52.79.62.33 "
  cd ~/17th-team3-Server
  sed -i 's|^<KEY>=.*|<KEY>=<NEW_VALUE>|' .env
  echo '--- 변경 후 확인 ---'
  grep '^<KEY>=' .env
"
```

### 3. 영향받는 서비스 재시작

`.env` 변경 후 해당 값을 사용하는 서비스를 재시작해야 반영된다.

| 변경 키 | 재시작 서비스 | 인스턴스 |
|---|---|---|
| `JWT_SECRET` | `app-server` | A, B |
| `GOOGLE_PLACES_API_KEY` | `app-server` (place 모듈) | B |
| `KAKAO_*`, `APPLE_*` | `app-server` | A, B |
| `PROD_DB_*` | `app-server` | A, B |
| `INSTANCE_B_PRIVATE_IP` | `app-server` | A (연결 대상 변경 시) |
| `REDIS_*` | `redis` | B |
| `REGISTRY_*` | 재시작 불필요 (pull 시에만 사용) | — |

```bash
# 재시작 예시 (Instance A — app-server)
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@3.34.32.206 "
  cd ~/17th-team3-Server
  docker compose --project-directory ~/17th-team3-Server \
    -f deploy/docker-compose.instance-a.yml \
    up -d --no-deps --force-recreate app-server
"

# 재시작 예시 (Instance B — redis)
ssh -i "${SSH_KEY_PATH:-/Users/parkmineum/.ssh/gdg-cicd-key.pem}" -o StrictHostKeyChecking=no ubuntu@52.79.62.33 "
  cd ~/17th-team3-Server
  docker compose --project-directory ~/17th-team3-Server \
    -f deploy/docker-compose.instance-b.yml \
    up -d --no-deps --force-recreate redis
"
```

### 4. 헬스체크

재시작 후 `/health-check`로 정상 기동 확인.

## 주의사항

- `APPLE_PRIVATE_KEY`처럼 멀티라인 값은 `sed` 대신 직접 편집 필요
- `.env` 파일은 git에 포함되지 않음 — 양쪽 인스턴스에 각각 적용 필요
- 민감한 값(비밀번호, API 키)은 대화창에 직접 입력하지 말 것
