#!/bin/bash
# IaC 보안 자동 감사 Hook
# PostToolUse: .tf 파일 수정 시 자동 실행

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

# .tf 파일이 아니면 종료
if [[ "$FILE_PATH" != *.tf ]]; then
    exit 0
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🔍 IaC 보안 감사: $(basename $FILE_PATH)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

WARNINGS=0

# 파일이 존재하는지 확인
if [[ ! -f "$FILE_PATH" ]]; then
    exit 0
fi

# 1. RDS 암호화 확인
if grep -q "aws_db_instance" "$FILE_PATH"; then
    if ! grep -q "storage_encrypted\s*=\s*true" "$FILE_PATH"; then
        echo "⚠️  RDS storage_encrypted = true 누락"
        WARNINGS=$((WARNINGS + 1))
    fi
    if ! grep -q "deletion_protection\s*=\s*true" "$FILE_PATH"; then
        echo "⚠️  RDS deletion_protection = true 누락"
        WARNINGS=$((WARNINGS + 1))
    fi
    if grep -q "backup_retention_period\s*=\s*0" "$FILE_PATH"; then
        echo "⚠️  RDS backup_retention_period = 0 (백업 비활성화)"
        WARNINGS=$((WARNINGS + 1))
    fi
    if grep -q "publicly_accessible\s*=\s*true" "$FILE_PATH"; then
        echo "❌  RDS publicly_accessible = true (퍼블릭 노출!)"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# 2. EC2 IMDSv2 확인
if grep -q "aws_instance" "$FILE_PATH"; then
    if ! grep -q "http_tokens\s*=\s*\"required\"" "$FILE_PATH"; then
        echo "⚠️  EC2 IMDSv2 미강제 (http_tokens = \"required\" 누락)"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# 3. EBS 암호화 확인
if grep -q "aws_ebs_volume\|aws_instance" "$FILE_PATH"; then
    if grep -q "encrypted\s*=\s*false" "$FILE_PATH"; then
        echo "⚠️  EBS encrypted = false 명시됨"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# 4. 민감 포트 직접 노출 확인 (3306, 6379)
if grep -q "aws_security_group" "$FILE_PATH"; then
    if grep -A5 "from_port\s*=\s*3306\|from_port\s*=\s*6379" "$FILE_PATH" | grep -q "0\.0\.0\.0/0"; then
        echo "❌  민감 포트(3306/6379)가 0.0.0.0/0에 노출됨"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# 5. tags 누락 확인
RESOURCE_COUNT=$(grep -c "^resource " "$FILE_PATH" 2>/dev/null || echo 0)
TAGS_COUNT=$(grep -c "tags\s*=" "$FILE_PATH" 2>/dev/null || echo 0)
if [[ "$RESOURCE_COUNT" -gt 0 && "$TAGS_COUNT" -eq 0 ]]; then
    echo "⚠️  tags 정의 없음 ($RESOURCE_COUNT 개 리소스)"
    WARNINGS=$((WARNINGS + 1))
fi

# 결과 출력
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ "$WARNINGS" -eq 0 ]]; then
    echo "✅ 보안 감사 통과 (자동 체크)"
else
    echo "⚠️  $WARNINGS 개 항목 검토 필요 — /iac-audit 으로 상세 감사 실행"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
