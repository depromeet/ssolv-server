#!/bin/bash
# 변경된 모듈만 선택적으로 테스트 실행
cd "$CLAUDE_PROJECT_DIR"

CHANGED=$(git diff --name-only && git diff --cached --name-only)

if [ -z "$CHANGED" ]; then
  echo "[test-changed-modules] No changes detected, skipping tests."
  exit 0
fi

# .kt / .kts 변경이 없으면 테스트 생략 (문서/설정/YAML/훅 등만 수정한 턴에서 불필요한 지연 방지)
if ! echo "$CHANGED" | grep -qE '\.(kt|kts)$'; then
  echo "[test-changed-modules] No .kt/.kts changes, skipping tests."
  exit 0
fi

run_core=false
run_place=false

# 공유 모듈 변경 시 두 서비스 모두 테스트
if echo "$CHANGED" | grep -qE "^ssolv-(api-common|domain|infrastructure|global-utils|batch)/"; then
  run_core=true
  run_place=true
fi

if echo "$CHANGED" | grep -q "^ssolv-api-core/"; then
  run_core=true
fi

if echo "$CHANGED" | grep -q "^ssolv-api-place/"; then
  run_place=true
fi

tasks=""
if [ "$run_core" = true ]; then tasks="$tasks :ssolv-api-core:test"; fi
if [ "$run_place" = true ]; then tasks="$tasks :ssolv-api-place:test"; fi

if [ -n "$tasks" ]; then
  echo "[test-changed-modules] Running:$tasks"
  ./gradlew $tasks --continue
else
  echo "[test-changed-modules] No testable module changes, skipping."
fi
