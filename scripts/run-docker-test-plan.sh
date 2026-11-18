#!/usr/bin/env bash
# =============================================================================
# run-docker-test-plan.sh — automated full-docker-test-plan.md (Steps 1–12)
#
# Builds and starts the docker-compose stack, then runs every step of
# full-docker-test-plan.md plus value checks for all 17 sample rules, a live
# rate-limit spoofing test, the runaway-rule timeout (408), RULE_DELETED
# propagation and the request-path bypass check (ADR-024). Prints a PASS/FAIL/INFO
# table and exits non-zero if any check FAILs. INFO rows document known issues
# (they never fail the run).
#
# Usage:
#   ./scripts/run-docker-test-plan.sh              # full run, stack removed at the end
#   ./scripts/run-docker-test-plan.sh --keep-stack # leave the stack running
#   ./scripts/run-docker-test-plan.sh --skip-build # reuse the existing image
#
# Requirements: docker (Engine + Compose v2), curl, jq. ~8–10 minutes.
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
rec 3.3 "Refresh single rule" "$(ok jq -e '.status=="success"' <<<"$j")" "$(jq -c '{rule_id,status,compilation_time_ms}' <<<"$j")"
t=$(curl -s "$A/admin/rules" "${K[@]}" | jq .total_rules); rec 3.4 "Still 17 after refresh" "$([ "$t" = 17 ] && echo 1 || echo 0)" "total=$t"

# ── Step 4 + X1: execution with VALUE checks ──────────────────────────────────
echo "=== Step 4 / X1: rule execution — values checked (all 17 loaded → stacking applies)"
chk() { local id="$1" name="$2" body="$3" filt="$4" j; j=$(ex "$body"); rec "$id" "$name" "$(ok jq -e "(.error==null) and ($filt)" <<<"$j")" "$(jq -c .result <<<"$j" | head -c 150)"; }
chk 4.1  "simple \$100 -> \$90"              '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' '.result.amount==90 and .result.discount==10'
chk 4.2  "vip \$100 stacks -> \$72"          '{"rule_id":"pricing.discount.vip","data":{"customerType":"VIP","amount":100}}' '(.result.amount*100|round)==7200'
chk 4.3  "bulk 15 x \$200 -> \$153"          '{"rule_id":"pricing.discount.bulk","data":{"quantity":15,"amount":200}}' '(.result.amount*100|round)==15300'
chk 4.4  "first-time \$100 -> \$85.50"       '{"rule_id":"pricing.discount.first-time","data":{"isFirstTimeCustomer":true,"amount":100}}' '(.result.amount*100|round)==8550'
chk 4.5  "standard ship 0.5kg -> 5.99"       '{"rule_id":"pricing.shipping.standard","data":{"shippingType":"standard","weight":0.5}}' '.result.shippingCost==5.99 and .result.estimatedDays==5'
chk 4.6  "express 3kg \$150 -> free"         '{"rule_id":"pricing.shipping.express","data":{"shippingType":"express","weight":3,"amount":150}}' '.result.freeShipping==true and .result.shippingCost==0'
chk 4.7  "blackfriday \$200 -> \$135"        '{"rule_id":"seasonal.holiday.blackfriday","data":{"promotionCode":"BLACK2024","amount":200}}' '(.result.amount*100|round)==13500 and .result.promotionValid==true'
chk 4.8  "holiday \$100 -> \$79.20"          '{"rule_id":"seasonal.holiday.discount","data":{"isHolidaySeason":true,"amount":100}}' '(.result.amount*100|round)==7920'
chk 4.9  "age 15 -> REJECTED"                '{"rule_id":"validation.customer.age","data":{"customerAge":15}}' '.result.validationResult=="REJECTED" and .result.eligible==false'
chk 4.10 "credit 720 -> Good, 5000"          '{"rule_id":"validation.customer.credit","data":{"creditScore":720,"requestedAmount":10000}}' '.result.creditTier=="Good" and .result.approvedAmount==5000'
chk X1.1 "simple \$40 -> unchanged"          '{"rule_id":"pricing.discount.simple","data":{"amount":40}}' '.result.amount==40 and (.result|has("discount")|not)'
chk X1.2 "loyalty \$100 -> \$76.50"          '{"rule_id":"pricing.loyalty.salience","data":{"loyaltyMember":true,"amount":100}}' '(.result.amount*100|round)==7650 and .result.loyaltyApplied==true'
chk X1.3 "age 70 -> Senior"                  '{"rule_id":"validation.customer.age","data":{"customerAge":70}}' '.result.ageGroup=="Senior" and .result.seniorDiscount==true'
chk X1.4 "credit 580 -> REJECTED"            '{"rule_id":"validation.customer.credit","data":{"creditScore":580,"requestedAmount":10000}}' '.result.validationResult=="REJECTED" and .result.approvedAmount==0'
chk X1.5 "bundle 50+60 -> 99"                '{"rule_id":"pricing.bundle.accumulate","data":{"items":[{"price":50,"stockLevel":9},{"price":60,"stockLevel":9}]}}' '.result.bundleTotal==110 and .result.bundleFinalAmount==99'
chk X1.6 "exists: low stock warning"         '{"rule_id":"inventory.warning.exists","data":{"items":[{"price":1,"stockLevel":10},{"price":1,"stockLevel":3}]}}' '.result.lowStockWarning==true'
chk X1.7 "notempty [] -> REJECTED"           '{"rule_id":"validation.cart.notempty","data":{"items":[]}}' '.result.validationResult=="REJECTED" and .result.reason=="EMPTY_CART"'
chk X1.8 "forall: all in stock"              '{"rule_id":"validation.cart.forall","data":{"items":[{"price":1,"stockLevel":10},{"price":1,"stockLevel":20}]}}' '.result.allItemsInStock==true'
chk X1.9 "email internal -> VALID"           '{"rule_id":"validation.email.compound","data":{"email":"alice@company.com","customerType":"internal"}}' '.result.emailValidationResult=="VALID"'
chk X1.10 "temporal -> ACTIVE, 235 days"     '{"rule_id":"seasonal.expiry.temporal","data":{"currentDate":"2026-05-10","expiryDate":"2026-12-31"}}' '.result.promoStatus=="ACTIVE" and .result.daysRemaining==235'
j=$(ex '{"rule_id":"validation.cart.forall","data":{"items":[{"stockLevel":10}]}}')
note X1.K "Known issue: items without price" "error=$(jq -c .error.code <<<"$j") (bundle rule NPE on shared Map; backlog)"

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

# ── Step 10: logs (ignore errors this plan provokes on purpose) ───────────────
echo "=== Step 10: app log errors"
"${DC[@]}" logs --no-log-prefix app 2>&1 | grep -E '"level":"ERROR"| ERROR ' | grep -v stack_trace | grep -v 'test.runaway.loop' > "$TMP/errors.txt"
n=$(grep -c . "$TMP/errors.txt"); rec 10 "No unexpected ERROR lines in app log" "$([ "$n" = 0 ] && echo 1 || echo 0)" "$n lines"
[ "$n" = 0 ] || head -5 "$TMP/errors.txt" | cut -c1-200

# ── Known issues (INFO only; tracked in the post-merge backlog) ───────────────
for spec in "GET /execute-rule|GET|$A/execute-rule|" "POST text/plain /execute-rule|POST|$A/execute-rule|Content-Type: text/plain"; do
  IFS='|' read -r nm m u h <<<"$spec"; c=$(code -X "$m" ${h:+-H "$h"} "$u"); note X2 "Known issue: $nm" "http=$c (expected 405/415; backlog)"
done

# ── Step 11 + summary ─────────────────────────────────────────────────────────
echo; echo "================ SUMMARY: PASS=$PASS FAIL=$FAIL INFO=$INFO ================"
printf '%s\n' "${RESULTS[@]}" | awk -F'|' '$2=="FAIL"{print "  FAIL " $1 " " $3 " — " $4}'
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$FAIL CHECK(S) FAILED"
exit $(( FAIL > 0 ? 1 : 0 ))
