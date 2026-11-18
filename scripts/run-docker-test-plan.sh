#!/usr/bin/env bash
# =============================================================================
# run-docker-test-plan.sh — automated full-docker-test-plan.md (Steps 1–13)
#
# Builds and starts the docker-compose stack, then runs every step of
# full-docker-test-plan.md plus value checks for all 17 sample rules, a live
# rate-limit spoofing test, the runaway-rule timeout (408), RULE_DELETED
# propagation, the request-path bypass check (ADR-024) and refresh safety / readiness /
# reconcile (Step 13: S3 down, empty bucket, start with 0 rules, direct S3 edit). Prints a PASS/FAIL/INFO
# table and exits non-zero if any check FAILs. INFO rows document known issues
# (they never fail the run).
#
# Usage:
#   ./scripts/run-docker-test-plan.sh              # full run, stack removed at the end
#   ./scripts/run-docker-test-plan.sh --keep-stack # leave the stack running
#   ./scripts/run-docker-test-plan.sh --skip-build # reuse the existing image
#
# Requirements: docker (Engine + Compose v2), curl, jq. ~12–15 minutes.
# Linux note: if Podman is also installed and DOCKER_HOST points at it, run with
#   DOCKER_HOST=unix:///var/run/docker.sock ./scripts/run-docker-test-plan.sh
# No tracked file is modified (Redis-off mode uses a temporary compose override).
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
KEEP_STACK=false
SKIP_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --keep-stack) KEEP_STACK=true ;;
    --skip-build) SKIP_BUILD=true ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done
for tool in docker curl jq; do command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 2; }; done
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 ('docker compose') is required" >&2; exit 2; }

cd "$ROOT_DIR"
TMP="$(mktemp -d)"
DC=(docker compose)
A=http://localhost:8080; ACT=http://localhost:8081
KEY="${ADMIN_API_KEY:-admin-secret}"; K=(-H "X-Admin-API-Key: $KEY")
PASS=0; FAIL=0; INFO=0; RESULTS=()

cleanup() {
  if [ "$KEEP_STACK" = true ]; then echo "Stack left running (--keep-stack). Stop with: docker compose down -v"
  else "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1; fi
  rm -rf "$TMP"
}
trap cleanup EXIT

rec() { # rec ID NAME OK(1/0) DETAIL
  local s; if [ "$3" = 1 ]; then PASS=$((PASS + 1)); s=PASS; else FAIL=$((FAIL + 1)); s=FAIL; fi
  RESULTS+=("$1|$s|$2|$4"); printf '%-7s %-4s %-46s %s\n' "$1" "$s" "$2" "$4"
}
note() { INFO=$((INFO + 1)); RESULTS+=("$1|INFO|$2|$3"); printf '%-7s %-4s %-46s %s\n' "$1" "INFO" "$2" "$3"; }
ok() { "$@" >/dev/null 2>&1 && echo 1 || echo 0; }        # ok CMD... -> 1/0
ex() { curl -s -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d "$1"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
app_id() { "${DC[@]}" ps -q app; }
wait_healthy() {
  for i in $(seq 1 60); do
    [ "$(docker inspect --format='{{.State.Health.Status}}' "$(app_id)" 2>/dev/null)" = healthy ] && { echo "~$((i * 5))s"; return 0; }
    sleep 5
  done; return 1
}
redis() { "${DC[@]}" exec -T redis redis-cli "$@"; }

echo "=== Prerequisites: clean slate"; "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1

# ── Step 1 ────────────────────────────────────────────────────────────────────
echo "=== Step 1: build & start"
T0=$(date +%s)
if [ "$SKIP_BUILD" = true ]; then "${DC[@]}" up -d >"$TMP/up.log" 2>&1; else "${DC[@]}" up -d --build >"$TMP/up.log" 2>&1; fi
H=$(wait_healthy); hok=$?
PS=$("${DC[@]}" ps --format '{{.Service}}={{.Health}}' | tr '\n' ' ')
all=0; [ $hok = 0 ] && [[ "$PS" == *app=healthy* && "$PS" == *localstack=healthy* && "$PS" == *redis=healthy* ]] && all=1
rec 1 "Docker stack starts, all healthy" $all "$H; $PS($(( $(date +%s) - T0 ))s)"
[ $all = 1 ] || { "${DC[@]}" logs --tail=40 app; exit 1; }

# ── Step 2 ────────────────────────────────────────────────────────────────────
echo "=== Step 2: health & admin endpoints"
j=$(curl -s "$A/admin/health" "${K[@]}");        rec 2.1 "Health UP" "$(ok jq -e '.status=="UP"' <<<"$j")" "components=$(jq -c '.components|keys' <<<"$j")"
j=$(curl -s "$A/admin/info" "${K[@]}");          rec 2.2 "Admin info" "$(ok jq -e 'has("application") and has("java_version") and has("version")' <<<"$j")" "$(jq -c '{version,java_version}' <<<"$j")"
j=$(curl -s "$A/admin/thread-pools" "${K[@]}");  rec 2.3 "Thread pools" "$(ok jq -e 'has("rule_execution_pool") and has("storage_pool")' <<<"$j")" "$(jq -r .rule_execution_pool <<<"$j" | head -c 80)"
j=$(curl -s "$A/admin/memory/info" "${K[@]}");   rec 2.4 "Memory info" "$(ok jq -e '.heap.usedMB > 0' <<<"$j")" "heap=$(jq -c .heap <<<"$j")"
j=$(curl -s "$A/admin/memory/snapshot" "${K[@]}"); rec 2.5 "Memory snapshot" "$(ok jq -e 'has("heapUsedMB") and has("heapMaxMB")' <<<"$j")" "$(jq -c . <<<"$j")"
j=$(curl -s "$ACT/actuator/health");             rec 2.6 "Actuator health (8081)" "$(ok jq -e '.status=="UP"' <<<"$j")" "$(jq -c . <<<"$j")"
n=$(curl -s "$ACT/actuator/metrics" | jq '.names|length'); rec 2.7 "Actuator metrics (8081)" "$([ "${n:-0}" -gt 0 ] && echo 1 || echo 0)" "names=$n"

# ── Step 3 ────────────────────────────────────────────────────────────────────
echo "=== Step 3: rules loading & listing"
j=$(curl -s "$A/admin/rules" "${K[@]}")
rec 3.1 "List rules = 17, all ACTIVE" "$(ok jq -e '.total_rules==17 and ([.rules[]|select(.status=="ACTIVE")]|length)==17' <<<"$j")" "total=$(jq .total_rules <<<"$j")"
j=$(curl -s -X POST "$A/admin/refresh-rules" "${K[@]}")
rec 3.2 "Refresh all" "$(ok jq -e '.status=="completed" and .rules_loaded==17 and .rules_failed==0' <<<"$j")" "$(jq -c '{status,rules_loaded,rules_failed,duration_ms}' <<<"$j")"
j=$(curl -s -X POST "$A/admin/refresh-rules/pricing.discount.simple" "${K[@]}")
rec 3.3 "Refresh single rule" "$(ok jq -e '.status=="success" or .status=="unchanged"' <<<"$j")" "$(jq -c '{rule_id,status,compilation_time_ms}' <<<"$j")"
t=$(curl -s "$A/admin/rules" "${K[@]}" | jq .total_rules); rec 3.4 "Still 17 after refresh" "$([ "$t" = 17 ] && echo 1 || echo 0)" "total=$t"

# ── Step 4 + X1: execution with VALUE checks ──────────────────────────────────
echo "=== Step 4 / X1: rule execution — values checked (each rule_id runs only its own rules, ADR-025)"
chk() { local id="$1" name="$2" body="$3" filt="$4" j; j=$(ex "$body"); rec "$id" "$name" "$(ok jq -e "(.error==null) and ($filt)" <<<"$j")" "$(jq -c .result <<<"$j" | head -c 150)"; }
chk 4.1  "simple \$100 -> \$90"              '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' '.result.amount==90 and .result.discount==10'
chk 4.2  "vip \$100 -> \$80 (isolated)"      '{"rule_id":"pricing.discount.vip","data":{"customerType":"VIP","amount":100}}' '(.result.amount*100|round)==8000 and .result.customerTier=="VIP"'
chk 4.3  "bulk 15 x \$200 -> \$170"          '{"rule_id":"pricing.discount.bulk","data":{"quantity":15,"amount":200}}' '(.result.amount*100|round)==17000'
chk 4.4  "first-time \$100 -> \$95"          '{"rule_id":"pricing.discount.first-time","data":{"isFirstTimeCustomer":true,"amount":100}}' '(.result.amount*100|round)==9500'
chk 4.5  "standard ship 0.5kg -> 5.99"       '{"rule_id":"pricing.shipping.standard","data":{"shippingType":"standard","weight":0.5}}' '.result.shippingCost==5.99 and .result.estimatedDays==5'
chk 4.6  "express 3kg \$150 -> free"         '{"rule_id":"pricing.shipping.express","data":{"shippingType":"express","weight":3,"amount":150}}' '.result.freeShipping==true and .result.shippingCost==0 and .result.amount==150'
chk 4.7  "blackfriday \$200 -> \$150"        '{"rule_id":"seasonal.holiday.blackfriday","data":{"promotionCode":"BLACK2024","amount":200}}' '(.result.amount*100|round)==15000 and .result.promotionValid==true'
chk 4.8  "holiday \$100 -> \$88"             '{"rule_id":"seasonal.holiday.discount","data":{"isHolidaySeason":true,"amount":100}}' '(.result.amount*100|round)==8800'
chk 4.9  "age 15 -> REJECTED"                '{"rule_id":"validation.customer.age","data":{"customerAge":15}}' '.result.validationResult=="REJECTED" and .result.eligible==false'
chk 4.10 "credit 720 -> Good, 5000"          '{"rule_id":"validation.customer.credit","data":{"creditScore":720,"requestedAmount":10000}}' '.result.creditTier=="Good" and .result.approvedAmount==5000'
chk X1.1 "simple \$40 -> unchanged"          '{"rule_id":"pricing.discount.simple","data":{"amount":40}}' '.result.amount==40 and (.result|has("discount")|not)'
chk X1.2 "loyalty \$100 -> \$85"             '{"rule_id":"pricing.loyalty.salience","data":{"loyaltyMember":true,"amount":100}}' '(.result.amount*100|round)==8500 and .result.loyaltyApplied==true'
chk X1.3 "age 70 -> Senior"                  '{"rule_id":"validation.customer.age","data":{"customerAge":70}}' '.result.ageGroup=="Senior" and .result.seniorDiscount==true'
chk X1.4 "credit 580 -> REJECTED"            '{"rule_id":"validation.customer.credit","data":{"creditScore":580,"requestedAmount":10000}}' '.result.validationResult=="REJECTED" and .result.approvedAmount==0'
chk X1.5 "bundle 50+60 -> 99"                '{"rule_id":"pricing.bundle.accumulate","data":{"items":[{"price":50},{"price":60}]}}' '.result.bundleTotal==110 and .result.bundleFinalAmount==99'
chk X1.6 "exists: low stock warning"         '{"rule_id":"inventory.warning.exists","data":{"items":[{"stockLevel":10},{"stockLevel":3}]}}' '.result.lowStockWarning==true'
chk X1.7 "notempty [] -> REJECTED"           '{"rule_id":"validation.cart.notempty","data":{"items":[]}}' '.result.validationResult=="REJECTED" and .result.reason=="EMPTY_CART"'
chk X1.8 "forall: all in stock"              '{"rule_id":"validation.cart.forall","data":{"items":[{"stockLevel":10},{"stockLevel":20}]}}' '.result.allItemsInStock==true'
chk X1.9 "email internal -> VALID"           '{"rule_id":"validation.email.compound","data":{"email":"alice@company.com","customerType":"internal"}}' '.result.emailValidationResult=="VALID"'
chk X1.10 "temporal -> ACTIVE, 235 days"     '{"rule_id":"seasonal.expiry.temporal","data":{"currentDate":"2026-05-10","expiryDate":"2026-12-31"}}' '.result.promoStatus=="ACTIVE" and .result.daysRemaining==235'
chk X1.K "forall, items without price -> OK" '{"rule_id":"validation.cart.forall","data":{"items":[{"stockLevel":10}]}}' '.result.allItemsInStock==true and (.result|has("bundleTotal")|not)'
chk X1.11 "simple w/ VIP fields -> \$90 only" '{"rule_id":"pricing.discount.simple","data":{"customerType":"VIP","amount":100}}' '.result.amount==90 and (.result|has("customerTier")|not)'

# ── Step 5 ────────────────────────────────────────────────────────────────────
echo "=== Step 5: error handling"
j=$(ex '{"rule_id":"nonexistent.rule","data":{"test":true}}'); rec 5.1 "Non-existent rule -> RULE_NOT_FOUND" "$(ok jq -e '.error.code=="RULE_NOT_FOUND"' <<<"$j")" "$(jq -r .error.message <<<"$j")"
j=$(ex '{"data":{"test":true}}');                              rec 5.2 "Missing rule_id -> INVALID_INPUT" "$(ok jq -e '.error.code=="INVALID_INPUT"' <<<"$j")" "$(jq -r .error.details <<<"$j" | head -c 90)"
j=$(ex '{"rule_id":"pricing.discount.simple","data":{}}');     rec 5.3 "Empty data -> {}" "$(ok jq -e '.error==null and .result=={}' <<<"$j")" "$(jq -c '{result,error}' <<<"$j")"
c=$(curl -s -o "$TMP/54.json" -w '%{http_code}' -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d 'not json')
rec 5.4 "Invalid JSON -> 400 INVALID_INPUT" "$([ "$c" = 400 ] && jq -e '.error.code=="INVALID_INPUT"' "$TMP/54.json" >/dev/null && echo 1 || echo 0)" "http=$c"
j=$(ex '{"rule_id":"../../../etc/passwd","data":{}}');         rec 5.5 "Path traversal -> INVALID_INPUT" "$(ok jq -e '.error.code=="INVALID_INPUT"' <<<"$j")" "$(jq -r .error.details <<<"$j" | head -c 90)"

# ── Steps 6–7 ─────────────────────────────────────────────────────────────────
echo "=== Step 6/7: memory"
hb=$(curl -s "$A/admin/memory/info" "${K[@]}" | jq .heap.usedMB)
for _ in 1 2 3; do curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" >/dev/null; sleep 2; done
ha=$(curl -s "$A/admin/memory/info" "${K[@]}" | jq .heap.usedMB)
rec 6 "Memory stable (< 500MB after 3 refreshes)" "$([ "${ha%.*}" -lt 500 ] && echo 1 || echo 0)" "before=${hb}MB after=${ha}MB"
j=$(curl -s -X POST "$A/admin/memory/gc" "${K[@]}"); rec 7 "GC trigger" "$(ok jq -e '.message=="Garbage collection triggered"' <<<"$j")" "$(jq -c '{usedBeforeMB,usedAfterMB,freedMemoryMB}' <<<"$j")"

# ── Step 8 ────────────────────────────────────────────────────────────────────
echo "=== Step 8: Redis cache layer"
j=$(curl -s "$A/admin/health" "${K[@]}")
rec 8.1 "Redis health: decorator + connected" "$(ok jq -e '.components.cache.details.mode=="redis" and .components.redis.details.connected==true and .components.redis.details.cache_decorator=="RedisCachedRuleStorage"' <<<"$j")" "$(jq -c '.components.redis.details' <<<"$j")"
L=$("${DC[@]}" logs --no-log-prefix app 2>&1); m=0
for p in "RedisCachedRuleStorage delegate set" "RuleRefreshPublisher initialized" "RuleRefreshSubscriber initialized" "pub/sub listener" "Drools instance ID"; do grep -q "$p" <<<"$L" && m=$((m + 1)); done
rec 8.2 "Redis bean wiring (startup log, full)" "$([ $m = 5 ] && echo 1 || echo 0)" "$m/5 expected lines"
curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" >/dev/null
kc=$(redis KEYS 'drools:rule:*' | grep -c .); k1=$(redis KEYS 'drools:rule:*' | head -1); v=$(redis GET "$k1" | head -c 300)
rec 8.3 "Refresh populates Redis (17 keys)" "$([ "$kc" = 17 ] && grep -q '"content"' <<<"$v" && echo 1 || echo 0)" "keys=$kc"
ttl=$(redis TTL "$k1"); rec 8.4 "TTL applied (0 < ttl <= 900)" "$([ "$ttl" -gt 0 ] && [ "$ttl" -le 900 ] && echo 1 || echo 0)" "ttl=$ttl"
redis SET drools:rule:stale '"GHOST"' >/dev/null; g1=$(redis GET drools:rule:stale)
curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" >/dev/null
g2=$(redis GET drools:rule:stale); kc=$(redis KEYS 'drools:rule:*' | grep -c .)
rec 8.5 "Refresh invalidation (stale key wiped)" "$([ "$g1" = '"GHOST"' ] && [ -z "$g2" ] && [ "$kc" = 17 ] && echo 1 || echo 0)" "before=$g1 after='$g2' keys=$kc"
"${DC[@]}" exec -T -d redis sh -c 'redis-cli SUBSCRIBE drools:rule:events > /tmp/sub.log 2>&1'; sleep 1
curl -s -X POST "$A/admin/refresh-rules/pricing.discount.simple" "${K[@]}" >/dev/null; sleep 2
sl=$("${DC[@]}" exec -T redis cat /tmp/sub.log)
rec 8.6 "Pub/sub RULE_REFRESHED event fires" "$(grep -q '"event":"RULE_REFRESHED"' <<<"$sl" && grep -q '"rule_id":"pricing.discount.simple"' <<<"$sl" && echo 1 || echo 0)" "$(grep -o '{.*}' <<<"$sl" | head -1 | head -c 120)"
curl -s -X POST "$A/admin/refresh-rules/pricing.discount.bulk" "${K[@]}" >/dev/null
st=$(curl -s "$A/admin/health" "${K[@]}" | jq -c '.components.cache.details.statistics')
rec 8.7 "Cache stats present" "$(ok jq -e 'has("hits") and has("misses") and has("hit_rate")' <<<"$st")" "$st"

# ── Step 9 (+12.4): Redis OFF via temporary override (no tracked-file edits) ──
echo "=== Step 9: Redis OFF regression"
cat > "$TMP/redis-off.yml" <<'YML'
services:
  app:
    environment:
      - REDIS_ENABLED=false
      - REDIS_PUBSUB_ENABLED=false
YML
OFF=(docker compose -f docker-compose.yml -f "$TMP/redis-off.yml")
"${OFF[@]}" up -d app >/dev/null 2>&1; sleep 5; wait_healthy >/dev/null
c=$("${OFF[@]}" logs --since 3m app 2>&1 | grep -cE "RedisCachedRuleStorage initialized|RuleRefreshPublisher initialized")
rec 9.1 "Redis OFF: no Redis beans" "$([ "$c" = 0 ] && echo 1 || echo 0)" "init lines=$c"
j=$(curl -s "$A/admin/health" "${K[@]}"); rec 9.2 "Redis OFF: cache mode=off, no redis" "$(ok jq -e '.components.cache.details.mode=="off" and .components.redis==null' <<<"$j")" "mode=$(jq -r .components.cache.details.mode <<<"$j")"
rl=$(curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" | jq .rules_loaded); e=$(ex '{"rule_id":"pricing.discount.simple","data":{"amount":150}}' | jq -c .error)
rec 9.3 "Redis OFF: refresh + execute" "$([ "$rl" = 17 ] && [ "$e" = null ] && echo 1 || echo 0)" "rules_loaded=$rl error=$e"
ah=$(code "$ACT/actuator/health"); rec 12.4 "Actuator 200 with Redis off (P4)" "$([ "$ah" = 200 ] && echo 1 || echo 0)" "http=$ah"
"${DC[@]}" up -d app >/dev/null 2>&1; sleep 5; wait_healthy >/dev/null
rec 9.4 "Back to default (Redis on)" "$(ok jq -e '.components.cache.details.mode=="redis"' <<<"$(curl -s "$A/admin/health" "${K[@]}")")" "tracked compose untouched"

# ── Step 12: security & robustness ────────────────────────────────────────────
echo "=== Step 12: security & robustness"
a=$(code -X POST "$A/admin/refresh-rules"); b=$(code -X POST "$A/admin/refresh-rules" "${K[@]}"); c=$(code -X POST "$A/admin/refresh-rules" -H 'X-Admin-API-Key: wrong')
rec 12.1 "Admin auth no-key/key/wrong = 401/200/401" "$([ "$a$b$c" = 401200401 ] && echo 1 || echo 0)" "$a/$b/$c"
# 12.2 live: 1100 requests from one IP with rotating X-Client-Id/X-API-Key; limit is 1000/min per IP.
sleep 61   # fresh per-minute window
seq 1 1100 | xargs -P 40 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST "$A/execute-rule" -H 'Content-Type: application/json' \
  -H 'X-Client-Id: c{}' -H 'X-API-Key: k{}' -d '{"rule_id":"pricing.discount.simple","data":{"amount":1}}' > "$TMP/rl.txt"
n200=$(grep -c '^200$' "$TMP/rl.txt"); n429=$(grep -c '^429$' "$TMP/rl.txt")
rec 12.2 "Rate limit by IP; spoofed headers don't help" "$([ "$n200" -le 1000 ] && [ "$n429" -ge 100 ] && echo 1 || echo 0)" "200=$n200 429=$n429 of 1100"
p=$("${DC[@]}" port app 8081); rec 12.3 "Actuator 8081 loopback-only (P5)" "$([[ "$p" == 127.0.0.1:* ]] && echo 1 || echo 0)" "$p"
sleep 61   # let the rate-limit window reset before more /execute-rule calls
"${DC[@]}" exec -T localstack sh -c 'mkdir -p /tmp/rr && printf "%s\n" "package com.company.rules.test.runaway" "import java.util.Map" "rule \"Runaway loop\"" "when" "    \$data : Map(this[\"__runaway__\"] == true)" "then" "    while (true) { }" "end" > /tmp/rr/loop.drl && awslocal s3 cp /tmp/rr/loop.drl s3://local-rules/test/runaway/loop.drl >/dev/null'
rr=$(curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" | jq -c '{rules_loaded,rules_failed}')
pre=$(code -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}')
t0=$(date +%s); rc=$(code -m 60 -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d '{"rule_id":"test.runaway.loop","data":{"__runaway__":true}}'); dt=$(( $(date +%s) - t0 ))
post=$(code -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}')
rec 12.5 "Runaway rule -> 408; service stays responsive" "$([ "$rc" = 408 ] && [ "$pre" = 200 ] && [ "$post" = 200 ] && echo 1 || echo 0)" "refresh=$rr pre=$pre runaway=$rc (${dt}s) post=$post"
stuck=$(curl -s "$ACT/actuator/metrics/drools.rule.execution.stuck" | jq '.measurements[0].value|floor')
rec 12.5b "Stuck-worker gauge counts the runaway thread" "$([ "${stuck:-0}" -ge 1 ] 2>/dev/null && echo 1 || echo 0)" "drools.rule.execution.stuck=$stuck"
"${DC[@]}" exec -T localstack awslocal s3 rm s3://local-rules/test/runaway/loop.drl >/dev/null
curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" >/dev/null
redis PUBLISH drools:rule:events '{"event":"RULE_DELETED","rule_id":"pricing.discount.simple","source_instance_id":"foreign","timestamp":"2026-11-17T00:00:00Z"}' >/dev/null; sleep 3
t1=$(curl -s "$A/admin/rules" "${K[@]}" | jq .total_rules); curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" >/dev/null; t2=$(curl -s "$A/admin/rules" "${K[@]}" | jq .total_rules)
rec 12.6 "RULE_DELETED 17->16, refresh -> 17 (S5)" "$([ "$t1" = 16 ] && [ "$t2" = 17 ] && echo 1 || echo 0)" "after delete=$t1 after refresh=$t2"
bad=0; out=""
for spec in "GET /admin;x/health" "GET /%61dmin/health" "GET /admin/health;x" "POST /%61dmin/refresh-rules" "POST /%61dmin/memory/gc" "POST /%65xecute-rule"; do
  set -- $spec; c=$(code -X "$1" "$A$2" -H 'Content-Type: application/json' -d '{}'); out="$out $2=$c"; [ "$c" = 400 ] || bad=1
done
rec 12.7 "Path bypass variants -> 400 (ADR-024)" "$([ $bad = 0 ] && echo 1 || echo 0)" "$out"
# 12.8 firing cap: a rule that re-triggers itself (update without no-loop) -> 422, no partial result
"${DC[@]}" exec -T localstack sh -c 'mkdir -p /tmp/rl && printf "%s\n" "package com.company.rules.test.capped" "import java.util.Map" "rule \"Self retrigger\"" "when" "    \$d : Map(this[\"__loop__\"] != null)" "then" "    \$d.put(\"__loop__\", ((Number) \$d.get(\"__loop__\")).intValue() + 1);" "    update(\$d);" "end" > /tmp/rl/loop.drl && awslocal s3 cp /tmp/rl/loop.drl s3://local-rules/test/capped/loop.drl >/dev/null'
curl -s -X POST "$A/admin/refresh-rules/test.capped.loop" "${K[@]}" >/dev/null
j=$(curl -s -m 60 -w '\n%{http_code}' -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d '{"rule_id":"test.capped.loop","data":{"__loop__":0}}')
hc=$(tail -1 <<<"$j"); cd8=$(head -1 <<<"$j" | jq -r .error.code); res=$(head -1 <<<"$j" | jq -c .result)
post=$(code -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}')
rec 12.8 "Rule loop -> 422 RULE_LIMIT_EXCEEDED, no result" "$([ "$hc" = 422 ] && [ "$cd8" = RULE_LIMIT_EXCEEDED ] && [ "$res" = null ] && [ "$post" = 200 ] && echo 1 || echo 0)" "http=$hc code=$cd8 result=$res post=$post"
"${DC[@]}" exec -T localstack awslocal s3 rm s3://local-rules/test/capped/loop.drl >/dev/null
curl -s -X POST "$A/admin/refresh-rules/test.capped.loop" "${K[@]}" >/dev/null
# 12.9 audit trail: refreshes are logged with actor + rule IDs, never DRL text
"${DC[@]}" logs --no-log-prefix app 2>&1 | grep 'AUDIT action=' > "$TMP/audit.txt"
na=$(grep -c 'action=refresh_all' "$TMP/audit.txt"); nr=$(grep -c 'action=refresh_rule' "$TMP/audit.txt"); leak=$(grep -c -E 'rule "|\$data|then ' "$TMP/audit.txt")
rec 12.9 "AUDIT lines for refreshes, no DRL content" "$([ "$na" -ge 1 ] && [ "$nr" -ge 1 ] && [ "$leak" = 0 ] && grep -q 'actor=[0-9]' "$TMP/audit.txt" && echo 1 || echo 0)" "refresh_all=$na refresh_rule=$nr drl_leaks=$leak"

# ── Step 10: logs (ignore errors this plan provokes on purpose) ───────────────
echo "=== Step 10: app log errors"
"${DC[@]}" logs --no-log-prefix app 2>&1 | grep -E '"level":"ERROR"| ERROR ' | grep -v stack_trace | grep -v -E 'test\.runaway\.loop|test\.capped\.loop' > "$TMP/errors.txt"
n=$(grep -c . "$TMP/errors.txt"); rec 10 "No unexpected ERROR lines in app log" "$([ "$n" = 0 ] && echo 1 || echo 0)" "$n lines"
[ "$n" = 0 ] || head -5 "$TMP/errors.txt" | cut -c1-200

# ── Step 13: refresh safety, readiness, reconcile (engine-isolation Phase 3) ─────
# Runs after Step 10 because it provokes storage/refresh errors on purpose.
echo "=== Step 13: refresh safety / readiness / reconcile"
aws() { "${DC[@]}" exec -T localstack awslocal "$@"; }
total() { curl -s "$A/admin/rules" "${K[@]}" | jq -r .total_rules; }
ready() { code "$ACT/actuator/health/readiness"; }
rd=$(ready); rec 13.1 "Readiness probe UP (rules loaded)" "$([ "$rd" = 200 ] && echo 1 || echo 0)" "/actuator/health/readiness=$rd"

"${DC[@]}" stop localstack >/dev/null 2>&1
j=$(curl -s -m 120 -w '\n%{http_code}' -X POST "$A/admin/refresh-rules" "${K[@]}"); hc=$(tail -1 <<<"$j"); st=$(head -1 <<<"$j" | jq -r .status)
t=$(total); v=$(ex '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq '.result.amount|round')
rec 13.2 "S3 down: refresh 500 'failed', no rule lost" "$([ "$hc" = 500 ] && [ "$st" = failed ] && [ "$t" = 17 ] && [ "$v" = 90 ] && echo 1 || echo 0)" "http=$hc status=$st total=$t simple=$v"
"${DC[@]}" up -d localstack >/dev/null 2>&1
for i in $(seq 1 40); do [ "$(aws s3 ls s3://local-rules/ --recursive 2>/dev/null | grep -c '\.drl$')" -ge 17 ] && break; sleep 3; done
# The S3 circuit breaker opened during the outage; retry until it lets calls through again.
for i in $(seq 1 30); do j=$(curl -s -X POST "$A/admin/refresh-rules" "${K[@]}"); [ "$(jq -r .status <<<"$j")" = completed ] && break; sleep 5; done
rec 13.3 "S3 back: refresh completed, 17 unchanged" "$(ok jq -e '.status=="completed" and .rules_loaded==17 and .rules_unchanged==17' <<<"$j")" "$(jq -c '{status,rules_loaded,rules_unchanged,rules_removed}' <<<"$j")"

aws s3 mb s3://rules-bak >/dev/null 2>&1; aws s3 mv s3://local-rules s3://rules-bak --recursive >/dev/null
j=$(curl -s -w '\n%{http_code}' -X POST "$A/admin/refresh-rules" "${K[@]}"); hc=$(tail -1 <<<"$j"); st=$(head -1 <<<"$j" | jq -r .status)
rec 13.4 "Empty bucket: refresh refused (409), 17 kept" "$([ "$hc" = 409 ] && [ "$st" = rejected_empty ] && [ "$(total)" = 17 ] && echo 1 || echo 0)" "http=$hc status=$st total=$(total)"

# Restart the app against the empty bucket with a 1-minute reconcile.
cat > "$TMP/reconcile.yml" <<'YML'
services:
  app:
    environment:
      - AUTO_REFRESH_ENABLED=true
      - AUTO_REFRESH_INTERVAL_MINUTES=1
YML
"${DC[@]}" -f docker-compose.yml -f "$TMP/reconcile.yml" up -d app >/dev/null 2>&1
for i in $(seq 1 60); do c=$(code "$A/admin/health" "${K[@]}"); [ "$c" = 200 ] || [ "$c" = 503 ] && break; sleep 3; done
hh=$(code "$A/admin/health" "${K[@]}"); rd=$(ready); ec=$(code -X POST "$A/execute-rule" -H 'Content-Type: application/json' -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}')
rec 13.5 "Start with 0 rules: NOT ready, /admin/health 503" "$([ "$hh" = 503 ] && [ "$rd" = 503 ] && echo 1 || echo 0)" "admin/health=$hh readiness=$rd execute=$ec"

aws s3 mv s3://rules-bak s3://local-rules --recursive >/dev/null
t0=$(date +%s); for i in $(seq 1 30); do [ "$(total)" = 17 ] && break; sleep 5; done; dt=$(( $(date +%s) - t0 ))
rec 13.6 "Reconcile loads restored rules, no admin call" "$([ "$(total)" = 17 ] && [ "$(ready)" = 200 ] && [ "$(code "$A/admin/health" "${K[@]}")" = 200 ] && echo 1 || echo 0)" "total=$(total) after ${dt}s readiness=$(ready)"

"${DC[@]}" exec -T localstack sh -c 'awslocal s3 cp s3://local-rules/pricing/discount/simple.drl /tmp/s.drl >/dev/null && sed -i "s/amount \* 0.90/amount * 0.80/" /tmp/s.drl && awslocal s3 cp /tmp/s.drl s3://local-rules/pricing/discount/simple.drl >/dev/null'
t0=$(date +%s); v=0; for i in $(seq 1 30); do v=$(ex '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq '.result.amount|round'); [ "$v" = 80 ] && break; sleep 5; done; dt=$(( $(date +%s) - t0 ))
rec 13.7 "Direct S3 edit picked up by reconcile" "$([ "$v" = 80 ] && echo 1 || echo 0)" "simple \$100 -> $v after ${dt}s"
"${DC[@]}" exec -T localstack sh -c 'sed -i "s/amount \* 0.80/amount * 0.90/" /tmp/s.drl && awslocal s3 cp /tmp/s.drl s3://local-rules/pricing/discount/simple.drl >/dev/null'
curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" >/dev/null   # invalidate Redis + reload the restored DRL

aws s3 cp s3://local-rules/pricing/discount/vip.drl s3://rules-bak/vip.drl >/dev/null; aws s3 rm s3://local-rules/pricing/discount/vip.drl >/dev/null
j=$(curl -s -X POST "$A/admin/refresh-rules/pricing.discount.vip" "${K[@]}"); st=$(jq -r .status <<<"$j"); t=$(total)
aws s3 cp s3://rules-bak/vip.drl s3://local-rules/pricing/discount/vip.drl >/dev/null
j2=$(curl -s -X POST "$A/admin/refresh-rules/pricing.discount.vip" "${K[@]}"); st2=$(jq -r .status <<<"$j2")
rec 13.8 "Deleted in S3: single refresh removes it" "$([ "$st" = removed ] && [ "$t" = 16 ] && [ "$st2" = success ] && [ "$(total)" = 17 ] && echo 1 || echo 0)" "status=$st total=$t; restored status=$st2 total=$(total)"
j=$(curl -s -X POST "$A/admin/refresh-rules/pricing.discount.vip" "${K[@]}")
rec 13.9 "Same DRL again: 'unchanged' (not recompiled)" "$(ok jq -e '.status=="unchanged"' <<<"$j")" "$(jq -c '{status}' <<<"$j")"

"${DC[@]}" up -d app >/dev/null 2>&1; wait_healthy >/dev/null || true   # back to the default app config

# ── Known issues (INFO only; tracked in the post-merge backlog) ───────────────
for spec in "GET /execute-rule|GET|$A/execute-rule|" "POST text/plain /execute-rule|POST|$A/execute-rule|Content-Type: text/plain"; do
  IFS='|' read -r nm m u h <<<"$spec"; c=$(code -X "$m" ${h:+-H "$h"} "$u"); note X2 "Known issue: $nm" "http=$c (expected 405/415; backlog)"
done

# ── Step 11 + summary ─────────────────────────────────────────────────────────
echo; echo "================ SUMMARY: PASS=$PASS FAIL=$FAIL INFO=$INFO ================"
printf '%s\n' "${RESULTS[@]}" | awk -F'|' '$2=="FAIL"{print "  FAIL " $1 " " $3 " — " $4}'
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$FAIL CHECK(S) FAILED"
exit $(( FAIL > 0 ? 1 : 0 ))
