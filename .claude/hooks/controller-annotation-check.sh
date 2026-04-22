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
echo "📋 Controller 어노테이션 검증: $(basename "$FILE_PATH")"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 차단 vs 경고 정책 (하네스 설계 문서 참조):
#   차단 (exit 2)  — 아키텍처/보안 계약 위반: @AuthenticationPrincipal, DpmApiResponse 누락
#   경고 (exit 0)  — 문서 품질 미비: @Tag, @Operation 누락
BLOCKERS=0
WARNINGS=0

# [BLOCK] @AuthenticationPrincipal 직접 사용 (@UserId 어노테이션으로 교체해야 함)
if grep -q "@AuthenticationPrincipal" "$FILE_PATH"; then
    echo "❌ @AuthenticationPrincipal 직접 사용 — @UserId 로 교체 필요 (CLAUDE.md 규칙)"
    BLOCKERS=$((BLOCKERS + 1))
fi

# [BLOCK] DpmApiResponse 래핑 누락 — API 응답 계약
MAPPING_COUNT=$(grep -cE '^[[:space:]]*@(Get|Post|Put|Delete|Patch)Mapping' "$FILE_PATH" 2>/dev/null || echo 0)
RESPONSE_COUNT=$(grep -cE ':[[:space:]]*DpmApiResponse<[^>]+>' "$FILE_PATH" 2>/dev/null || echo 0)
if [[ "$MAPPING_COUNT" -gt 0 && "$RESPONSE_COUNT" -eq 0 ]]; then
    echo "❌ DpmApiResponse 래핑 누락 — 모든 응답은 DpmApiResponse<T>로 감싸야 함 (CLAUDE.md 규칙)"
    BLOCKERS=$((BLOCKERS + 1))
fi

# [WARN] 클래스 레벨 @Tag 누락 — Swagger 문서 품질
if ! grep -Eq '^[[:space:]]*@Tag\(' "$FILE_PATH"; then
    echo "⚠️  @Tag(name = \"...\", description = \"...\") 누락 — 클래스 레벨 필수"
    WARNINGS=$((WARNINGS + 1))
fi

# [WARN] @Operation 누락
if ! grep -Eq '^[[:space:]]*@Operation\(' "$FILE_PATH"; then
    echo "⚠️  @Operation(summary = \"...\") 누락 — 각 엔드포인트 메서드에 필요"
    WARNINGS=$((WARNINGS + 1))
fi

# 결과 출력
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ "$BLOCKERS" -eq 0 && "$WARNINGS" -eq 0 ]]; then
    echo "✅ Controller 어노테이션 검증 통과"
elif [[ "$BLOCKERS" -eq 0 ]]; then
    echo "⚠️  $WARNINGS 개 경고 — /api-patterns 스킬 참고 (차단 아님)"
else
    echo "❌ $BLOCKERS 개 위반으로 차단 — 위 항목을 수정 후 다시 시도하세요."
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

if [[ "$BLOCKERS" -gt 0 ]]; then
    exit 2
fi
exit 0
