#!/bin/bash
# @Value 직접 주입 금지 검증 Hook
# PostToolUse(Edit|Write): .kt 파일 저장 시 자동 실행
# CLAUDE.md 규칙: "never inject properties directly with @Value"

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

# 테스트 파일은 제외 (테스트에서 @Value 허용)
if [[ "$FILE_PATH" == *Test* || "$FILE_PATH" == */test/* || "$FILE_PATH" == */testFixtures/* ]]; then
    exit 0
fi

# @Value 직접 주입 패턴 탐지
# 라인 시작 기준으로 실제 어노테이션만 검사 (주석 제외)
VALUE_COUNT=$(grep -cE '^[[:space:]]*@Value\(' "$FILE_PATH" 2>/dev/null || echo 0)

if [[ "$VALUE_COUNT" -gt 0 ]]; then
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "❌ @Value 직접 주입 감지: $(basename "$FILE_PATH")"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "   @Value로 프로퍼티를 직접 주입하지 않아야 합니다. (CLAUDE.md 규칙)"
    echo ""
    echo "   ❌ 금지:"
    echo "     @Value(\"\${jwt.secret}\") private lateinit var secret: String"
    echo ""
    echo "   ✅ 올바른 방법 — @ConfigurationProperties 클래스 생성:"
    echo "     @Component"
    echo "     @ConfigurationProperties(prefix = \"jwt\")"
    echo "     data class JwtProperties(var secret: String = \"\")"
    echo ""
    echo "   → /domain-model 스킬의 '@ConfigurationProperties 규칙' 참고"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    exit 2
fi

exit 0
