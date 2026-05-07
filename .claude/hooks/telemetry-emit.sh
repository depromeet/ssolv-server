#!/bin/bash
# Claude Code 텔레메트리 이벤트 전송 헬퍼
#
# 용도: 스킬 호출, 훅 차단 등 커스텀 이벤트를 OTLP HTTP로 Alloy에 전송.
# 네이티브 claude_code.* 메트릭(토큰/비용/tool decision)은 CLAUDE_CODE_ENABLE_TELEMETRY로 별도 처리.
#
# 사용법:
#   CLAUDE_OTLP_ENDPOINT=http://host:4318 telemetry-emit.sh <event_name> [key=value ...]
#
# 예시:
#   telemetry-emit.sh hook.blocked hook_name=commit-msg-check reason=non-ascii
#   telemetry-emit.sh skill.invoked skill_name=git-conventions

EVENT_NAME="${1:-unknown}"
shift

# CLAUDE_OTLP_ENDPOINT가 설정되지 않으면 OTLP 전송 생략 (로컬 JSONL만 기록)
OTLP_ENDPOINT="${CLAUDE_OTLP_ENDPOINT:-}"
LOG_FILE="${HOME}/.claude/telemetry/events.jsonl"

mkdir -p "$(dirname "$LOG_FILE")"
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# key=value 쌍을 Python으로 안전하게 처리 (셸 인젝션 방지)
# 모든 값은 환경변수와 argv를 통해 Python으로 전달
ATTRS_JSON=$(python3 - "$@" <<'PYEOF'
import sys, json, os

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

# 로컬 JSONL에 항상 기록 (OTLP 실패해도 유실 없음)
printf '{"ts":"%s","event":"%s","attrs":%s}\n' \
    "$TIMESTAMP" "$EVENT_NAME" "$ATTRS_JSON" >> "$LOG_FILE"

# OTLP 엔드포인트 미설정 시 종료
[ -z "$OTLP_ENDPOINT" ] && exit 0

# OTLP HTTP 전송 (백그라운드 — 느려도 훅 차단 안 함)
NOW_NS="$(date +%s)000000000"
BODY=$(EVENT_NAME="$EVENT_NAME" \
       ATTRS_JSON="$ATTRS_JSON" \
       DEVELOPER="${USER:-unknown}" \
       NOW_NS="$NOW_NS" \
       python3 - <<'PYEOF'
import json, os

event_name = os.environ["EVENT_NAME"]
attrs      = json.loads(os.environ["ATTRS_JSON"])
developer  = os.environ["DEVELOPER"]
now_ns     = os.environ["NOW_NS"]

kv_list = [{"key": k, "value": {"stringValue": v}} for k, v in attrs.items()]

payload = {
    "resourceLogs": [{
        "resource": {
            "attributes": [
                {"key": "service.name", "value": {"stringValue": "claude-code"}},
                {"key": "developer",    "value": {"stringValue": developer}},
            ]
        },
        "scopeLogs": [{
            "logRecords": [{
                "timeUnixNano": now_ns,
                "severityText": "INFO",
                "body": {"stringValue": event_name},
                "attributes": kv_list,
            }]
        }]
    }]
}
print(json.dumps(payload))
PYEOF
)

if [ -n "$BODY" ]; then
    curl -sf -X POST "${OTLP_ENDPOINT}/v1/logs" \
        -H "Content-Type: application/json" \
        -d "$BODY" \
        --max-time 2 \
        > /dev/null 2>&1 &
fi
