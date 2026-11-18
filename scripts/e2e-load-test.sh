#!/usr/bin/env bash
# =============================================================================
# e2e-load-test.sh — Full E2E + load test for Drools Rule Engine (one command)
#
# Runs from scratch:
#   1. Start Docker stack (LocalStack + Redis + App), rate limiting disabled via
#      the scripts/docker-compose.loadtest.yml overlay (no tracked file is edited)
#   2. Verify the 17 sample rules are in LocalStack S3 (uploaded by the
#      LocalStack ready hook — no host AWS CLI needed)
#   3. Load rules into the engine
#   4. Smoke-test rules with VALUE checks (rules are isolated per rule_id — ADR-025)
#   5. Hot reload test
#   6. Memory stability check (5 rapid reloads + GC verify)
#   7. Load test: keep-alive Python harness (scripts/lib/e2e_load.py), N workers,
#      hot reload at 2 min, every response's values validated
#   8. Tear down
#
# Usage:
#   ./scripts/e2e-load-test.sh                    # full test (300 s load) + tear down
#   ./scripts/e2e-load-test.sh --duration 60      # shorter load phase
#   ./scripts/e2e-load-test.sh --workers 50       # more concurrency
#   ./scripts/e2e-load-test.sh --no-teardown      # keep stack running after test
#   ./scripts/e2e-load-test.sh --skip-build       # skip docker compose build
#
# Requirements: docker (Engine + Compose v2), curl, python3.
# Linux note: if Podman is also installed and DOCKER_HOST points at it, run with
#   DOCKER_HOST=unix:///var/run/docker.sock ./scripts/e2e-load-test.sh
#
# Exit code: number of failed checks (0 = all passed). CI-friendly.
#
# History: until 2026-11-17 the load step used bash/curl workers that opened one
# TCP connection per request; on Linux they exhausted ephemeral ports in ~30 s and
# died silently under `set -e`, so the script reported PASS for a stalled test.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yml" -f "$ROOT_DIR/scripts/docker-compose.loadtest.yml")
ADMIN_KEY="${ADMIN_API_KEY:-admin-secret}"
APP_URL="http://localhost:8080"
EXPECTED_RULES=17
TEARDOWN=true
SKIP_BUILD=false
LOAD_DURATION=300
LOAD_WORKERS=20
MIN_RPS=200

while [ $# -gt 0 ]; do
  case "$1" in
    --no-teardown) TEARDOWN=false ;;
    --skip-build)  SKIP_BUILD=true ;;
    --duration)    LOAD_DURATION="${2:?--duration needs seconds}"; shift ;;
    --workers)     LOAD_WORKERS="${2:?--workers needs a number}"; shift ;;
    --min-rps)     MIN_RPS="${2:?--min-rps needs a number}"; shift ;;
    -h|--help)     sed -n '2,33p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

pass() { echo -e "${GREEN}  ✓ $*${RESET}"; }
fail() { echo -e "${RED}  ✗ $*${RESET}"; FAILURES=$((FAILURES + 1)); }
info() { echo -e "${CYAN}  → $*${RESET}"; }
step() { echo -e "\n${BOLD}${YELLOW}══ $* ══${RESET}"; }
banner() { echo -e "\n${BOLD}${CYAN}$*${RESET}"; }

FAILURES=0
STACK_STARTED=false
LOAD_JSON=""

for tool in docker curl python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool" >&2; exit 2; }
done
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 ('docker compose') is required" >&2; exit 2; }

# ── Cleanup on exit ───────────────────────────────────────────────────────────
cleanup() {
  if [ "$TEARDOWN" = true ] && [ "$STACK_STARTED" = true ]; then
    step "Step 8 — Tear down"
    info "Stopping Docker stack..."
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    pass "Stack stopped"
  elif [ "$TEARDOWN" = false ]; then
    info "Stack left running (--no-teardown). Stop with: docker compose -f docker-compose.yml -f scripts/docker-compose.loadtest.yml down -v"
  fi
}
trap cleanup EXIT

# ── Helpers ───────────────────────────────────────────────────────────────────
# JSON field extraction without jq: json_get '<json>' 'a.b.c'  (prints "" on any error)
json_get() {
  python3 -c '
import json, sys
try:
    v = json.loads(sys.argv[1])
    for k in sys.argv[2].split("."):
        v = v[int(k)] if isinstance(v, list) else v[k]
    print("" if v is None else (json.dumps(v) if isinstance(v, (dict, list)) else v))
except Exception:
    print("")' "$1" "$2"
}

admin() {  # admin METHOD PATH
  curl -s -X "$1" "$APP_URL$2" -H "X-Admin-API-Key: $ADMIN_KEY" 2>/dev/null || true
}

app_container() { "${COMPOSE[@]}" ps -q app 2>/dev/null || true; }

wait_for_app_healthy() {
  local max="${1:-60}" status=""
  for i in $(seq 1 "$max"); do
    status=$(docker inspect --format='{{.State.Health.Status}}' "$(app_container)" 2>/dev/null || echo "starting")
    if [ "$status" = "healthy" ]; then pass "App is healthy (~$((i * 5))s)"; return 0; fi
    printf "    waiting for app... %d/%d (%s)\r" "$i" "$max" "$status"
    sleep 5
  done
  echo ""
  fail "App did not become healthy after $((max * 5))s (status=$status)"
  "${COMPOSE[@]}" logs --tail=30 app 2>&1 | sed 's/^/    /'
  return 1
}

mem_info() {
  local j; j=$(admin GET /admin/memory/info)
  echo "$(json_get "$j" heap.usedMB)MB / $(json_get "$j" heap.maxMB)MB ($(json_get "$j" heap.usagePercent)%)"
}

# ── Start ─────────────────────────────────────────────────────────────────────
cd "$ROOT_DIR"

banner "╔══════════════════════════════════════════════════╗"
banner "║   Drools Rule Engine — E2E + Load Test           ║"
banner "╚══════════════════════════════════════════════════╝"
echo "  Root:     $ROOT_DIR"
echo "  Teardown: $TEARDOWN  |  Skip build: $SKIP_BUILD"
echo "  Load:     ${LOAD_WORKERS} workers × ${LOAD_DURATION}s (min avg RPS ${MIN_RPS})"
echo "  Docker:   ${DOCKER_HOST:-default context}"
echo "  Started:  $(date)"

# ── Step 1: Start stack ───────────────────────────────────────────────────────
step "Step 1 — Start Docker stack (rate limiting disabled via loadtest overlay)"

if [ "$SKIP_BUILD" = false ]; then
  info "Building app image..."
  "${COMPOSE[@]}" build app 2>&1 | tail -3
fi

info "Starting stack (LocalStack + Redis + App)..."
"${COMPOSE[@]}" up -d 2>&1 | grep -E "Started|Healthy|Error" || true
STACK_STARTED=true

wait_for_app_healthy 60 || exit "$FAILURES"

RL_ENV=$(docker exec "$(app_container)" printenv DROOLS_RATE_LIMITING_ENABLED 2>/dev/null || echo "?")
if [ "$RL_ENV" = "false" ]; then pass "Rate limiting disabled for the load test (overlay)"; else fail "Rate limiting not disabled (DROOLS_RATE_LIMITING_ENABLED=$RL_ENV)"; fi

# ── Step 2: Sample rules in S3 ────────────────────────────────────────────────
step "Step 2 — Verify $EXPECTED_RULES sample rules in LocalStack S3"

RULE_COUNT=$("${COMPOSE[@]}" exec -T localstack awslocal s3 ls s3://local-rules/ --recursive 2>/dev/null | grep -c '\.drl$' || true)
if [ "$RULE_COUNT" = "$EXPECTED_RULES" ]; then
  pass "$EXPECTED_RULES .drl rules in s3://local-rules (uploaded by the LocalStack ready hook)"
else
  fail "Expected $EXPECTED_RULES .drl rules in S3, found ${RULE_COUNT:-0}"
fi

# ── Step 3: Load rules into engine ────────────────────────────────────────────
step "Step 3 — Load rules into engine"

REFRESH=$(admin POST /admin/refresh-rules)
LOADED=$(json_get "$REFRESH" rules_loaded); FAILED=$(json_get "$REFRESH" rules_failed); DURATION_MS=$(json_get "$REFRESH" duration_ms)
if [ "$LOADED" = "$EXPECTED_RULES" ] && [ "$FAILED" = "0" ]; then
  pass "$EXPECTED_RULES rules loaded in ${DURATION_MS}ms, 0 failures"
else
  fail "Rule load: loaded=${LOADED:-?} failed=${FAILED:-?}"
fi
info "Heap after load: $(mem_info)"

# ── Step 4: Smoke tests (value checks) ────────────────────────────────────────
step "Step 4 — Smoke test rules (values checked; each rule_id runs only its own rules)"

# smoke_test LABEL PAYLOAD PYTHON_PREDICATE_ON_r
smoke_test() {
  local label="$1" payload="$2" pred="$3" resp http
  resp=$(curl -s -w '\n%{http_code}' -X POST "$APP_URL/execute-rule" -H "Content-Type: application/json" -d "$payload" 2>/dev/null || echo $'\n000')
  http="${resp##*$'\n'}"; resp="${resp%$'\n'*}"
  if [ "$http" = "200" ] && python3 -c '
import json, sys
j = json.loads(sys.argv[1]); r = j["result"]
near = lambda a, b: abs(a - b) < 0.005
sys.exit(0 if j.get("error") is None and ('"$pred"') else 1)' "$resp" 2>/dev/null; then
    pass "$label"
  else
    fail "$label — HTTP=$http body=$(echo "$resp" | head -c 200)"
  fi
}

smoke_test "simple: \$150 → \$135"        '{"rule_id":"pricing.discount.simple","data":{"amount":150}}'                         'near(r["amount"], 135.0)'
smoke_test "vip: \$100 → \$80 (isolated)" '{"rule_id":"pricing.discount.vip","data":{"customerType":"VIP","amount":100}}'       'near(r["amount"], 80.0)'
smoke_test "bulk: 15 × \$200 → \$170"      '{"rule_id":"pricing.discount.bulk","data":{"quantity":15,"amount":200}}'             'near(r["amount"], 170.0)'
smoke_test "age: 15 → REJECTED"            '{"rule_id":"validation.customer.age","data":{"customerAge":15}}'                     'r["validationResult"] == "REJECTED"'
smoke_test "blackfriday: \$200 → \$150"    '{"rule_id":"seasonal.holiday.blackfriday","data":{"promotionCode":"BLACK2024","amount":200}}' 'near(r["amount"], 150.0) and r["promotionValid"] is True'

# ── Step 5: Hot reload ────────────────────────────────────────────────────────
step "Step 5 — Hot reload test"

RELOAD=$(admin POST /admin/refresh-rules)
RL=$(json_get "$RELOAD" rules_loaded); RF=$(json_get "$RELOAD" rules_failed); RMS=$(json_get "$RELOAD" duration_ms)
if [ "$RL" = "$EXPECTED_RULES" ] && [ "$RF" = "0" ]; then pass "Reload: $RL rules in ${RMS}ms, 0 failures"; else fail "Reload: loaded=${RL:-?} failed=${RF:-?}"; fi
smoke_test "execution correct right after reload" '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' 'near(r["amount"], 90.0)'

# ── Step 6: Memory stability ──────────────────────────────────────────────────
step "Step 6 — Memory stability (5 rapid reloads + GC verify)"

# Raw heap readings between reloads are a GC-timing sawtooth, not a leak signal. Compare RETAINED
# heap (after an explicit GC) before and after the reloads instead.
post_gc_heap() { admin POST /admin/memory/gc >/dev/null; sleep 4; local u; u=$(json_get "$(admin GET /admin/memory/info)" heap.usedMB); echo "${u:-999}"; }
BASE_GC=$(post_gc_heap)
info "Retained heap before reloads (post-GC): ${BASE_GC}MB"
for i in 1 2 3 4 5; do
  admin POST /admin/refresh-rules >/dev/null
  sleep 2
  USED=$(json_get "$(admin GET /admin/memory/info)" heap.usedMB)
  info "Reload $i: heap=${USED:-?}MB (raw, includes garbage)"
done
POST_GC=$(post_gc_heap)
if [ "$POST_GC" -le $((BASE_GC + 50)) ] 2>/dev/null && [ "$POST_GC" -lt 150 ] 2>/dev/null; then
  pass "Retained heap after 5 reloads: ${POST_GC}MB (was ${BASE_GC}MB; growth ≤ 50MB, < 150MB — no leak)"
else
  fail "Possible memory leak — retained (post-GC) heap ${BASE_GC}MB → ${POST_GC}MB after 5 reloads"
fi

# ── Step 7: Load test ─────────────────────────────────────────────────────────
step "Step 7 — Load test (${LOAD_WORKERS} workers × ${LOAD_DURATION}s, hot reload at 120s, values validated)"

RELOAD_AT=120
[ "$LOAD_DURATION" -le "$RELOAD_AT" ] && RELOAD_AT=$((LOAD_DURATION / 2))
set +e
LOAD_JSON=$(python3 "$SCRIPT_DIR/lib/e2e_load.py" --workers "$LOAD_WORKERS" --duration "$LOAD_DURATION" \
  --reload-at "$RELOAD_AT" --min-rps "$MIN_RPS" --expect-rules "$EXPECTED_RULES" \
  --admin-key "$ADMIN_KEY" --label e2e-load)
LOAD_RC=$?
set -e
echo "  $LOAD_JSON"
if [ "$LOAD_RC" -eq 0 ]; then
  pass "Load test PASSED (0 errors, 0 wrong values, no stalls, rules intact)"
else
  fail "Load test FAILED: $(json_get "$LOAD_JSON" failures)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
banner "╔══════════════════════════════════════════════════╗"
banner "║              TEST SUMMARY                        ║"
banner "╚══════════════════════════════════════════════════╝"
echo ""
echo -e "  ${BOLD}Load test results${RESET}"
echo    "  ─────────────────────────────"
echo    "  Duration     : $(json_get "$LOAD_JSON" duration_s)s  (${LOAD_WORKERS} workers)"
echo    "  Total req    : $(json_get "$LOAD_JSON" requests)"
echo    "  Avg RPS      : ~$(json_get "$LOAD_JSON" rps)"
echo    "  HTTP status  : $(json_get "$LOAD_JSON" http_status)"
echo    "  Wrong values : $(json_get "$LOAD_JSON" wrong_results)"
echo    "  Client errors: $(json_get "$LOAD_JSON" client_exceptions)"
echo    "  Latency (ms) : $(json_get "$LOAD_JSON" latency_ms)"
echo    "  Min 10s win  : $(json_get "$LOAD_JSON" min_10s_window) requests"
echo    "  Hot reload   : $(json_get "$LOAD_JSON" reloads)"
echo    "  Rules loaded : $(json_get "$LOAD_JSON" rules_loaded_after) / $EXPECTED_RULES"
echo    "  Final heap   : $(mem_info)"
echo ""

if [ "$FAILURES" -eq 0 ]; then
  echo -e "  ${GREEN}${BOLD}ALL CHECKS PASSED${RESET}"
else
  echo -e "  ${RED}${BOLD}$FAILURES CHECK(S) FAILED — review output above${RESET}"
fi
echo ""
echo "  Finished: $(date)"
echo ""

exit "$FAILURES"
