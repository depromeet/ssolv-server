#!/usr/bin/env python3
"""
SonarCloud auto-fix queue generator.

Fetch open issues from SonarCloud, filter via allowlist, group by (rule × module),
and emit either JSON (for downstream automation) or markdown (for GitHub Issue body).

Usage:
    python3 .claude/scripts/sonar-fetch.py --output markdown
    python3 .claude/scripts/sonar-fetch.py --output json

Env:
    SONAR_TOKEN      Optional. Required for private projects.
    SONAR_PROJECT    Optional override. Defaults to value in build.gradle.kts.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST_PATH = REPO_ROOT / ".claude" / "sonar-allowlist.yml"

DEFAULT_PROJECT = "parkmineum_17th-team3-server"
SONAR_HOST = "https://sonarcloud.io"


# ──────────────────────────────────────────────────────────────────────
# Allowlist loader (minimal YAML — avoids PyYAML dependency)
# ──────────────────────────────────────────────────────────────────────


@dataclass
class Allowlist:
    auto_safe: dict[str, str] = field(default_factory=dict)
    auto_with_review: dict[str, str] = field(default_factory=dict)
    manual_only: dict[str, str] = field(default_factory=dict)
    max_effort_minutes: int = 30
    min_age_days: int = 7
    exclude_paths: list[str] = field(default_factory=list)
    # required_assignee = None → 미배정 이슈만 처리 (현재 정책)
    # 문자열 값 → 해당 사용자에게 배정된 이슈만 처리
    required_assignee: str | None = None

    def classify(self, rule: str) -> str | None:
        if rule in self.auto_safe:
            return "auto_safe"
        if rule in self.auto_with_review:
            return "auto_with_review"
        if rule in self.manual_only:
            return "manual_only"
        return None

    def reason(self, rule: str) -> str:
        return (
            self.auto_safe.get(rule)
            or self.auto_with_review.get(rule)
            or self.manual_only.get(rule)
            or ""
        )


def load_allowlist(path: Path) -> Allowlist:
    """Parse the small subset of YAML used by sonar-allowlist.yml.

    Format:
        section_name:
          - rule: kotlin:S1234
            reason: "..."
        filters:
          max_effort_minutes: 30
    """
    a = Allowlist()
    section: str | None = None
    in_filters = False
    pending_rule: str | None = None
    def strip_inline_comment(s: str) -> str:
        # Strip ` # comment` (preserves `#` inside quoted strings — naive but YAML stays simple here).
        in_quote: str | None = None
        for i, ch in enumerate(s):
            if ch in ('"', "'"):
                in_quote = None if in_quote == ch else (in_quote or ch)
            elif ch == "#" and in_quote is None and (i == 0 or s[i - 1] in " \t"):
                return s[:i].rstrip()
        return s.rstrip()

    for raw in path.read_text(encoding="utf-8").splitlines():
        line = strip_inline_comment(raw.rstrip())
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        # Top-level section
        if not line.startswith(" "):
            key = line.split(":", 1)[0].strip()
            if key in {"auto_safe", "auto_with_review", "manual_only"}:
                section, in_filters = key, False
            elif key == "filters":
                section, in_filters = None, True
            else:
                section, in_filters = None, False
            pending_rule = None
            continue
        stripped = line.strip()
        if in_filters:
            if ":" in stripped:
                k, v = (s.strip() for s in stripped.split(":", 1))
                v = v.strip().strip('"').strip("'")
                if k == "max_effort_minutes":
                    a.max_effort_minutes = int(v)
                elif k == "min_age_days":
                    a.min_age_days = int(v)
                elif k == "required_assignee":
                    a.required_assignee = None if v in ("null", "~", "") else v
                elif k.startswith("- "):
                    a.exclude_paths.append(k[2:].strip().strip('"'))
            elif stripped.startswith("- "):
                a.exclude_paths.append(stripped[2:].strip().strip('"'))
            continue
        if section is None:
            continue
        m = re.match(r"-\s*rule:\s*(.+)", stripped)
        if m:
            pending_rule = m.group(1).strip().strip('"').strip("'")
            getattr(a, section)[pending_rule] = ""
            continue
        m = re.match(r"reason:\s*(.+)", stripped)
        if m and pending_rule is not None:
            reason = m.group(1).strip().strip('"').strip("'")
            getattr(a, section)[pending_rule] = reason
    return a


# ──────────────────────────────────────────────────────────────────────
# SonarCloud API
# ──────────────────────────────────────────────────────────────────────


def fetch_issues(project: str, token: str | None) -> list[dict[str, Any]]:
    """Fetch all open CODE_SMELL / BUG issues at MAJOR/MINOR severity."""
    issues: list[dict[str, Any]] = []
    page = 1
    while True:
        params = {
            "componentKeys": project,
            "types": "CODE_SMELL,BUG",
            "severities": "MAJOR,MINOR",
            "statuses": "OPEN",
            "resolved": "false",
            "ps": "500",
            "p": str(page),
        }
        url = f"{SONAR_HOST}/api/issues/search?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        batch = data.get("issues", [])
        issues.extend(batch)
        total = data.get("total", 0)
        if len(issues) >= total or not batch:
            break
        page += 1
        if page > 20:  # safety
            break
    return issues


# ──────────────────────────────────────────────────────────────────────
# Filtering & grouping
# ──────────────────────────────────────────────────────────────────────


_EFFORT_RE = re.compile(r"(\d+)(min|h|d)")


def effort_minutes(effort: str | None) -> int:
    if not effort:
        return 0
    m = _EFFORT_RE.match(effort)
    if not m:
        return 9999
    n, unit = int(m.group(1)), m.group(2)
    return n if unit == "min" else n * 60 if unit == "h" else n * 480


def parse_module(component: str) -> str:
    """parkmineum_...:ssolv-api-core/src/main/... → ssolv-api-core"""
    m = re.search(r":(ssolv-[^/]+)/", component)
    return m.group(1) if m else "unknown"


def parse_path(component: str) -> str:
    """parkmineum_...:ssolv-x/src/main/foo.kt → ssolv-x/src/main/foo.kt"""
    return component.split(":", 1)[1] if ":" in component else component


def days_since(iso_ts: str) -> int:
    if not iso_ts:
        return 0
    s = iso_ts.replace("Z", "+00:00")
    # SonarCloud emits `+0000` (no colon); fromisoformat needs `+00:00` on <3.11.
    if re.search(r"[+-]\d{4}$", s):
        s = s[:-2] + ":" + s[-2:]
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        return 0
    return (datetime.now(timezone.utc) - dt).days


def _path_excluded(path: str, patterns: list[str]) -> bool:
    """Glob-aware substring match. `**/X/**` 는 `/X/` 부분문자열로 단순화한다."""
    for pattern in patterns:
        # `**/segment/**` → `/segment/` 부분 매칭
        core = pattern.strip("*/")
        if not core:
            continue
        needle = f"/{core}/"
        if needle in path or path.startswith(f"{core}/"):
            return True
    return False


def issue_classification(
    issue: dict[str, Any], allow: Allowlist
) -> tuple[str | None, str | None]:
    """Return (classification, gate_reason).

    classification: 최종 분류 (`auto_safe` / `auto_with_review` / `manual_only`)
                    또는 룰이 allowlist 에 없으면 None.
    gate_reason:    분류는 매칭됐으나 게이팅으로 제외됐을 때의 사유 ("effort" /
                    "age" / "assignee" / "path"). 매칭 실패면 None.
    """
    rule = issue.get("rule", "")
    cls = allow.classify(rule)
    if cls is None:
        return None, None
    if cls == "manual_only":
        return cls, None
    # 게이팅 — auto_safe / auto_with_review 만 적용
    if effort_minutes(issue.get("effort", "")) > allow.max_effort_minutes:
        return None, "effort"
    if days_since(issue.get("creationDate", "")) < allow.min_age_days:
        return None, "age"
    assignee = issue.get("assignee")
    if allow.required_assignee is None:
        if assignee:
            return None, "assignee"
    elif assignee != allow.required_assignee:
        return None, "assignee"
    path = parse_path(issue.get("component", ""))
    if _path_excluded(path, allow.exclude_paths):
        return None, "path"
    # BUG type — even on auto_safe rules, force review
    if issue.get("type") == "BUG" and cls == "auto_safe":
        return "auto_with_review", None
    return cls, None


def group_issues(
    issues: list[dict[str, Any]], allow: Allowlist
) -> tuple[
    dict[tuple[str, str, str], list[dict[str, Any]]],
    list[dict[str, Any]],
    list[tuple[dict[str, Any], str]],
]:
    """Group filtered issues by (classification, rule, module).

    Returns (groups, excluded, silently_filtered):
      - groups:            처리 대상 그룹
      - excluded:          manual_only — 정보 노출용
      - silently_filtered: allowlist 에는 있으나 게이팅(effort/age/assignee/path)으로
                           제외된 이슈와 그 사유. total_count 정확도를 위해 추적.
    """
    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    excluded: list[dict[str, Any]] = []
    silently_filtered: list[tuple[dict[str, Any], str]] = []
    for issue in issues:
        cls, gate = issue_classification(issue, allow)
        if cls == "manual_only":
            excluded.append(issue)
            continue
        if cls is None:
            if gate is not None:
                silently_filtered.append((issue, gate))
            continue
        module = parse_module(issue.get("component", ""))
        groups[(cls, issue.get("rule", ""), module)].append(issue)
    return groups, excluded, silently_filtered


def detect_file_conflicts(
    groups: dict[tuple[str, str, str], list[dict[str, Any]]],
) -> list[tuple[str, str, str]]:
    """Find groups that touch the same file → must be serialized."""
    file_to_groups: dict[str, set[tuple[str, str, str]]] = defaultdict(set)
    for key, issues in groups.items():
        for issue in issues:
            file_to_groups[parse_path(issue.get("component", ""))].add(key)
    conflicts: set[tuple[tuple[str, str, str], tuple[str, str, str]]] = set()
    for keys in file_to_groups.values():
        if len(keys) <= 1:
            continue
        ordered = sorted(keys)
        for i in range(len(ordered)):
            for j in range(i + 1, len(ordered)):
                conflicts.add((ordered[i], ordered[j]))
    # Flatten for serialization
    return [(f"{a[0]}/{a[1]}/{a[2]}", f"{b[0]}/{b[1]}/{b[2]}", "shared file") for a, b in conflicts]


# ──────────────────────────────────────────────────────────────────────
# Output renderers
# ──────────────────────────────────────────────────────────────────────


CLASS_BADGE = {
    "auto_safe": "🟢",
    "auto_with_review": "🟡",
    "manual_only": "🔴",
}


def render_json(
    groups: dict[tuple[str, str, str], list[dict[str, Any]]],
    excluded: list[dict[str, Any]],
    silently_filtered: list[tuple[dict[str, Any], str]],
    allow: Allowlist,
) -> str:
    silent_counts: dict[str, int] = defaultdict(int)
    for _, gate in silently_filtered:
        silent_counts[gate] += 1
    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "groups": [
            {
                "id": f"{cls}-{rule.replace(':', '_')}-{module}",
                "classification": cls,
                "rule": rule,
                "module": module,
                "count": len(items),
                "reason": allow.reason(rule),
                "files": sorted({parse_path(i.get("component", "")) for i in items}),
                "issue_keys": [i.get("key") for i in items],
            }
            for (cls, rule, module), items in sorted(groups.items())
        ],
        "manual_only": [
            {
                "rule": i.get("rule"),
                "file": parse_path(i.get("component", "")),
                "line": i.get("line"),
                "message": i.get("message", ""),
                "reason": allow.reason(i.get("rule", "")),
            }
            for i in excluded
        ],
        "silently_filtered": dict(silent_counts),
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def render_markdown(
    groups: dict[tuple[str, str, str], list[dict[str, Any]]],
    excluded: list[dict[str, Any]],
    silently_filtered: list[tuple[dict[str, Any], str]],
    conflicts: list[tuple[str, str, str]],
    allow: Allowlist,
    project: str,
) -> str:
    by_class: dict[str, list[tuple[tuple[str, str, str], list[dict[str, Any]]]]] = defaultdict(list)
    for key, items in groups.items():
        by_class[key[0]].append((key, items))

    silent_counts: dict[str, int] = defaultdict(int)
    for _, gate in silently_filtered:
        silent_counts[gate] += 1
    silent_total = sum(silent_counts.values())

    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    actionable_total = sum(len(v) for v in groups.values()) + len(excluded)
    total_count = actionable_total + silent_total
    safe_count = sum(len(v) for k, v in groups.items() if k[0] == "auto_safe")
    review_count = sum(len(v) for k, v in groups.items() if k[0] == "auto_with_review")
    manual_count = len(excluded)

    out: list[str] = []
    out.append(f"# 🔍 Sonar Auto-Fix Queue — {today}")
    out.append("")
    out.append(f"> 자동 생성됨 by `.github/workflows/sonar-issue.yml`")
    out.append(f"> 데이터: SonarCloud `{project}` · OPEN · CODE_SMELL/BUG · MAJOR/MINOR")
    out.append(
        f"> 총 **{total_count}건** → 🟢 Auto-safe {safe_count} / "
        f"🟡 Auto-with-review {review_count} / 🔴 Manual {manual_count}"
        + (f" / ⚪ Filtered {silent_total}" if silent_total else "")
    )
    if silent_total:
        breakdown = ", ".join(f"{k}={v}" for k, v in sorted(silent_counts.items()))
        out.append(f"> Filtered breakdown: {breakdown}")
    out.append("")
    out.append("## 사용법")
    out.append("- 체크박스 선택 후 댓글에 `/sonar-fix run` → 선택된 그룹 직렬 처리")
    out.append("- 🟢 = PR 자동 머지 / 🟡 = PR 만 생성 (사람 리뷰 필수) / 🔴 = 작업 안 함")
    out.append("- 실패 시 이 Issue 에 escalate 코멘트")
    out.append("")

    if not by_class:
        out.append("## 처리할 그룹 없음")
        out.append("")
        out.append(
            "현재 allowlist 와 필터 조건을 만족하는 OPEN 이슈가 없습니다. "
            "(`.claude/sonar-allowlist.yml` 참고)"
        )
        out.append("")

    for cls in ("auto_safe", "auto_with_review"):
        items_for_class = by_class.get(cls, [])
        if not items_for_class:
            continue
        badge = CLASS_BADGE[cls]
        title = "Auto-safe" if cls == "auto_safe" else "Auto-with-review (자동 머지 X)"
        out.append(f"## {badge} {title}")
        out.append("")
        for idx, ((_cls, rule, module), issues) in enumerate(
            sorted(items_for_class, key=lambda x: -len(x[1])), start=1
        ):
            group_id = f"{cls}-{rule.replace(':', '_')}-{module}"
            sample = issues[0]
            effort_total = sum(effort_minutes(i.get("effort", "")) for i in issues)
            out.append(
                f"- [ ] `{group_id}` · `{rule}` × `{module}` ({len(issues)}건)"
                f" — {sample.get('message','')[:80]} _(~{effort_total}min)_"
            )
            out.append("")
            out.append(f"  **근거**: {allow.reason(rule)}")
            out.append("")
            out.append("  <details><summary>대상 파일</summary>")
            out.append("")
            out.append("  | 파일 | 라인 |")
            out.append("  |---|---:|")
            for i in issues[:20]:
                path = parse_path(i.get("component", ""))
                out.append(f"  | `{path}` | {i.get('line', '-')} |")
            if len(issues) > 20:
                out.append(f"  | ...{len(issues)-20}건 더 | |")
            out.append("")
            out.append("  </details>")
            out.append("")

    if excluded:
        out.append("## 🔴 Manual only (작업 안 함, 참고용)")
        out.append("")
        out.append("| 룰 | 파일 | 라인 | 사유 |")
        out.append("|---|---|---:|---|")
        for i in excluded[:30]:
            out.append(
                f"| `{i.get('rule')}` | `{parse_path(i.get('component', ''))}` "
                f"| {i.get('line', '-')} | {allow.reason(i.get('rule', ''))} |"
            )
        if len(excluded) > 30:
            out.append(f"| ...{len(excluded)-30}건 더 | | | |")
        out.append("")

    if conflicts:
        out.append("## ⚠️ 직렬화 필요 (같은 파일 건드림)")
        out.append("")
        for a, b, _ in conflicts[:20]:
            out.append(f"- `{a}` ↔ `{b}`")
        out.append("")

    out.append("---")
    out.append("")
    out.append(
        f"🤖 Allowlist: `.claude/sonar-allowlist.yml` · "
        f"규칙 추가/제거는 PR 머지 이력 기반"
    )
    return "\n".join(out)


# ──────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--output", choices=["json", "markdown"], default="markdown")
    p.add_argument("--project", default=os.environ.get("SONAR_PROJECT", DEFAULT_PROJECT))
    p.add_argument("--allowlist", default=str(ALLOWLIST_PATH))
    p.add_argument("--input", help="Local JSON file for testing (skips API call)")
    args = p.parse_args()

    allow = load_allowlist(Path(args.allowlist))

    if args.input:
        with open(args.input, encoding="utf-8") as f:
            issues = json.load(f).get("issues", [])
    else:
        token = os.environ.get("SONAR_TOKEN")
        try:
            issues = fetch_issues(args.project, token)
        except Exception as exc:  # noqa: BLE001 — surface to CI logs
            print(f"::error::SonarCloud fetch failed: {exc}", file=sys.stderr)
            return 1

    groups, excluded, silently_filtered = group_issues(issues, allow)
    conflicts = detect_file_conflicts(groups)

    if args.output == "json":
        print(render_json(groups, excluded, silently_filtered, allow))
    else:
        print(
            render_markdown(
                groups, excluded, silently_filtered, conflicts, allow, args.project
            )
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
