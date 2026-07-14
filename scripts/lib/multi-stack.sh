#!/usr/bin/env bash
# Multi-container stack lifecycle for Phase 9.2 / 9.3 / 9.4.
# Sourced by run-load-test.sh; not meant to be run standalone.
#
# Topology assumed (see scripts/docker-compose.loadtest-multi.yml):
#   drools-app-1, drools-app-2, drools-app-3   — actuators on host 18081/28081/38081
#   drools-lb (nginx)                          — JMeter target on host 8080
#   drools-redis                               — deterministic name for `docker kill`
#   drools-microservice-localstack-1           — inherits default Compose naming
#
# IMPORTANT: multi_stack::up uses an EXPLICIT service list so the base `app:` block
# in docker-compose.yml does NOT start (it would fight app1/2/3 for ports 8080/8081).

_MULTI_COMPOSE_FILES=(
  -f docker-compose.yml
  -f scripts/docker-compose.loadtest.yml
  -f scripts/docker-compose.loadtest-multi.yml
)
_MULTI_SERVICES=(app1 app2 app3 nginx redis localstack)

# Brings up the 3-replica multi-container stack.
# Args:
#   $1 mode    "cache-only" (REDIS_PUBSUB_ENABLED=false) or "full" (REDIS_PUBSUB_ENABLED=true)
multi_stack::up() {
  local mode="${1:-full}"
  case "${mode}" in
    cache-only) export REDIS_PUBSUB_ENABLED=false ;;
    full)       export REDIS_PUBSUB_ENABLED=true ;;
    *) echo "  [FAIL] multi_stack::up: unknown mode '${mode}' (expected cache-only|full)"; return 1 ;;
  esac

  echo "==> Bringing multi-container stack up (mode=${mode}, REDIS_PUBSUB_ENABLED=${REDIS_PUBSUB_ENABLED})"
  docker compose "${_MULTI_COMPOSE_FILES[@]}" \
    up -d --build "${_MULTI_SERVICES[@]}" > /dev/null
}

# Tears down everything in the project (volumes too). --remove-orphans is belt-and-braces
# in case a prior single-container run left base `app:` containers around.
multi_stack::down() {
  echo "==> Tearing multi-container stack down (volumes removed)"
  docker compose "${_MULTI_COMPOSE_FILES[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
}

# Waits for all 3 app replicas + nginx LB to report healthy.
# Args:
#   $1 timeout_seconds  (default 240 — replicas can take 90s+ to JIT-warm on cold start)
multi_stack::wait_for_health() {
  local timeout="${1:-240}"
  local start
  start=$(date +%s)

  echo "==> Waiting for 3 app replicas + nginx healthy (max ${timeout}s)"

  # Phase 1: per-replica actuator UP via host-port forward.
  local idx ok_replicas
  for idx in 1 2 3; do
    local port="${idx}8081"
    while true; do
      local elapsed=$(( $(date +%s) - start ))
      if [[ ${elapsed} -ge ${timeout} ]]; then
        echo "  [FAIL] timeout after ${elapsed}s waiting for app${idx} actuator on :${port}"
        return 1
      fi
      local status
      status=$(curl -s --max-time 2 "http://localhost:${port}/actuator/health" 2>/dev/null \
                  | jq -r '.status // empty' 2>/dev/null)
      if [[ "${status}" == "UP" ]]; then
        echo "  [ok]   app${idx} actuator UP after ${elapsed}s"
        break
      fi
      sleep 2
    done
  done

  # Phase 2: nginx round-robins /admin/health to a healthy replica.
  while true; do
    local elapsed=$(( $(date +%s) - start ))
    if [[ ${elapsed} -ge ${timeout} ]]; then
      echo "  [FAIL] timeout after ${elapsed}s waiting for nginx /admin/health"
      docker logs --tail 20 drools-lb 2>&1 | sed 's/^/      /' || true
      return 1
    fi
    local status
    status=$(curl -s --max-time 2 http://localhost:8080/admin/health 2>/dev/null \
                | jq -r '.status // empty' 2>/dev/null)
    if [[ "${status}" == "UP" ]]; then
      echo "  [ok]   nginx → backend healthy after ${elapsed}s"
      return 0
    fi
    sleep 2
  done
}

# Waits for `pubsub.subscriber.connected == true` on all 3 replicas. Defensive:
# if `/admin/health` doesn't expose the pubsub component (older builds or pub/sub
# disabled), the function returns success after one round so it can't block the harness.
# Args:
#   $1 timeout_seconds (default 60)
multi_stack::wait_for_pubsub() {
  local timeout="${1:-60}"
  local start
  start=$(date +%s)

  echo "==> Waiting for pub/sub subscriber connected on all 3 replicas (max ${timeout}s)"
  local idx
  for idx in 1 2 3; do
    local port="${idx}8081"
    while true; do
      local elapsed=$(( $(date +%s) - start ))
      if [[ ${elapsed} -ge ${timeout} ]]; then
        echo "  [warn] timeout waiting for pubsub.subscriber.connected on app${idx}; proceeding"
        return 0
      fi
      local body
      body=$(curl -s --max-time 2 "http://localhost:${port}/admin/health" 2>/dev/null)
      # Field shape from checklist 5.3: `components.pubsub.details.subscriber.connected`
      # or `pubsub.subscriber.connected`. Try both; treat absence as "not yet ready".
      local connected
      connected=$(echo "${body}" | jq -r '
        (.components.pubsub.details.subscriber.connected //
         .pubsub.subscriber.connected //
         empty) | tostring' 2>/dev/null)
      if [[ "${connected}" == "true" ]]; then
        echo "  [ok]   app${idx} pub/sub subscriber connected after ${elapsed}s"
        break
      fi
      # If the field is structurally absent (the build doesn't expose it), don't loop forever.
      local pubsub_present
      pubsub_present=$(echo "${body}" | jq -r '(.components.pubsub // .pubsub) | type' 2>/dev/null)
      if [[ "${pubsub_present}" == "null" ]]; then
        echo "  [warn] app${idx} /admin/health does not expose pubsub component; skipping wait"
        break
      fi
      sleep 2
    done
  done
  return 0
}

# Echoes the host-bound actuator URL for a replica index (1, 2, or 3).
multi_stack::actuator_url() {
  local idx="${1}"
  printf 'http://localhost:%d8081' "${idx}"
}

# Echoes the container name for a replica index.
multi_stack::container_for_idx() {
  local idx="${1}"
  printf 'drools-app-%d' "${idx}"
}

# Echoes the three container names, space-separated.
multi_stack::list_app_containers() {
  printf 'drools-app-1 drools-app-2 drools-app-3'
}

# Echoes the three actuator URLs, space-separated.
multi_stack::list_actuator_urls() {
  printf 'http://localhost:18081 http://localhost:28081 http://localhost:38081'
}

# Reverse-maps a host actuator URL back to its container name. Used by the convergence
# harness to pick the right `docker exec` target.
multi_stack::container_for_actuator() {
  local url="${1}"
  case "${url}" in
    *18081*) printf 'drools-app-1' ;;
    *28081*) printf 'drools-app-2' ;;
    *38081*) printf 'drools-app-3' ;;
    *)       printf '' ;;
  esac
}

multi_stack::redis_container_name() {
  printf 'drools-redis'
}

# Hard-kills the Redis container (SIGKILL). Exit 0 even if already gone.
#
# IMPORTANT: the loadtest compose file has `restart: unless-stopped` on redis
# (and on the apps) so the stack stays up during normal operation. That policy
# makes `docker kill` a no-op for failure-mode testing — the Docker daemon
# respawns the container within milliseconds, so the apps' Lettuce client
# reconnects before any failure can register on the CB sliding window. We have
# to temporarily disable the restart policy around the kill window, then restore
# it after `docker start`.
#
# Phase 9.4 ran for a long time reporting "CB never opens during 60s Redis kill"
# until this was diagnosed: the kill was effectively a no-op.
multi_stack::kill_redis() {
  local name
  name=$(multi_stack::redis_container_name)
  echo "  [info] docker update --restart=no ${name} (so the kill actually keeps Redis down)"
  docker update --restart=no "${name}" > /dev/null 2>&1 || true
  echo "  [info] docker kill ${name}"
  docker kill "${name}" > /dev/null 2>&1 || true
}

# Restarts a previously-killed Redis container. Exit 0 on success.
# Restores the `unless-stopped` restart policy that kill_redis disabled.
multi_stack::start_redis() {
  local name
  name=$(multi_stack::redis_container_name)
  echo "  [info] docker start ${name}"
  docker start "${name}" > /dev/null 2>&1
  echo "  [info] docker update --restart=unless-stopped ${name} (restore policy)"
  docker update --restart=unless-stopped "${name}" > /dev/null 2>&1 || true
  # Give the daemon a beat to wire up the network alias.
  sleep 1
}

# Triggers an admin endpoint INSIDE a specific replica (bypasses nginx round-robin).
# Args:
#   $1 container       e.g. drools-app-1
#   $2 path            e.g. /admin/refresh-rules or /admin/refresh-rules/<id>
#   $3 timeout_s       optional, default 180. Bulk refresh of 1000 rules can take ~50s
#                      (Drools compile time); single-rule refresh is ~50ms but we keep
#                      the same generous default for simplicity.
# Echoes response body. Returns wget's exit code (non-zero on HTTP 4xx/5xx).
#
# Uses `wget` (not curl) because the Amazon Corretto Alpine app image only ships wget.
# `--post-data=''` makes it a POST with no body — what Spring expects for these endpoints.
multi_stack::exec_admin_post() {
  local container="${1}"
  local path="${2}"
  local timeout_s="${3:-180}"
  docker exec "${container}" \
    wget --quiet --tries=1 --timeout="${timeout_s}" --post-data='' \
         -O - "http://localhost:8080${path}" 2>/dev/null
}
