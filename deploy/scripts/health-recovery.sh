#!/bin/bash
# health-recovery.sh
# Checks app-server health and auto-restarts the container if unhealthy.
# Runs every 5 minutes via crontab on both Instance A and B.
#
# Crontab entry:
#   */5 * * * * /home/ubuntu/17th-team3-Server/deploy/scripts/health-recovery.sh >> /var/log/ssolv-health-recovery.log 2>&1

LOG_FILE="/var/log/ssolv-health-recovery.log"
HEALTH_URL="http://localhost:8080/actuator/health"
CONTAINER_NAME="app-server"
MAX_LOG_BYTES=1048576  # 1MB

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# Rotate log if too large
if [ -f "$LOG_FILE" ] && [ "$(stat -c%s "$LOG_FILE" 2>/dev/null || echo 0)" -gt "$MAX_LOG_BYTES" ]; then
  mv "$LOG_FILE" "${LOG_FILE}.1"
fi

# Check container existence
CONTAINER_STATUS=$(docker inspect --format='{{.State.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo "missing")

if [ "$CONTAINER_STATUS" = "missing" ]; then
  log "ERROR: Container '$CONTAINER_NAME' not found — skipping"
  exit 1
fi

# Restart if container is not running
if [ "$CONTAINER_STATUS" != "running" ]; then
  log "WARNING: Container '$CONTAINER_NAME' is '$CONTAINER_STATUS' — restarting"
  docker start "$CONTAINER_NAME" 2>&1
  sleep 15
  NEW_STATUS=$(docker inspect --format='{{.State.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo "missing")
  log "INFO: Status after restart: $NEW_STATUS"
  exit 0
fi

# Check HTTP health endpoint
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  --connect-timeout 5 --max-time 10 "$HEALTH_URL" 2>/dev/null || echo "000")

if [ "$HTTP_CODE" = "200" ]; then
  # Healthy — no log needed (silent on success to keep log clean)
  exit 0
fi

log "WARNING: Health endpoint returned HTTP $HTTP_CODE"

# Check Docker's own health status
DOCKER_HEALTH=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo "unknown")
log "INFO: Docker health status: $DOCKER_HEALTH"

if [ "$DOCKER_HEALTH" = "unhealthy" ]; then
  log "WARNING: Container is unhealthy — restarting"
  docker restart "$CONTAINER_NAME" 2>&1
  log "INFO: Restart triggered for '$CONTAINER_NAME'"
fi
