#!/bin/bash
# Conventional Commits 형식 검증 Hook
# PreToolUse(Bash): git commit 명령 실행 전 자동 실행

TOOL_INPUT=$(cat)

# Early-exit: 대부분의 Bash 호출은 git commit이 아님.
# python3 프로세스 스폰 비용을 피하기 위해 쉘 레벨에서 먼저 필터.
if ! printf '%s' "$TOOL_INPUT" | grep -q '"git commit'; then
    exit 0
fi

# Bash 명령어 추출
COMMAND=$(echo "$TOOL_INPUT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    cmd = d.get('tool_input', {}).get('command', '')
    print(cmd)
except:
    print('')
" 2>/dev/null)

# 명령어를 토큰 단위로 파싱하여 git commit 여부와 --no-verify 플래그를 정확히 판별
PARSED=$(echo "$COMMAND" | python3 -c "
import sys, shlex
cmd = sys.stdin.read().strip()
try:
    tokens = shlex.split(cmd)
except ValueError:
    tokens = cmd.split()
# git commit 이 실제 argv[0..1]에 있는지 확인
is_commit = len(tokens) >= 2 and tokens[0] == 'git' and tokens[1] == 'commit'
# --no-verify 가 실제 플래그로 존재하는지 확인 (메시지 본문 제외)
has_no_verify = '--no-verify' in tokens
print('commit' if is_commit else 'skip')
print('no-verify' if has_no_verify else 'ok')
" 2>/dev/null)

IS_COMMIT=$(echo "$PARSED" | sed -n '1p')
HAS_NO_VERIFY=$(echo "$PARSED" | sed -n '2p')

# git commit 명령이 아니면 종료
if [[ "$IS_COMMIT" != "commit" ]]; then
    exit 0
fi

# --no-verify 플래그가 있으면 건너뜀 (사용자가 명시적으로 우회 요청)
if [[ "$HAS_NO_VERIFY" == "no-verify" ]]; then
    exit 0
fi

# 커밋 메시지 추출 (-m 플래그 이후 값)
# heredoc(EOF) 방식과 -m "..." 방식 모두 처리
MSG=$(echo "$COMMAND" | python3 -c "
import sys, re
cmd = sys.stdin.read()

# EOF heredoc 방식: cat <<'EOF'\n...\nEOF
heredoc = re.search(r\"cat <<['\"]?EOF['\"]?\s*\n(.*?)\nEOF\", cmd, re.DOTALL)
if heredoc:
    print(heredoc.group(1).strip())
    sys.exit(0)

# -m '...' 또는 -m \"...\" 방식
m_flag = re.search(r'-m\s+[\"\'](.*?)[\"\']', cmd, re.DOTALL)
if m_flag:
    print(m_flag.group(1).strip())
    sys.exit(0)

print('')
" 2>/dev/null)

if [ -z "$MSG" ]; then
    exit 0
fi

# Conventional Commits 패턴 검증
# 형식: type(scope): message 또는 type: message
VALID_TYPES="feat|fix|refactor|test|docs|perf|chore|build|ci|revert"
PATTERN="^($VALID_TYPES)(\([a-zA-Z0-9_/-]+\))?: .+"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📝 커밋 메시지 검증"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "메시지: $MSG"

# 한국어(비 ASCII) 포함 여부 검사
if echo "$MSG" | python3 -c "
import sys
msg = sys.stdin.read().strip()
if any(ord(c) > 127 for c in msg):
    sys.exit(1)
sys.exit(0)
" 2>/dev/null; then
    :
else
    echo "❌ 커밋 메시지에 한국어(비 ASCII)가 포함되어 있습니다."
    echo "   → 커밋 메시지는 영문으로 작성해야 합니다. (CLAUDE.md 규칙)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    exit 2
fi

# Conventional Commits 형식 검사
FIRST_LINE=$(echo "$MSG" | head -1)
if echo "$FIRST_LINE" | grep -qP "$PATTERN" 2>/dev/null || \
   echo "$FIRST_LINE" | grep -qE "$PATTERN" 2>/dev/null; then
    echo "✅ 커밋 메시지 형식 통과"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    exit 0
else
    echo "❌ Conventional Commits 형식 위반"
    echo ""
    echo "   올바른 형식: type(scope): message"
    echo "   유효한 타입: feat | fix | refactor | test | docs | perf | chore | build | ci | revert"
    echo ""
    echo "   예시:"
    echo "     feat(place): implement dynamic Redis TTL based on meeting endAt"
    echo "     fix(auth): handle null userId on optional endpoint"
    echo "     refactor: remove unused benchmark dependencies"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    exit 2
fi
