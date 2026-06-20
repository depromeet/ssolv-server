#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
plan_dir="${2:-build/terraform-plans}"
manifest="$plan_dir/manifest.tsv"

if [[ "$mode" != "encrypt" && "$mode" != "decrypt" ]]; then
  echo "Usage: scripts/protect-terraform-plans.sh <encrypt|decrypt> [plan-directory]" >&2
  exit 2
fi

if [[ -z "${TERRAFORM_PLAN_ENCRYPTION_KEY:-}" ]]; then
  echo "TERRAFORM_PLAN_ENCRYPTION_KEY is required" >&2
  exit 2
fi

if [[ ! -s "$manifest" ]]; then
  echo "Terraform plan manifest is missing: $manifest" >&2
  exit 2
fi

while IFS=$'\t' read -r _ _ plan; do
  [[ -z "$plan" ]] && continue
  plaintext="$plan_dir/$plan"
  encrypted="$plaintext.enc"

  if [[ "$mode" == "encrypt" ]]; then
    openssl enc -aes-256-cbc -salt -pbkdf2 -iter 100000 \
      -in "$plaintext" \
      -out "$encrypted" \
      -pass env:TERRAFORM_PLAN_ENCRYPTION_KEY
    rm -f "$plaintext"
  else
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
      -in "$encrypted" \
      -out "$plaintext" \
      -pass env:TERRAFORM_PLAN_ENCRYPTION_KEY
    rm -f "$encrypted"
  fi
done < "$manifest"
