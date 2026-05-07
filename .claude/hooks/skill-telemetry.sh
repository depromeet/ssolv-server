#!/bin/bash
# PreToolUse(Skill) — 스킬 호출 이벤트 추적
#
# 어떤 skill이 얼마나 호출되는지 OTLP 로그로 기록한다.
# settings.json: PreToolUse matcher "Skill"

TOOL_INPUT=$(cat)

SKILL_NAME=$(echo "$TOOL_INPUT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    args = d.get('tool_input', {})
    print(args.get('skill', args.get('name', 'unknown')))
except:
    print('unknown')
" 2>/dev/null)

HOOKS_DIR="$(dirname "$0")"
bash "$HOOKS_DIR/telemetry-emit.sh" "skill.invoked" "skill_name=$SKILL_NAME" &

exit 0
