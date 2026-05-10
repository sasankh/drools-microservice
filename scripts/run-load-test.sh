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
  --phase N         Run only phase N (2..8). Skips preflight if stack is up.
  --from N          Resume from phase N (skip earlier phases).
  --quick           Scale-down for smoke test: 100 rules, 5-min soak,
                    1-min baseline. Useful for verifying the harness itself.
  --no-cleanup      Leave the stack up after exit (skip docker compose down).
  -h, --help        Show this help.

Phases:
  0  Pre-flight checks (always runs unless --from > 0)
  2  Corpus generation + stack boot + first-refresh baseline
  3  Baseline (steady state, 50 RPS x 30 min)
  4  Concurrency ramp (50 -> 100 -> 250 -> 500 RPS)
  5  Hot full-refresh under load (180 refreshes / 30 min)
  6  Hot single-rule refresh under load (360 refreshes / 30 min)
  7  1-hour soak with mixed workload + heap dumps at start/end
  8  Heap analysis + verdict (writes summary.md)

  (Phase 1 = Tier 1 sample-rules expansion, source-code change, not orchestrated.)

Env-var knobs:
  RULE_COUNT          1000 by default
  SOAK_MIN            60 minutes by default
  BASELINE_MIN        30 minutes by default
  BASELINE_RPS        50 by default
  RAMP_STEPS          "50,100,250,500" by default
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
  ./scripts/run-load-test.sh                    # Full run (~6h, much unattended)
  ./scripts/run-load-test.sh --quick            # Smoke test (~10 min)
  ./scripts/run-load-test.sh --phase 3          # Just baseline
  ./scripts/run-load-test.sh --from 5           # Resume from hot-refresh
  RULE_COUNT=5000 SOAK_MIN=240 ./scripts/run-load-test.sh
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
fi

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
if [[ -n "${PHASE_FILTER}" && "${PHASE_FILTER}" -ge "${resume_threshold}" ]]; then
  should_resume=true
fi
if [[ -n "${FROM_PHASE}" && "${FROM_PHASE}" -ge "${resume_threshold}" ]]; then
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
  if [[ -n "${FROM_PHASE}" && ${n} -lt ${FROM_PHASE} ]]; then
    return 1
  fi
  return 0
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

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
echo "==> Load test run starting; output dir: ${RUN_DIR}"
$QUICK_MODE && echo "==> --quick mode: ${RULE_COUNT} rules, ${BASELINE_MIN} min baseline, ${SOAK_MIN} min soak"

phase0 || exit 1
phase2 || exit 1
phase3 || true   # individual phase failures don't block the rest; final verdict aggregates
phase4 || true
phase5 || true
phase6 || true
phase7 || true
phase8 || true
