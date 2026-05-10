#!/usr/bin/env bash
# Background memory poller. Writes a CSV row every N seconds with heap stats from
# /admin/memory/info. Used by Phases 3-7 to plot retained-heap-over-time offline.

# Header for the CSV file (idempotent: only written if file doesn't exist).
memory_poll::ensure_header() {
  local csv="${1}"
  if [[ ! -f "${csv}" ]]; then
    echo "epoch_s,iso_ts,used_mb,committed_mb,max_mb,usage_percent" > "${csv}"
  fi
}

# Starts a background poller. Writes its PID to <pid_file> so memory_poll::stop can kill it.
# Args: <csv_path> <pid_file> [interval_seconds=5]
memory_poll::start() {
  local csv="${1}"
  local pid_file="${2}"
  local interval="${3:-5}"

  memory_poll::ensure_header "${csv}"

  (
    while true; do
      local epoch iso info used committed maxmb pct
      epoch=$(date +%s)
      iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)
      info=$(curl -s http://localhost:8080/admin/memory/info 2>/dev/null)
      used=$(echo "${info}" | jq -r '.heap.usedMB // empty' 2>/dev/null)
      committed=$(echo "${info}" | jq -r '.heap.committedMB // empty' 2>/dev/null)
      maxmb=$(echo "${info}" | jq -r '.heap.maxMB // empty' 2>/dev/null)
      pct=$(echo "${info}" | jq -r '.heap.usagePercent // empty' 2>/dev/null)
      if [[ -n "${used}" ]]; then
        echo "${epoch},${iso},${used},${committed},${maxmb},${pct}" >> "${csv}"
      fi
      sleep "${interval}"
    done
  ) &
  echo "$!" > "${pid_file}"
}

# Stops the poller named by pid_file. Safe to call even if the poller is already gone.
memory_poll::stop() {
  local pid_file="${1}"
  if [[ -f "${pid_file}" ]]; then
    local pid
    pid=$(cat "${pid_file}")
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
      wait "${pid}" 2>/dev/null || true
    fi
    rm -f "${pid_file}"
  fi
}
