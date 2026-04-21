#!/bin/bash
# /retro 리마인더 Hook
# PostToolUse(Bash): git push 명령이 성공적으로 완료된 후 /retro 실행을 권유.
# Phase 2 컴파운딩 루프의 (b) 결정사항 — "수동 호출 + 리마인드"

TOOL_INPUT=$(cat)

# Bash 명령어 및 exit code 추출
PARSED=$(echo "$TOOL_INPUT" | python3 -c "
import sys, json, shlex
try:
    d = json.load(sys.stdin)
    cmd = d.get('tool_input', {}).get('command', '')
    response = d.get('tool_response', {}) or d.get('tool_output', {})
    exit_code = response.get('exit_code', response.get('returncode', 0))
    try:
        tokens = shlex.split(cmd)
    except ValueError:
        tokens = cmd.split()
    # git push 인지 확인 (subcommand 위치: argv[0..1])
    is_push = len(tokens) >= 2 and tokens[0] == 'git' and tokens[1] == 'push'
    # --delete / --dry-run 제외 (실제 배포/반영이 아닌 경우)
    excluded = any(flag in tokens for flag in ['--delete', '-d', '--dry-run', '-n'])
    print('push' if (is_push and not excluded) else 'skip')
    print(exit_code)
except Exception:
    print('skip')
    print(1)
" 2>/dev/null)

IS_PUSH=$(echo "$PARSED" | sed -n '1p')
EXIT_CODE=$(echo "$PARSED" | sed -n '2p')

# git push 가 아니면 종료
if [[ "$IS_PUSH" != "push" ]]; then
    exit 0
fi

# push 실패 시 종료 (실패는 다른 hook/CI가 처리)
if [[ "$EXIT_CODE" != "0" ]]; then
    exit 0
fi

# 사용자에게 리마인더 출력 (Claude도 이 메시지를 볼 수 있음)
cat <<'EOF'

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💡 Push 완료 — /retro 돌릴 타이밍입니다
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
이번 작업에서 드러난 사용자·조직·프로젝트 사실, 반복된 교정,
발견된 스킬 갭을 memory / CLAUDE.md / .claude/thoughts/ 로 축적하려면
  → /retro
  (수동 호출입니다. 돌리지 않아도 됩니다.)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

EOF

exit 0
