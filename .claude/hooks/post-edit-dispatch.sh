#!/bin/bash
# PostToolUse(Edit|Write) 통합 디스패처
# 파일 경로에 따라 관련 검증 훅만 선택적으로 실행.
#
# 이전 구조: settings.json에서 훅 4개를 모두 매번 실행 → 프로세스 4개 스폰 + JSON 파싱 4회
# 현재 구조: 이 스크립트 1회 실행 후 경로 매칭되는 훅만 파이프로 전달.

TOOL_INPUT=$(cat)

FILE_PATH=$(echo "$TOOL_INPUT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get('tool_input', {}).get('file_path', ''))
except:
    print('')
" 2>/dev/null)

# 파일 경로를 찾지 못했으면 조용히 종료 (Edit 외 툴 등)
[ -z "$FILE_PATH" ] && exit 0

HOOKS_DIR="$(dirname "$0")"

# 차단 신호(exit 2)는 반드시 상위로 전파되어야 함 — 안 그러면 모든 blocking 훅이 무음 실패함.
# 정책: 하나라도 exit 2 면 최종 exit 2. 그 외 비정상은 로그만 남기고 통과.
FINAL_EXIT=0
run_hook() {
    local hook="$1"
    echo "$TOOL_INPUT" | bash "$HOOKS_DIR/$hook"
    local rc=$?
    if [ "$rc" -eq 2 ]; then
        FINAL_EXIT=2
    elif [ "$rc" -ne 0 ]; then
        echo "⚠️  $hook exited with code $rc (non-blocking)" >&2
    fi
}

case "$FILE_PATH" in
    *.tf)
        run_hook iac-security-check.sh
        ;;
    *Controller.kt)
        run_hook controller-annotation-check.sh
        run_hook value-injection-check.sh
        run_hook module-import-check.sh
        ;;
    *.kt|*.kts)
        run_hook value-injection-check.sh
        run_hook module-import-check.sh
        ;;
esac

exit "$FINAL_EXIT"
