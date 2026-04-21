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

# 파일 확장자/패턴별로 필요한 훅만 실행
run_hook() {
    local hook="$1"
    echo "$TOOL_INPUT" | bash "$HOOKS_DIR/$hook"
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

exit 0
