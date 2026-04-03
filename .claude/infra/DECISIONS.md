# Infrastructure Decision Records (ADR)

> This file records key decisions made during infrastructure design and their rationale.
> Includes judgments derived from conversations with Claude.

---

## ADR-001: IaC Tool — Terraform

- **Date**: 2026-03-29
- **Decision**: Use Terraform instead of CDK.
- **Rationale**:
  - Portable across AWS accounts — only the provider needs to change
  - `count` / `for_each` allows single↔multi server switching with one variable
  - CDK migrate can reverse-engineer existing CloudFormation → Terraform
- **Trade-off**: Less type safety than TypeScript CDK. Simplicity wins here.

---

## ADR-002: Remove CloudFront

- **Date**: 2026-03-29
- **Decision**: Do not use CloudFront in the new account.
- **Rationale**: Caching benefit is limited for a pure API server. Simplicity first.
- **Revisit if**: Static asset serving or global traffic growth requires it.

---

## ADR-003: No ALB — Nginx Handles the Front

- **Date**: 2026-03-29
- **Decision**: Place Nginx on each EC2 instead of using an Application Load Balancer.
- **Rationale**:
  - Cost savings (~$16/month for ALB)
  - Nginx config already exists
  - Sufficient for multi-server setup
- **Trade-off**: Loses ALB's automatic health check / target management. Nginx requires manual management.

---

## ADR-004: Redis — Keep EC2 Container, No ElastiCache

- **Date**: 2026-03-29
- **Decision**: Run Redis as a container on Instance B.
- **Rationale**: Current usage is small (~100MB maxmemory). ElastiCache cost is not justified.
- **Revisit if**: Redis memory usage spikes or high availability is required.

---

## ADR-005: Keep Self-Hosted Docker Registry

- **Date**: 2026-03-29
- **Decision**: Keep `registry.ssolv.site` self-hosted instead of migrating to ECR.
- **Rationale**: Compatibility with existing CI/CD pipeline. Migrating to ECR would require major GitHub Actions changes.
- **Trade-off**: Registry container failure blocks all deploys. Dependency concentrated on Instance B.

---

## ADR-006: Claude as IaC Static Analyzer

- **Date**: 2026-03-29
- **Decision**: Claude acts as a security auditor when writing Terraform code.
- **Rationale**:
  - Prevents missed settings when migrating from manual console to Terraform
  - Context-aware (understands project-specific constraints) vs. tflint/checkov
  - Pre-deployment automated validation prevents production incidents
- **Implementation**:
  - `settings.json` PostToolUse Hook: auto-audit on `.tf` file modification
  - `/iac-audit` slash command: on-demand full audit
  - `CLAUDE.md` IaC rules: loaded automatically every session
- **Audit exclusions**: Inbound `0.0.0.0/0` on 80/443/22 (intentional)

---

## ADR-007: Single↔Multi Server Switching Strategy

- **Date**: 2026-03-29
- **Decision**: Control server count with a single `app_instance_count` variable.
- **Rationale**: Must be able to revert to single server instantly in a rollback scenario.
- **Implementation**: Set `app_instance_count = 1` or `2` in `terraform.tfvars`.

---

## ADR-008: Instance Types — t3.micro (A) + t3.small (B)

- **Date**: 2026-03-29
- **Decision**: Instance A uses t3.micro; Instance B uses t3.small.
- **Rationale**:
  - Actual app-server memory usage: 357–484MB (limit 768MB)
  - ALB would cost ~$18/month — more than running two t3.smalls ($30/month)
  - With JVM heap reduced to `-Xmx400m`, Instance A total memory ~650MB → fits within t3.micro (1GB)
  - Instance B hosts redis, registry, alloy, and exporters in addition to app-server — t3.small is required
- **Per-instance setup**:
  - A (t3.micro / 1GB): nginx + app-server (`-Xms128m -Xmx400m`)
  - B (t3.small / 2GB): nginx + app-server + redis + registry + alloy + exporters
- **Trade-off**: OOM risk on Instance A under traffic spikes. Monitoring alerts required.
- **Revisit if**: Instance A memory usage exceeds 80% — upgrade to t3.small.

---

## ADR-009: Reduce app-server JVM Heap

- **Date**: 2026-03-29
- **Decision**: Reduce Instance A app-server JVM heap from 768MB to 400MB.
- **Rationale**: Required to fit within t3.micro (1GB). Actual usage (357–484MB) means 400MB covers the peak.
- **Setting**: `JAVA_OPTS: "-Xms128m -Xmx400m"` in Instance A's docker-compose.
- **Monitoring**: Grafana JVM heap usage alert required.

---

## ADR-010: No IAM — Root Access Key for Terraform Auth

- **Date**: 2026-03-31
- **Decision**: Authenticate Terraform with root account Access Key — no IAM users or roles.
- **Rationale**: Eliminates IAM setup complexity for a migration-scoped task. Simplicity prioritized for a short-lived project.
- **Trade-off**: Violates least-privilege principle. Full account exposure risk if key is leaked.
- **Mitigation**: Access Key stored only in `.env` or environment variables — never hardcoded.

---

## ADR-011: New RDS Without Snapshot + Code-Level Initialization

- **Date**: 2026-03-31
- **Decision**: Create a fresh RDS instance without migrating existing data. Seed required master data via `ApplicationRunner`.
- **Rationale**: Pre-migration user data is irrelevant. Skips complex cross-account snapshot transfer.
- **Implementation**:
  - `SurveyCategoryInitializer` — survey category master data (already implemented)
  - `StationInitializer` — subway station coordinate data (already implemented)
- **Idempotency**: Skip if `count() > 0`.

---

## ADR-012: DNS — Manual Gabia Change ~~(superseded by ADR-016)~~

- **Date**: 2026-03-31
- **Status**: ❌ Superseded by ADR-016 (replaced by Route53 Multivalue Answer)
- **Original plan**: Change A records directly in Gabia without Route53
- **Why changed**: Simple A records cannot support health-check-based failover → Route53 introduced in ADR-016

---

## ADR-013: HTTPS — Re-issue Let's Encrypt Certificates

- **Date**: 2026-03-31
- **Decision**: Re-issue Let's Encrypt certificates via certbot on the new server. Both domains handled on Instance B.
- **Rationale**: Re-issuing is safer and simpler than copying existing certificates. Nginx config can be cleaned up at the same time.
- **Implementation**: `nginx-cicd` in `docker-compose.cicd-infra.yml` handles 443/80. certbot standalone or webroot mode.

---

## ADR-014: Multi-server Load Balancing ~~(superseded by ADR-016)~~

- **Date**: 2026-03-31
- **Status**: ❌ Superseded by ADR-016 (traffic structure changed)
- **Original plan**: Gabia DNS → Instance A only; A's nginx proxies upstream to B
- **Why changed**: Route53 Multivalue Answer means each instance serves traffic independently. A→B upstream proxy is no longer needed. Each nginx handles only its local app-server.
- **Current structure (see ADR-016)**: Route53 load-balances A/B equally; nginx handles local traffic only.

---

## ADR-015: Terraform State — Local File

- **Date**: 2026-03-31
- **Decision**: Manage `terraform.tfstate` as a local file — no S3 backend.
- **Rationale**: S3 is excluded from the infrastructure policy. Remote state is unnecessary for a single-operator setup.
- **Note**: `terraform.tfstate` must be in `.gitignore` (may contain sensitive data).

---

## ADR-016: Route53 — Health-Check-Based DNS Failover

- **Date**: 2026-03-31
- **Decision**: Use Route53 Multivalue Answer routing + health checks for equal load balancing across both instances.
- **Rationale**:
  - Gabia DNS Round Robin has no health checks — traffic keeps flowing to a failed instance
  - Route53 health checks ($2/month) achieve the same effect as ALB ($18/month) at much lower cost
  - Maintains peer architecture for A and B (each handles traffic independently via local nginx)
  - Managed by Terraform — easy to roll back (`terraform destroy -target=module.dns`)
- **Implementation**:
  - `api.ssolv.site` Multivalue Answer: A (3.34.32.206) + B (52.79.62.33)
  - Health check target: `https://api.ssolv.site/actuator/health` (per IP)
  - Failed health check → IP automatically removed from DNS responses
  - Gabia nameservers → Route53 NS records (one-time manual change)
- **Trade-off**: Gabia NS change required (one-time). TTL propagation delay possible.

---

## ADR-017: Vercel Frontend DNS — Consolidated in Route53

- **Date**: 2026-04-03
- **Decision**: Manage `ssolv.site` (apex) and `www.ssolv.site` DNS records in Route53.
- **Rationale**:
  - After delegating Gabia NS to Route53, all DNS records are managed in one place
  - Apex domain cannot use CNAME → register Vercel Anycast IP (`76.76.21.21`) as A record directly
  - www uses CNAME to `cname.vercel-dns.com.`
- **Implementation**:
  - `aws_route53_record.apex`: `ssolv.site` → `76.76.21.21` (A, TTL 300)
  - `aws_route53_record.www`: `www.ssolv.site` → `cname.vercel-dns.com.` (CNAME, TTL 300)
- **Trade-off**: Vercel IP change requires Terraform code update (Vercel Anycast IP is long-term stable).

---

## ADR-018: CD Failure Auto-Diagnostics

- **Date**: 2026-04-03
- **Decision**: Add a `diagnose-on-failure` job to `cd-deploy.yml` that automatically SSHes into the failed instance and collects diagnostics when a deploy job fails.
- **Rationale**:
  - Without this, a failed deploy requires manual SSH to investigate logs — slow feedback loop.
  - Rolling deployment (A→B) means partial failure state can persist; surfacing it immediately reduces MTTR.
- **Implementation**: `.github/workflows/cd-deploy.yml` — `diagnose-on-failure` job with `if: failure()`, runs only for the failed instance (A or B).
- **Output**: Container status, last 100 lines of docker logs, free memory, docker stats, disk usage — all visible in GitHub Actions run output.

---

## ADR-019: Health Check Auto-Recovery (Crontab)

- **Date**: 2026-04-03
- **Decision**: Run `deploy/scripts/health-recovery.sh` every 5 minutes via crontab on both Instance A and B.
- **Rationale**:
  - Route53 health check detects failure but only removes the instance from DNS — it does not restart the container.
  - A dead container on Instance A means 50% of traffic goes to a non-existent endpoint until manually fixed.
  - Auto-restart covers the gap between Route53 failover (DNS-level) and actual container recovery.
- **Trigger**: Container stopped OR Docker health status = `unhealthy`.
- **Log**: `/var/log/ssolv-health-recovery.log` (rotates at 1MB).
- **Trade-off**: Blind restart on unhealthy status may mask underlying bug. Log is preserved for post-mortem.

---

## ADR-020: Instance A Memory Monitoring (Crontab)

- **Date**: 2026-04-03
- **Decision**: Run `deploy/scripts/memory-check.sh` every 5 minutes on Instance A (t3.micro) only.
- **Rationale**:
  - Instance A runs nginx + app-server on 1GB RAM with `-Xmx400m`. OOM risk is real under traffic spikes (ADR-008).
  - Grafana alerts require Alloy scrape cycle; crontab-based check is immediate and independent of monitoring stack health.
- **Thresholds**: Log diagnostic snapshot at ≥80%; auto-restart app-server at ≥90% if container is also unhealthy.
- **Log**: `/var/log/ssolv-memory-check.log` (rotates at 1MB).

---

## ADR-021: Sentry Auto-Analysis (Claude Scheduled Task)

- **Date**: 2026-04-03
- **Decision**: Run a Claude scheduled task daily at 09:00 that fetches new Sentry issues and produces a prioritized root-cause report.
- **Rationale**:
  - ssolv integrates Google Places API, Kakao OAuth, and Firebase FCM — external API failures are common noise.
  - Manual Sentry triage requires context-switching; Claude can correlate stack traces with source code automatically.
- **Implementation**: `~/.claude/scheduled-tasks/sentry-auto-analysis/SKILL.md` — runs daily, notifies on completion.
- **Output**: Per-issue root cause + affected file:line + priority (HIGH/MEDIUM/LOW).

---

## ADR-022: Terraform Drift Detection (Claude Scheduled Task)

- **Date**: 2026-04-03
- **Decision**: Run a Claude scheduled task every Monday at 10:00 that executes `terraform plan` and reports any infrastructure drift.
- **Rationale**:
  - Terraform state is local (ADR-015); no remote locking or drift detection built in.
  - Manual console changes (e.g., temporary security group rule) can silently diverge from IaC.
  - Weekly cadence balances cost (Terraform plan hits AWS APIs) and detection lag.
- **Implementation**: `~/.claude/scheduled-tasks/terraform-drift-check/SKILL.md`.
- **Output**: Per-resource change summary; flags unexpected drift as HIGH priority. Never runs `terraform apply`.
