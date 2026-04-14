#!/bin/bash
# 모듈 간 금지된 import 방향 검사 Hook
# PostToolUse(Edit|Write): .kt 파일 저장 시 자동 실행
#
# 금지된 방향:
#   ssolv-api-core  ↔  ssolv-api-place  (상호 참조 금지)
#   ssolv-domain       ssolv-infrastructure를 import하면 안 됨
#   ssolv-domain       ssolv-api-common/api-core/api-place를 import하면 안 됨

TOOL_INPUT=$(cat)

# 수정된 파일 경로 추출
FILE_PATH=$(echo "$TOOL_INPUT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    path = d.get('tool_input', {}).get('file_path', '')
    print(path)
except:
    print('')
" 2>/dev/null)

# .kt 파일이 아니면 종료
if [[ "$FILE_PATH" != *.kt ]]; then
    exit 0
fi

# 파일이 존재하는지 확인
if [[ ! -f "$FILE_PATH" ]]; then
    exit 0
fi

# 테스트 파일 제외
if [[ "$FILE_PATH" == *Test* || "$FILE_PATH" == */test/* || "$FILE_PATH" == */testFixtures/* ]]; then
    exit 0
fi

VIOLATIONS=0
MESSAGES=()

# 1. ssolv-api-core 파일이 ssolv-api-place 패키지를 import하는 경우
if [[ "$FILE_PATH" == */ssolv-api-core/* ]]; then
    if grep -qE '^import org\.depromeet\.team3\.(place|placelike)\.' "$FILE_PATH" 2>/dev/null; then
        MESSAGES+=("ssolv-api-core에서 ssolv-api-place 패키지(place, placelike) import 금지")
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
fi

# 2. ssolv-api-place 파일이 ssolv-api-core 전용 패키지를 import하는 경우
if [[ "$FILE_PATH" == */ssolv-api-place/* ]]; then
    if grep -qE '^import org\.depromeet\.team3\.(meeting|auth|survey|surveycategory|station|meetingattendee|notification)\.' "$FILE_PATH" 2>/dev/null; then
        MESSAGES+=("ssolv-api-place에서 ssolv-api-core 전용 패키지 import 금지")
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
fi

# 3. ssolv-domain 파일이 infrastructure/api 패키지를 import하는 경우
if [[ "$FILE_PATH" == */ssolv-domain/* ]]; then
    if grep -qE '^import org\.depromeet\.team3\.(mapper|config|security)\.' "$FILE_PATH" 2>/dev/null; then
        MESSAGES+=("ssolv-domain에서 infrastructure/api 계층 import 금지")
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
    # JPA 어노테이션 import
    if grep -qE '^import (jakarta\.persistence|org\.springframework\.data\.jpa)\.' "$FILE_PATH" 2>/dev/null; then
        MESSAGES+=("ssolv-domain에서 JPA 어노테이션 import 금지 — Entity는 ssolv-infrastructure에 작성")
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
fi

if [[ "$VIOLATIONS" -gt 0 ]]; then
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "❌ 모듈 의존성 방향 위반: $(basename "$FILE_PATH")"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    for msg in "${MESSAGES[@]}"; do
        echo "   → $msg"
    done
    echo ""
    echo "   허용된 의존 방향:"
    echo "   ssolv-api-core  →  ssolv-api-common, ssolv-domain, ssolv-infrastructure, ssolv-global-utils"
    echo "   ssolv-api-place →  ssolv-api-common, ssolv-domain, ssolv-infrastructure, ssolv-global-utils"
    echo "   ssolv-domain    →  ssolv-global-utils 만 허용"
    echo ""
    echo "   → /architecture 스킬 참고"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    exit 2
fi

exit 0
