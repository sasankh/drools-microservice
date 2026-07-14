#!/usr/bin/env bash
# Cross-replica convergence measurement harness for Phase 9.3 / 9.4.
# Triggers a rule refresh on one replica via `docker exec`, then polls each sibling
# replica's `drools.refresh.received{event=<type>}` counter via actuator until it
# increments or the deadline expires. Records per-subscriber latency in CSV.
#
# Counter is incremented by RuleRefreshSubscriber.onMessage BEFORE the engine swap,
# so this measures pub/sub propagation, NOT handler completion. To measure handler
# completion, watch `drools.refresh.processing.duration{event=<type>}` count instead.
#
# Depends on: lib/stack.sh (now_ms), lib/actuator.sh, lib/multi-stack.sh.

# Writes the CSV header (once, idempotent — only writes if file is absent).
convergence::write_csv_header() {
  local out_csv="${1}"
  if [[ ! -f "${out_csv}" ]]; then
    echo "event_type,publisher_url,subscriber_url,rule_id,t_published_ms,t_received_ms,delta_ms,deadline_ms,pass" \
      > "${out_csv}"
  fi
}

# Truncates a counter value string ("5.0", "", "12") to an integer floor.
# Used to compare Micrometer floating-point counters with bash arithmetic.
convergence::_int_floor() {
  local v="${1:-0}"
  [[ -z "${v}" ]] && { printf '0'; return 0; }
  printf '%d' "${v%%.*}"
}

# Measures convergence latency for a single refresh event across N subscribers.
#
# Args:
#   $1 event_type       RULE_REFRESHED | RULE_REFRESHED_BULK
#   $2 pub_actuator     publisher actuator URL  (e.g. http://localhost:18081)
#   $3 sub_actuators    subscriber actuator URLs, space-separated
#   $4 rule_id          required for RULE_REFRESHED; ignored (use "") for BULK
#   $5 out_csv          path to append measurement rows (header must already exist)
#   $6 deadline_ms      per-subscriber wait deadline (default 2000)
#
# Returns 0 if every subscriber observed the event within the deadline; 1 otherwise.
convergence::measure_single() {
  local event_type="${1}"
  local pub_actuator="${2}"
  local sub_actuators="${3}"
  local rule_id="${4:-}"
  local out_csv="${5}"
  local deadline_ms="${6:-2000}"

  local pub_container
  pub_container=$(multi_stack::container_for_actuator "${pub_actuator}")
  if [[ -z "${pub_container}" ]]; then
    echo "  [FAIL] convergence::measure_single: cannot resolve container for '${pub_actuator}'"
    return 1
  fi

  local admin_path
  case "${event_type}" in
    RULE_REFRESHED)
      if [[ -z "${rule_id}" ]]; then
        echo "  [FAIL] convergence::measure_single: RULE_REFRESHED requires a rule_id"
        return 1
      fi
      admin_path="/admin/refresh-rules/${rule_id}"
      ;;
    RULE_REFRESHED_BULK)
      admin_path="/admin/refresh-rules"
      ;;
    *)
      echo "  [FAIL] convergence::measure_single: unknown event_type '${event_type}'"
      return 1
      ;;
  esac

  # 1. Baseline each subscriber's counter (with event-type tag filter).
  # Use parallel indexed arrays — bash 3.2 (macOS default) lacks associative arrays.
  local -a sub_arr=( ${sub_actuators} )
  local -a baseline_arr=()
  local sub b
  for sub in "${sub_arr[@]}"; do
    b=$(actuator::counter_at "${sub}" "drools.refresh.received" "event:${event_type}")
    baseline_arr+=( "$(convergence::_int_floor "${b}")" )
  done

  # 2. Trigger refresh inside the publisher container (bypasses nginx round-robin).
  # Anchor t_pub AFTER the wget response, not before — for a single-rule refresh the
  # server side is ~50ms, but a bulk refresh recompiles all rules (~5s for 100 rules,
  # ~50s for 1000) before publishing. Pre-call anchoring would make the 2s convergence
  # deadline expire before publish even happens. Anchoring post-response measures pure
  # publish→subscriber-receive latency (which can be slightly negative if subscriber
  # observes faster than wget returns to host; pass on delta<=deadline still applies).
  if ! multi_stack::exec_admin_post "${pub_container}" "${admin_path}" > /dev/null; then
    echo "  [FAIL] convergence::measure_single: refresh POST failed on ${pub_container}"
    return 1
  fi
  local t_pub
  t_pub=$(now_ms)
  local deadline_epoch
  deadline_epoch=$(( t_pub + deadline_ms ))

  # 3. Poll each subscriber until counter increments past baseline OR deadline.
  local overall=0
  local i
  for (( i=0; i<${#sub_arr[@]}; i++ )); do
    sub="${sub_arr[${i}]}"
    local target=$(( baseline_arr[${i}] + 1 ))
    local res
    res=$(actuator::wait_for_counter \
            "${sub}" "drools.refresh.received" "event:${event_type}" \
            "${target}" "${deadline_epoch}") || true
    local t_obs="${res%%:*}"
    local val="${res##*:}"
    local delta pass
    if [[ "${t_obs}" == "-1" || -z "${t_obs}" ]]; then
      delta=-1
      pass=false
      overall=1
    else
      delta=$(( t_obs - t_pub ))
      if (( delta <= deadline_ms )); then
        pass=true
      else
        pass=false
        overall=1
      fi
    fi
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "${event_type}" "${pub_actuator}" "${sub}" "${rule_id}" \
      "${t_pub}" "${t_obs}" "${delta}" "${deadline_ms}" "${pass}" \
      >> "${out_csv}"
  done

  return ${overall}
}

# Runs N convergence rounds rotating the publisher across all 3 replicas.
# Picks a fresh rule_id from the corpus CSV on each round (column 1, header skipped).
#
# Args:
#   $1 event_type       RULE_REFRESHED | RULE_REFRESHED_BULK
#   $2 rounds           how many measurement rounds to run
#   $3 rule_ids_csv     path to corpus CSV (header + `rule_id,input_json` rows)
#   $4 out_csv          measurements CSV (header must already exist)
#   $5 deadline_ms      default 2000
#
# Echoes "rounds=<N> passes=<P> failures=<F> max_delta_ms=<M>".
# Returns 0 if all rounds passed; 1 if any failed.
convergence::run_rounds() {
  local event_type="${1}"
  local rounds="${2}"
  local rule_ids_csv="${3}"
  local out_csv="${4}"
  local deadline_ms="${5:-2000}"

  # Cache the 3 replica URLs so we can rotate publisher / subscriber sets cheaply.
  local urls=(
    "$(multi_stack::actuator_url 1)"
    "$(multi_stack::actuator_url 2)"
    "$(multi_stack::actuator_url 3)"
  )

  # Build rule-id pool. For BULK we don't need IDs; just loop the rounds.
  local -a pool=()
  if [[ "${event_type}" == "RULE_REFRESHED" ]]; then
    # Read the CSV starting from row 2, take rule_id column.
    while IFS= read -r line; do
      pool+=("${line}")
    done < <(awk -F, 'NR>1 {print $1}' "${rule_ids_csv}" | head -n "${rounds}")
    if [[ ${#pool[@]} -lt ${rounds} ]]; then
      echo "  [warn] only ${#pool[@]} rule IDs available in corpus; reducing rounds to that"
      rounds=${#pool[@]}
    fi
  fi

  local r passes=0 fails=0
  for (( r=0; r<rounds; r++ )); do
    local pub_idx=$(( r % 3 ))
    local pub="${urls[${pub_idx}]}"
    local subs=""
    local i
    for i in 0 1 2; do
      if (( i != pub_idx )); then
        subs="${subs} ${urls[${i}]}"
      fi
    done
    subs="${subs# }"

    local rid=""
    [[ "${event_type}" == "RULE_REFRESHED" ]] && rid="${pool[${r}]}"

    echo "  round $((r+1))/${rounds}: pub=${pub} rid=${rid:-<bulk>}"
    if convergence::measure_single \
         "${event_type}" "${pub}" "${subs}" "${rid}" "${out_csv}" "${deadline_ms}"; then
      passes=$(( passes + 1 ))
    else
      fails=$(( fails + 1 ))
    fi
    # Small breather so the next round's baseline read sees the previous round's
    # increment as committed.
    sleep 0.2
  done

  local max_delta
  max_delta=$(awk -F, 'NR>1 && $7+0>max {max=$7+0} END {print max+0}' "${out_csv}")
  echo "rounds=${rounds} passes=${passes} failures=${fails} max_delta_ms=${max_delta}"
  [[ ${fails} -eq 0 ]]
}
