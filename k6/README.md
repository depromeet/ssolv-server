# K6 부하 테스트

## 설치

```bash
# macOS
brew install k6
```

## 실행

### 기본 실행 (웹 대시보드 포함)
```bash
k6 run --out web-dashboard k6/ssolv-main-flow.js
```
→ 브라우저에서 http://localhost:5665 접속하면 실시간 그래프 확인 가능

### VU 수 / 지속 시간 변경
```bash
K6_VU=100 K6_DURATION=5m k6 run --out web-dashboard k6/ssolv-main-flow.js
```

### 결과를 HTML 리포트로 저장
```bash
K6_WEB_DASHBOARD_EXPORT=report.html k6 run --out web-dashboard k6/ssolv-main-flow.js
```

## 트래픽 분포 (nGrinder 원본과 동일)

| 비율 | API |
|------|-----|
| 60%  | `GET /meetings` |
| 30%  | `GET /meetings/{token}` |
| 10%  | `GET /meetings/validate-invite?token=...` |

## 주의사항

- `TOKEN`이 만료된 경우 스크립트 상단의 `TOKEN` 값을 갱신하세요.
- 로컬에서 실행해도 `BASE_URL`이 `https://api.ssolv.site`이므로 **운영 서버로 요청이 전송됩니다.**
- 테스트 전에 팀원에게 공유하고, 운영 DB 영향을 고려하세요.
