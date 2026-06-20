#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/selective-terraform-plan.sh <target-csv> [output-directory]

Creates one saved Terraform plan per affected root module. Targets that belong
to the main root are combined into a single plan; independently stateful roots
receive their own plan. The generated manifest is consumed by CI/CD gates.
USAGE
}

targets_csv="${1:-}"
output_dir="${2:-build/terraform-plans}"

if [[ -z "$targets_csv" ]]; then
  usage >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="$repo_root/$output_dir"
mkdir -p "$output_dir"

root_targets=()
plan_root=false
plan_pipeline_runtime=false
plan_restaurant_storage=false

add_root_target() {
  local value="$1"
  local current
  for current in "${root_targets[@]}"; do
    [[ "$current" == "$value" ]] && return
  done
  root_targets+=("$value")
}

IFS=',' read -r -a targets <<< "$targets_csv"
for target in "${targets[@]}"; do
  case "$target" in
    root)
      plan_root=true
      root_targets=()
      ;;
    network) [[ "$plan_root" == true ]] || add_root_target "module.network" ;;
    compute) [[ "$plan_root" == true ]] || add_root_target "module.compute" ;;
    database) [[ "$plan_root" == true ]] || add_root_target "module.database" ;;
    dns) [[ "$plan_root" == true ]] || add_root_target "module.dns" ;;
    storage|batch-pipeline)
      [[ "$plan_root" == true ]] || add_root_target "module.restaurant_pipeline"
      ;;
    batch-pipeline-runtime) plan_pipeline_runtime=true ;;
    restaurant-storage) plan_restaurant_storage=true ;;
    "") ;;
    *)
      echo "Unsupported Terraform target: $target" >&2
      exit 2
      ;;
  esac
done

manifest="$output_dir/manifest.tsv"
: > "$manifest"

run_plan() {
  local name="$1"
  local directory="$2"
  shift 2
  local plan_file="$output_dir/$name.tfplan"

  terraform -chdir="$repo_root/$directory" init -backend=false -input=false
  terraform -chdir="$repo_root/$directory" validate
  terraform -chdir="$repo_root/$directory" plan \
    -refresh=false \
    -input=false \
    -lock=false \
    -out="$plan_file" \
    "$@"
  terraform -chdir="$repo_root/$directory" show -no-color "$plan_file" > "$output_dir/$name.txt"
  printf '%s\t%s\t%s\n' "$name" "$directory" "$(basename "$plan_file")" >> "$manifest"
}

if [[ "$plan_root" == true || ${#root_targets[@]} -gt 0 ]]; then
  root_args=()
  if [[ "$plan_root" != true ]]; then
    for target in "${root_targets[@]}"; do
      root_args+=("-target=$target")
    done
  fi
  run_plan "root" "deploy/terraform" "${root_args[@]}"
fi

if [[ "$plan_pipeline_runtime" == true ]]; then
  run_plan "batch-pipeline-runtime" "deploy/terraform/restaurant-pipeline-runtime"
fi

if [[ "$plan_restaurant_storage" == true ]]; then
  run_plan "restaurant-storage" "deploy/terraform/restaurant-storage"
fi

if [[ ! -s "$manifest" ]]; then
  echo "No Terraform plans were generated for: $targets_csv" >&2
  exit 2
fi

echo "Generated Terraform plans:"
cat "$manifest"
