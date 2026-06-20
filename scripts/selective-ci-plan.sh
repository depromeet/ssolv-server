#!/usr/bin/env bash
set -eo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/selective-ci-plan.sh [git-diff-range]
  scripts/selective-ci-plan.sh --changed-files-file <path>

Examples:
  scripts/selective-ci-plan.sh origin/main...HEAD
  scripts/selective-ci-plan.sh --changed-files-file /tmp/changed-files.txt
  SELECTIVE_CI_ENV_FILE=/tmp/plan.env scripts/selective-ci-plan.sh origin/dev...HEAD

The script maps changed files to Gradle modules, expands the impact through the
project dependency graph, and emits the Gradle/Terraform work needed for CI.
When GITHUB_OUTPUT is set, the same values are written as GitHub Actions outputs.
USAGE
}

range="origin/dev...HEAD"
changed_files_file=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --changed-files-file)
      changed_files_file="${2:-}"
      shift 2
      ;;
    *)
      range="$1"
      shift
      ;;
  esac
done

modules=(
  "ssolv-api-common"
  "ssolv-api-core"
  "ssolv-api-place"
  "ssolv-batch"
  "ssolv-domain"
  "ssolv-global-utils"
  "ssolv-infrastructure"
)

changed_files=()
changed_modules=()
affected_modules=()
test_tasks=()
package_tasks=()
image_tasks=()
deploy_targets=()
terraform_targets=()

full_validation=false

add_unique() {
  local value="$1"
  shift
  local existing
  for existing in "$@"; do
    [[ "$existing" == "$value" ]] && return 1
  done
  return 0
}

add_changed_module() {
  local value="$1"
  if add_unique "$value" "${changed_modules[@]}"; then
    changed_modules+=("$value")
  fi
}

add_affected_module() {
  local value="$1"
  if add_unique "$value" "${affected_modules[@]}"; then
    affected_modules+=("$value")
  fi
}

add_test_task() {
  local value="$1"
  if add_unique "$value" "${test_tasks[@]}"; then
    test_tasks+=("$value")
  fi
}

add_package_task() {
  local value="$1"
  if add_unique "$value" "${package_tasks[@]}"; then
    package_tasks+=("$value")
  fi
}

add_image_task() {
  local value="$1"
  if add_unique "$value" "${image_tasks[@]}"; then
    image_tasks+=("$value")
  fi
}

add_deploy_target() {
  local value="$1"
  if add_unique "$value" "${deploy_targets[@]}"; then
    deploy_targets+=("$value")
  fi
}

add_terraform_target() {
  local value="$1"
  if add_unique "$value" "${terraform_targets[@]}"; then
    terraform_targets+=("$value")
  fi
}

join_by() {
  local delimiter="$1"
  shift
  local result=""
  local item
  for item in "$@"; do
    if [[ -z "$result" ]]; then
      result="$item"
    else
      result="${result}${delimiter}${item}"
    fi
  done
  printf '%s' "$result"
}

task_for_module() {
  local module="$1"
  printf ':%s:test' "$module"
}

mark_executable_module() {
  local module="$1"
  case "$module" in
    "ssolv-api-core")
      add_package_task ":ssolv-api-core:bootJar"
      add_image_task ":ssolv-api-core:jib"
      add_deploy_target "api-server"
      ;;
    "ssolv-api-place")
      # place is currently packaged into api-core in production.
      add_package_task ":ssolv-api-place:jar"
      ;;
    "ssolv-batch")
      add_package_task ":ssolv-batch:bootJar"
      add_image_task ":ssolv-batch:jib"
      add_deploy_target "batch-worker"
      add_terraform_target "batch-pipeline-runtime"
      ;;
  esac
}

mark_module_impact() {
  local module="$1"

  case "$module" in
    "ssolv-api-common")
      add_affected_module "ssolv-api-common"
      add_affected_module "ssolv-api-core"
      add_affected_module "ssolv-api-place"
      add_affected_module "ssolv-batch"
      ;;
    "ssolv-api-core")
      add_affected_module "ssolv-api-core"
      ;;
    "ssolv-api-place")
      add_affected_module "ssolv-api-place"
      add_affected_module "ssolv-api-core"
      ;;
    "ssolv-batch")
      add_affected_module "ssolv-batch"
      add_affected_module "ssolv-api-core"
      ;;
    "ssolv-domain")
      add_affected_module "ssolv-domain"
      add_affected_module "ssolv-infrastructure"
      add_affected_module "ssolv-batch"
      add_affected_module "ssolv-api-core"
      ;;
    "ssolv-global-utils")
      add_affected_module "ssolv-global-utils"
      add_affected_module "ssolv-api-core"
      add_affected_module "ssolv-batch"
      ;;
    "ssolv-infrastructure")
      add_affected_module "ssolv-infrastructure"
      add_affected_module "ssolv-batch"
      add_affected_module "ssolv-api-core"
      ;;
  esac
}

mark_full_validation() {
  full_validation=true
  local module
  for module in "${modules[@]}"; do
    add_affected_module "$module"
  done
  mark_executable_module "ssolv-api-core"
  mark_executable_module "ssolv-api-place"
  mark_executable_module "ssolv-batch"
}

map_terraform_target() {
  local file="$1"
  case "$file" in
    deploy/terraform/modules/compute/*) add_terraform_target "compute" ;;
    deploy/terraform/modules/database/*) add_terraform_target "database" ;;
    deploy/terraform/modules/dns/*) add_terraform_target "dns" ;;
    deploy/terraform/modules/network/*) add_terraform_target "network" ;;
    deploy/terraform/modules/restaurant-pipeline/*) add_terraform_target "batch-pipeline" ;;
    deploy/terraform/modules/storage/*) add_terraform_target "storage" ;;
    deploy/terraform/restaurant-pipeline-runtime/*) add_terraform_target "batch-pipeline-runtime" ;;
    deploy/terraform/restaurant-storage/*) add_terraform_target "restaurant-storage" ;;
    deploy/terraform/*) add_terraform_target "root" ;;
  esac
}

while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  changed_files+=("$file")

  case "$file" in
    ssolv-api-common/*) add_changed_module "ssolv-api-common" ;;
    ssolv-api-core/*) add_changed_module "ssolv-api-core" ;;
    ssolv-api-place/*) add_changed_module "ssolv-api-place" ;;
    ssolv-batch/*) add_changed_module "ssolv-batch" ;;
    ssolv-domain/*) add_changed_module "ssolv-domain" ;;
    ssolv-global-utils/*) add_changed_module "ssolv-global-utils" ;;
    ssolv-infrastructure/*) add_changed_module "ssolv-infrastructure" ;;
    build.gradle.kts|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat|gradle/*|gradle/**|.github/workflows/*|.github/workflows/**|scripts/selective-ci-plan.sh)
      mark_full_validation
      ;;
    scripts/selective-terraform-plan.sh|scripts/protect-terraform-plans.sh)
      mark_full_validation
      add_terraform_target "root"
      add_terraform_target "batch-pipeline-runtime"
      add_terraform_target "restaurant-storage"
      ;;
  esac

  case "$file" in
    deploy/terraform/*|deploy/terraform/**) map_terraform_target "$file" ;;
  esac
done < <(
  if [[ -n "$changed_files_file" ]]; then
    cat "$changed_files_file"
  else
    git diff --name-only "$range"
  fi
)

for module in "${changed_modules[@]}"; do
  mark_module_impact "$module"
done

for module in "${affected_modules[@]}"; do
  add_test_task "$(task_for_module "$module")"
  mark_executable_module "$module"
done

has_app_changes=false
has_terraform_changes=false
has_test_tasks=false
has_package_tasks=false
has_image_tasks=false

[[ ${#affected_modules[@]} -gt 0 ]] && has_app_changes=true
[[ ${#terraform_targets[@]} -gt 0 ]] && has_terraform_changes=true
[[ ${#test_tasks[@]} -gt 0 ]] && has_test_tasks=true
[[ ${#package_tasks[@]} -gt 0 ]] && has_package_tasks=true
[[ ${#image_tasks[@]} -gt 0 ]] && has_image_tasks=true

changed_files_text="$(join_by $'\n' "${changed_files[@]}")"
changed_modules_csv="$(join_by "," "${changed_modules[@]}")"
affected_modules_csv="$(join_by "," "${affected_modules[@]}")"
test_tasks_text="$(join_by " " "${test_tasks[@]}")"
package_tasks_text="$(join_by " " "${package_tasks[@]}")"
image_tasks_text="$(join_by " " "${image_tasks[@]}")"
deploy_targets_csv="$(join_by "," "${deploy_targets[@]}")"
terraform_targets_csv="$(join_by "," "${terraform_targets[@]}")"

write_outputs() {
  local output_file="$1"
  {
    printf 'changed_modules=%q\n' "$changed_modules_csv"
    printf 'affected_modules=%q\n' "$affected_modules_csv"
    printf 'test_tasks=%q\n' "$test_tasks_text"
    printf 'package_tasks=%q\n' "$package_tasks_text"
    printf 'image_tasks=%q\n' "$image_tasks_text"
    printf 'deploy_targets=%q\n' "$deploy_targets_csv"
    printf 'terraform_targets=%q\n' "$terraform_targets_csv"
    printf 'full_validation=%s\n' "$full_validation"
    printf 'has_app_changes=%s\n' "$has_app_changes"
    printf 'has_terraform_changes=%s\n' "$has_terraform_changes"
    printf 'has_test_tasks=%s\n' "$has_test_tasks"
    printf 'has_package_tasks=%s\n' "$has_package_tasks"
    printf 'has_image_tasks=%s\n' "$has_image_tasks"
  } >> "$output_file"
}

write_github_outputs() {
  local output_file="$1"
  {
    printf 'changed_files<<EOF\n%s\nEOF\n' "$changed_files_text"
    printf 'changed_modules=%s\n' "$changed_modules_csv"
    printf 'affected_modules=%s\n' "$affected_modules_csv"
    printf 'test_tasks=%s\n' "$test_tasks_text"
    printf 'package_tasks=%s\n' "$package_tasks_text"
    printf 'image_tasks=%s\n' "$image_tasks_text"
    printf 'deploy_targets=%s\n' "$deploy_targets_csv"
    printf 'terraform_targets=%s\n' "$terraform_targets_csv"
    printf 'full_validation=%s\n' "$full_validation"
    printf 'has_app_changes=%s\n' "$has_app_changes"
    printf 'has_terraform_changes=%s\n' "$has_terraform_changes"
    printf 'has_test_tasks=%s\n' "$has_test_tasks"
    printf 'has_package_tasks=%s\n' "$has_package_tasks"
    printf 'has_image_tasks=%s\n' "$has_image_tasks"
  } >> "$output_file"
}

if [[ -n "${SELECTIVE_CI_ENV_FILE:-}" ]]; then
  : > "$SELECTIVE_CI_ENV_FILE"
  write_outputs "$SELECTIVE_CI_ENV_FILE"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  write_github_outputs "$GITHUB_OUTPUT"
fi

cat <<REPORT
Selective CI plan
=================
Diff range: $range

Changed files:
$(if [[ ${#changed_files[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${changed_files[@]}"; fi)
Changed modules:
$(if [[ ${#changed_modules[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${changed_modules[@]}"; fi)
Affected modules:
$(if [[ ${#affected_modules[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${affected_modules[@]}"; fi)
Gradle test tasks:
$(if [[ ${#test_tasks[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${test_tasks[@]}"; fi)
Gradle package tasks:
$(if [[ ${#package_tasks[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${package_tasks[@]}"; fi)
Image/deploy build tasks:
$(if [[ ${#image_tasks[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${image_tasks[@]}"; fi)
Deploy targets:
$(if [[ ${#deploy_targets[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${deploy_targets[@]}"; fi)
Terraform targets:
$(if [[ ${#terraform_targets[@]} -eq 0 ]]; then printf '  (none)\n'; else printf '  - %s\n' "${terraform_targets[@]}"; fi)
REPORT
