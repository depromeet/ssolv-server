#!/usr/bin/env bash
set -eo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/measure-selective-ci.sh [git-diff-range] [--runs N] [--warmup N] [--mode warm|cold] [--profile]
  scripts/measure-selective-ci.sh --changed-files-file <path> [--runs N] [--warmup N] [--mode warm|cold]
  scripts/measure-selective-ci.sh --scenario <name|random> [--runs N] [--warmup N] [--mode warm|cold] [--profile] [--plan-only]

Examples:
  scripts/measure-selective-ci.sh origin/main...HEAD
  scripts/measure-selective-ci.sh --changed-files-file /tmp/place-change.txt --runs 3
  scripts/measure-selective-ci.sh --scenario random --runs 3 --warmup 1 --mode warm --profile
  scripts/measure-selective-ci.sh --scenario api-core --runs 3 --warmup 1 --mode warm --profile
  scripts/measure-selective-ci.sh --scenario place --runs 3 --mode cold
  scripts/measure-selective-ci.sh --scenario random --plan-only
  scripts/measure-selective-ci.sh origin/dev...HEAD --runs 3 --warmup 1 --profile

Measures full pipeline time vs selective pipeline time based on selective-ci-plan.sh.
The default warm mode uses --rerun-tasks to avoid UP-TO-DATE test timings.
Cold mode uses clean + --no-build-cache for a more conservative measurement.

Scenarios:
  core, api-core, place, batch, common, domain, infrastructure, terraform, gradle, random
USAGE
}

range="origin/dev...HEAD"
runs=1
warmup=0
mode="warm"
profile=false
changed_files_file=""
scenario=""
resolved_scenario="custom"
plan_only=false
generated_files=()
generated_changed_files_file=""

cleanup() {
  rm -f "$plan_env"

  local file
  for file in "${generated_files[@]}"; do
    rm -f "$file"
  done

  if [[ -n "$generated_changed_files_file" ]]; then
    rm -f "$generated_changed_files_file"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --runs)
      runs="${2:-}"
      shift 2
      ;;
    --warmup)
      warmup="${2:-}"
      shift 2
      ;;
    --mode)
      mode="${2:-}"
      shift 2
      ;;
    --profile)
      profile=true
      shift
      ;;
    --changed-files-file)
      changed_files_file="${2:-}"
      shift 2
      ;;
    --scenario)
      scenario="${2:-}"
      shift 2
      ;;
    --plan-only)
      plan_only=true
      shift
      ;;
    *)
      range="$1"
      shift
      ;;
  esac
done

if ! [[ "$runs" =~ ^[0-9]+$ ]] || [[ "$runs" -lt 1 ]]; then
  echo "--runs must be a positive integer" >&2
  exit 1
fi

if ! [[ "$warmup" =~ ^[0-9]+$ ]]; then
  echo "--warmup must be a non-negative integer" >&2
  exit 1
fi

case "$mode" in
  warm|cold) ;;
  *)
    echo "--mode must be either warm or cold" >&2
    exit 1
    ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
plan_env="$(mktemp)"
trap cleanup EXIT

cd "$repo_root"

create_probe_file() {
  local path="$1"
  mkdir -p "$(dirname "$path")"
  {
    echo "selective-ci probe"
    echo "created_at=$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  } > "$path"
  generated_files+=("$path")
  printf '%s\n' "$path" >> "$generated_changed_files_file"
}

create_scenario() {
  local name="$1"
  local stamp
  stamp="$(date +%s)-$RANDOM"
  generated_changed_files_file="$(mktemp)"

  if [[ "$name" == "random" ]]; then
    local scenarios=(api-core place batch common domain infrastructure gradle)
    name="${scenarios[$((RANDOM % ${#scenarios[@]}))]}"
  fi

  resolved_scenario="$name"

  case "$name" in
    core|api-core)
      create_probe_file "ssolv-api-core/.selective-ci-probe-${stamp}.txt"
      ;;
    place)
      create_probe_file "ssolv-api-place/.selective-ci-probe-${stamp}.txt"
      ;;
    batch)
      create_probe_file "ssolv-batch/.selective-ci-probe-${stamp}.txt"
      ;;
    common)
      create_probe_file "ssolv-api-common/.selective-ci-probe-${stamp}.txt"
      ;;
    domain)
      create_probe_file "ssolv-domain/.selective-ci-probe-${stamp}.txt"
      ;;
    infrastructure)
      create_probe_file "ssolv-infrastructure/.selective-ci-probe-${stamp}.txt"
      ;;
    terraform)
      create_probe_file "deploy/terraform/modules/compute/.selective-ci-probe-${stamp}.txt"
      create_probe_file "deploy/terraform/modules/storage/.selective-ci-probe-${stamp}.txt"
      ;;
    gradle)
      # Full-validation scenario. The file is not edited because touching build
      # scripts would be risky; the changed-file list still exercises the logic.
      printf '%s\n' "build.gradle.kts" >> "$generated_changed_files_file"
      ;;
    *)
      echo "Unknown scenario: $name" >&2
      usage >&2
      exit 1
      ;;
  esac

  changed_files_file="$generated_changed_files_file"
  echo "Generated measurement scenario: $name"
  echo "Changed-files list: $changed_files_file"
}

if [[ -n "$scenario" && -n "$changed_files_file" ]]; then
  echo "Use either --scenario or --changed-files-file, not both." >&2
  exit 1
fi

if [[ -n "$scenario" ]]; then
  create_scenario "$scenario"
fi

if [[ -n "$changed_files_file" ]]; then
  if [[ -z "$scenario" ]]; then
    resolved_scenario="changed-files"
  fi
  SELECTIVE_CI_ENV_FILE="$plan_env" "$script_dir/selective-ci-plan.sh" --changed-files-file "$changed_files_file"
else
  resolved_scenario="diff-range"
  SELECTIVE_CI_ENV_FILE="$plan_env" "$script_dir/selective-ci-plan.sh" "$range"
fi
# shellcheck disable=SC1090
source "$plan_env"

if [[ "$plan_only" == "true" ]]; then
  echo
  echo "Plan-only mode enabled. Skipping Gradle timing."
  cleanup
  trap - EXIT
  exit 0
fi

if [[ "${has_test_tasks:-false}" != "true" ]]; then
  echo
  echo "No selective test tasks were selected for this range."
  exit 0
fi

profile_flag=()
if [[ "$profile" == "true" ]]; then
  profile_flag=(--profile)
fi

full_pipeline_tasks=(
  test
  :ssolv-api-core:bootJar
  :ssolv-api-place:jar
  :ssolv-batch:bootJar
)

read -r -a selective_test_task_array <<< "$test_tasks"
read -r -a selective_package_task_array <<< "$package_tasks"
selective_pipeline_tasks=("${selective_test_task_array[@]}" "${selective_package_task_array[@]}")

gradle_command_for() {
  local scope="$1"
  shift || true

  if [[ "$mode" == "cold" ]]; then
    if [[ "$scope" == "full" ]]; then
      GRADLE_COMMAND=(./gradlew clean "${full_pipeline_tasks[@]}" --no-build-cache "${profile_flag[@]}")
    else
      GRADLE_COMMAND=(./gradlew clean "${selective_pipeline_tasks[@]}" --no-build-cache "${profile_flag[@]}")
    fi
  else
    if [[ "$scope" == "full" ]]; then
      GRADLE_COMMAND=(./gradlew "${full_pipeline_tasks[@]}" --rerun-tasks "${profile_flag[@]}")
    else
      GRADLE_COMMAND=(./gradlew "${selective_pipeline_tasks[@]}" --rerun-tasks "${profile_flag[@]}")
    fi
  fi
}

measure_command() {
  local label="$1"
  shift
  local start
  local end

  echo
  echo "[$label]"
  echo "Command: $*"
  start="$(date +%s)"
  "$@"
  end="$(date +%s)"
  MEASURE_SECONDS=$((end - start))
}

run_pair() {
  local label="$1"
  echo
  echo "$label"

  gradle_command_for "full"
  measure_command "full pipeline" "${GRADLE_COMMAND[@]}"
  PAIR_FULL_SECONDS="$MEASURE_SECONDS"

  gradle_command_for "selective"
  measure_command "selective pipeline" "${GRADLE_COMMAND[@]}"
  PAIR_SELECTIVE_SECONDS="$MEASURE_SECONDS"
}

for ((warmup_run = 1; warmup_run <= warmup; warmup_run++)); do
  run_pair "Warmup run $warmup_run/$warmup"
  echo "Warmup $warmup_run result: full=${PAIR_FULL_SECONDS}s selective=${PAIR_SELECTIVE_SECONDS}s"
done

sum_full=0
sum_selective=0

for ((run = 1; run <= runs; run++)); do
  run_pair "Measurement run $run/$runs"
  full_seconds="$PAIR_FULL_SECONDS"
  selective_seconds="$PAIR_SELECTIVE_SECONDS"

  sum_full=$((sum_full + full_seconds))
  sum_selective=$((sum_selective + selective_seconds))

  echo "Run $run result: full=${full_seconds}s selective=${selective_seconds}s"
done

avg_full=$((sum_full / runs))
avg_selective=$((sum_selective / runs))

if [[ "$avg_full" -eq 0 ]]; then
  reduction="0"
else
  reduction="$(awk -v full="$avg_full" -v selective="$avg_selective" 'BEGIN { printf "%.1f", ((full - selective) / full) * 100 }')"
fi

report_dir="build/reports/selective-ci"
mkdir -p "$report_dir"
timestamp="$(date +"%Y%m%d-%H%M%S")"
report_base="${resolved_scenario}-${mode}-${timestamp}"
summary_md="$report_dir/${report_base}.md"
summary_csv="$report_dir/${report_base}.csv"
latest_md="$report_dir/latest.md"
latest_csv="$report_dir/latest.csv"

full_task_list="$(printf '%s<br>' "${full_pipeline_tasks[@]}")"
selective_task_list="$(printf '%s<br>' "${selective_pipeline_tasks[@]}")"
portfolio_sentence="Gradle 멀티모듈 변경 영향 분석을 기반으로 ${resolved_scenario} 변경 시 전체 파이프라인 대비 선택적 파이프라인 실행 시간을 ${avg_full}s에서 ${avg_selective}s로 줄여 약 ${reduction}% 단축했다."

cat > "$summary_md" <<REPORT_MD
# Selective CI Performance Summary

## Result

| Scenario | Mode | Runs | Warmup | Full Pipeline Avg | Selective Pipeline Avg | Reduction |
|---|---:|---:|---:|---:|---:|---:|
| \`${resolved_scenario}\` | \`${mode}\` | ${runs} | ${warmup} | ${avg_full}s | ${avg_selective}s | ${reduction}% |

## Portfolio Sentence

${portfolio_sentence}

## Full Pipeline Tasks

$(printf -- '- `%s`\n' "${full_pipeline_tasks[@]}")

## Selective Pipeline Tasks

$(printf -- '- `%s`\n' "${selective_pipeline_tasks[@]}")

## Metadata

- Diff range: \`${range}\`
- Changed modules: \`${changed_modules:-none}\`
- Affected modules: \`${affected_modules:-none}\`
- Test tasks: \`${test_tasks:-none}\`
- Package tasks: \`${package_tasks:-none}\`
REPORT_MD

cat > "$summary_csv" <<REPORT_CSV
scenario,mode,runs,warmup,full_pipeline_avg_seconds,selective_pipeline_avg_seconds,reduction_percent,full_pipeline_tasks,selective_pipeline_tasks
"${resolved_scenario}","${mode}",${runs},${warmup},${avg_full},${avg_selective},${reduction},"${full_task_list}","${selective_task_list}"
REPORT_CSV

cp "$summary_md" "$latest_md"
cp "$summary_csv" "$latest_csv"

cat <<REPORT

Selective CI performance summary
================================
Diff range: $range
Mode: $mode
Runs: $runs
Warmup runs: $warmup
Full pipeline average: ${avg_full}s
Selective pipeline average: ${avg_selective}s
Reduction: ${reduction}%

Full pipeline tasks:
$(printf '  - %s\n' "${full_pipeline_tasks[@]}")

Selective tasks:
$(printf '  - %s\n' "${selective_pipeline_tasks[@]}")

Reports:
  - $summary_md
  - $summary_csv
  - $latest_md
  - $latest_csv
REPORT
