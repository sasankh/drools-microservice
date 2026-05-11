#!/usr/bin/env bash
# Docker compose stack lifecycle helpers.
# Sourced by run-load-test.sh.

stack::up() {
  local rebuild="${1:-yes}"
  echo "==> Bringing stack up (with load-test override: rate-limit disabled)"
  local override="scripts/docker-compose.loadtest.yml"
  if [[ "${rebuild}" == "yes" ]]; then
    docker compose -f docker-compose.yml -f "${override}" up -d --build > /dev/null
  else
    docker compose -f docker-compose.yml -f "${override}" up -d > /dev/null
  fi
}

stack::down() {
  echo "==> Tearing stack down (volumes removed)"
  local override="scripts/docker-compose.loadtest.yml"
  if [[ -f "${override}" ]]; then
    docker compose -f docker-compose.yml -f "${override}" down -v > /dev/null 2>&1 || true
  else
    docker compose down -v > /dev/null 2>&1 || true
  fi
}

# Polls /admin/health until either status==UP or timeout. Defaults to 90s timeout.
stack::wait_for_health() {
  local timeout="${1:-90}"
  local start
  start=$(date +%s)

  echo "==> Waiting for app health (max ${timeout}s)"
  while true; do
    local elapsed=$(( $(date +%s) - start ))
    if [[ ${elapsed} -ge ${timeout} ]]; then
      echo "  [FAIL] timeout after ${timeout}s; current /admin/health:"
      curl -s http://localhost:8080/admin/health 2>/dev/null | head -50 || echo "  (no response)"
      return 1
    fi

    local status
    status=$(curl -s http://localhost:8080/admin/health 2>/dev/null | jq -r '.status // empty' 2>/dev/null)
    if [[ "${status}" == "UP" ]]; then
      echo "  [ok]   app healthy after ${elapsed}s"
      return 0
    fi

    sleep 2
  done
}

# Returns the JVM PID inside the app container (used by heap-dump.sh and jstat).
stack::app_pid() {
  docker exec drools-microservice-app-1 \
    sh -c "cat /proc/[0-9]*/comm 2>/dev/null | grep -l java > /dev/null; \
           ps -e -o pid,comm 2>/dev/null | awk '/java/ {print \$1; exit}'"
}

# Gets a metric from /admin/memory/info via jq path. Empty on failure.
stack::heap_used_mb() {
  curl -s http://localhost:8080/admin/memory/info 2>/dev/null \
    | jq -r '.heap.usedMB // empty' 2>/dev/null
}

# Triggers a JVM GC via the admin endpoint and reports usedAfterMB / freedMemoryMB.
stack::trigger_gc() {
  curl -s -X POST http://localhost:8080/admin/memory/gc 2>/dev/null
}

# Returns current time in milliseconds since epoch. Portable across macOS (BSD date pre-Sonoma
# does not support %N) and Linux. Uses perl, which ships with both.
now_ms() {
  perl -MTime::HiRes -e 'printf "%d\n", Time::HiRes::time()*1000'
}
