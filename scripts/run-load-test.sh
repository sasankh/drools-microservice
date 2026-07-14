#!/usr/bin/env bash
# ============================================================================
# Production-readiness load + stress + soak test orchestrator
#
# One command. Sequences every phase end-to-end. Re-runnable on any branch
# whenever the architecture changes (Drools version, Spring Boot version,
# rule shape, caching layer).
#
# See .ai-workspace/project-plans/load-test-plan.md for the design.
# See scripts/README.md for usage.
# ============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# Locations
# ----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIB_DIR="${SCRIPT_DIR}/lib"
JMX_DIR="${SCRIPT_DIR}/jmeter"

cd "${PROJECT_ROOT}"

# ----------------------------------------------------------------------------
# Source library helpers
# ----------------------------------------------------------------------------
# shellcheck source=lib/preflight.sh
source "${LIB_DIR}/preflight.sh"
# shellcheck source=lib/stack.sh
source "${LIB_DIR}/stack.sh"
# shellcheck source=lib/corpus.sh
source "${LIB_DIR}/corpus.sh"
# shellcheck source=lib/memory-poll.sh
source "${LIB_DIR}/memory-poll.sh"
# shellcheck source=lib/refresh-loop.sh
source "${LIB_DIR}/refresh-loop.sh"
# shellcheck source=lib/single-refresh-loop.sh
source "${LIB_DIR}/single-refresh-loop.sh"
# shellcheck source=lib/heap-dump.sh
source "${LIB_DIR}/heap-dump.sh"
# Phase 9 (Redis cache + pub/sub) — multi-container + convergence harness.
# shellcheck source=lib/multi-stack.sh
source "${LIB_DIR}/multi-stack.sh"
# shellcheck source=lib/actuator.sh
source "${LIB_DIR}/actuator.sh"
# shellcheck source=lib/convergence.sh
source "${LIB_DIR}/convergence.sh"

# ----------------------------------------------------------------------------
# Defaults (overridable via env vars; --quick scales them down)
# ----------------------------------------------------------------------------
RULE_COUNT="${RULE_COUNT:-1000}"
BASELINE_RPS="${BASELINE_RPS:-50}"
BASELINE_MIN="${BASELINE_MIN:-30}"
RAMP_STEPS="${RAMP_STEPS:-50,100,250,500}"
RAMP_STEP_MIN="${RAMP_STEP_MIN:-5}"
HOT_REFRESH_MIN="${HOT_REFRESH_MIN:-30}"
HOT_REFRESH_INTERVAL_S="${HOT_REFRESH_INTERVAL_S:-10}"
HOT_SINGLE_INTERVAL_S="${HOT_SINGLE_INTERVAL_S:-5}"
SOAK_MIN="${SOAK_MIN:-60}"
SOAK_FULL_REFRESH_S="${SOAK_FULL_REFRESH_S:-30}"
SOAK_SINGLE_REFRESH_S="${SOAK_SINGLE_REFRESH_S:-10}"

# Acceptance thresholds (from load-test-plan.md success criteria)
BASELINE_P99_MS_MAX="${BASELINE_P99_MS_MAX:-200}"
SAFE_RPS_MIN="${SAFE_RPS_MIN:-250}"
HEAP_DRIFT_MB_MAX="${HEAP_DRIFT_MB_MAX:-100}"
HOT_REFRESH_P99_MULTIPLIER_MAX="${HOT_REFRESH_P99_MULTIPLIER_MAX:-2}"

# Phase 9 (Redis cache + pub/sub) knobs
PHASE9_CACHE_ONLY_MIN="${PHASE9_CACHE_ONLY_MIN:-10}"       # 9.2 load duration (min)
PHASE9_FULL_MODE_MIN="${PHASE9_FULL_MODE_MIN:-5}"          # 9.3 background load duration (min)
PHASE9_FAILURE_MIN="${PHASE9_FAILURE_MIN:-10}"             # 9.4 total JMeter run (min)
PHASE9_SINGLE_ROUNDS="${PHASE9_SINGLE_ROUNDS:-10}"         # 9.3 single-rule convergence rounds
PHASE9_BULK_ROUNDS="${PHASE9_BULK_ROUNDS:-5}"              # 9.3 bulk convergence rounds
PHASE9_UNDER_LOAD_ROUNDS="${PHASE9_UNDER_LOAD_ROUNDS:-5}"  # 9.3 convergence rounds during load
CONVERGENCE_DEADLINE_MS="${CONVERGENCE_DEADLINE_MS:-2000}" # per-subscriber wait deadline
REDIS_KILL_AT_S="${REDIS_KILL_AT_S:-120}"                  # 9.4 wallclock offset for Redis kill
REDIS_DOWN_DURATION_S="${REDIS_DOWN_DURATION_S:-120}"      # 9.4 stay-down duration before restart
CB_OPEN_DEADLINE_S="${CB_OPEN_DEADLINE_S:-30}"             # 9.4 must-open-by deadline
CB_CLOSE_DEADLINE_S="${CB_CLOSE_DEADLINE_S:-90}"           # 9.4 must-close-by deadline (after restart)
PHASE9_4_RECOVERY_SETTLE_S="${PHASE9_4_RECOVERY_SETTLE_S:-10}"  # 9.4 sleep after CB-closed before firing post-restart convergence round, so the listener's 2s FixedBackOff retry cycle completes re-subscription before the publish lands. Default 10s based on observed Spring Data Redis recovery time; tune down if your Redis stack reconnects faster.

# Run-mode flags
PHASE_FILTER=""
FROM_PHASE=""
QUICK_MODE=false
NO_CLEANUP=false

# ----------------------------------------------------------------------------
# CLI parsing
# ----------------------------------------------------------------------------
usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --phase N         Run only phase N (2..8, or 9 / 9.1 / 9.2 / 9.3 / 9.4).
                    --phase 9 runs all four Phase 9 sub-tests in sequence.
  --from N          Resume from phase N. Accepts decimal (9.2 = run 9.2..9.4).
  --quick           Scale-down for smoke test: 100 rules, 5-min soak,
                    1-min baseline. Useful for verifying the harness itself.
  --no-cleanup      Leave the stack up after exit (skip docker compose down).
  -h, --help        Show this help.

Phases 0-8 (existing single-container load test — see load-test-plan.md):
  0  Pre-flight checks (always runs unless --from > 0)
  2  Corpus generation + stack boot + first-refresh baseline
  3  Baseline (steady state, 50 RPS x 30 min)
  4  Concurrency ramp (50 -> 100 -> 250 -> 500 RPS)
  5  Hot full-refresh under load (180 refreshes / 30 min)
  6  Hot single-rule refresh under load (360 refreshes / 30 min)
  7  1-hour soak with mixed workload + heap dumps at start/end
  8  Heap analysis + verdict (writes summary.md)

  (Phase 1 = Tier 1 sample-rules expansion, source-code change, not orchestrated.)

Phases 9.1-9.4 (Redis cache + pub/sub validation — opt-in only):
  9.1  Baseline regression with REDIS_ENABLED=false (single container, 30 min)
  9.2  3-replica cache-only mode (REDIS_PUBSUB_ENABLED=false, 10 min)
  9.3  3-replica full mode + cross-replica convergence measurement (~25 min)
  9.4  Full mode + Redis kill/restart mid-load (failure-mode validation, ~12 min)
  Phase 9 also writes phase-9-summary.md (in the run dir) aggregating sub-test results.

Env-var knobs:
  RULE_COUNT                    1000 by default
  SOAK_MIN                      60 minutes by default
  BASELINE_MIN                  30 minutes by default
  BASELINE_RPS                  50 by default
  RAMP_STEPS                    "50,100,250,500" by default

  Phase 9 knobs:
  PHASE9_CACHE_ONLY_MIN         9.2 load duration (min, default 10)
  PHASE9_FULL_MODE_MIN          9.3 background load duration (min, default 5)
  PHASE9_FAILURE_MIN            9.4 total JMeter run (min, default 10)
  PHASE9_SINGLE_ROUNDS          9.3 single-rule convergence rounds (default 10)
  PHASE9_BULK_ROUNDS            9.3 bulk convergence rounds (default 5)
  PHASE9_UNDER_LOAD_ROUNDS      9.3 convergence rounds under load (default 5)
  CONVERGENCE_DEADLINE_MS       Per-subscriber wait deadline (default 2000)
  REDIS_KILL_AT_S               9.4 offset for Redis kill (default 120)
  REDIS_DOWN_DURATION_S         9.4 stay-down duration before restart (default 120)
  CB_OPEN_DEADLINE_S            9.4 must-open-by deadline (default 30)
  CB_CLOSE_DEADLINE_S           9.4 must-close-by deadline (default 90)
  PHASE9_4_RECOVERY_SETTLE_S    9.4 sleep after CB-closed before convergence round
                                 (default 10 — Spring Data Redis listener recovery
                                  can take longer than the FixedBackOff interval alone)
  PHASE9_4_EXERCISER_INTERVAL_S 9.4 CB-exerciser per-iteration sleep (default 1 —
                                 lower cadence accumulates failures fast enough to
                                 flip the 50-call CB sliding window within
                                 CB_OPEN_DEADLINE_S)
  ... (see top of script)

Output:
  scripts/load-test-results/<timestamp>/
    phase-{0..8}/...
    memory.csv
    heap-dump-{start,end}.hprof
    jtl/...
    reports/...
    summary.md         (auto-generated PASS/FAIL verdict)

Examples:
  ./scripts/run-load-test.sh                    # Full phases 0-8 run (~6h)
  ./scripts/run-load-test.sh --quick            # Smoke test (~10 min)
  ./scripts/run-load-test.sh --phase 3          # Just baseline
  ./scripts/run-load-test.sh --from 5           # Resume from hot-refresh
  RULE_COUNT=5000 SOAK_MIN=240 ./scripts/run-load-test.sh

  Phase 9 (Redis cache + pub/sub validation; opt-in):
  ./scripts/run-load-test.sh --phase 9          # All 9.1-9.4 in sequence (~90 min)
  ./scripts/run-load-test.sh --phase 9.1        # Single-container baseline regression
  ./scripts/run-load-test.sh --phase 9.3        # 3-replica pub/sub convergence headline
  ./scripts/run-load-test.sh --phase 9.4 --quick  # Quick failure-mode smoke (~5 min)
  ./scripts/run-load-test.sh --from 9.2         # Skip 9.1, run 9.2-9.4
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --phase)      PHASE_FILTER="$2"; shift 2 ;;
    --from)       FROM_PHASE="$2"; shift 2 ;;
    --quick)      QUICK_MODE=true; shift ;;
    --no-cleanup) NO_CLEANUP=true; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            echo "ERROR: unknown flag '$1'"; usage; exit 1 ;;
  esac
done

if $QUICK_MODE; then
  RULE_COUNT=100
  SOAK_MIN=5
  BASELINE_MIN=1
  HOT_REFRESH_MIN=2
  RAMP_STEP_MIN=1
  PHASE9_CACHE_ONLY_MIN=2
  PHASE9_FULL_MODE_MIN=1
  PHASE9_FAILURE_MIN=4
  REDIS_KILL_AT_S=60
  REDIS_DOWN_DURATION_S=60
fi

# Flag preflight that Phase 9 multi-container mode (9.2/9.3/9.4) is in play, so it
# also checks for free ports 18081/28081/38081. should_run_phase9 is safe to call here
# even though it's defined further down — bash resolves function bodies at call time.

# ----------------------------------------------------------------------------
# Run-scoped output directory + summary state
#
# Default: each invocation creates a fresh timestamped dir.
# Exception: when running an individual phase >= 3, or --from >= 3, we continue
# the most recent existing run dir (so rule-ids.csv from Phase 2, baseline_p99
# from Phase 3, etc. survive across invocations).
# ----------------------------------------------------------------------------
RUN_TS="$(date -u +%Y-%m-%dT%H%M%SZ)"
RUN_DIR="${SCRIPT_DIR}/load-test-results/${RUN_TS}"

resume_threshold=3
should_resume=false
# Use integer prefix to keep bash arithmetic happy with decimal phase IDs (e.g. "9.2").
if [[ -n "${PHASE_FILTER}" && "${PHASE_FILTER%%.*}" -ge "${resume_threshold}" ]]; then
  should_resume=true
fi
if [[ -n "${FROM_PHASE}" && "${FROM_PHASE%%.*}" -ge "${resume_threshold}" ]]; then
  should_resume=true
fi
if $should_resume; then
  # Find the most recent prior run dir that actually has rule-ids.csv (skips failed runs).
  prior_run=""
  for d in $(find "${SCRIPT_DIR}/load-test-results" -mindepth 1 -maxdepth 1 -type d \
              -name '20*' 2>/dev/null | sort -r); do
    if [[ -f "${d}/rule-ids.csv" ]]; then
      prior_run="${d}"
      break
    fi
  done
  if [[ -n "${prior_run}" ]]; then
    RUN_DIR="${prior_run}"
    RUN_TS=$(basename "${prior_run}")
    echo "==> Continuing prior run dir: ${RUN_DIR}"
  fi
fi

mkdir -p "${RUN_DIR}"

# rule-ids.csv (corpus) lives at RUN_DIR root for JMeter/loop scripts to share.
RULE_IDS_CSV="${RUN_DIR}/rule-ids.csv"

# Memory poller for the whole run (each phase appends; phase boundaries are recorded
# as marker rows via memory_poll::ensure_header indirectly).
MEMORY_CSV="${RUN_DIR}/memory.csv"
MEMORY_POLL_PID="${RUN_DIR}/.memory-poll.pid"

# Per-phase results — bash 3.2-compatible flat log (one "phase=status" line per phase).
PHASE_RESULTS_LOG=""

set_phase_result() {
  PHASE_RESULTS_LOG="${PHASE_RESULTS_LOG}${1}=${2}
"
}

get_phase_result() {
  local phase="$1"
  printf '%s' "${PHASE_RESULTS_LOG}" \
    | awk -F'=' -v p="${phase}" '$1 == p { v=$2 } END { print v }'
}

# ----------------------------------------------------------------------------
# Trap: ensure cleanup runs even on Ctrl-C / errors
# ----------------------------------------------------------------------------
cleanup() {
  local exit_code=$?
  echo
  echo "==> Cleanup"
  memory_poll::stop "${MEMORY_POLL_PID}" 2>/dev/null || true
  refresh_loop::stop "${RUN_DIR}/.refresh-loop.pid" 2>/dev/null || true
  single_refresh_loop::stop "${RUN_DIR}/.single-refresh-loop.pid" 2>/dev/null || true
  if ! $NO_CLEANUP; then
    stack::down
  else
    echo "  --no-cleanup: leaving stack up"
  fi
  if [[ ${exit_code} -eq 0 ]]; then
    echo "==> Run finished. Output: ${RUN_DIR}"
  else
    echo "==> Run exited with code ${exit_code}. Output: ${RUN_DIR}"
  fi
  exit ${exit_code}
}
trap cleanup EXIT INT TERM

# ----------------------------------------------------------------------------
# Phase dispatch helper. Decides whether a given phase should run based on
# --phase / --from flags.
# ----------------------------------------------------------------------------
should_run_phase() {
  local n="$1"
  if [[ -n "${PHASE_FILTER}" ]]; then
    [[ "${PHASE_FILTER}" == "${n}" ]] && return 0 || return 1
  fi
  if [[ -n "${FROM_PHASE}" ]]; then
    # Use integer part so decimal FROM_PHASE (e.g. "9.2") still compares against integer n.
    local from_int="${FROM_PHASE%%.*}"
    if [[ ${n} -lt ${from_int} ]]; then
      return 1
    fi
    # FROM_PHASE inside the 9.x family means "skip 0-8 entirely"; integer-phase callers
    # should never see a 9.x FROM_PHASE asking them to run. Treat n<9 as skip.
    if [[ ${from_int} -ge 9 && ${n} -lt 9 ]]; then
      return 1
    fi
  fi
  return 0
}

# Decides whether a 9.x sub-phase should run. Opt-in only: 9.x never runs unless
# the user explicitly passed --phase 9, --phase 9.x, or --from 9[.x].
# Args:
#   $1 id    e.g. "9.1"
should_run_phase9() {
  local id="$1"
  if [[ -n "${PHASE_FILTER}" ]]; then
    [[ "${PHASE_FILTER}" == "${id}" || "${PHASE_FILTER}" == "9" ]] && return 0 || return 1
  fi
  if [[ -n "${FROM_PHASE}" ]]; then
    local from_int="${FROM_PHASE%%.*}"
    [[ ${from_int} -lt 9 ]] && return 1
    # Compute decimal parts (default 0 for bare "9").
    local from_dec=0 id_dec
    [[ "${FROM_PHASE}" == *.* ]] && from_dec="${FROM_PHASE##*.}"
    id_dec="${id##*.}"
    if (( id_dec < from_dec )); then return 1; fi
    return 0
  fi
  return 1
}

phase_banner() {
  local n="$1" name="$2"
  echo
  echo "============================================================"
  echo "Phase ${n} — ${name}"
  echo "============================================================"
}

# ----------------------------------------------------------------------------
# JMeter helper. Runs execute-only.jmx with the given parameters and produces
# a JTL file in the phase output dir, plus an HTML report.
# Args: phase_dir rps duration_s threads tag
# ----------------------------------------------------------------------------
run_jmeter() {
  local phase_dir="$1" rps="$2" duration_s="$3" threads="$4" tag="$5"
  local jtl="${phase_dir}/jtl/${tag}.jtl"
  local report_dir="${phase_dir}/reports/${tag}"
  mkdir -p "$(dirname "${jtl}")" "${report_dir}"

  # Constant Throughput Timer is per-MINUTE under calcMode=2 (all-active-threads-shared)
  local rps_per_min=$(( rps * 60 ))

  echo "  jmeter: ${rps} RPS for ${duration_s}s with ${threads} threads → ${tag}.jtl"
  jmeter -n \
    -t "${JMX_DIR}/execute-only.jmx" \
    -Jhost=localhost \
    -Jport=8080 \
    -Jrps_per_min="${rps_per_min}" \
    -Jduration_s="${duration_s}" \
    -Jthreads="${threads}" \
    -Jrule_ids_csv="${RULE_IDS_CSV}" \
    -Jjtl_path="${jtl}" \
    -j "${phase_dir}/jmeter-${tag}.log" \
    -l /dev/null \
    > "${phase_dir}/jmeter-${tag}.stdout" 2>&1 || {
      echo "  [FAIL] jmeter exited non-zero — see ${phase_dir}/jmeter-${tag}.log"
      return 1
    }

  # HTML report (best-effort; non-fatal if it fails)
  jmeter -g "${jtl}" -o "${report_dir}" \
    > "${phase_dir}/jmeter-${tag}-report.stdout" 2>&1 || true

  return 0
}

# Extracts P50/P95/P99/error-rate/count from a JTL file using perl.
# Outputs a single CSV line: count,p50,p95,p99,err_pct
# Uses perl rather than awk because macOS BSD awk lacks asort().
analyze_jtl() {
  local jtl="$1"
  perl -e '
    my @times; my $errors = 0; my $total = 0;
    open(my $fh, "<", $ARGV[0]) or die "cannot open $ARGV[0]: $!";
    <$fh>;                                  # skip header
    while (my $line = <$fh>) {
      chomp $line;
      my @f = split /,/, $line;
      next if @f < 7;
      push @times, $f[1] + 0;               # elapsed ms is column 2 (index 1)
      $errors++ if $f[6] ne "true";         # success column is 7 (index 6)
      $total++;
    }
    close $fh;
    if ($total == 0) { print "0,0,0,0,0\n"; exit; }
    my @sorted = sort { $a <=> $b } @times;
    my $i50 = int($total * 0.50); $i50 = 0 if $i50 >= $total;
    my $i95 = int($total * 0.95); $i95 = $total - 1 if $i95 >= $total;
    my $i99 = int($total * 0.99); $i99 = $total - 1 if $i99 >= $total;
    printf "%d,%d,%d,%d,%.3f\n",
      $total, $sorted[$i50], $sorted[$i95], $sorted[$i99],
      $errors * 100.0 / $total;
  ' "${jtl}"
}

# ----------------------------------------------------------------------------
# Phase 0 — Pre-flight
# ----------------------------------------------------------------------------
phase0() {
  if ! should_run_phase 0; then return 0; fi
  phase_banner 0 "Pre-flight"
  if ! preflight::check; then
    set_phase_result phase0 FAIL
    return 1
  fi
  set_phase_result phase0 PASS
}

# ----------------------------------------------------------------------------
# Phase 2 — Corpus generation + stack boot + first-refresh baseline
# ----------------------------------------------------------------------------
phase2() {
  if ! should_run_phase 2; then return 0; fi
  phase_banner 2 "Corpus generation + stack boot"
  local pdir="${RUN_DIR}/phase-2"
  mkdir -p "${pdir}"

  echo "==> Generating corpus (${RULE_COUNT} synthetic rules)"
  corpus::generate "${RULE_COUNT}" "${pdir}"
  cp "${pdir}/rule-ids.csv" "${RULE_IDS_CSV}"

  stack::down
  stack::up
  if ! stack::wait_for_health 120; then
    set_phase_result phase2 FAIL
    return 1
  fi

  corpus::clear_s3
  corpus::upload "${pdir}"

  echo "==> First full refresh (measures per-${RULE_COUNT}-rule compile time)"
  local start_ms end_ms
  start_ms=$(now_ms)
  curl -s -X POST http://localhost:8080/admin/refresh-rules > "${pdir}/first-refresh.json"
  end_ms=$(now_ms)
  local duration=$(( end_ms - start_ms ))
  echo "${duration}" > "${pdir}/first-refresh-ms.txt"

  local loaded
  loaded=$(jq -r '.rules_loaded // empty' "${pdir}/first-refresh.json" 2>/dev/null)
  echo "  first refresh: ${duration} ms; rules_loaded: ${loaded}"

  if [[ "${loaded}" != "${RULE_COUNT}" ]]; then
    echo "  [FAIL] expected ${RULE_COUNT} rules loaded, got '${loaded}'"
    set_phase_result phase2 FAIL
    return 1
  fi
  set_phase_result phase2 PASS
  echo "==> Phase 2 PASS"
}

# ----------------------------------------------------------------------------
# Phase 3 — Baseline (steady state)
# ----------------------------------------------------------------------------
phase3() {
  if ! should_run_phase 3; then return 0; fi
  phase_banner 3 "Baseline — ${BASELINE_RPS} RPS for ${BASELINE_MIN} min"
  local pdir="${RUN_DIR}/phase-3"
  mkdir -p "${pdir}"

  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"

  local duration_s=$(( BASELINE_MIN * 60 ))
  local threads=$(( BASELINE_RPS / 10 )); (( threads < 5 )) && threads=5
  if ! run_jmeter "${pdir}" "${BASELINE_RPS}" "${duration_s}" "${threads}" "baseline"; then
    memory_poll::stop "${MEMORY_POLL_PID}"
    set_phase_result phase3 FAIL
    return 1
  fi

  memory_poll::stop "${MEMORY_POLL_PID}"

  local stats
  stats=$(analyze_jtl "${pdir}/jtl/baseline.jtl")
  echo "  count,p50,p95,p99,err_pct = ${stats}"
  echo "${stats}" > "${pdir}/stats.csv"
  local p99 err_pct
  p99=$(echo "${stats}" | cut -d',' -f4)
  err_pct=$(echo "${stats}" | cut -d',' -f5)

  # Record baseline P99 for later phases to compare against
  echo "${p99}" > "${RUN_DIR}/baseline_p99_ms.txt"

  if (( p99 > BASELINE_P99_MS_MAX )); then
    echo "  [FAIL] P99 ${p99}ms > acceptance ${BASELINE_P99_MS_MAX}ms"
    set_phase_result phase3 FAIL
    return 1
  fi
  if [[ "${err_pct%.*}" != "0" ]] && [[ "${err_pct}" != "0.000" ]]; then
    echo "  [FAIL] error rate ${err_pct}% > 0"
    set_phase_result phase3 FAIL
    return 1
  fi
  set_phase_result phase3 PASS
  echo "==> Phase 3 PASS (P99=${p99}ms, err=${err_pct}%)"
}

# ----------------------------------------------------------------------------
# Phase 4 — Concurrency ramp
# ----------------------------------------------------------------------------
phase4() {
  if ! should_run_phase 4; then return 0; fi
  phase_banner 4 "Concurrency ramp — ${RAMP_STEPS} RPS, ${RAMP_STEP_MIN} min/step"
  local pdir="${RUN_DIR}/phase-4"
  mkdir -p "${pdir}"

  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"

  local safe_rps=0
  local IFS=','
  for step in ${RAMP_STEPS}; do
    local duration_s=$(( RAMP_STEP_MIN * 60 ))
    local threads=$(( step / 10 )); (( threads < 5 )) && threads=5
    if ! run_jmeter "${pdir}" "${step}" "${duration_s}" "${threads}" "rps-${step}"; then
      echo "  [info] step ${step} RPS — jmeter run failed; treating as cliff"
      break
    fi
    local stats p99 err_pct
    stats=$(analyze_jtl "${pdir}/jtl/rps-${step}.jtl")
    p99=$(echo "${stats}" | cut -d',' -f4)
    err_pct=$(echo "${stats}" | cut -d',' -f5)
    echo "  step ${step} RPS: P99=${p99}ms, err=${err_pct}%"
    echo "${step},${stats}" >> "${pdir}/ramp-stats.csv"

    # Stop conditions: P99 > 1000ms OR error rate > 1%
    if (( p99 > 1000 )); then
      echo "  [info] step ${step} RPS: P99 ${p99}ms > 1000ms — cliff reached"
      break
    fi
    if (( $(echo "${err_pct} > 1" | bc -l 2>/dev/null || echo 0) )); then
      echo "  [info] step ${step} RPS: err ${err_pct}% > 1% — cliff reached"
      break
    fi
    safe_rps=${step}
  done

  memory_poll::stop "${MEMORY_POLL_PID}"

  echo "${safe_rps}" > "${RUN_DIR}/safe_rps.txt"
  echo "  safe-RPS ceiling: ${safe_rps}"

  if (( safe_rps < SAFE_RPS_MIN )); then
    echo "  [FAIL] safe-RPS ceiling ${safe_rps} < acceptance ${SAFE_RPS_MIN}"
    set_phase_result phase4 FAIL
    return 1
  fi
  set_phase_result phase4 PASS
  echo "==> Phase 4 PASS (safe-RPS=${safe_rps})"
}

# ----------------------------------------------------------------------------
# Phase 5 — Hot full-refresh under load
# ----------------------------------------------------------------------------
phase5() {
  if ! should_run_phase 5; then return 0; fi
  phase_banner 5 "Hot full-refresh under load — ${HOT_REFRESH_MIN} min"
  local pdir="${RUN_DIR}/phase-5"
  mkdir -p "${pdir}"

  local safe_rps
  safe_rps=$(cat "${RUN_DIR}/safe_rps.txt" 2>/dev/null || echo "${BASELINE_RPS}")
  local target_rps=$(( safe_rps / 2 )); (( target_rps < BASELINE_RPS )) && target_rps=${BASELINE_RPS}
  echo "  background load = ${target_rps} RPS; refreshing every ${HOT_REFRESH_INTERVAL_S}s"

  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"
  refresh_loop::start "${pdir}/refresh-loop.csv" "${RUN_DIR}/.refresh-loop.pid" "${HOT_REFRESH_INTERVAL_S}"

  local duration_s=$(( HOT_REFRESH_MIN * 60 ))
  local threads=$(( target_rps / 10 )); (( threads < 5 )) && threads=5
  run_jmeter "${pdir}" "${target_rps}" "${duration_s}" "${threads}" "exec-during-refresh" || true

  refresh_loop::stop "${RUN_DIR}/.refresh-loop.pid"
  memory_poll::stop "${MEMORY_POLL_PID}"

  local stats p99 err_pct
  stats=$(analyze_jtl "${pdir}/jtl/exec-during-refresh.jtl")
  p99=$(echo "${stats}" | cut -d',' -f4)
  err_pct=$(echo "${stats}" | cut -d',' -f5)
  echo "${stats}" > "${pdir}/stats.csv"

  local baseline_p99
  baseline_p99=$(cat "${RUN_DIR}/baseline_p99_ms.txt" 2>/dev/null || echo 200)
  local p99_max=$(( baseline_p99 * HOT_REFRESH_P99_MULTIPLIER_MAX ))

  echo "  P99=${p99}ms (baseline ${baseline_p99}ms, max ${p99_max}ms); err=${err_pct}%"

  local pass=true
  (( p99 > p99_max )) && { echo "  [FAIL] P99 above ${HOT_REFRESH_P99_MULTIPLIER_MAX}x baseline"; pass=false; }
  if [[ "${err_pct}" != "0.000" ]]; then
    if (( $(echo "${err_pct} > 0" | bc -l 2>/dev/null || echo 0) )); then
      echo "  [FAIL] error rate ${err_pct}% > 0"; pass=false
    fi
  fi

  if $pass; then
    set_phase_result phase5 PASS
    echo "==> Phase 5 PASS"
  else
    set_phase_result phase5 FAIL
    return 1
  fi
}

# ----------------------------------------------------------------------------
# Phase 6 — Hot single-rule refresh under load (riskiest)
# ----------------------------------------------------------------------------
phase6() {
  if ! should_run_phase 6; then return 0; fi
  phase_banner 6 "Hot single-rule refresh under load — ${HOT_REFRESH_MIN} min"
  local pdir="${RUN_DIR}/phase-6"
  mkdir -p "${pdir}"

  local safe_rps
  safe_rps=$(cat "${RUN_DIR}/safe_rps.txt" 2>/dev/null || echo "${BASELINE_RPS}")
  local target_rps=$(( safe_rps / 2 )); (( target_rps < BASELINE_RPS )) && target_rps=${BASELINE_RPS}
  echo "  background load = ${target_rps} RPS; single-rule refresh every ${HOT_SINGLE_INTERVAL_S}s"

  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"
  single_refresh_loop::start \
    "${pdir}/single-refresh-loop.csv" \
    "${RUN_DIR}/.single-refresh-loop.pid" \
    "${RULE_IDS_CSV}" \
    "${HOT_SINGLE_INTERVAL_S}"

  local duration_s=$(( HOT_REFRESH_MIN * 60 ))
  local threads=$(( target_rps / 10 )); (( threads < 5 )) && threads=5
  run_jmeter "${pdir}" "${target_rps}" "${duration_s}" "${threads}" "exec-during-single-refresh" || true

  single_refresh_loop::stop "${RUN_DIR}/.single-refresh-loop.pid"
  memory_poll::stop "${MEMORY_POLL_PID}"

  local stats p95 p99 err_pct
  stats=$(analyze_jtl "${pdir}/jtl/exec-during-single-refresh.jtl")
  p95=$(echo "${stats}" | cut -d',' -f3)
  p99=$(echo "${stats}" | cut -d',' -f4)
  err_pct=$(echo "${stats}" | cut -d',' -f5)
  echo "${stats}" > "${pdir}/stats.csv"

  local baseline_p99
  baseline_p99=$(cat "${RUN_DIR}/baseline_p99_ms.txt" 2>/dev/null || echo 200)
  echo "  P95=${p95}ms; P99=${p99}ms (baseline ${baseline_p99}ms); err=${err_pct}%"

  # Acceptance: 0 errors. P95 stable. P99 may spike during refresh windows
  # (acceptable up to compile time); >3x baseline is a flag-for-revisit signal.
  local pass=true
  if [[ "${err_pct}" != "0.000" ]]; then
    if (( $(echo "${err_pct} > 0" | bc -l 2>/dev/null || echo 0) )); then
      echo "  [FAIL] error rate ${err_pct}% > 0"; pass=false
    fi
  fi
  local p99_3x=$(( baseline_p99 * 3 ))
  if (( p99 > p99_3x )); then
    echo "  [warn] P99 ${p99}ms > 3x baseline ${baseline_p99}ms — flag for ADR revisit"
  fi

  if $pass; then
    set_phase_result phase6 PASS
    echo "==> Phase 6 PASS"
  else
    set_phase_result phase6 FAIL
    return 1
  fi
}

# ----------------------------------------------------------------------------
# Phase 7 — 1-hour soak (the leak hunt)
# ----------------------------------------------------------------------------
phase7() {
  if ! should_run_phase 7; then return 0; fi
  phase_banner 7 "1-hour soak — ${SOAK_MIN} min mixed workload"
  local pdir="${RUN_DIR}/phase-7"
  mkdir -p "${pdir}"

  local safe_rps
  safe_rps=$(cat "${RUN_DIR}/safe_rps.txt" 2>/dev/null || echo "${BASELINE_RPS}")
  local target_rps=$(( safe_rps / 2 )); (( target_rps < BASELINE_RPS )) && target_rps=${BASELINE_RPS}

  echo "==> Forcing GC + capturing start heap dump"
  stack::trigger_gc > /dev/null
  sleep 5
  local heap_start
  heap_start=$(stack::heap_used_mb)
  echo "${heap_start}" > "${pdir}/heap-start-mb.txt"
  heap_dump::capture "${pdir}/heap-dump-start.hprof" || echo "  [warn] start heap dump failed"

  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"
  refresh_loop::start "${pdir}/refresh-loop.csv" "${RUN_DIR}/.refresh-loop.pid" "${SOAK_FULL_REFRESH_S}"
  single_refresh_loop::start \
    "${pdir}/single-refresh-loop.csv" \
    "${RUN_DIR}/.single-refresh-loop.pid" \
    "${RULE_IDS_CSV}" \
    "${SOAK_SINGLE_REFRESH_S}"

  local duration_s=$(( SOAK_MIN * 60 ))
  local threads=$(( target_rps / 10 )); (( threads < 5 )) && threads=5
  echo "==> Running ${SOAK_MIN}-min soak at ${target_rps} RPS"
  run_jmeter "${pdir}" "${target_rps}" "${duration_s}" "${threads}" "soak" || true

  refresh_loop::stop "${RUN_DIR}/.refresh-loop.pid"
  single_refresh_loop::stop "${RUN_DIR}/.single-refresh-loop.pid"
  memory_poll::stop "${MEMORY_POLL_PID}"

  echo "==> Forcing GC + capturing end heap dump"
  stack::trigger_gc > /dev/null
  sleep 5
  local heap_end
  heap_end=$(stack::heap_used_mb)
  echo "${heap_end}" > "${pdir}/heap-end-mb.txt"
  heap_dump::capture "${pdir}/heap-dump-end.hprof" || echo "  [warn] end heap dump failed"

  local heap_delta=$(( heap_end - heap_start ))
  echo "${heap_delta}" > "${pdir}/heap-delta-mb.txt"
  echo "  heap start=${heap_start}MB, end=${heap_end}MB, delta=${heap_delta}MB"

  local stats err_pct
  stats=$(analyze_jtl "${pdir}/jtl/soak.jtl")
  err_pct=$(echo "${stats}" | cut -d',' -f5)
  echo "${stats}" > "${pdir}/stats.csv"

  local pass=true
  if (( heap_delta > HEAP_DRIFT_MB_MAX )); then
    echo "  [FAIL] heap drift ${heap_delta}MB > acceptance ${HEAP_DRIFT_MB_MAX}MB"
    pass=false
  fi
  if [[ "${err_pct}" != "0.000" ]]; then
    if (( $(echo "${err_pct} > 0" | bc -l 2>/dev/null || echo 0) )); then
      echo "  [FAIL] error rate ${err_pct}% > 0"
      pass=false
    fi
  fi

  if $pass; then
    set_phase_result phase7 PASS
    echo "==> Phase 7 PASS (heap delta ${heap_delta}MB, err ${err_pct}%)"
  else
    set_phase_result phase7 FAIL
    return 1
  fi
}

# ----------------------------------------------------------------------------
# Phase 8 — Heap analysis + verdict
# ----------------------------------------------------------------------------
phase8() {
  if ! should_run_phase 8; then return 0; fi
  phase_banner 8 "Heap analysis + verdict"
  local summary="${RUN_DIR}/summary.md"

  {
    echo "# Load Test Summary — ${RUN_TS}"
    echo
    echo "## Parameters"
    echo "- RULE_COUNT: ${RULE_COUNT}"
    echo "- BASELINE: ${BASELINE_RPS} RPS x ${BASELINE_MIN} min"
    echo "- RAMP: ${RAMP_STEPS} RPS, ${RAMP_STEP_MIN} min/step"
    echo "- HOT_REFRESH: ${HOT_REFRESH_MIN} min, every ${HOT_REFRESH_INTERVAL_S}s (full) / ${HOT_SINGLE_INTERVAL_S}s (single)"
    echo "- SOAK: ${SOAK_MIN} min, full-refresh every ${SOAK_FULL_REFRESH_S}s + single-refresh every ${SOAK_SINGLE_REFRESH_S}s"
    echo
    echo "## Acceptance criteria"
    echo "- Baseline P99 < ${BASELINE_P99_MS_MAX}ms"
    echo "- Safe-RPS ceiling ≥ ${SAFE_RPS_MIN}"
    echo "- Heap drift over soak < ${HEAP_DRIFT_MB_MAX}MB"
    echo "- Hot-refresh P99 ≤ ${HOT_REFRESH_P99_MULTIPLIER_MAX}x baseline"
    echo "- Zero errors in any phase"
    echo
    echo "## Per-phase results"
    echo
    local n
    for n in 0 2 3 4 5 6 7; do
      local key="phase${n}"
      local result
      result=$(get_phase_result "${key}")
      [[ -z "${result}" ]] && result="SKIPPED"
      echo "- Phase ${n}: ${result}"
    done
    echo
    echo "## Headline numbers"
    [[ -f "${RUN_DIR}/baseline_p99_ms.txt" ]] && echo "- Baseline P99: $(cat "${RUN_DIR}/baseline_p99_ms.txt") ms"
    [[ -f "${RUN_DIR}/safe_rps.txt" ]] && echo "- Safe-RPS ceiling: $(cat "${RUN_DIR}/safe_rps.txt") RPS"
    [[ -f "${RUN_DIR}/phase-7/heap-delta-mb.txt" ]] && echo "- Heap drift across soak: $(cat "${RUN_DIR}/phase-7/heap-delta-mb.txt") MB"
    [[ -f "${RUN_DIR}/phase-2/first-refresh-ms.txt" ]] && echo "- First refresh (compile time at ${RULE_COUNT} rules): $(cat "${RUN_DIR}/phase-2/first-refresh-ms.txt") ms"
    echo
    echo "## Verdict"
    local fails=0
    for n in 0 2 3 4 5 6 7; do
      [[ "$(get_phase_result phase${n})" == "FAIL" ]] && fails=$((fails + 1))
    done
    if [[ ${fails} -eq 0 ]]; then
      echo "**PASS** — production-ready against the documented acceptance criteria."
    else
      echo "**FAIL** — ${fails} phase(s) failed. See per-phase output dirs for details."
    fi
    echo
    echo "## Heap dump diff guidance"
    echo "Compare \`phase-7/heap-dump-start.hprof\` vs \`phase-7/heap-dump-end.hprof\` in Eclipse MAT or jhat."
    echo "Look for accumulated:"
    echo "- \`org.kie.api.builder.KieModule\` (should be ≤ 1)"
    echo "- \`com.company.drools.core.model.Rule\` (should be ≤ ${RULE_COUNT})"
    echo "- \`org.kie.api.runtime.KieSession\` (should be 0 retained)"
    echo "- \`org.drools.core.impl.KnowledgeBaseImpl\` (should be ≤ 1)"
  } > "${summary}"

  echo "==> Summary written to ${summary}"
  cat "${summary}"
  set_phase_result phase8 PASS
}

# ============================================================================
# Phase 9 — Redis cache + pub/sub validation
# Opt-in only via --phase 9 / --phase 9.x / --from 9[.x].
# See .ai-workspace/project-plans/redis-cache-layering-checklist.md section "Phase 9".
# ============================================================================

# Resolves PHASE9_CORPUS_DIR (a directory containing synthetic/ + rule-ids.csv) for the
# corpus::upload helper. Reuses a prior corpus IFF its rule count matches RULE_COUNT;
# otherwise (re-)generates into phase-corpus/. Idempotent — safe to call multiple times
# in one run.
PHASE9_CORPUS_DIR=""
phase9::ensure_corpus() {
  local existing rows
  for existing in "${RUN_DIR}/phase-corpus" "${RUN_DIR}/phase-2"; do
    if [[ -d "${existing}/synthetic" && -f "${existing}/rule-ids.csv" ]]; then
      rows=$(($(wc -l < "${existing}/rule-ids.csv") - 1))   # subtract header
      if [[ "${rows}" == "${RULE_COUNT}" ]]; then
        PHASE9_CORPUS_DIR="${existing}"
        cp -f "${existing}/rule-ids.csv" "${RULE_IDS_CSV}"
        return 0
      fi
      echo "  [info] ${existing} has ${rows} rules but RULE_COUNT=${RULE_COUNT}; regenerating"
    fi
  done
  PHASE9_CORPUS_DIR="${RUN_DIR}/phase-corpus"
  rm -rf "${PHASE9_CORPUS_DIR}"
  mkdir -p "${PHASE9_CORPUS_DIR}"
  echo "==> Generating corpus (${RULE_COUNT} synthetic rules) for Phase 9"
  corpus::generate "${RULE_COUNT}" "${PHASE9_CORPUS_DIR}"
  cp -f "${PHASE9_CORPUS_DIR}/rule-ids.csv" "${RULE_IDS_CSV}"
}

# Phase 9.1 — single-container baseline regression with REDIS_ENABLED=false.
# Acceptance: zero errors, P99 within BASELINE_P99_MS_MAX, ≥90% of expected request count.
phase_9_1_baseline() {
  if ! should_run_phase9 "9.1"; then return 0; fi
  phase_banner "9.1" "Single-container baseline regression (REDIS_ENABLED=false)"
  local pdir="${RUN_DIR}/phase-9-1"
  # Wipe prior-run artifacts. JMeter APPENDS to .jtl files by default, so re-running
  # without this would conflate verdicts across runs (we hit this exact bug).
  rm -rf "${pdir}"
  mkdir -p "${pdir}/jtl" "${pdir}/reports"

  phase9::ensure_corpus

  stack::down
  stack::up_with_disabled_overlay
  if ! stack::wait_for_health 180; then
    set_phase_result "phase9.1" FAIL
    echo "FAIL: stack did not become healthy" > "${pdir}/result.txt"
    return 1
  fi

  # Sanity: if disabled-mode is wired correctly, the RedisCachedRuleStorage bean should
  # never start. Warn (don't fail) if we see it — useful breadcrumb when the overlay misses.
  if docker logs drools-microservice-app-1 2>&1 | grep -qi 'RedisCachedRuleStorage'; then
    echo "  [warn] RedisCachedRuleStorage seen in app-1 logs despite REDIS_ENABLED=false — overlay merge may have failed"
  else
    echo "  [ok]   RedisCachedRuleStorage not loaded in disabled mode"
  fi

  corpus::clear_s3
  corpus::upload "${PHASE9_CORPUS_DIR}"
  echo "==> First refresh to load corpus"
  curl -s --max-time 180 -X POST http://localhost:8080/admin/refresh-rules > "${pdir}/first-refresh.json" || true

  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"
  local duration_s=$(( BASELINE_MIN * 60 ))
  local threads=$(( BASELINE_RPS / 10 )); (( threads < 5 )) && threads=5
  if ! run_jmeter "${pdir}" "${BASELINE_RPS}" "${duration_s}" "${threads}" "baseline-disabled"; then
    memory_poll::stop "${MEMORY_POLL_PID}"
    set_phase_result "phase9.1" FAIL
    echo "FAIL: jmeter run failed" > "${pdir}/result.txt"
    return 1
  fi
  memory_poll::stop "${MEMORY_POLL_PID}"

  local stats count p99 err_pct
  stats=$(analyze_jtl "${pdir}/jtl/baseline-disabled.jtl")
  echo "${stats}" > "${pdir}/stats.csv"
  count=$(echo "${stats}" | cut -d',' -f1)
  p99=$(echo "${stats}" | cut -d',' -f4)
  err_pct=$(echo "${stats}" | cut -d',' -f5)
  local min_count=$(( BASELINE_RPS * duration_s * 9 / 10 ))

  local pass=true
  local -a reasons=()
  (( p99 > BASELINE_P99_MS_MAX )) && { pass=false; reasons+=("P99 ${p99}ms > ${BASELINE_P99_MS_MAX}ms"); }
  if [[ "${err_pct%.*}" != "0" ]] && [[ "${err_pct}" != "0.000" ]]; then
    pass=false; reasons+=("error rate ${err_pct}% > 0")
  fi
  if (( count < min_count )); then
    pass=false; reasons+=("total ${count} < expected ${min_count} (90% of ${BASELINE_RPS} RPS × ${duration_s}s)")
  fi

  echo "  count=${count} p99=${p99}ms err=${err_pct}%"
  if $pass; then
    echo "PASS count=${count} p99=${p99} err=${err_pct}" > "${pdir}/result.txt"
    set_phase_result "phase9.1" PASS
    echo "==> Phase 9.1 PASS"
  else
    {
      echo "FAIL count=${count} p99=${p99} err=${err_pct}"
      printf -- '- %s\n' "${reasons[@]}"
    } > "${pdir}/result.txt"
    set_phase_result "phase9.1" FAIL
    return 1
  fi
}

# Phase 9.2 — 3-replica cache-only mode (REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false).
# Acceptance: stack runs without errors under load; Redis is actually being exercised
# (at least one cache-related counter is non-zero on every replica); P99 ≤ 2× baseline.
phase_9_2_cache_only() {
  if ! should_run_phase9 "9.2"; then return 0; fi
  phase_banner "9.2" "3-replica cache-only mode (REDIS_PUBSUB_ENABLED=false)"
  local pdir="${RUN_DIR}/phase-9-2"
  rm -rf "${pdir}"
  mkdir -p "${pdir}/jtl" "${pdir}/reports"

  phase9::ensure_corpus

  stack::down
  multi_stack::down
  multi_stack::up cache-only
  if ! multi_stack::wait_for_health 240; then
    set_phase_result "phase9.2" FAIL
    echo "FAIL: stack did not become healthy" > "${pdir}/result.txt"
    return 1
  fi

  corpus::clear_s3
  corpus::upload "${PHASE9_CORPUS_DIR}"

  # CRITICAL: pub/sub is OFF here, so a single nginx-routed bulk refresh would only load
  # rules into ONE replica — the other two would 404 every /execute-rule request and the
  # JMeter run would post ~67% errors. Refresh each replica directly via docker exec so
  # all three have the corpus loaded.
  echo "==> Warming: bulk refresh on each replica directly (pub/sub is off)"
  local i container
  for i in 1 2 3; do
    container=$(multi_stack::container_for_idx "${i}")
    if multi_stack::exec_admin_post "${container}" "/admin/refresh-rules" \
         > "${pdir}/warm-refresh-app-${i}.json"; then
      local loaded
      loaded=$(jq -r '.rules_loaded // empty' "${pdir}/warm-refresh-app-${i}.json" 2>/dev/null)
      echo "  [ok]   ${container} loaded ${loaded:-?} rules"
    else
      echo "  [warn] refresh on ${container} did not return 2xx; load may be partial"
    fi
  done
  sleep 3

  # Capture per-replica forensics: snapshot Phase 9 metrics on each replica, pre-load.
  for i in 1 2 3; do
    actuator::snapshot_phase9_metrics \
      "$(multi_stack::actuator_url ${i})" \
      "${pdir}/metric-snapshot-app-${i}-pre.json"
  done

  # Sustained load through nginx.
  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"
  local duration_s=$(( PHASE9_CACHE_ONLY_MIN * 60 ))
  local rps="${BASELINE_RPS}"
  local threads=$(( rps / 10 )); (( threads < 5 )) && threads=5
  run_jmeter "${pdir}" "${rps}" "${duration_s}" "${threads}" "cache-only-load" || true
  memory_poll::stop "${MEMORY_POLL_PID}"

  # Post snapshots + cross-replica Redis-touched check.
  echo "==> Snapshotting per-replica Phase 9 metrics (post-load)"
  {
    echo "replica,bulk_hit,bulk_miss,hit,miss,refresh_received,refresh_published"
    for i in 1 2 3; do
      local url; url=$(multi_stack::actuator_url ${i})
      actuator::snapshot_phase9_metrics "${url}" "${pdir}/metric-snapshot-app-${i}-post.json"
      local bh bm h m rr rp
      bh=$(actuator::counter_at "${url}" "drools.cache.bulk.hit")
      bm=$(actuator::counter_at "${url}" "drools.cache.bulk.miss")
      h=$(actuator::counter_at  "${url}" "drools.cache.hit" "layer:redis")
      m=$(actuator::counter_at  "${url}" "drools.cache.miss" "layer:redis")
      rr=$(actuator::counter_at "${url}" "drools.refresh.received")
      rp=$(actuator::counter_at "${url}" "drools.refresh.published")
      echo "drools-app-${i},${bh:-0},${bm:-0},${h:-0},${m:-0},${rr:-0},${rp:-0}"
    done
  } > "${pdir}/redis-metrics-post.csv"

  # Assertion 1: every replica touched Redis at least once (bulk.hit+bulk.miss > 0).
  local any_silent=false
  while IFS=, read -r replica bh bm _h _m _rr _rp; do
    [[ "${replica}" == "replica" ]] && continue
    local sum=$(( ${bh%%.*} + ${bm%%.*} ))
    if (( sum == 0 )); then
      echo "  [warn] ${replica} shows zero Redis activity (bulk.hit=${bh}, bulk.miss=${bm})"
      any_silent=true
    fi
  done < "${pdir}/redis-metrics-post.csv"

  # Assertion 2: pub/sub was off — every replica's refresh.received should be 0.
  local pubsub_leak=false
  while IFS=, read -r replica _bh _bm _h _m rr _rp; do
    [[ "${replica}" == "replica" ]] && continue
    if (( ${rr%%.*} > 0 )); then
      echo "  [warn] ${replica} received pub/sub events in cache-only mode (rr=${rr})"
      pubsub_leak=true
    fi
  done < "${pdir}/redis-metrics-post.csv"

  local stats p99 err_pct
  stats=$(analyze_jtl "${pdir}/jtl/cache-only-load.jtl")
  echo "${stats}" > "${pdir}/stats.csv"
  p99=$(echo "${stats}" | cut -d',' -f4)
  err_pct=$(echo "${stats}" | cut -d',' -f5)

  local pass=true
  local -a reasons=()
  if $any_silent; then pass=false; reasons+=("at least one replica showed zero Redis activity"); fi
  if $pubsub_leak; then pass=false; reasons+=("at least one replica received pub/sub events despite REDIS_PUBSUB_ENABLED=false"); fi
  if [[ "${err_pct%.*}" != "0" ]] && [[ "${err_pct}" != "0.000" ]]; then
    pass=false; reasons+=("load error rate ${err_pct}% > 0")
  fi
  local baseline_p99; baseline_p99=$(cat "${RUN_DIR}/baseline_p99_ms.txt" 2>/dev/null || echo "${BASELINE_P99_MS_MAX}")
  local p99_max=$(( baseline_p99 * 2 ))
  if (( p99 > p99_max )); then
    pass=false; reasons+=("P99 ${p99}ms > 2× baseline ${baseline_p99}ms (max ${p99_max}ms)")
  fi

  echo "  P99=${p99}ms err=${err_pct}%"
  if $pass; then
    echo "PASS p99=${p99} err=${err_pct}" > "${pdir}/result.txt"
    set_phase_result "phase9.2" PASS
    echo "==> Phase 9.2 PASS"
  else
    {
      echo "FAIL"
      printf -- '- %s\n' "${reasons[@]}"
    } > "${pdir}/result.txt"
    set_phase_result "phase9.2" FAIL
    return 1
  fi
}

# Phase 9.3 — 3-replica full mode (REDIS_PUBSUB_ENABLED=true). Headline convergence test.
# Acceptance: every convergence round (single + bulk + under-load) finishes within
# CONVERGENCE_DEADLINE_MS for every subscriber.
phase_9_3_full_mode() {
  if ! should_run_phase9 "9.3"; then return 0; fi
  phase_banner "9.3" "3-replica full mode (pub/sub convergence)"
  local pdir="${RUN_DIR}/phase-9-3"
  rm -rf "${pdir}"
  mkdir -p "${pdir}/jtl" "${pdir}/reports"

  phase9::ensure_corpus

  stack::down
  multi_stack::down
  multi_stack::up full
  if ! multi_stack::wait_for_health 240; then
    set_phase_result "phase9.3" FAIL
    echo "FAIL: stack did not become healthy" > "${pdir}/result.txt"
    return 1
  fi
  multi_stack::wait_for_pubsub 60

  corpus::clear_s3
  corpus::upload "${PHASE9_CORPUS_DIR}"

  echo "==> Warming: bulk refresh + 60s JIT-warm"
  curl -s --max-time 180 -X POST http://localhost:8080/admin/refresh-rules > "${pdir}/warm-refresh.json" || true
  sleep 60

  # Snapshot pre.
  local i
  for i in 1 2 3; do
    actuator::snapshot_phase9_metrics \
      "$(multi_stack::actuator_url ${i})" \
      "${pdir}/metric-snapshot-app-${i}-pre.json"
  done

  # --- Convergence: single-rule refresh ---
  local conv_single="${pdir}/convergence-single.csv"
  convergence::write_csv_header "${conv_single}"
  echo "==> Convergence: ${PHASE9_SINGLE_ROUNDS} single-rule refresh rounds"
  local single_summary
  single_summary=$(convergence::run_rounds RULE_REFRESHED "${PHASE9_SINGLE_ROUNDS}" \
                     "${RULE_IDS_CSV}" "${conv_single}" "${CONVERGENCE_DEADLINE_MS}") || true
  echo "  ${single_summary}"

  # --- Convergence: bulk refresh ---
  local conv_bulk="${pdir}/convergence-bulk.csv"
  convergence::write_csv_header "${conv_bulk}"
  echo "==> Convergence: ${PHASE9_BULK_ROUNDS} bulk refresh rounds"
  local bulk_summary
  bulk_summary=$(convergence::run_rounds RULE_REFRESHED_BULK "${PHASE9_BULK_ROUNDS}" \
                   "${RULE_IDS_CSV}" "${conv_bulk}" "${CONVERGENCE_DEADLINE_MS}") || true
  echo "  ${bulk_summary}"

  # --- Convergence under load ---
  local conv_load="${pdir}/convergence-under-load.csv"
  convergence::write_csv_header "${conv_load}"
  echo "==> Convergence under load: starting ${PHASE9_FULL_MODE_MIN}-min JMeter background then ${PHASE9_UNDER_LOAD_ROUNDS} convergence rounds"
  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"
  (
    local duration_s=$(( PHASE9_FULL_MODE_MIN * 60 ))
    local rps=100
    local threads=$(( rps / 10 )); (( threads < 5 )) && threads=5
    run_jmeter "${pdir}" "${rps}" "${duration_s}" "${threads}" "full-mode-load"
  ) &
  local jmeter_pid=$!
  # Stagger so JMeter ramps before we measure.
  sleep 15
  local load_summary
  load_summary=$(convergence::run_rounds RULE_REFRESHED "${PHASE9_UNDER_LOAD_ROUNDS}" \
                   "${RULE_IDS_CSV}" "${conv_load}" "${CONVERGENCE_DEADLINE_MS}") || true
  echo "  ${load_summary}"
  wait "${jmeter_pid}" 2>/dev/null || true
  memory_poll::stop "${MEMORY_POLL_PID}"

  # --- Self-dedup check on publisher ---
  echo "==> Verifying skipped_self counter on publisher (app-1)"
  local skipped_self
  skipped_self=$(actuator::counter_at "$(multi_stack::actuator_url 1)" "drools.refresh.skipped_self")
  skipped_self="${skipped_self:-0}"
  local skipped_int=$(( ${skipped_self%%.*} ))

  # Snapshot post.
  for i in 1 2 3; do
    actuator::snapshot_phase9_metrics \
      "$(multi_stack::actuator_url ${i})" \
      "${pdir}/metric-snapshot-app-${i}-post.json"
  done

  # --- JMeter result ---
  local stats err_pct
  stats=$(analyze_jtl "${pdir}/jtl/full-mode-load.jtl")
  echo "${stats}" > "${pdir}/stats.csv"
  err_pct=$(echo "${stats}" | cut -d',' -f5)

  # --- Aggregate convergence verdict ---
  # Parse `failures=N` from each run_rounds summary string. This captures rounds where
  # the refresh-POST itself failed (no CSV row written) — those would be invisible to
  # the per-row CSV awk and the phase would falsely report PASS on an empty CSV.
  local single_fails bulk_fails load_fails
  single_fails=$(echo "${single_summary}" | sed -n 's/.*failures=\([0-9][0-9]*\).*/\1/p')
  bulk_fails=$(echo   "${bulk_summary}"   | sed -n 's/.*failures=\([0-9][0-9]*\).*/\1/p')
  load_fails=$(echo   "${load_summary}"   | sed -n 's/.*failures=\([0-9][0-9]*\).*/\1/p')
  [[ -z "${single_fails}" ]] && single_fails=999
  [[ -z "${bulk_fails}"   ]] && bulk_fails=999
  [[ -z "${load_fails}"   ]] && load_fails=999
  local single_max bulk_max load_max
  single_max=$(awk -F, 'NR>1 && $7+0>m {m=$7+0} END {print m+0}' "${conv_single}")
  bulk_max=$(awk   -F, 'NR>1 && $7+0>m {m=$7+0} END {print m+0}' "${conv_bulk}")
  load_max=$(awk   -F, 'NR>1 && $7+0>m {m=$7+0} END {print m+0}' "${conv_load}")

  # Expected skipped_self lower bound: 1 self-event per published round (PHASE9_SINGLE_ROUNDS/3
  # of the singles + PHASE9_BULK_ROUNDS/3 of the bulks + PHASE9_UNDER_LOAD_ROUNDS/3 land on
  # app-1 as publisher). Tolerant lower bound is ≥1.
  local pass=true
  local -a reasons=()
  if (( single_fails > 0 )); then pass=false; reasons+=("${single_fails} single convergence failures (max delta ${single_max}ms)"); fi
  if (( bulk_fails > 0 ));   then pass=false; reasons+=("${bulk_fails} bulk convergence failures (max delta ${bulk_max}ms)"); fi
  if (( load_fails > 0 ));   then pass=false; reasons+=("${load_fails} under-load convergence failures (max delta ${load_max}ms)"); fi
  if [[ "${err_pct%.*}" != "0" ]] && [[ "${err_pct}" != "0.000" ]]; then
    pass=false; reasons+=("under-load error rate ${err_pct}% > 0")
  fi
  if (( skipped_int < 1 )); then
    pass=false; reasons+=("publisher's skipped_self counter is ${skipped_int}; pub/sub self-dedup did not run")
  fi

  echo "  single max=${single_max}ms fails=${single_fails} / ${PHASE9_SINGLE_ROUNDS}"
  echo "  bulk   max=${bulk_max}ms fails=${bulk_fails} / ${PHASE9_BULK_ROUNDS}"
  echo "  load   max=${load_max}ms fails=${load_fails} / ${PHASE9_UNDER_LOAD_ROUNDS}"
  echo "  publisher skipped_self=${skipped_int}; under-load err=${err_pct}%"

  if $pass; then
    {
      echo "PASS"
      echo "single_max_ms=${single_max}  fails=${single_fails}/${PHASE9_SINGLE_ROUNDS}"
      echo "bulk_max_ms=${bulk_max}      fails=${bulk_fails}/${PHASE9_BULK_ROUNDS}"
      echo "load_max_ms=${load_max}      fails=${load_fails}/${PHASE9_UNDER_LOAD_ROUNDS}"
      echo "skipped_self=${skipped_int}  err_pct=${err_pct}"
    } > "${pdir}/result.txt"
    set_phase_result "phase9.3" PASS
    echo "==> Phase 9.3 PASS"
  else
    {
      echo "FAIL"
      printf -- '- %s\n' "${reasons[@]}"
    } > "${pdir}/result.txt"
    set_phase_result "phase9.3" FAIL
    return 1
  fi
}

# Reads a per-replica circuit-breaker state via actuator. Echoes one of
# "open" / "closed" / "half_open" / "unknown". Top-level so bash 3.2 doesn't have
# to deal with nested-function scoping quirks.
#
# The Micrometer-registered Resilience4j CB name for the Redis breaker is "redis"
# (visible via `/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:redis`).
# Earlier versions queried "redisCircuitBreaker" (Spring bean name) and never matched.
phase9_4::cb_state() {
  local idx="$1"
  local url; url=$(multi_stack::actuator_url "${idx}")
  local s v
  for s in open closed half_open; do
    v=$(actuator::counter_at "${url}" "resilience4j.circuitbreaker.state" \
          "name:redis,state:${s}")
    if [[ -n "${v}" && "${v%%.*}" == "1" ]]; then
      printf '%s' "${s}"
      return 0
    fi
  done
  printf 'unknown'
}

# Phase 9.4 — failure mode: kill Redis mid-load, verify CB engages + recovery.
phase_9_4_failure_mode() {
  if ! should_run_phase9 "9.4"; then return 0; fi
  phase_banner "9.4" "Failure mode (docker kill drools-redis mid-load)"
  local pdir="${RUN_DIR}/phase-9-4"
  rm -rf "${pdir}"
  mkdir -p "${pdir}/jtl" "${pdir}/reports"

  phase9::ensure_corpus

  stack::down
  multi_stack::down
  multi_stack::up full
  if ! multi_stack::wait_for_health 240; then
    set_phase_result "phase9.4" FAIL
    echo "FAIL: stack did not become healthy" > "${pdir}/result.txt"
    return 1
  fi
  multi_stack::wait_for_pubsub 60

  corpus::clear_s3
  corpus::upload "${PHASE9_CORPUS_DIR}"
  curl -s --max-time 180 -X POST http://localhost:8080/admin/refresh-rules > "${pdir}/warm-refresh.json" || true
  sleep 10

  # Start JMeter in the background — full mode load.
  memory_poll::start "${MEMORY_CSV}" "${MEMORY_POLL_PID}"
  local duration_s=$(( PHASE9_FAILURE_MIN * 60 ))
  local rps=100
  local threads=$(( rps / 10 )); (( threads < 5 )) && threads=5
  (
    run_jmeter "${pdir}" "${rps}" "${duration_s}" "${threads}" "failure-mode"
  ) &
  local jmeter_pid=$!

  echo "  jmeter PID=${jmeter_pid}; killing Redis at T+${REDIS_KILL_AT_S}s"
  sleep "${REDIS_KILL_AT_S}"

  # CB state timeline. Header: epoch_s,replica,state
  local cb_csv="${pdir}/cb-state-timeline.csv"
  echo "epoch_s,replica,state" > "${cb_csv}"

  # Start background CB-exerciser. /execute-rule traffic doesn't touch Redis (kieContainer
  # is in-memory), so without admin refreshes the CB would never see any failure and never
  # open. Every PHASE9_4_EXERCISER_INTERVAL_S seconds, fire wget POST
  # /admin/refresh-rules/<id> on each replica — the decorator wraps the Redis
  # DEL in the redisCircuitBreaker; once slidingWindowSize calls accumulate
  # failures (Redis down), the CB opens.
  #
  # Cadence note (Phase 9.4 follow-up): with the prior 3s cadence the exerciser
  # generated ~20 CB ops in the 30s `CB_OPEN_DEADLINE_S` window — not enough to
  # flip the 50-call sliding window past the 60% failure-rate threshold, even
  # when every kill-window call failed. Real production traffic is much denser;
  # the 1s default here is closer to that pattern. Tune via env var if needed.
  local exerciser_interval_s="${PHASE9_4_EXERCISER_INTERVAL_S:-1}"
  local exerciser_rid
  exerciser_rid=$(awk -F, 'NR==2 {print $1; exit}' "${RULE_IDS_CSV}")
  local exerciser_pid_file="${pdir}/.cb-exerciser.pid"
  (
    while true; do
      local ei
      for ei in 1 2 3; do
        multi_stack::exec_admin_post "drools-app-${ei}" \
          "/admin/refresh-rules/${exerciser_rid}" > /dev/null 2>&1 || true
      done
      sleep "${exerciser_interval_s}"
    done
  ) &
  echo "$!" > "${exerciser_pid_file}"
  echo "  [info] CB-exerciser started (PID $(cat "${exerciser_pid_file}"), every ${exerciser_interval_s}s × 3 replicas)"

  # Pre-kill snapshot: baseline counter values for resilience4j.circuitbreaker.calls
  # (kind=successful/failed/not_permitted/ignored). Used post-hoc to diagnose whether
  # the CB sliding window saw enough failures during the kill window.
  echo "  [info] capturing pre-kill metric snapshots (3 replicas)"
  for i in 1 2 3; do
    actuator::snapshot_phase9_metrics \
      "$(multi_stack::actuator_url ${i})" \
      "${pdir}/metric-snapshot-app-${i}-pre-kill.json"
  done

  # Also capture per-tag CB call breakdown to a single CSV so the failure-rate
  # math (failed / (failed+successful)) is easy to compute post-hoc. The bulk
  # JSON snapshot above only returns aggregates across tag dimensions.
  local cb_calls_csv="${pdir}/cb-calls-per-replica.csv"
  echo "phase,replica,name,kind,count" > "${cb_calls_csv}"
  phase9_4::record_cb_calls() {
    local phase_label="${1}"
    for i in 1 2 3; do
      local url; url=$(multi_stack::actuator_url ${i})
      for kind in successful failed not_permitted ignored; do
        local c; c=$(actuator::counter_at "${url}" \
          "resilience4j.circuitbreaker.calls" "name:redis,kind:${kind}")
        echo "${phase_label},drools-app-${i},redis,${kind},${c:-0}" >> "${cb_calls_csv}"
      done
    done
  }
  phase9_4::record_cb_calls "pre-kill"

  local t_kill
  t_kill=$(date +%s)
  multi_stack::kill_redis

  # Poll CB until each replica reports `open` OR deadline expires.
  # bash 3.2 (macOS default) lacks associative arrays — use parallel indexed arrays.
  # Index 0/1/2 = replica 1/2/3. Empty string = not yet seen open.
  local -a t_open=("" "" "")
  local deadline_open=$(( t_kill + CB_OPEN_DEADLINE_S ))
  while true; do
    local now; now=$(date +%s)
    local all_open=true
    for i in 1 2 3; do
      local state; state=$(phase9_4::cb_state "${i}")
      echo "${now},drools-app-${i},${state}" >> "${cb_csv}"
      if [[ "${state}" != "open" ]]; then
        all_open=false
      elif [[ -z "${t_open[$((i-1))]}" ]]; then
        t_open[$((i-1))]=${now}
      fi
    done
    if $all_open; then
      echo "  [ok]   all 3 replicas CB=open after $((now - t_kill))s"
      break
    fi
    if (( now > deadline_open )); then
      echo "  [warn] CB-open deadline (${CB_OPEN_DEADLINE_S}s) exceeded; continuing test"
      break
    fi
    sleep 1
  done

  echo "  Redis down for ${REDIS_DOWN_DURATION_S}s; jmeter continues"
  local elapsed_down=$(( $(date +%s) - t_kill ))
  if (( elapsed_down < REDIS_DOWN_DURATION_S )); then
    sleep $(( REDIS_DOWN_DURATION_S - elapsed_down ))
  fi

  # Pre-restart snapshot: counters just before Redis comes back. Diff vs pre-kill =
  # what the CB observed during the entire kill window. Critical for diagnosing
  # why the breaker did/didn't trip (Phase 9.4 CB-engagement sub-criterion).
  echo "  [info] capturing pre-restart metric snapshots (3 replicas)"
  for i in 1 2 3; do
    actuator::snapshot_phase9_metrics \
      "$(multi_stack::actuator_url ${i})" \
      "${pdir}/metric-snapshot-app-${i}-pre-restart.json"
  done
  phase9_4::record_cb_calls "pre-restart"

  local t_restart
  t_restart=$(date +%s)
  multi_stack::start_redis

  # Poll CB until each replica reports `closed` OR deadline expires.
  local -a t_close=("" "" "")
  local deadline_close=$(( t_restart + CB_CLOSE_DEADLINE_S ))
  while true; do
    local now; now=$(date +%s)
    local all_closed=true
    for i in 1 2 3; do
      local state; state=$(phase9_4::cb_state "${i}")
      echo "${now},drools-app-${i},${state}" >> "${cb_csv}"
      if [[ "${state}" != "closed" ]]; then
        all_closed=false
      elif [[ -z "${t_close[$((i-1))]}" ]]; then
        t_close[$((i-1))]=${now}
      fi
    done
    if $all_closed; then
      echo "  [ok]   all 3 replicas CB=closed after $((now - t_restart))s"
      break
    fi
    if (( now > deadline_close )); then
      echo "  [warn] CB-close deadline (${CB_CLOSE_DEADLINE_S}s) exceeded; continuing test"
      break
    fi
    sleep 2
  done

  # Post-restart snapshot: counters after CB-close (or after the deadline). Diff vs
  # pre-restart = recovery activity. Together with pre-kill + pre-restart, this gives
  # a full pre/mid/post forensic timeline of CB calls per replica.
  echo "  [info] capturing post-restart metric snapshots (3 replicas)"
  for i in 1 2 3; do
    actuator::snapshot_phase9_metrics \
      "$(multi_stack::actuator_url ${i})" \
      "${pdir}/metric-snapshot-app-${i}-post-restart.json"
  done
  phase9_4::record_cb_calls "post-restart"

  # Stop the CB-exerciser before the convergence-recovery round; otherwise its concurrent
  # refresh fire could confuse the per-rule counter baseline used by convergence::measure_single.
  if [[ -f "${exerciser_pid_file}" ]]; then
    local epid; epid=$(cat "${exerciser_pid_file}")
    if [[ -n "${epid}" ]] && kill -0 "${epid}" 2>/dev/null; then
      kill "${epid}" 2>/dev/null || true
      wait "${epid}" 2>/dev/null || true
    fi
    rm -f "${exerciser_pid_file}"
    echo "  [info] CB-exerciser stopped"
  fi

  # Settle before the post-restart convergence round so the
  # RedisMessageListenerContainer's 2s FixedBackOff retry cycle has time to
  # complete re-subscription. Without this, the publish lands before the
  # listener is back on the channel and the event is lost (pub/sub is
  # fire-and-forget). Configurable via PHASE9_4_RECOVERY_SETTLE_S.
  echo "  [info] sleeping ${PHASE9_4_RECOVERY_SETTLE_S}s for RedisMessageListenerContainer to re-subscribe (2s FixedBackOff + safety margin)"
  sleep "${PHASE9_4_RECOVERY_SETTLE_S}"

  # Post-restart convergence round: proves pub/sub re-subscribed.
  local conv_recover="${pdir}/convergence-recovery.csv"
  convergence::write_csv_header "${conv_recover}"
  echo "==> Post-restart convergence round (proves RedisMessageListenerContainer re-subscribed)"
  local urls=(
    "$(multi_stack::actuator_url 1)"
    "$(multi_stack::actuator_url 2)"
    "$(multi_stack::actuator_url 3)"
  )
  local rid; rid=$(awk -F, 'NR==2 {print $1; exit}' "${RULE_IDS_CSV}")
  local recovery_pass=true
  if ! convergence::measure_single RULE_REFRESHED "${urls[0]}" \
        "${urls[1]} ${urls[2]}" "${rid}" "${conv_recover}" "${CONVERGENCE_DEADLINE_MS}"; then
    recovery_pass=false
  fi
  local recovery_delta
  recovery_delta=$(awk -F, 'NR>1 && $7+0>m {m=$7+0} END {print m+0}' "${conv_recover}")

  # Wait for JMeter to finish.
  wait "${jmeter_pid}" 2>/dev/null || true
  memory_poll::stop "${MEMORY_POLL_PID}"

  # Events CSV: condensed timeline.
  local events_csv="${pdir}/events.csv"
  {
    echo "event,epoch_s"
    echo "redis_killed,${t_kill}"
    echo "redis_restarted,${t_restart}"
    local k
    for k in 1 2 3; do
      echo "cb_open_app-${k},${t_open[$((k-1))]:-}"
      echo "cb_closed_app-${k},${t_close[$((k-1))]:-}"
    done
    echo "convergence_recovery_delta_ms,${recovery_delta}"
  } > "${events_csv}"

  # Capture app-container logs while the stack is still up. Critical for diagnosing
  # whether Redis calls during the kill window were genuinely failing inside the app
  # or being silently swallowed (e.g., Lettuce queueing them across reconnect).
  # Filter to Redis/CB-relevant lines to keep file size manageable.
  echo "  [info] capturing per-replica app logs (filtered to Redis/CB-relevant lines)"
  for i in 1 2 3; do
    docker logs "drools-app-${i}" 2>&1 \
      | grep -iE 'redis|circuit|lettuce|cachedrulestorage|refresh|connect|timeout|exception' \
      > "${pdir}/app-${i}-redis-relevant.log" 2>/dev/null || true
  done

  # Assertions.
  local stats err_pct
  stats=$(analyze_jtl "${pdir}/jtl/failure-mode.jtl")
  echo "${stats}" > "${pdir}/stats.csv"
  err_pct=$(echo "${stats}" | cut -d',' -f5)

  local pass=true
  local -a reasons=()
  local i
  for i in 1 2 3; do
    if [[ -z "${t_open[$((i-1))]:-}" ]]; then
      pass=false; reasons+=("CB did not open on app-${i} within ${CB_OPEN_DEADLINE_S}s")
    fi
    if [[ -z "${t_close[$((i-1))]:-}" ]]; then
      pass=false; reasons+=("CB did not close on app-${i} within ${CB_CLOSE_DEADLINE_S}s")
    fi
  done
  if [[ "${err_pct%.*}" != "0" ]] && [[ "${err_pct}" != "0.000" ]]; then
    pass=false; reasons+=("JMeter error rate ${err_pct}% > 0 across full window")
  fi
  if ! $recovery_pass; then
    pass=false; reasons+=("post-restart convergence round failed (delta=${recovery_delta}ms); pub/sub did not re-subscribe")
  fi

  echo "  err=${err_pct}%; recovery_delta=${recovery_delta}ms"
  if $pass; then
    echo "PASS err=${err_pct} recovery_delta_ms=${recovery_delta}" > "${pdir}/result.txt"
    set_phase_result "phase9.4" PASS
    echo "==> Phase 9.4 PASS"
  else
    {
      echo "FAIL err=${err_pct} recovery_delta_ms=${recovery_delta}"
      printf -- '- %s\n' "${reasons[@]}"
    } > "${pdir}/result.txt"
    set_phase_result "phase9.4" FAIL
    return 1
  fi
}

# Phase 9 summary — writes ${RUN_DIR}/phase-9-summary.md with PASS/FAIL verdict and
# per-sub-test headlines. Always runs if any phase9.x function ran.
phase9_summary() {
  # Skip if no Phase 9 sub-test ran (avoid emitting a misleading empty summary).
  local any=false
  local key
  for key in "phase9.1" "phase9.2" "phase9.3" "phase9.4"; do
    if [[ -n "$(get_phase_result "${key}")" ]]; then any=true; break; fi
  done
  $any || return 0

  local summary="${RUN_DIR}/phase-9-summary.md"
  phase_banner "9-summary" "Phase 9 summary"

  {
    echo "# Phase 9 — Redis cache + pub/sub load test — ${RUN_TS}"
    echo
    echo "## Sub-test verdicts"
    echo
    echo "| Sub-test | Result | Detail |"
    echo "|---|---|---|"
    local key label
    for key in "phase9.1" "phase9.2" "phase9.3" "phase9.4"; do
      label="${key#phase}"
      local res; res=$(get_phase_result "${key}")
      [[ -z "${res}" ]] && res="SKIPPED"
      local rfile="${RUN_DIR}/phase-9-${label//./-}/result.txt"
      local detail=""
      if [[ -f "${rfile}" ]]; then
        detail=$(head -n 1 "${rfile}" | tr -d '\n')
      fi
      echo "| ${label} | ${res} | ${detail} |"
    done
    echo
    echo "## Verdict"
    local fails=0
    for key in "phase9.1" "phase9.2" "phase9.3" "phase9.4"; do
      [[ "$(get_phase_result "${key}")" == "FAIL" ]] && fails=$((fails + 1))
    done
    if [[ ${fails} -eq 0 ]]; then
      echo "**Result: PASS** — Redis cache decorator + pub/sub fan-out validated end-to-end."
    else
      echo "**Result: FAIL** — ${fails} sub-test(s) failed. See per-phase result.txt files."
    fi
    echo
    echo "## Artifacts"
    echo
    local label dir
    for key in "phase9.1" "phase9.2" "phase9.3" "phase9.4"; do
      label="${key#phase}"
      dir="phase-9-${label//./-}"
      if [[ -d "${RUN_DIR}/${dir}" ]]; then
        echo "- \`${dir}/\` — jtl/, reports/, stats.csv, result.txt"
        if compgen -G "${RUN_DIR}/${dir}/convergence-*.csv" > /dev/null; then
          echo "  - convergence CSVs: \`$(cd "${RUN_DIR}/${dir}" && ls convergence-*.csv | tr '\n' ' ')\`"
        fi
      fi
    done
    echo
    echo "After review, append a Phase 9 addendum to \`project-documentation/39-load-test-findings.md\` and tick the Phase 9 row in \`.ai-workspace/project-plans/redis-cache-layering-checklist.md\`."
  } > "${summary}"

  echo "==> Phase 9 summary → ${summary}"
  cat "${summary}"
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
echo "==> Load test run starting; output dir: ${RUN_DIR}"
$QUICK_MODE && echo "==> --quick mode: ${RULE_COUNT} rules, ${BASELINE_MIN} min baseline, ${SOAK_MIN} min soak"

# Flag preflight that Phase 9 multi-container mode (9.2/9.3/9.4) will run, so it also
# checks ports 18081/28081/38081.
if should_run_phase9 "9.2" || should_run_phase9 "9.3" || should_run_phase9 "9.4"; then
  export MULTI_NODE=true
fi

phase0 || exit 1
# Phases 2..8 only run when no Phase 9 sub-test is selected (Phase 9 has its own stack
# lifecycle and would interfere with phase 2's stack::up).
if ! should_run_phase9 "9.1" && ! should_run_phase9 "9.2" && \
   ! should_run_phase9 "9.3" && ! should_run_phase9 "9.4"; then
  phase2 || exit 1
  phase3 || true   # individual phase failures don't block the rest; final verdict aggregates
  phase4 || true
  phase5 || true
  phase6 || true
  phase7 || true
  phase8 || true
fi

phase_9_1_baseline    || true
phase_9_2_cache_only  || true
phase_9_3_full_mode   || true
phase_9_4_failure_mode || true
phase9_summary        || true
