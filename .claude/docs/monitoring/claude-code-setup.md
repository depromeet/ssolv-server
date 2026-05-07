# Claude Code 모니터링 개발자 설정

## 개요

Claude Code 사용 지표(토큰/비용/tool 사용률)는 OTLP → Instance A Alloy → Grafana Cloud로 수집된다.
스킬 호출 및 훅 차단 이벤트는 커스텀 훅이 별도 OTLP 로그로 전송한다.

## 수집 지표

| 지표 | 출처 | Grafana 위치 |
|---|---|---|
| 토큰 사용량 (input/output/cache) | `claude_code.token.usage` | Prometheus → Grafana |
| API 비용 (USD) | `claude_code.cost.usage` | Prometheus → Grafana |
| Edit 수락/거절 비율 | `claude_code.code_edit_tool.decision` | Prometheus → Grafana |
| Tool 사용 분포 | `claude_code.tool_decision` | Prometheus → Grafana |
| 커밋/PR 수 | `claude_code.commit.count` | Prometheus → Grafana |
| 스킬 호출 이벤트 | `skill.invoked` (커스텀) | Loki → Grafana |
| 훅 차단 이벤트 | `hook.blocked` (커스텀) | Loki → Grafana |

## 로컬 환경 설정

`~/.zshrc` (또는 `~/.zshenv`)에 추가:

```bash
# Claude Code OTel 텔레메트리
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT=http://3.34.32.206:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
# cumulative: Prometheus 호환 백엔드가 delta temporality를 무음 드롭하는 것 방지
export OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=cumulative
export OTEL_RESOURCE_ATTRIBUTES=service.name=claude-code,developer=<본인-github-id>

# 커스텀 훅 이벤트 엔드포인트 (네이티브 OTel과 동일 엔드포인트)
export CLAUDE_OTLP_ENDPOINT=http://3.34.32.206:4318
```

설정 후 적용:
```bash
source ~/.zshrc
```

## 로컬 이벤트 로그

훅 이벤트는 OTLP 전송 실패 시에도 로컬에 보존된다:

```
~/.claude/telemetry/events.jsonl
```

## 구조

```
로컬 Mac
  Claude Code ──OTLP metrics──▶ Instance A :4318 ──▶ Grafana Cloud Prometheus
  Hook events ──OTLP logs────▶ Instance A :4318 ──▶ Grafana Cloud Loki

Instance A (3.34.32.206)
  Alloy: OTLP receiver(4317/4318) + prometheus remote_write + loki write
```
