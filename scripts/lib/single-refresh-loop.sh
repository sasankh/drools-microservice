#!/usr/bin/env bash
# Background single-rule refresh loop. Picks a random rule from rule-ids.csv and
# calls POST /admin/refresh-rules/{ruleId} every <interval>s. Records per-call
# duration and outcome.
#
# This is the SLO-risk scenario: loadOrReplaceRule holds the write lock through
# compilation. Per-call duration approximates how long rule-execution reads are
# blocked during the swap.

single_refresh_loop::ensure_header() {
  local csv="${1}"
  if [[ ! -f "${csv}" ]]; then
    echo "epoch_s,iso_ts,rule_id,duration_ms,http_status,status" > "${csv}"
  fi
}

# Args: <csv_path> <pid_file> <rule_ids_csv> [interval_seconds=5]
single_refresh_loop::start() {
  local csv="${1}"
  local pid_file="${2}"
  local rule_ids_csv="${3}"
  local interval="${4:-5}"

  single_refresh_loop::ensure_header "${csv}"

  (
    # Read rule IDs into an array once at startup. Re-read every 100 iterations to pick up
    # corpus changes if any.
    local -a rule_ids
    local read_counter=0

    while true; do
      if (( read_counter % 100 == 0 )); then
        rule_ids=()
        # Skip header line (row 1)
        while IFS=',' read -r rid _; do
          [[ "${rid}" == "rule_id" || -z "${rid}" ]] && continue
          rule_ids+=("${rid}")
        done < "${rule_ids_csv}"
      fi
      read_counter=$((read_counter + 1))

      if [[ ${#rule_ids[@]} -eq 0 ]]; then
        sleep "${interval}"
        continue
      fi

      # Random rule
      local idx=$(( RANDOM % ${#rule_ids[@]} ))
      local rule_id="${rule_ids[idx]}"

      local epoch iso start_ms end_ms duration body http_status status
      epoch=$(date +%s)
      iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)
      start_ms=$(perl -MTime::HiRes -e 'printf "%d\n", Time::HiRes::time()*1000')
      body=$(curl -s -o /tmp/single-refresh-body.json -w "%{http_code}" \
                  -X POST "http://localhost:8080/admin/refresh-rules/${rule_id}" 2>/dev/null)
      end_ms=$(perl -MTime::HiRes -e 'printf "%d\n", Time::HiRes::time()*1000')
      duration=$(( end_ms - start_ms ))
      http_status="${body}"
      status=$(jq -r '.status // empty' /tmp/single-refresh-body.json 2>/dev/null)
      echo "${epoch},${iso},${rule_id},${duration},${http_status},${status}" >> "${csv}"
      sleep "${interval}"
    done
  ) &
  echo "$!" > "${pid_file}"
}

single_refresh_loop::stop() {
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
  rm -f /tmp/single-refresh-body.json
}
