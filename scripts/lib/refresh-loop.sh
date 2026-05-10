#!/usr/bin/env bash
# Background full-refresh loop. Calls POST /admin/refresh-rules every <interval>s
# and writes per-call duration + outcome to a CSV.

refresh_loop::ensure_header() {
  local csv="${1}"
  if [[ ! -f "${csv}" ]]; then
    echo "epoch_s,iso_ts,duration_ms,http_status,rules_loaded,rules_failed" > "${csv}"
  fi
}

# Args: <csv_path> <pid_file> [interval_seconds=10]
refresh_loop::start() {
  local csv="${1}"
  local pid_file="${2}"
  local interval="${3:-10}"

  refresh_loop::ensure_header "${csv}"

  (
    while true; do
      local epoch iso start_ms end_ms duration body http_status loaded failed
      epoch=$(date +%s)
      iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)
      start_ms=$(perl -MTime::HiRes -e 'printf "%d\n", Time::HiRes::time()*1000')
      body=$(curl -s -o /tmp/refresh-body.json -w "%{http_code}" \
                  -X POST http://localhost:8080/admin/refresh-rules 2>/dev/null)
      end_ms=$(perl -MTime::HiRes -e 'printf "%d\n", Time::HiRes::time()*1000')
      duration=$(( end_ms - start_ms ))
      http_status="${body}"
      loaded=$(jq -r '.rules_loaded // empty' /tmp/refresh-body.json 2>/dev/null)
      failed=$(jq -r '.rules_failed // empty' /tmp/refresh-body.json 2>/dev/null)
      echo "${epoch},${iso},${duration},${http_status},${loaded},${failed}" >> "${csv}"
      sleep "${interval}"
    done
  ) &
  echo "$!" > "${pid_file}"
}

refresh_loop::stop() {
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
  rm -f /tmp/refresh-body.json
}
