.PHONY: help \
	up-prod down-prod restart-prod logs-prod \
	up-monitoring down-monitoring restart-monitoring logs-monitoring \
	up-cicd down-cicd restart-cicd logs-cicd \
	up-ngrinder down-ngrinder restart-ngrinder logs-ngrinder \
	up-all down-all restart-all logs-all

DOCKER_COMPOSE ?= docker compose

COMPOSE_PROD := deploy/docker-compose.prod.yml
COMPOSE_MONITORING := deploy/docker-compose.monitoring.yml
COMPOSE_CICD := deploy/docker-compose.cicd-infra.yml
COMPOSE_NGRINDER := deploy/docker-compose.test-dev.yml

help:
	@echo "Usage: make <target>"
	@echo
	@echo "Available targets:"
	@echo "  up-prod / down-prod / restart-prod / logs-prod               - Production stack"
	@echo "  up-monitoring / down-monitoring / restart-monitoring / logs-monitoring - Monitoring stack"
	@echo "  up-cicd / down-cicd / restart-cicd / logs-cicd               - CI/CD infrastructure"
	@echo "  up-ngrinder / down-ngrinder / restart-ngrinder / logs-ngrinder - Performance testing"
	@echo "  up-all / down-all / restart-all / logs-all                   - All production stacks (excludes ngrinder)"
	@echo
	@echo "Environment variables:"
	@echo "  DOCKER_COMPOSE (default: docker compose)"

# --- Production stack ---
up-prod:
	$(DOCKER_COMPOSE) -f $(COMPOSE_PROD) up -d

down-prod:
	$(DOCKER_COMPOSE) -f $(COMPOSE_PROD) down

restart-prod: down-prod up-prod

logs-prod:
	$(DOCKER_COMPOSE) -f $(COMPOSE_PROD) logs -f

# --- Monitoring stack ---
up-monitoring:
	$(DOCKER_COMPOSE) -f $(COMPOSE_MONITORING) up -d

down-monitoring:
	$(DOCKER_COMPOSE) -f $(COMPOSE_MONITORING) down

restart-monitoring: down-monitoring up-monitoring

logs-monitoring:
	$(DOCKER_COMPOSE) -f $(COMPOSE_MONITORING) logs -f

# --- CICD infra stack ---
up-cicd:
	$(DOCKER_COMPOSE) -f $(COMPOSE_CICD) up -d

down-cicd:
	$(DOCKER_COMPOSE) -f $(COMPOSE_CICD) down

restart-cicd: down-cicd up-cicd

logs-cicd:
	$(DOCKER_COMPOSE) -f $(COMPOSE_CICD) logs -f

# --- nGrinder performance testing ---
up-ngrinder:
	@echo "Starting nGrinder controller and agent..."
	@mkdir -p ssolv-infrastructure/ngrinder/controller
	@mkdir -p ssolv-infrastructure/ngrinder/agent
	@mkdir -p ssolv-infrastructure/ngrinder/agent-2
	$(DOCKER_COMPOSE) -f $(COMPOSE_NGRINDER) up -d
	@echo "nGrinder web console: http://localhost:8000"

down-ngrinder:
	$(DOCKER_COMPOSE) -f $(COMPOSE_NGRINDER) down

restart-ngrinder: down-ngrinder up-ngrinder

logs-ngrinder:
	$(DOCKER_COMPOSE) -f $(COMPOSE_NGRINDER) logs -f

# --- Aggregate helpers ---
up-all: up-prod up-monitoring up-cicd

down-all: down-cicd down-monitoring down-prod

restart-all: down-all up-all

logs-all:
	$(DOCKER_COMPOSE) -f $(COMPOSE_PROD) logs -f & \
	$(DOCKER_COMPOSE) -f $(COMPOSE_MONITORING) logs -f & \
	$(DOCKER_COMPOSE) -f $(COMPOSE_CICD) logs -f & \
	wait