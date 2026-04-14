특정 인스턴스의 서비스를 재시작합니다.

## 사용법

```
/deploy <service> [instance]
```

- `service`: 재시작할 컨테이너 이름 (예: `app-server`, `nginx`, `redis`, `registry`)
- `instance`: `a` 또는 `b` (생략 시 두 인스턴스 모두 재시작)

예시:
- `/deploy app-server a` — Instance A의 app-server만 재시작
- `/deploy app-server b` — Instance B의 app-server만 재시작
- `/deploy app-server` — A, B 모두 순차 재시작 (A → B)
- `/deploy nginx a` — Instance A의 nginx 재시작

## 실행 절차

아래 단계를 순서대로 수행한다.

### Instance A (t3.micro — nginx + app-server)

```bash
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@3.34.32.206 "
  cd ~/17th-team3-Server
  docker compose --project-directory ~/17th-team3-Server \
    -f deploy/docker-compose.instance-a.yml \
    up -d --no-deps --force-recreate <service>
  echo '--- Container status ---'
  docker ps --filter name=<service> --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
"
```

### Instance B (t3.small — app-server + redis + registry + monitoring)

```bash
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33 "
  cd ~/17th-team3-Server
  docker compose --project-directory ~/17th-team3-Server \
    -f deploy/docker-compose.instance-b.yml \
    up -d --no-deps --force-recreate <service>
  echo '--- Container status ---'
  docker ps --filter name=<service> --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
"
```

### 재시작 후 헬스체크

```bash
# Instance A
curl -s https://api.ssolv.site/actuator/health | python3 -m json.tool

# Instance B (직접 IP 확인)
ssh -i /Users/parkmineum/.ssh/gdg-cicd-key.pem -o StrictHostKeyChecking=no ubuntu@52.79.62.33 \
  "curl -s http://localhost:8080/actuator/health"
```

### 양쪽 모두 재시작하는 경우 (instance 인자 생략)

1. Instance A 재시작 + 헬스체크 통과 확인
2. Instance B 재시작 + 헬스체크 통과 확인
3. Route53 헬스체크 상태 확인 (약 30초 대기)

## 서비스별 docker-compose 파일 매핑

| 인스턴스 | compose 파일 |
|---|---|
| A | `deploy/docker-compose.instance-a.yml` |
| B | `deploy/docker-compose.instance-b.yml` |

## 주의사항

- `--no-deps` 사용 — 다른 서비스에 영향 없이 단일 서비스만 재시작
- `--force-recreate` 사용 — 설정 변경이 반영되도록 강제 재생성
- CD 파이프라인이 없는 신규 서비스는 이 커맨드로 수동 배포
- `.env` 변경이 있으면 재시작 전 SSH로 파일 업데이트 필요
