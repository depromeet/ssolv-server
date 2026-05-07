#!/bin/bash
# Claude Code 텔레메트리 이벤트 전송 헬퍼
#
# 용도: 스킬 호출, 훅 차단 등 커스텀 이벤트를 OTLP HTTP로 Instance A Alloy에 전송.
# 네이티브 claude_code.* 메트릭(토큰/비용/tool decision)은 CLAUDE_CODE_ENABLE_TELEMETRY로 별도 처리.
#
# 사용법:
#   telemetry-emit.sh <event_name> [key=value ...]
#
# 예시:
#   telemetry-emit.sh hook.blocked hook_name=commit-msg-check reason=non-ascii
#   telemetry-emit.sh skill.invoked skill_name=git-conventions

EVENT_NAME="${1:-unknown}"
shift

OTLP_ENDPOINT="${CLAUDE_OTLP_ENDPOINT:-http://3.34.32.206:4318}"
LOG_FILE="${HOME}/.claude/telemetry/events.jsonl"

# 로컬 JSONL에 항상 기록 (OTLP 실패해도 유실 없음)
mkdir -p "$(dirname "$LOG_FILE")"
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ATTRS="{}"
for pair in "$@"; do
    key="${pair%%=*}"
    val="${pair#*=}"
    ATTRS=$(printf '%s' "$ATTRS" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['$key'] = '$val'
print(json.dumps(d))
" 2>/dev/null || echo "$ATTRS")
done

printf '{"ts":"%s","event":"%s","attrs":%s}\n' "$TIMESTAMP" "$EVENT_NAME" "$ATTRS" >> "$LOG_FILE"

# OTLP HTTP 전송 (백그라운드 — 느려도 훅 차단 안 함)
NOW_NS=$(date +%s)000000000
BODY=$(python3 -c "
import json, sys

attrs = json.loads('$ATTRS') if '$ATTRS' != '{}' else {}
kv_list = [{'key': k, 'value': {'stringValue': v}} for k, v in attrs.items()]
kv_list.append({'key': 'service.name', 'value': {'stringValue': 'claude-code'}})
kv_list.append({'key': 'developer', 'value': {'stringValue': '${USER:-unknown}'}})

payload = {
    'resourceLogs': [{
        'resource': {
            'attributes': [
                {'key': 'service.name', 'value': {'stringValue': 'claude-code'}},
                {'key': 'developer', 'value': {'stringValue': '${USER:-unknown}'}},
            ]
        },
        'scopeLogs': [{
            'logRecords': [{
                'timeUnixNano': '${NOW_NS}',
                'severityText': 'INFO',
                'body': {'stringValue': '$EVENT_NAME'},
                'attributes': kv_list,
            }]
        }]
    }]
}
print(json.dumps(payload))
" 2>/dev/null)

if [ -n "$BODY" ]; then
    curl -sf -X POST "${OTLP_ENDPOINT}/v1/logs" \
        -H "Content-Type: application/json" \
        -d "$BODY" \
        --max-time 2 \
        > /dev/null 2>&1 &
fi
