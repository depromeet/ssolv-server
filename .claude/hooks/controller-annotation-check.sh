#!/bin/bash
# Controller 파일 Swagger 어노테이션 검증 Hook
# PostToolUse(Write|Edit): Controller.kt 파일 작성/수정 시 자동 실행

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

# Controller.kt 파일이 아니면 종료
if [[ "$FILE_PATH" != *Controller.kt ]]; then
    exit 0
fi

# 파일이 존재하는지 확인
if [[ ! -f "$FILE_PATH" ]]; then
    exit 0
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📋 Controller Swagger 어노테이션 검증: $(basename $FILE_PATH)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

WARNINGS=0

# 1. 클래스 레벨 @Tag 확인
if ! grep -q "@Tag(" "$FILE_PATH"; then
    echo "⚠️  @Tag(name = \"...\", description = \"...\") 누락 — 클래스 레벨 필수"
    WARNINGS=$((WARNINGS + 1))
fi

# 2. @Operation 확인 (최소 1개 이상)
if ! grep -q "@Operation(" "$FILE_PATH"; then
    echo "⚠️  @Operation(summary = \"...\") 누락 — 각 엔드포인트 메서드에 필요"
    WARNINGS=$((WARNINGS + 1))
fi

# 3. @AuthenticationPrincipal 직접 사용 금지 (@UserId 사용해야 함)
if grep -q "@AuthenticationPrincipal" "$FILE_PATH"; then
    echo "❌  @AuthenticationPrincipal 직접 사용 — @UserId 어노테이션으로 교체 필요"
    WARNINGS=$((WARNINGS + 1))
fi

# 4. DpmApiResponse 래핑 확인
MAPPING_COUNT=$(grep -cE "@(Get|Post|Put|Delete|Patch)Mapping" "$FILE_PATH" 2>/dev/null || echo 0)
RESPONSE_COUNT=$(grep -c "DpmApiResponse" "$FILE_PATH" 2>/dev/null || echo 0)
if [[ "$MAPPING_COUNT" -gt 0 && "$RESPONSE_COUNT" -eq 0 ]]; then
    echo "⚠️  DpmApiResponse 래핑 없음 — 모든 응답은 DpmApiResponse<T>로 감싸야 함"
    WARNINGS=$((WARNINGS + 1))
fi

# 결과 출력
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ "$WARNINGS" -eq 0 ]]; then
    echo "✅ Controller 어노테이션 검증 통과"
else
    echo "⚠️  $WARNINGS 개 항목 검토 필요 — /api-patterns 스킬 참고"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
