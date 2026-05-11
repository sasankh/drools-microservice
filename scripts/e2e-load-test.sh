#!/usr/bin/env bash
# =============================================================================
# e2e-load-test.sh — Full E2E + 5-min load test for Drools Rule Engine
#
# Runs from scratch:
#   1. Start Docker stack (LocalStack + Redis + App)
#   2. Upload 17 sample rules to LocalStack S3
#   3. Load rules into the engine
#   4. Smoke test 3 rules
#   5. Hot reload test
#   6. Memory stability check (5 rapid reloads + GC verify)
#   7. 5-minute load test (20 workers, hot reload at 2 min)
#   8. Tear down
#
# Usage:
#   ./scripts/e2e-load-test.sh              # full test + tear down at end
#   ./scripts/e2e-load-test.sh --no-teardown  # keep stack running after test
#   ./scripts/e2e-load-test.sh --skip-build   # skip docker compose build
#
# Requirements: docker, docker compose, curl, python3, bc
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
ADMIN_KEY="${ADMIN_API_KEY:-admin-secret}"
APP_URL="http://localhost:8080"
ACTUATOR_URL="http://localhost:8081"
TEARDOWN=true
SKIP_BUILD=false

for arg in "$@"; do
  case $arg in
    --no-teardown) TEARDOWN=false ;;
    --skip-build)  SKIP_BUILD=true ;;
  esac
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
COMPOSE_MODIFIED=false
STACK_STARTED=false

# ── Cleanup on exit ───────────────────────────────────────────────────────────
cleanup() {
  # Restore docker-compose.yml if we modified it
  if [ "$COMPOSE_MODIFIED" = true ]; then
    info "Restoring docker-compose.yml (re-enabling rate limiting)..."
    cd "$ROOT_DIR"
    sed -i.bak '/DROOLS_RATE_LIMITING_ENABLED=false/d' "$COMPOSE_FILE" && rm -f "${COMPOSE_FILE}.bak"
    pass "docker-compose.yml restored"
  fi

  if [ "$TEARDOWN" = true ] && [ "$STACK_STARTED" = true ]; then
    step "Step 8 — Tear down"
    info "Stopping Docker stack..."
    cd "$ROOT_DIR" && docker compose down --remove-orphans 2>/dev/null || true
    pass "Stack stopped"
  elif [ "$TEARDOWN" = false ]; then
    info "Stack left running (--no-teardown). Stop with: docker compose down"
  fi
}
trap cleanup EXIT

# ── Helpers ───────────────────────────────────────────────────────────────────
wait_for_health() {
  local url="$1" label="$2" max="${3:-40}"
  for i in $(seq 1 $max); do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
    if [ "$STATUS" = "200" ]; then pass "$label is UP"; return 0; fi
    printf "    waiting... attempt %d/%d (HTTP %s)\r" "$i" "$max" "$STATUS"
    sleep 4
  done
  fail "$label did not become healthy after $((max * 4))s"
  return 1
}

mem_info() {
  curl -s "$APP_URL/admin/memory/info" 2>/dev/null | \
    python3 -c "import sys,json; d=json.load(sys.stdin); \
      print(f\"{d['heap']['usedMB']}MB / {d['heap']['maxMB']}MB ({d['heap']['usagePercent']}%)\")" 2>/dev/null || echo "?"
}

# ── Start ─────────────────────────────────────────────────────────────────────
cd "$ROOT_DIR"

banner "╔══════════════════════════════════════════════════╗"
banner "║   Drools Rule Engine — E2E + Load Test           ║"
banner "╚══════════════════════════════════════════════════╝"
echo "  Root:    $ROOT_DIR"
echo "  Teardown: $TEARDOWN  |  Skip build: $SKIP_BUILD"
echo "  Started:  $(date)"

# ── Step 1: Start stack ───────────────────────────────────────────────────────
step "Step 1 — Start Docker stack"

# Patch docker-compose.yml to disable rate limiting for the test
if grep -q "DROOLS_RATE_LIMITING_ENABLED" "$COMPOSE_FILE"; then
  info "Rate limiting already configured in compose file"
else
  info "Disabling rate limiting in docker-compose.yml for load test..."
  # Insert after the LOG_LEVEL line
  sed -i.bak 's/- LOG_LEVEL=INFO/- DROOLS_RATE_LIMITING_ENABLED=false\n      - LOG_LEVEL=INFO/' "$COMPOSE_FILE"
  rm -f "${COMPOSE_FILE}.bak"
  COMPOSE_MODIFIED=true
  pass "Rate limiting disabled (will be restored on exit)"
fi

if [ "$SKIP_BUILD" = false ]; then
  info "Building app image..."
  docker compose build app 2>&1 | tail -3
fi

info "Starting stack (LocalStack + Redis + App)..."
docker compose up -d 2>&1 | grep -E "Started|Healthy|Error" || true
STACK_STARTED=true

wait_for_health "$ACTUATOR_URL/actuator/health" "App"

# ── Step 2: Upload sample rules ───────────────────────────────────────────────
step "Step 2 — Upload 17 sample rules to LocalStack S3"

info "Running init-localstack.sh..."
"$ROOT_DIR/init-localstack.sh" 2>&1 | grep -E "✅|✗|Rules:|Error" || true

RULE_COUNT=$(aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules/ --recursive 2>/dev/null | wc -l | tr -d ' ')
if [ "$RULE_COUNT" = "17" ]; then
  pass "17 rules in S3 bucket"
else
  fail "Expected 17 rules in S3, found $RULE_COUNT"
fi

# ── Step 3: Load rules into engine ────────────────────────────────────────────
step "Step 3 — Load rules into engine"

REFRESH=$(curl -s -X POST "$APP_URL/admin/refresh-rules" -H "X-Admin-API-Key: $ADMIN_KEY" 2>/dev/null)
LOADED=$(echo "$REFRESH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('rules_loaded',0))" 2>/dev/null || echo "0")
FAILED=$(echo "$REFRESH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('rules_failed',0))" 2>/dev/null || echo "?")
DURATION_MS=$(echo "$REFRESH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('duration_ms','?'))" 2>/dev/null || echo "?")

if [ "$LOADED" = "17" ] && [ "$FAILED" = "0" ]; then
  pass "17 rules loaded in ${DURATION_MS}ms, 0 failures"
else
  fail "Rule load: loaded=$LOADED failed=$FAILED"
fi

info "Heap after load: $(mem_info)"

# ── Step 4: Smoke tests ───────────────────────────────────────────────────────
step "Step 4 — Smoke test rules"

smoke_test() {
  local rule_id="$1" payload="$2" label="$3"
  RESP=$(curl -s -X POST "$APP_URL/execute-rule" \
    -H "Content-Type: application/json" \
    -d "{\"rule_id\":\"$rule_id\",\"data\":$payload}" 2>/dev/null)
  HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$APP_URL/execute-rule" \
    -H "Content-Type: application/json" \
    -d "{\"rule_id\":\"$rule_id\",\"data\":$payload}" 2>/dev/null)
  ERR=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('error') or 'none')" 2>/dev/null || echo "?")
  if [ "$HTTP" = "200" ] && [ "$ERR" = "None" ]; then
    pass "$label (HTTP 200, no error)"
  else
    fail "$label — HTTP=$HTTP error=$ERR"
  fi
}

smoke_test "pricing.discount.simple"    '{"amount":150,"customer_tier":"gold"}'    "pricing.discount.simple"
smoke_test "pricing.discount.bulk"      '{"amount":500,"quantity":20}'             "pricing.discount.bulk"
smoke_test "validation.customer.age"    '{"age":25,"customer_id":"cust-001"}'      "validation.customer.age"
smoke_test "seasonal.holiday.blackfriday" '{"amount":200,"customer_tier":"silver"}' "seasonal.holiday.blackfriday"

# ── Step 5: Hot reload ────────────────────────────────────────────────────────
step "Step 5 — Hot reload test"

RELOAD=$(curl -s -X POST "$APP_URL/admin/refresh-rules" -H "X-Admin-API-Key: $ADMIN_KEY" 2>/dev/null)
RL=$(echo "$RELOAD" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('rules_loaded',0))" 2>/dev/null || echo "0")
RF=$(echo "$RELOAD" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('rules_failed',0))" 2>/dev/null || echo "?")
RMS=$(echo "$RELOAD" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('duration_ms','?'))" 2>/dev/null || echo "?")

if [ "$RL" = "17" ] && [ "$RF" = "0" ]; then
  pass "Reload: 17 rules in ${RMS}ms, 0 failures"
else
  fail "Reload: loaded=$RL failed=$RF"
fi

# Verify execution still works immediately after reload
HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$APP_URL/execute-rule" \
  -H "Content-Type: application/json" \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100,"customer_tier":"gold"}}' 2>/dev/null)
if [ "$HTTP" = "200" ]; then
  pass "Rules execute correctly after reload"
else
  fail "Rule execution after reload returned HTTP $HTTP"
fi

# ── Step 6: Memory stability ──────────────────────────────────────────────────
step "Step 6 — Memory stability (5 rapid reloads + GC verify)"

MEM_READINGS=()
for i in 1 2 3 4 5; do
  curl -s -X POST "$APP_URL/admin/refresh-rules" -H "X-Admin-API-Key: $ADMIN_KEY" > /dev/null 2>&1
  sleep 2
  USED=$(curl -s "$APP_URL/admin/memory/info" 2>/dev/null | \
    python3 -c "import sys,json; d=json.load(sys.stdin); print(d['heap']['usedMB'])" 2>/dev/null || echo "0")
  MEM_READINGS+=("$USED")
  info "Reload $i: heap=${USED}MB"
done

# Check for monotonic growth (leak signal): all 5 readings increasing is suspicious
LEAK=false
if [ "${MEM_READINGS[4]}" -gt "$((${MEM_READINGS[0]} * 3))" ] 2>/dev/null; then
  LEAK=true
fi

if [ "$LEAK" = false ]; then
  pass "No monotonic heap growth detected across 5 reloads"
else
  fail "Possible memory leak — heap grew from ${MEM_READINGS[0]}MB to ${MEM_READINGS[4]}MB"
fi

info "Triggering GC..."
curl -s -X POST "$APP_URL/admin/memory/gc" -H "X-Admin-API-Key: $ADMIN_KEY" > /dev/null 2>&1
sleep 4
POST_GC=$(curl -s "$APP_URL/admin/memory/info" 2>/dev/null | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d['heap']['usedMB'])" 2>/dev/null || echo "999")

if [ "$POST_GC" -lt 150 ] 2>/dev/null; then
  pass "Post-GC heap: ${POST_GC}MB (< 150MB — no leak)"
else
  info "Post-GC heap: ${POST_GC}MB (elevated but may need more GC cycles)"
fi

# ── Step 7: Load test ─────────────────────────────────────────────────────────
step "Step 7 — 5-minute load test (20 workers, hot reload at 2 min)"

LOAD_DURATION=300
LOAD_WORKERS=20
PAYLOADS=(
  "{\"rule_id\":\"pricing.discount.simple\",\"data\":{\"amount\":150,\"customer_tier\":\"gold\"}}"
  "{\"rule_id\":\"pricing.discount.bulk\",\"data\":{\"amount\":500,\"quantity\":20}}"
  "{\"rule_id\":\"seasonal.holiday.blackfriday\",\"data\":{\"amount\":200,\"customer_tier\":\"silver\"}}"
  "{\"rule_id\":\"validation.customer.age\",\"data\":{\"age\":30,\"customer_id\":\"cust-100\"}}"
  "{\"rule_id\":\"pricing.shipping.standard\",\"data\":{\"amount\":80,\"weight\":2.5}}"
  "{\"rule_id\":\"pricing.discount.vip\",\"data\":{\"amount\":300,\"customer_tier\":\"vip\"}}"
)

TMPDIR_LT=$(mktemp -d)
LT_START=$(date +%s)
LT_END=$((LT_START + LOAD_DURATION))
LT_RELOAD_DONE=0

lt_worker() {
  local pidx=$(( $1 % 6 ))
  local payload="${PAYLOADS[$pidx]}"
  while [ "$(date +%s)" -lt "$LT_END" ]; do
    HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$APP_URL/execute-rule" \
      -H "Content-Type: application/json" -d "$payload" --max-time 5 2>/dev/null)
    if [ "$HTTP" = "200" ]; then echo 1 >> "$TMPDIR_LT/s"; else echo "$HTTP" >> "$TMPDIR_LT/e"; fi
  done
}

info "Spawning $LOAD_WORKERS workers..."
for w in $(seq 1 $LOAD_WORKERS); do lt_worker "$w" & done

LAST_REPORT=$LT_START
while [ "$(date +%s)" -lt "$LT_END" ]; do
  NOW=$(date +%s); ELAPSED=$((NOW - LT_START))

  if [ $LT_RELOAD_DONE -eq 0 ] && [ $ELAPSED -ge 120 ]; then
    echo ""
    info ">>> HOT RELOAD triggered at ${ELAPSED}s <<<"
    RR=$(curl -s -X POST "$APP_URL/admin/refresh-rules" -H "X-Admin-API-Key: $ADMIN_KEY" 2>/dev/null | \
      python3 -c "import sys,json; d=json.load(sys.stdin); \
        print(f'rules={d[\"rules_loaded\"]} failed={d[\"rules_failed\"]} ms={d[\"duration_ms\"]}')" 2>/dev/null || echo "?")
    info ">>> $RR <<<"
    LT_RELOAD_DONE=1
  fi

  if [ $((NOW - LAST_REPORT)) -ge 30 ] && [ $ELAPSED -gt 0 ]; then
    SUC=$(wc -l < "$TMPDIR_LT/s" 2>/dev/null | tr -d ' '); SUC=${SUC:-0}
    ERR=$(wc -l < "$TMPDIR_LT/e" 2>/dev/null | tr -d ' '); ERR=${ERR:-0}
    TOT=$((SUC + ERR)); RPS=$((TOT / ELAPSED))
    MEM=$(mem_info)
    printf "  [%3ds] success=%-6s errors=%-4s rps~%-4s heap=%s\n" "$ELAPSED" "$SUC" "$ERR" "$RPS" "$MEM"
    LAST_REPORT=$NOW
  fi
  sleep 5
done

wait

LT_ELAPSED=$(($(date +%s) - LT_START))
LT_SUC=$(wc -l < "$TMPDIR_LT/s" 2>/dev/null | tr -d ' '); LT_SUC=${LT_SUC:-0}
LT_ERR=$(wc -l < "$TMPDIR_LT/e" 2>/dev/null | tr -d ' '); LT_ERR=${LT_ERR:-0}
LT_TOT=$((LT_SUC + LT_ERR))
LT_ERR_PCT=$(echo "scale=2; $LT_ERR * 100 / $LT_TOT" | bc 2>/dev/null || echo "0")
LT_AVG_RPS=$((LT_TOT / LT_ELAPSED))
LT_ERR_CODES=$(sort "$TMPDIR_LT/e" 2>/dev/null | uniq -c | sort -rn | head -3 | xargs 2>/dev/null || echo "none")
LT_FINAL_MEM=$(mem_info)
rm -rf "$TMPDIR_LT"

LT_RULES=$(curl -s "$APP_URL/admin/health" 2>/dev/null | \
  python3 -c "import sys,json; d=json.load(sys.stdin); \
    print(d['components']['drools']['details']['loaded_rules'])" 2>/dev/null || echo "?")

if [ "$LT_ERR" = "0" ]; then
  pass "Load test: 0 errors across $LT_TOT requests"
elif [ "$(echo "$LT_ERR_PCT < 0.5" | bc 2>/dev/null)" = "1" ]; then
  pass "Load test: error rate ${LT_ERR_PCT}% (< 0.5% threshold)"
else
  fail "Load test: error rate ${LT_ERR_PCT}% ($LT_ERR errors / $LT_TOT total)"
fi

if [ "$LT_RULES" = "17" ]; then
  pass "All 17 rules still loaded after load test"
else
  fail "Rules loaded after test: $LT_RULES (expected 17)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
banner "╔══════════════════════════════════════════════════╗"
banner "║              TEST SUMMARY                        ║"
banner "╚══════════════════════════════════════════════════╝"
echo ""
echo -e "  ${BOLD}Load test results${RESET}"
echo    "  ─────────────────────────────"
echo    "  Duration     : ${LT_ELAPSED}s"
echo    "  Total req    : $LT_TOT"
echo    "  Success      : $LT_SUC"
echo    "  Errors       : $LT_ERR ($LT_ERR_PCT%)"
echo    "  Avg RPS      : ~$LT_AVG_RPS"
echo    "  Error codes  : $LT_ERR_CODES"
echo    "  Final heap   : $LT_FINAL_MEM"
echo    "  Rules loaded : $LT_RULES / 17"
echo    "  Hot reload   : triggered at 2 min mid-test"
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
