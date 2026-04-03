#!/bin/bash
# memory-check.sh
# Monitors memory usage on Instance A (t3.micro, 1GB).
# Logs a full diagnostic snapshot when usage exceeds threshold.
# Runs every 5 minutes via crontab on Instance A only.
#
# Crontab entry:
#   */5 * * * * /home/ubuntu/17th-team3-Server/deploy/scripts/memory-check.sh >> /var/log/ssolv-memory-check.log 2>&1

LOG_FILE="/var/log/ssolv-memory-check.log"
THRESHOLD=80  # Alert at 80% memory usage
CONTAINER_NAME="app-server"
MAX_LOG_BYTES=1048576  # 1MB

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# Rotate log if too large
if [ -f "$LOG_FILE" ] && [ "$(stat -c%s "$LOG_FILE" 2>/dev/null || echo 0)" -gt "$MAX_LOG_BYTES" ]; then
  mv "$LOG_FILE" "${LOG_FILE}.1"
fi

# Calculate memory usage %
MEM_TOTAL=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
MEM_AVAIL=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
MEM_USED=$((MEM_TOTAL - MEM_AVAIL))
MEM_PCT=$(awk "BEGIN {printf \"%d\", ($MEM_USED / $MEM_TOTAL) * 100}")

if [ "$MEM_PCT" -lt "$THRESHOLD" ]; then
  exit 0  # Under threshold — silent exit
fi

log "ALERT: Memory usage at ${MEM_PCT}% (threshold: ${THRESHOLD}%)"

log "--- free -h ---"
free -h

log "--- docker stats snapshot ---"
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}" 2>/dev/null || true

log "--- JVM heap (actuator/metrics) ---"
JVM_USED=$(curl -s --connect-timeout 3 \
  "http://localhost:8080/actuator/metrics/jvm.memory.used" 2>/dev/null \
  | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 || echo "unavailable")
JVM_MAX=$(curl -s --connect-timeout 3 \
  "http://localhost:8080/actuator/metrics/jvm.memory.max" 2>/dev/null \
  | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 || echo "unavailable")
log "JVM heap used=${JVM_USED} max=${JVM_MAX}"

log "--- top 5 memory processes ---"
ps aux --sort=-%mem | head -6

# Auto-restart if container is unhealthy and memory is critically high (>90%)
if [ "$MEM_PCT" -ge 90 ]; then
  DOCKER_HEALTH=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo "unknown")
  if [ "$DOCKER_HEALTH" = "unhealthy" ]; then
    log "CRITICAL: Memory ${MEM_PCT}% + container unhealthy — restarting $CONTAINER_NAME"
    docker restart "$CONTAINER_NAME" 2>&1
    log "INFO: Restart triggered"
  else
    log "WARNING: Memory critical (${MEM_PCT}%) but container health=$DOCKER_HEALTH — monitoring only"
  fi
fi
