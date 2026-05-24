#!/usr/bin/env bash
# Spring Boot Actuator metric helpers.
# Used by lib/convergence.sh (Phase 9.3) and phase_9_4_failure_mode (CB state polling).
#
# Spring's `/actuator/metrics/{name}` response shape:
#   {"name":"drools.refresh.received",
#    "measurements":[{"statistic":"COUNT","value":5.0}],
#    "availableTags":[{"tag":"event","values":["RULE_REFRESHED",...]}]}
#
# Filter by tag with `?tag=event:RULE_REFRESHED`. Counters return COUNT; gauges return VALUE.
# Either way, `.measurements[0].value` is the scalar we want.

# Fetches a single metric scalar from an actuator endpoint.
# Args:
#   $1 base_url   e.g. http://localhost:18081
#   $2 metric     e.g. drools.refresh.received
#   $3 tag_pair   optional, e.g. "event:RULE_REFRESHED" or "name:redisCircuitBreaker,state:open"
# Prints the scalar to stdout (e.g. "5.0"). Empty on failure (curl error, metric absent,
# tag combination yields no measurement).
actuator::counter_at() {
  local base_url="${1}"
  local metric="${2}"
  local tag_pair="${3:-}"

  local url="${base_url}/actuator/metrics/${metric}"
  if [[ -n "${tag_pair}" ]]; then
    # Spring accepts `tag=key:val` repeated. Comma-separated input expands to repeats.
    local query=""
    local IFS=','
    for pair in ${tag_pair}; do
      query="${query}&tag=${pair}"
    done
    url="${url}?${query#&}"
  fi

  local body
  body=$(curl -s --max-time 2 "${url}" 2>/dev/null) || return 0
  # `measurements` may be absent (404 on unknown metric) or empty (tag combo no-match).
  # jq returns "null" string in those cases; normalize to empty for callers.
  local val
  val=$(echo "${body}" | jq -r '.measurements[0].value // empty' 2>/dev/null)
  printf '%s' "${val}"
}

# Waits for a counter to reach at least `target_value`. Polls at 50ms granularity.
# Echoes "<epoch_ms>:<observed_value>" on success and returns 0.
# Echoes "-1:" and returns 1 on timeout.
#
# Args:
#   $1 base_url
#   $2 metric
#   $3 tag_pair        (may be empty)
#   $4 target_value    integer comparison floor; pass `baseline+1` to wait for any increment
#   $5 deadline_epoch_ms
actuator::wait_for_counter() {
  local base_url="${1}"
  local metric="${2}"
  local tag_pair="${3:-}"
  local target_value="${4}"
  local deadline_epoch_ms="${5}"

  while true; do
    local now
    now=$(now_ms)
    if (( now > deadline_epoch_ms )); then
      printf -- '-1:'
      return 1
    fi

    local cur
    cur=$(actuator::counter_at "${base_url}" "${metric}" "${tag_pair}")
    # cur may be empty (metric not yet registered on a cold replica) — treat as 0.
    if [[ -n "${cur}" ]]; then
      # Bash can't compare floats; truncate to integer for the >= test. This is safe
      # because Micrometer counters are monotonic and we only need "≥ baseline+1".
      local cur_int="${cur%%.*}"
      if (( cur_int >= target_value )); then
        printf '%s:%s' "${now}" "${cur}"
        return 0
      fi
    fi

    sleep 0.05
  done
}

# Convenience: dump every interesting Phase-9 metric on a replica into a JSON map.
# Used by phase_9_3 + phase_9_4 to capture pre/post snapshots for forensics.
# Args:
#   $1 base_url
#   $2 out_json_path
actuator::snapshot_phase9_metrics() {
  local base_url="${1}"
  local out="${2}"
  local metrics=(
    "drools.refresh.published"
    "drools.refresh.received"
    "drools.refresh.skipped_self"
    "drools.refresh.processing.duration"
    "drools.refresh.failed"
    "drools.cache.hit"
    "drools.cache.miss"
    "drools.cache.bulk.hit"
    "drools.cache.bulk.miss"
    "resilience4j.circuitbreaker.state"
    "resilience4j.circuitbreaker.calls"
  )
  echo "{" > "${out}"
  local first=true
  local m
  for m in "${metrics[@]}"; do
    local body
    body=$(curl -s --max-time 2 "${base_url}/actuator/metrics/${m}" 2>/dev/null || echo 'null')
    if [[ "${first}" == "true" ]]; then first=false; else echo "," >> "${out}"; fi
    printf '  "%s": %s' "${m}" "${body:-null}" >> "${out}"
  done
  echo >> "${out}"
  echo "}" >> "${out}"
}
