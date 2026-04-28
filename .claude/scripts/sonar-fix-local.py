#!/usr/bin/env python3
"""
로컬 Sonar Auto-Fix 스크립트.
이슈에서 체크된 그룹을 하나의 브랜치에서 순서대로 수정하고 PR 1개를 생성합니다.

사용법:
  python3 .claude/scripts/sonar-fix-local.py --issue 188
  python3 .claude/scripts/sonar-fix-local.py --issue 188 --groups "id1,id2"
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
    return list(dict.fromkeys(ids))


def fetch_sonar_groups(group_ids: list[str]) -> list[dict]:
    script = os.path.join(HERE, "sonar-fetch.py")
    r = subprocess.run(
        ["python3", script, "--output", "json"],
        capture_output=True, text=True, check=True,
    )
    data = json.loads(r.stdout)
    ids = set(group_ids)
    selected = [g for g in data["groups"] if g["id"] in ids]
    order = {gid: i for i, gid in enumerate(group_ids)}
    return sorted(selected, key=lambda g: order.get(g["id"], 999))


def checkout_branch(branch: str) -> None:
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


def fix_group_on_branch(group: dict) -> bool:
    """현재 브랜치에서 그룹 1개를 Claude로 수정 후 커밋. 변경사항 있으면 True."""
    rule   = group["rule"]
    module = group["module"]
    reason = group.get("reason", "")
    files  = group.get("files", [])

    commits_before = subprocess.run(
        ["git", "rev-parse", "HEAD"], capture_output=True, text=True
    ).stdout.strip()

    prompt = (
        f"Fix SonarCloud rule {rule} in module {module}. "
        f"{reason}. "
        f"Only modify these files: {', '.join(files)}. "
        f"Run ./gradlew :{module}:ktlintFormat :{module}:test before committing. "
        f"Commit message: refactor(sonar): fix {rule} in {module}."
    )

    print(f"\n[claude] {rule} / {module} 수정 중...")
    r = subprocess.run(["claude", "-p", prompt, "--allowedTools", ALLOWED_TOOLS])
    if r.returncode != 0:
        print(f"[warn] claude exited {r.returncode}")

    commits_after = subprocess.run(
        ["git", "rev-parse", "HEAD"], capture_output=True, text=True
    ).stdout.strip()

    return commits_before != commits_after


def main():
    parser = argparse.ArgumentParser(description="Sonar Auto-Fix (local) — 이슈당 PR 1개")
    parser.add_argument("--issue", required=True, help="이슈 번호")
    parser.add_argument("--groups", help="처리할 group ID comma 목록 (생략 시 이슈 체크박스 자동 파싱)")
    args = parser.parse_args()

    if args.groups:
        group_ids = [g.strip() for g in args.groups.split(",") if g.strip()]
    else:
        group_ids = get_checked_groups_from_issue(args.issue)
        print(f"이슈 #{args.issue}에서 {len(group_ids)}개 그룹 감지: {group_ids}")

    if not group_ids:
        print("체크된 그룹이 없습니다.")
        sys.exit(0)

    repo  = get_repo()
    groups = fetch_sonar_groups(group_ids)
    if not groups:
        print("Sonar 데이터에서 해당 그룹을 찾지 못했습니다.")
        sys.exit(1)

    branch = f"auto/sonar-fix/issue-{args.issue}"
    print(f"\n브랜치: {branch}")
    print(f"총 {len(groups)}개 그룹 처리 시작...\n")

    checkout_branch(branch)

    fixed = []
    skipped = []
    for g in groups:
        print(f"\n{'='*60}")
        print(f"Group : {g['id']}")
        print(f"Rule  : {g['rule']}  Module: {g['module']}")
        print(f"Files : {g.get('files', [])}")
        print(f"{'='*60}")

        if fix_group_on_branch(g):
            fixed.append(g)
        else:
            print(f"[warn] 커밋 없음 — 스킵")
            skipped.append(g)

    print(f"\n{'='*60}")
    if not fixed:
        print("수정된 그룹이 없어 PR을 생성하지 않습니다.")
        run(["git", "checkout", "dev"])
        sys.exit(0)

    run(["git", "push", "-u", "origin", branch])

    all_safe = all(g.get("classification") == "auto_safe" for g in fixed)
    labels = "sonar-auto-fix," + ("auto-merge" if all_safe else "review-required")

    fixed_summary = "\n".join(
        f"- `{g['rule']}` in `{g['module']}` ({g.get('count', len(g.get('files', [])))}건)"
        for g in fixed
    )
    body = (
        f"Closes #{args.issue}\n\n"
        f"## Fixed\n{fixed_summary}\n"
    )
    if skipped:
        skipped_summary = "\n".join(f"- `{g['id']}`" for g in skipped)
        body += f"\n## Skipped (no changes)\n{skipped_summary}\n"

    run([
        "gh", "pr", "create",
        "--repo", repo,
        "--base", "dev",
        "--head", branch,
        "--title", f"refactor(sonar): fix {len(fixed)} sonar issues (issue #{args.issue})",
        "--body", body,
        "--label", labels,
    ])

    run(["git", "checkout", "dev"])

    print(f"\n완료: {len(fixed)}개 그룹 수정, {len(skipped)}개 스킵")
    print(f"PR 생성 완료: {branch}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
