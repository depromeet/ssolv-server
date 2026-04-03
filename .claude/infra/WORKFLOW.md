# ssolv Infrastructure Migration Workload

> Goal: Single EC2 → Multi-server + new AWS account migration via Terraform
> Started: 2026-03-29 / Completed: 2026-03-31

---

## Previous Architecture (before migration)

```
[ Single EC2 ] ap-northeast-2 / t3.small / 30GB gp3
  ├── nginx-app (port 80) — load balancer
  ├── app-server-1 (768MB, Spring Boot)
  ├── app-server-2 (768MB, Spring Boot)
  ├── redis:7-alpine (100MB max)
  ├── alloy + node/nginx/redis-exporter
  └── nginx-cicd (443) + registry (registry.ssolv.site)

[ RDS ] MySQL 8.0.43 / db.t3.micro / 20GB gp3 / single AZ (ap-northeast-2b)
[ CloudFront ] → removed after migration
```

### Old Account Network Details (from ssolv.yaml)

```
VPC: vpc-0619f2e27641372f4 / 10.0.0.0/16 (depromeet)
  ├── Public Subnet:    10.0.0.0/20   ap-northeast-2a  (depromeet-public-subnet)
  ├── Private Subnet:   10.0.128.0/20 ap-northeast-2a  (depromeet-private-subnet)
  └── Private Subnet 2: 10.0.144.0/20 ap-northeast-2b  (depromeet-private-subnet-2)

EC2:
  ├── Instance: t3.small / AMI: ami-010be25c3775061c9
  ├── Private IP: 10.0.14.101 (Public Subnet)
  ├── EIP: 13.125.182.175  ← old account, already cleaned up
  ├── Volume: 30GB gp3 (iops 3000)
  └── Security Group (depromeet-security-group):
      Inbound: 22, 80, 443, 900, 3306, 4430, 8080 (0.0.0.0/0)

RDS:
  ├── DB: depromeet / User: depromeet
  ├── AZ: ap-northeast-2b (Private Subnet)
  ├── Publicly accessible: no
  └── Security Group: 3306 allowed from EC2 only

SSH Key: depromeet-secret (RSA) → reused in new account
```

## Current Architecture (migration complete)

```
[ Instance A ] t3.micro / ap-northeast-2a / EIP: 3.34.32.206
  ├── nginx (80/443, Let's Encrypt)
  └── app-server (Spring Boot, -Xms128m -Xmx400m)

[ Instance B ] t3.small / ap-northeast-2c / EIP: 52.79.62.33
  ├── nginx (80/443, Let's Encrypt — api + registry)
  ├── app-server (Spring Boot, -Xms128m -Xmx400m)
  ├── redis:7-alpine (150MB limit)
  ├── registry (registry.ssolv.site)
  └── alloy + node/nginx/redis-exporter

[ RDS ] MySQL 8.0.43 / db.t3.micro
  └── ssolv-mysql.cvosykk4qy21.ap-northeast-2.rds.amazonaws.com

[ Route53 ] api.ssolv.site — Multivalue Answer (A + B with health checks)
  ├── A: 3.34.32.206 (Instance A)
  └── B: 52.79.62.33 (Instance B)
```

### New Account Network Details

```
VPC: 10.1.0.0/16 (ssolv-vpc)
  ├── Public Subnet A:  10.1.0.0/24  ap-northeast-2a  (Instance A)
  ├── Public Subnet C:  10.1.1.0/24  ap-northeast-2c  (Instance B)
  ├── Private Subnet A: 10.1.128.0/24 ap-northeast-2a (RDS)
  └── Private Subnet B: 10.1.129.0/24 ap-northeast-2b (RDS multi-AZ standby)

Instance A: t3.micro / Private IP: 10.1.0.43
Instance B: t3.small / Private IP: 10.1.1.160

Security Group (ec2):
  Inbound: 22, 80, 443 (0.0.0.0/0) / 8080, 6379, 4317-4318, 5000 (self)
Security Group (rds):
  Inbound: 3306 (ec2 security group only)
```

---

## Task List

### Phase 0: Preparation
- [x] Install Claude Code on EC2
- [x] Obtain and analyze ssolv.yaml CloudFormation template
- [x] Create new AWS account (no IAM — root Access Key method)
- [x] Install Terraform locally
- [x] Issue root Access Key for new account and set local environment variables

### Phase 1: Terraform-Based Design
- [x] `modules/network` — VPC, subnets, IGW, security groups
- [x] `modules/compute` — EC2 A (t3.micro) + B (t3.small), EIP, key pair
- [x] `modules/database` — RDS MySQL 8.0.43
- [x] `modules/dns` — Route53 Hosted Zone + Multivalue Answer + health checks
- [x] `terraform/main.tf`, `variables.tf`, `outputs.tf`
- [x] `terraform plan` passes — no errors
- [x] `docker-compose.instance-a.yml` — nginx (HTTPS) + app-server (Xmx400m)
- [x] `docker-compose.instance-b.yml` — app-server + redis + registry + nginx + monitoring
- [x] `nginx-app-instance-a.conf` — Instance A dedicated (Route53 handles load balancing)
- [x] `nginx-instance-b.conf` — handles api + registry together
- [x] `alloy-config.alloy` — multi-server metrics collection (instance-a/b label separation)

### Phase 2: New Account Infrastructure Provisioning
- [x] `terraform apply` complete
- [x] Verified outputs: EIP A (`3.34.32.206`) / B (`52.79.62.33`), B private IP (`10.1.1.160`), RDS endpoint

### Phase 3: App Deployment and Verification
- [x] Install docker, docker-compose (both instances)
- [x] Deploy `.env` files
  - Common: `PROD_DB_ENDPOINT`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD`
  - Instance A: `INSTANCE_B_PRIVATE_IP=10.1.1.160`
  - Instance B: `INSTANCE_A_PRIVATE_IP=10.1.0.43`
- [x] Create docker networks (ssolv_prod_network, ssolv_cicd_network, ssolv_monitoring_network)
- [x] Issue SSL certificates via certbot DNS-01 challenge
  - Instance A: `api.ssolv.site`
  - Instance B: `api.ssolv.site` + `registry.ssolv.site`
- [x] `docker compose up` (both instances)
- [x] `curl https://api.ssolv.site/actuator/health` returns healthy response

### Phase 4: CD Pipeline Update
- [x] Replace GitHub Actions secrets for new account
  - `EC2_HOST` → `3.34.32.206`
  - `EC2_HOST_B` → `52.79.62.33`
  - `EC2_SSH_KEY` → depromeet-secret.pem contents
  - `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`
- [x] Multi-server rolling update strategy (A first → B)
- [x] `cd-deploy.yml` updated
- [x] CD pipeline triggered and deployment verified

### Phase 5: DNS Cutover
- [x] Gabia nameservers → Route53 NS (4 records) updated
- [x] Route53 `api.ssolv.site` Multivalue Answer registered (A + B)
- [x] Route53 health checks for A/B both Healthy
- [x] `dig api.ssolv.site` — both IPs responding correctly
- [ ] Delete CloudFront (old account — manual action required)
- [ ] Terminate old EC2 (`13.125.182.175`) (old account — manual action required)
- [x] Monitoring confirmed working (Grafana, Sentry)

---

## Automation

The following automated tasks are active in production. No manual intervention needed unless noted.

| # | Automation | Mechanism | Schedule | Target |
|---|-----------|-----------|----------|--------|
| 1 | CD failure diagnostics | GitHub Actions `diagnose-on-failure` job | On deploy failure | A or B (whichever failed) |
| 2 | Health check + auto-restart | `health-recovery.sh` via crontab | Every 5 min | Instance A + B |
| 3 | Memory monitoring (t3.micro) | `memory-check.sh` via crontab | Every 5 min | Instance A only |
| 4 | Sentry issue analysis | Claude scheduled task | Daily 09:00 | ssolv Sentry project |
| 5 | Terraform drift detection | Claude scheduled task | Every Monday 10:00 | terraform/ |

### Log locations (on instances)
- `/var/log/ssolv-health-recovery.log` — health check events and restarts
- `/var/log/ssolv-memory-check.log` — memory alerts and diagnostics

### Scheduled task management
- View/run: Claude Code sidebar → "Scheduled" section
- Task files: `~/.claude/scheduled-tasks/{task-id}/SKILL.md`
- First run: click "Run now" in sidebar to pre-approve tool permissions

---

## Single↔Multi Server Switching

```hcl
# terraform.tfvars
app_instance_count = 2  # set to 1 to revert to single server (B only)
```

---

## Key Decisions Summary

| Item | Decision |
|------|----------|
| CloudFront | Removed in new account |
| ALB | Not used (Nginx handles front, Route53 handles load balancing) |
| Redis | EC2 container on Instance B — no ElastiCache |
| Registry | Self-hosted at registry.ssolv.site on Instance B |
| IaC tool | Terraform (state: local file, added to `.gitignore`) |
| Instance types | A: t3.micro / B: t3.small |
| IAM | Not used — root Access Key for Terraform auth |
| RDS migration | No snapshot — fresh RDS + code-level initialization (SurveyCategoryInitializer, StationInitializer) |
| DNS | Route53 Multivalue Answer + health checks (Gabia NS → Route53 delegation) |
| HTTPS | Let's Encrypt DNS-01 challenge (certbot-dns-route53) |
| Load balancing | Route53 Multivalue Answer — automatic failover based on A/B health checks |

---

## Cross-Session Reference

- SSH key: `~/dpm-server/depromeet-secret.pem` (shared for A and B)
- Instance A EIP: `3.34.32.206` / Private: `10.1.0.43`
- Instance B EIP: `52.79.62.33` / Private: `10.1.1.160`
- RDS endpoint: `ssolv-mysql.cvosykk4qy21.ap-northeast-2.rds.amazonaws.com`
- RDS access: SSH tunnel required (private subnet) — tunnel host: `52.79.62.33`
- CD pipeline: `.github/workflows/cd-deploy.yml` (rolling update, A→B order)
- Monitoring: Alloy on Instance B (`docker-compose.instance-b.yml`)
