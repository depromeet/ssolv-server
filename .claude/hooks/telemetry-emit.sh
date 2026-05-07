#!/bin/bash
# Claude Code 텔레메트리 이벤트 전송 헬퍼
#
# Grafana Cloud Loki에 직접 푸시 (EC2/OTLP 경유 없음 — Security Group 불필요)
# 네이티브 claude_code.* 메트릭(토큰/비용/tool decision)은 CLAUDE_CODE_ENABLE_TELEMETRY로 별도 처리.
#
# 사용법:
#   telemetry-emit.sh <event_name> [key=value ...]
#
# 예시:
#   telemetry-emit.sh hook.blocked hook_name=commit-msg-check reason=non-ascii
#   telemetry-emit.sh skill.invoked skill_name=git-conventions
#
# 필요 환경변수 (~/. claude/settings.json env 블록에 설정):
#   GRAFANA_CLOUD_LOKI_URL  — Loki push URL
#   GRAFANA_CLOUD_LOKI_USER — Loki basic auth username
#   GRAFANA_CLOUD_API_KEY   — Grafana Cloud API key

EVENT_NAME="${1:-unknown}"
shift

LOKI_URL="${GRAFANA_CLOUD_LOKI_URL:-}"
LOKI_USER="${GRAFANA_CLOUD_LOKI_USER:-}"
LOKI_KEY="${GRAFANA_CLOUD_API_KEY:-}"
LOG_FILE="${HOME}/.claude/telemetry/events.jsonl"

mkdir -p "$(dirname "$LOG_FILE")"
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# key=value → JSON attrs (Python으로 안전하게 처리, 셸 인젝션 방지)
ATTRS_JSON=$(python3 - "$@" <<'PYEOF'
import sys, json
pairs = sys.argv[1:]
attrs = {}
for p in pairs:
    if '=' in p:
        k, _, v = p.partition('=')
        attrs[k] = v
print(json.dumps(attrs))
PYEOF
)
ATTRS_JSON="${ATTRS_JSON:-{}}"

# 로컬 JSONL 기록 (Loki 실패해도 유실 없음)
printf '{"ts":"%s","event":"%s","attrs":%s}\n' \
    "$TIMESTAMP" "$EVENT_NAME" "$ATTRS_JSON" >> "$LOG_FILE"

# Loki 크레덴셜 미설정 시 종료
[ -z "$LOKI_URL" ] || [ -z "$LOKI_KEY" ] && exit 0

# Loki 직접 푸시 (백그라운드 — 훅 차단 안 함)
EVENT_NAME="$EVENT_NAME" \
ATTRS_JSON="$ATTRS_JSON" \
LOKI_URL="$LOKI_URL" \
LOKI_USER="$LOKI_USER" \
LOKI_KEY="$LOKI_KEY" \
python3 - <<'PYEOF' > /dev/null 2>&1 &
import json, os, time, urllib.request, urllib.error, base64

event_name = os.environ["EVENT_NAME"]
attrs      = json.loads(os.environ["ATTRS_JSON"])
loki_url   = os.environ["LOKI_URL"]
loki_user  = os.environ["LOKI_USER"]
loki_key   = os.environ["LOKI_KEY"]
developer  = os.environ.get("USER", "unknown")

now_ns  = str(int(time.time() * 1e9))
log_line = json.dumps({"event": event_name, "developer": developer, **attrs}, ensure_ascii=False)

payload = json.dumps({
    "streams": [{
        "stream": {
            "service_name": "claude-code",
            "developer":    developer,
            "event":        event_name,
        },
        "values": [[now_ns, log_line]],
    }]
}).encode()

creds = base64.b64encode(f"{loki_user}:{loki_key}".encode()).decode()
req = urllib.request.Request(
    loki_url,
    data=payload,
    headers={"Content-Type": "application/json", "Authorization": f"Basic {creds}"},
    method="POST",
)
try:
    urllib.request.urlopen(req, timeout=3)
except Exception:
    pass
PYEOF
