#!/usr/bin/env python3
"""
로컬 Sonar Auto-Fix 스크립트.
이슈에서 체크된 그룹을 읽어 Claude Code CLI로 자동 수정 후 PR을 생성합니다.

사용법:
  python3 .claude/scripts/sonar-fix-local.py --issue 188
  python3 .claude/scripts/sonar-fix-local.py --groups "auto_safe-kotlin_S6518-ssolv-api-common,..."
"""
import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ALLOWED_TOOLS = (
    "Edit,MultiEdit,Glob,Grep,LS,Read,Write,"
    "Bash(git add:*),Bash(git commit:*),Bash(git status:*),"
    "Bash(git diff:*),Bash(git log:*),Bash(./gradlew:*)"
)


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    print(f"  $ {' '.join(cmd)}")
    return subprocess.run(cmd, check=True, **kwargs)


def get_repo() -> str:
    r = subprocess.run(
        ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
        capture_output=True, text=True, check=True,
    )
    return r.stdout.strip()


def get_checked_groups_from_issue(issue_number: str) -> list[str]:
    r = subprocess.run(
        ["gh", "issue", "view", issue_number, "--json", "body", "--jq", ".body"],
        capture_output=True, text=True, check=True,
    )
    ids = []
    for line in r.stdout.splitlines():
        if re.match(r"^- \[[xX]\]", line):
            m = re.search(r"`([a-z_]+-[a-zA-Z0-9_]+-ssolv-[a-z-]+)`", line)
            if m:
                ids.append(m.group(1))
    return list(dict.fromkeys(ids))  # dedup, preserve order


def fetch_sonar_groups(group_ids: list[str]) -> list[dict]:
    script = os.path.join(HERE, "sonar-fetch.py")
    r = subprocess.run(
        ["python3", script, "--output", "json"],
        capture_output=True, text=True, check=True,
    )
    data = json.loads(r.stdout)
    ids = set(group_ids)
    selected = [g for g in data["groups"] if g["id"] in ids]
    # preserve order of group_ids
    order = {gid: i for i, gid in enumerate(group_ids)}
    return sorted(selected, key=lambda g: order.get(g["id"], 999))


def fix_group(group: dict, repo: str) -> bool:
    gid     = group["id"]
    rule    = group["rule"]
    module  = group["module"]
    reason  = group.get("reason", "")
    files   = group.get("files", [])
    classif = group.get("classification", "auto_with_review")

    branch = re.sub(r"[^a-zA-Z0-9/_-]", "-", f"auto/sonar-fix/{gid}")

    print(f"\n{'='*60}")
    print(f"Group  : {gid}")
    print(f"Rule   : {rule}  Module: {module}")
    print(f"Branch : {branch}")
    print(f"Files  : {files}")
    print(f"{'='*60}")

    # checkout branch (create from dev if not exists)
    run(["git", "fetch", "origin"])
    remote_exists = subprocess.run(
        ["git", "ls-remote", "--heads", "origin", branch],
        capture_output=True, text=True,
    ).stdout.strip()

    if remote_exists:
        run(["git", "checkout", branch])
        run(["git", "pull", "origin", branch])
    else:
        run(["git", "checkout", "-b", branch, "origin/dev"])

    prompt = (
        f"Fix SonarCloud rule {rule} in module {module}. "
        f"{reason}. "
        f"Only modify these files: {', '.join(files)}. "
        f"Run ./gradlew :{module}:ktlintFormat :{module}:test before committing. "
        f"Commit message: refactor(sonar): fix {rule} in {module}."
    )

    print("\n[claude] 실행 중...")
    r = subprocess.run(["claude", "-p", prompt, "--allowedTools", ALLOWED_TOOLS])
    if r.returncode != 0:
        print(f"[warn] claude exited {r.returncode}")

    # 커밋이 생겼는지 확인
    no_diff = subprocess.run(
        ["git", "diff", "--quiet", "origin/dev...HEAD"],
        capture_output=True,
    ).returncode == 0

    if no_diff:
        print("[warn] 커밋 없음 — 스킵")
        run(["git", "checkout", "dev"])
        return False

    run(["git", "push", "-u", "origin", branch])

    labels = "sonar-auto-fix," + ("auto-merge" if classif == "auto_safe" else "review-required")
    run([
        "gh", "pr", "create",
        "--repo", repo,
        "--base", "dev",
        "--head", branch,
        "--title", f"refactor(sonar): fix {rule} in {module}",
        "--label", labels,
    ])

    run(["git", "checkout", "dev"])
    return True


def main():
    parser = argparse.ArgumentParser(description="Sonar Auto-Fix (local)")
    parser.add_argument("--issue", help="이슈 번호 (체크된 그룹 자동 파싱)")
    parser.add_argument("--groups", help="Comma-separated group IDs (직접 지정)")
    args = parser.parse_args()

    if args.groups:
        group_ids = [g.strip() for g in args.groups.split(",") if g.strip()]
    elif args.issue:
        group_ids = get_checked_groups_from_issue(args.issue)
        print(f"이슈 #{args.issue}에서 {len(group_ids)}개 그룹 감지: {group_ids}")
    else:
        parser.print_help()
        sys.exit(1)

    if not group_ids:
        print("체크된 그룹이 없습니다.")
        sys.exit(0)

    repo = get_repo()
    groups = fetch_sonar_groups(group_ids)
    if not groups:
        print("Sonar 데이터에서 해당 그룹을 찾지 못했습니다.")
        sys.exit(1)

    print(f"\n총 {len(groups)}개 그룹 처리 시작...\n")
    success, fail = 0, 0
    for g in groups:
        if fix_group(g, repo):
            success += 1
        else:
            fail += 1

    print(f"\n{'='*60}")
    print(f"완료: {success}개 PR 생성, {fail}개 스킵")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
