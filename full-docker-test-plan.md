# Full Docker Integration Test Plan
**Purpose**: Verify all application functionality works end-to-end in Docker.
**Usage**: run `./scripts/run-docker-test-plan.sh` (automates every step below and prints a PASS/FAIL table), or tell Claude "run the full docker test plan from full-docker-test-plan.md"
**Last Validated**: 2026-11-17 on Linux / Docker Engine 29.10 (pre-merge fixes — see [`.ai-workspace/project-plans/pre-merge-fixes-plan-2026-11-17.md`](.ai-workspace/project-plans/pre-merge-fixes-plan-2026-11-17.md))

> ⚠️ **Admin auth is now enforced.** As of the 2026-08-19 hardening, **all `/admin/*` endpoints
> require the `X-Admin-API-Key` header** (default `admin-secret`, set in `docker-compose.yml`). Add
> `-H "X-Admin-API-Key: admin-secret"` to every `/admin/*` curl below — without it they return `401`.
> In the `docker`/`prod` profiles the app **fails to start** if `ADMIN_API_KEY` is blank.

---

## Prerequisites

```bash
# Ensure Docker is running
docker info > /dev/null 2>&1 && echo "Docker OK" || echo "Docker NOT running"

# Ports 8080, 8081, 4566 and 6379 must be free (stop any local Redis on 6379).
# Linux with Podman also installed: export DOCKER_HOST=unix:///var/run/docker.sock

# Clean up any existing containers
docker compose down -v 2>/dev/null
```

## Step 1: Build & Start Docker Stack

```bash
# Build fresh image and start all services
docker compose up -d --build

# Wait for app to become healthy (localstack + redis must be healthy first)
# App depends_on both with condition: service_healthy
# Typical healthy time: ~30 seconds after containers start
for i in $(seq 1 30); do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' drools-microservice-app-1 2>/dev/null)
  if [ "$STATUS" = "healthy" ]; then echo "App healthy after ~$((i*5))s"; break; fi
  sleep 5
  echo "  [$i] status=$STATUS"
done

docker compose ps
```

**Expected**: All 3 services (app, localstack, redis) show status "Up ... (healthy)".

**If app stays unhealthy**: Check logs with `docker compose logs --tail=50 app` for startup errors.

---

## Step 2: Health & Admin Endpoints

### 2.1 Health Check
```bash
curl -s http://localhost:8080/admin/health -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with keys `status`, `components`, `timestamp`. Status should be `"UP"`.
```json
{
  "status": "UP",
  "components": { ... },
  "timestamp": "..."
}
```

### 2.2 Admin Info
```bash
curl -s http://localhost:8080/admin/info -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with keys `application`, `java_version`, `version`, `timestamp`.

### 2.3 Thread Pool Status
```bash
curl -s http://localhost:8080/admin/thread-pools -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with keys `rule_execution_pool`, `storage_pool`, `timestamp`.

### 2.4 Memory Info
```bash
curl -s http://localhost:8080/admin/memory/info -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with `heap` object containing `usedMB`, `maxMB`, `usagePercent`.

### 2.5 Memory Snapshot
```bash
curl -s http://localhost:8080/admin/memory/snapshot -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with keys `heapUsedMB`, `heapMaxMB`, `heapUsagePercent`, `timestamp`.

### 2.6 Actuator Health (port 8081)
```bash
curl -s http://localhost:8081/actuator/health | jq .
```
**Expected**: JSON with `"status": "UP"`.

### 2.7 Actuator Metrics (port 8081)
```bash
curl -s http://localhost:8081/actuator/metrics | jq '.names | length'
```
**Expected**: Number > 0 (typically 70+ available metrics).

---

## Step 3: Rules Loading & Listing

### 3.1 List All Loaded Rules
```bash
curl -s http://localhost:8080/admin/rules -H "X-Admin-API-Key: admin-secret" | jq '.total_rules'
curl -s http://localhost:8080/admin/rules -H "X-Admin-API-Key: admin-secret" | jq '.rules[].rule_id'
```
**Expected**: `total_rules` = 17. Response is an object with keys `rules` (array), `total_rules`, `timestamp`. Each rule has:
```json
{
  "rule_id": "pricing.discount.simple",
  "status": "ACTIVE",
  "loaded_at": "...",
  "execution_count": 0,
  "version": "1.0"
}
```
> The `cached` field was dropped in the Redis cache layering work (2026-05-20). Cache state is now reported via `/admin/health` `components.cache` instead.

The 17 rule IDs should include the original 10 plus 7 added 2026-05-10:
- `pricing.discount.simple`, `vip`, `bulk`, `first-time`
- `pricing.shipping.standard`, `express`
- `seasonal.holiday.blackfriday`, `discount`
- `seasonal.expiry.temporal`
- `validation.customer.age`, `credit`
- `validation.cart.notempty`, `forall`
- `validation.email.compound`
- `inventory.warning.exists`
- `pricing.bundle.accumulate`
- `pricing.loyalty.salience`

### 3.2 Refresh All Rules
```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with `"status": "completed"`, `"rules_loaded": 17`, `"rules_failed": 0`.
```json
{
  "status": "completed",
  "rules_loaded": 17,
  "rules_failed": 0,
  "duration_ms": ...,
  "errors": []
}
```

### 3.3 Refresh Single Rule
```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules/pricing.discount.simple \
  -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with `"status": "success"`, `"rule_id": "pricing.discount.simple"`.
```json
{
  "rule_id": "pricing.discount.simple",
  "status": "success",
  "compilation_time_ms": ...,
  "error": null
}
```

### 3.4 Verify Rules Still Loaded After Refresh
```bash
curl -s http://localhost:8080/admin/rules -H "X-Admin-API-Key: admin-secret" | jq '.total_rules'
```
**Expected**: `17` rules still present.

---

## Step 4: Rule Execution (10 Representative Sample Rules — values checked)

Each `rule_id` runs **only the rules in its own DRL file** ([ADR-025](project-documentation/36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)). Rules do not stack: `pricing.discount.vip` gives 20% off and nothing else, even though `pricing.discount.simple` (10% on `amount >= 50`) would also match the same data. The other 7 rules (accumulate, exists, salience, temporal, forall, notempty, compound) are checked by `scripts/run-docker-test-plan.sh` (X1.x) and `RuleExecutionIntegrationTest`. X1.K checks that a forall request whose items have no `price` succeeds (it used to crash in the bundle rule), and X1.11 checks that `pricing.discount.simple` with VIP fields gives 90 only.

All responses follow:
```json
{ "rule_id": "...", "result": { ... }, "error": null, "execution_time_ms": ... }
```
**Pass criteria**: `error` is `null` **and** the `result` values match.

| # | Request `data` (`rule_id`) | Expected `result` |
|---|---|---|
| 4.1 | `{"amount":100}` (`pricing.discount.simple`) | `amount` 90.0, `discount` 10.0 |
| 4.2 | `{"customerType":"VIP","amount":100}` (`pricing.discount.vip`) | `amount` 80.0, `discount` 20.0 |
| 4.3 | `{"quantity":15,"amount":200}` (`pricing.discount.bulk`) | `amount` 170.0, `discount` 30.0 |
| 4.4 | `{"isFirstTimeCustomer":true,"amount":100}` (`pricing.discount.first-time`) | `amount` 95.0, `discount` 5.0 |
| 4.5 | `{"shippingType":"standard","weight":0.5}` (`pricing.shipping.standard`) | `shippingCost` 5.99, `estimatedDays` 5 |
| 4.6 | `{"shippingType":"express","weight":3,"amount":150}` (`pricing.shipping.express`) | `freeShipping` true, `shippingCost` 0.0 |
| 4.7 | `{"promotionCode":"BLACK2024","amount":200}` (`seasonal.holiday.blackfriday`) | `amount` 150.0, `promotionValid` true |
| 4.8 | `{"isHolidaySeason":true,"amount":100}` (`seasonal.holiday.discount`) | `amount` 88.0, `discount` 12.0 |
| 4.9 | `{"customerAge":15}` (`validation.customer.age`) | `validationResult` REJECTED, `eligible` false |
| 4.10 | `{"creditScore":720,"requestedAmount":10000}` (`validation.customer.credit`) | `creditTier` Good, `approvedAmount` 5000.0 |

Example:
```bash
curl -s -X POST http://localhost:8080/execute-rule -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.vip", "data": {"customerType": "VIP", "amount": 100}}' | jq .result.amount   # 80.0
```

---

## Step 5: Error Handling & Edge Cases

### 5.1 Non-existent Rule
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "nonexistent.rule", "data": {"test": true}}' | jq .
```
**Expected**: Error response with `error.code` = `"RULE_NOT_FOUND"` and `error.message` containing the rule ID.
```json
{
  "error": {
    "code": "RULE_NOT_FOUND",
    "message": "Rule not found: nonexistent.rule",
    ...
  }
}
```

### 5.2 Missing rule_id
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"data": {"test": true}}' | jq .
```
**Expected**: Error response with `error.code` = `"INVALID_INPUT"` and `error.details` mentioning "Rule ID cannot be null".

### 5.3 Empty Data
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {}}' | jq .
```
**Expected**: Success — `error` is `null`, `result` is `{}` (empty object, no crash). No rules fire on empty data.

### 5.4 Invalid JSON
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d 'not json' | jq .
```
**Expected**: HTTP 400 with `error.code` = `"INVALID_INPUT"` and message "Request body is not valid JSON" (malformed JSON has been handled as a client error since 2026-05-10).

### 5.5 Invalid Rule ID Format (Path Traversal)
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "../../../etc/passwd", "data": {}}' | jq .
```
**Expected**: Validation error with `error.code` = `"INVALID_INPUT"` and details mentioning "invalid characters".

---

## Step 6: Memory Stability (Quick Smoke Test)

```bash
HEAP_BEFORE=$(curl -s http://localhost:8080/admin/memory/info -H "X-Admin-API-Key: admin-secret" | jq '.heap.usedMB')
echo "Heap before: ${HEAP_BEFORE}MB"

for i in 1 2 3; do
  curl -s -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret" > /dev/null
  sleep 2
done

HEAP_AFTER=$(curl -s http://localhost:8080/admin/memory/info -H "X-Admin-API-Key: admin-secret" | jq '.heap.usedMB')
echo "Heap after 3 refreshes: ${HEAP_AFTER}MB"
```
**Expected**: Heap usage is stable and under 500MB. Should not grow by more than ~100MB per refresh cycle (temporary allocation is normal; each refresh swaps in new per-rule-ID KieBases and the old ones are garbage-collected once no request uses them).

---

## Step 7: GC Trigger

```bash
curl -s -X POST http://localhost:8080/admin/memory/gc -H "X-Admin-API-Key: admin-secret" | jq .
```
**Expected**: JSON with `"message": "Garbage collection triggered"`, plus `usedBeforeMB`, `usedAfterMB`, `freedMemoryMB`.
```json
{
  "message": "Garbage collection triggered",
  "usedBeforeMB": ...,
  "usedAfterMB": ...,
  "freedMemoryMB": ...,
  "note": "This is a suggestion to JVM, actual GC timing is not guaranteed"
}
```

---

## Step 8: Redis Cache Layer (REDIS_ENABLED=true)

The `docker-compose.yml` default has `REDIS_ENABLED=true` + `REDIS_PUBSUB_ENABLED=true`, so the stack
is already running in full mode. These checks verify the Redis decorator is wired correctly and the
cache actually populates / invalidates.

### 8.1 Health: cache + redis components reflect Redis mode

```bash
curl -s http://localhost:8080/admin/health -H "X-Admin-API-Key: admin-secret" | jq '.components.cache, .components.redis'
```
**Expected**: cache section shows `mode: "redis"` and `enabled: true`; redis section shows
`connected: true` and `cache_decorator: "RedisCachedRuleStorage"`.

### 8.2 Bean wiring (startup logs)

```bash
docker compose logs app 2>&1 | grep -E "RedisCachedRuleStorage|RuleRefreshPublisher|RuleRefreshSubscriber|pub/sub listener|Drools instance ID" | head -10
```
**Expected**: lines for `RedisCachedRuleStorage delegate set: S3RuleStorage`,
`RuleRefreshPublisher initialized`, `RuleRefreshSubscriber initialized`,
`Configuring Redis pub/sub listener on channel: drools:rule:events`, and a `Drools instance ID: <uuid>`.

### 8.3 Refresh populates Redis with DRL text

```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret" > /dev/null
docker exec drools-microservice-redis-1 redis-cli KEYS 'drools:rule:*' | wc -l
docker exec drools-microservice-redis-1 redis-cli KEYS 'drools:rule:*' | head -1 \
  | xargs -I {} docker exec drools-microservice-redis-1 redis-cli GET {} | head -c 200
```
**Expected**: key count = 17. Sample value is JSON containing `ruleId`, `content` (DRL text), `metadata`.

### 8.4 TTL set on cache entries

```bash
docker exec drools-microservice-redis-1 redis-cli KEYS 'drools:rule:*' | head -1 \
  | xargs -I {} docker exec drools-microservice-redis-1 redis-cli TTL {}
```
**Expected**: positive integer ≤ 900 (15 min default). Confirms TTL is applied.

### 8.5 Refresh invalidation

```bash
docker exec drools-microservice-redis-1 redis-cli SET drools:rule:stale '"GHOST"' > /dev/null
docker exec drools-microservice-redis-1 redis-cli GET drools:rule:stale
curl -s -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret" > /dev/null
docker exec drools-microservice-redis-1 redis-cli GET drools:rule:stale
docker exec drools-microservice-redis-1 redis-cli KEYS 'drools:rule:*' | wc -l
```
**Expected**: First GET returns `"GHOST"`. After refresh, GET returns `(nil)`. Key count is 17
(the stale key was wiped during `SCAN+DEL`, then 17 real rules re-populated).

### 8.6 Pub/sub event fires on single-rule refresh

```bash
# Subscribe to channel in a background container
docker exec -d drools-microservice-redis-1 sh -c 'redis-cli SUBSCRIBE drools:rule:events > /tmp/sub.log 2>&1 &'
sleep 1
curl -s -X POST "http://localhost:8080/admin/refresh-rules/pricing.discount.simple" \
  -H "X-Admin-API-Key: admin-secret" > /dev/null
sleep 1
docker exec drools-microservice-redis-1 cat /tmp/sub.log | head -10
```
**Expected**: log shows a `message` line with JSON containing
`"event":"RULE_REFRESHED"`, `"rule_id":"pricing.discount.simple"`, and a UUID
`source_instance_id`.

### 8.7 Cache hit/miss metrics

The single-rule refresh endpoint goes through `storage.getRule()` which exercises the Redis
cache directly. Call it twice for the same rule — the first call invalidates Redis (DEL) then
populates it (miss), the second populates again (miss). The bulk refresh endpoint exercises
`drools.cache.bulk.*` counters instead.

```bash
curl -s -X POST "http://localhost:8080/admin/refresh-rules/pricing.discount.simple" -H "X-Admin-API-Key: admin-secret" > /dev/null
curl -s -X POST "http://localhost:8080/admin/refresh-rules/pricing.discount.bulk" -H "X-Admin-API-Key: admin-secret" > /dev/null
curl -s http://localhost:8080/admin/health -H "X-Admin-API-Key: admin-secret" | jq '.components.cache.details.statistics'
```
**Expected**: `statistics` object with `hits`, `misses`, `hit_rate` keys. In a single-instance
setup, `hits` typically stays at 0 because execution reads from in-process `loadedRules` (not
Redis); `misses` grows on each refresh-by-id call (DEL → re-fetch).

> Hit growth is the multi-instance benefit: when ECS task A refreshes a rule, task B receives
> a pub/sub event and reads the freshly-populated key from Redis (=> hit). Verifying that
> requires two app containers, which is out of scope here — covered by
> `RedisPubSubIntegrationTest` and the load-test orchestrator.

---

## Step 9: Redis OFF mode (regression)

Verify the service still works correctly with Redis disabled — this is the default for dev/local
single-instance setups.

```bash
# Temporary override (the tracked docker-compose.yml is not modified)
cat > /tmp/redis-off.yml <<'YML'
services:
  app:
    environment:
      - REDIS_ENABLED=false
      - REDIS_PUBSUB_ENABLED=false
YML
docker compose -f docker-compose.yml -f /tmp/redis-off.yml up -d app
for i in $(seq 1 30); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health 2>/dev/null)
  if [ "$STATUS" = "200" ]; then echo "App UP"; break; fi; sleep 3
done
```

### 9.1 No Redis beans created
```bash
docker compose logs --tail=200 app 2>&1 | grep -cE "RedisCachedRuleStorage initialized|RuleRefreshPublisher initialized"
```
**Expected**: `0` — no Redis classes initialize when disabled.

### 9.2 Health cache section shows mode=off
```bash
curl -s http://localhost:8080/admin/health -H "X-Admin-API-Key: admin-secret" | jq '.components.cache, .components.redis'
```
**Expected**: cache section shows `mode: "off"`, `enabled: false`. redis section is `null`.

### 9.3 Refresh + execute still work
```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret" | jq '.rules_loaded'
curl -s -X POST http://localhost:8080/execute-rule -H "Content-Type: application/json" \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":150,"customer_tier":"gold"}}' | jq '{rule_id, error}'
```
**Expected**: `rules_loaded` = 17. Rule execution returns `error: null`.

### 9.4 Back to the default (Redis on)
```bash
docker compose up -d app
```
**Expected**: app recreated with the default `REDIS_ENABLED=true`; `components.cache.details.mode` is `"redis"` again.

---

## Step 10: Docker Logs Check

```bash
docker compose logs --tail=100 app 2>&1 | grep "ERROR" | grep -v "stack_trace" | head -10
```
**Expected**: 0 ERROR lines. Some WARN lines may be acceptable (e.g., initial connection retries during startup).

---

## Step 11: Cleanup

```bash
docker compose down

```
**Expected**: All containers stopped and removed cleanly.

---

## Step 12: Security & Robustness Hardening (2026-08-19)

Verifies the production-readiness fixes. Assumes the default compose stack (`ADMIN_API_KEY=admin-secret`).

### 12.1 Admin auth enforced (P1)
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/admin/refresh-rules            # expect 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret"  # expect 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: wrong"         # expect 401
```
**Expected**: 401 / 200 / 401. (Fail-closed: booting the `docker` profile with a blank `ADMIN_API_KEY` makes the container restart-loop — `IllegalStateException` at startup.)

### 12.2 Rate limiting keyed on IP, not spoofable headers (P2)
Rotating `X-Client-Id`/`X-API-Key` per request no longer resets the bucket — the per-IP limit still applies (covered by `RateLimitingFilterTest`; observable via repeated `/execute-rule` from one host).

### 12.3 Actuator port is loopback-only (P5)
```bash
docker compose port app 8081     # expect 127.0.0.1:8081  (not 0.0.0.0)
```

### 12.4 Actuator health survives Redis-disabled (P4)
```bash
# with REDIS_ENABLED=false (e.g. a docker-compose.override.yml), after the app is healthy:
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/actuator/health   # expect 200 (redis health indicator gated off)
```

### 12.5 Rule-execution timeout returns 408, service stays responsive (P3/P7)
Upload a rule whose consequence never returns (`then while(true){} end`, guarded on a sentinel field), refresh it, then:
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' -d '{"rule_id":"test.runaway.loop","data":{"__runaway__":true}}'   # expect 408 (~timeout), NOT a hang
```
A normal rule still returns 200 before and after. (Pool saturation returns 503 — `RuleExecutorTest`.)

### 12.7 Request-path bypass rejected (ADR-024, 2026-11-17)
```bash
for p in '/admin;x/health' '/%61dmin/health' '/admin/health;x'; do
  curl -s -o /dev/null -w "$p -> %{http_code}\n" "http://localhost:8080$p"   # expect 400 (no key sent)
done
curl -s -o /dev/null -w '%{http_code}\n' -X POST 'http://localhost:8080/%61dmin/memory/gc'   # expect 400
curl -s -o /dev/null -w '%{http_code}\n' -X POST 'http://localhost:8080/%65xecute-rule' \
  -H 'Content-Type: application/json' -d '{}'                                                  # expect 400
```
**Expected**: all 400 `INVALID_INPUT` ("Malformed request path"). Before the fix these reached the admin controller without a key (200).

### 12.6 RULE_DELETED propagates to the compiled corpus (S5)
```bash
docker exec drools-microservice-redis-1 redis-cli PUBLISH drools:rule:events \
  '{"event":"RULE_DELETED","rule_id":"pricing.discount.simple","source_instance_id":"foreign","timestamp":"2026-08-19T00:00:00Z"}'
sleep 2
curl -s http://localhost:8080/admin/rules -H "X-Admin-API-Key: admin-secret" | jq '.total_rules'   # expect 16 (rule removed)
curl -s -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret" > /dev/null  # restore → 17
```

---

## Pass/Fail Summary

| Step | Test | Pass Criteria | Result |
|------|------|---------------|--------|
| 1 | Docker stack starts | All 3 services healthy | |
| 2.1 | Health check | `status` = `"UP"` | |
| 2.2 | Admin info | Returns `application`, `java_version`, `version` | |
| 2.3 | Thread pools | Returns `rule_execution_pool`, `storage_pool` | |
| 2.4 | Memory info | Returns `heap.usedMB` > 0 | |
| 2.5 | Memory snapshot | Returns `heapUsedMB`, `heapMaxMB` | |
| 2.6 | Actuator health (8081) | `status` = `"UP"` | |
| 2.7 | Actuator metrics (8081) | `names` count > 0 | |
| 3.1 | List rules | `total_rules` = 17 | |
| 3.2 | Refresh all rules | `status` = `"completed"`, `rules_loaded` = 17 | |
| 3.3 | Refresh single rule | `status` = `"success"` | |
| 3.4 | Rules after refresh | `total_rules` still 17 | |
| 4.1 | Simple discount | `amount` 90.0 | |
| 4.2 | VIP discount | `amount` 80.0 | |
| 4.3 | Bulk discount | `amount` 170.0 | |
| 4.4 | First-time discount | `amount` 95.0 | |
| 4.5 | Standard shipping | `shippingCost` 5.99 | |
| 4.6 | Express shipping | free shipping (amount ≥ 100) | |
| 4.7 | Black Friday | `amount` 150.0, `promotionValid` true | |
| 4.8 | Holiday discount | `amount` 88.0 | |
| 4.9 | Age validation | 15 → REJECTED | |
| 4.10 | Credit validation | 720 → Good, 5000 | |
| 5.1 | Non-existent rule | `error.code` = `"RULE_NOT_FOUND"` | |
| 5.2 | Missing rule_id | `error.code` = `"INVALID_INPUT"` | |
| 5.3 | Empty data | `error` is null, `result` = `{}` | |
| 5.4 | Invalid JSON | HTTP 400, `error.code` = `"INVALID_INPUT"` | |
| 5.5 | Path traversal | `error.code` = `"INVALID_INPUT"` | |
| 6 | Memory stability | Heap < 500MB after 3 refreshes | |
| 7 | GC trigger | `message` = `"Garbage collection triggered"` | |
| 8.1 | Redis health | `mode: "redis"`, `cache_decorator: "RedisCachedRuleStorage"` | |
| 8.2 | Redis bean wiring (logs) | decorator + publisher + subscriber + listener present | |
| 8.3 | Refresh populates Redis | 17 keys at `drools:rule:*`; values are JSON `Rule` objects | |
| 8.4 | TTL applied | `TTL` returns positive ≤ 900s | |
| 8.5 | Refresh invalidation | foreign `drools:rule:stale` key wiped; only 17 real rules remain | |
| 8.6 | Pub/sub event fires | `redis-cli SUBSCRIBE` log shows `RULE_REFRESHED` JSON | |
| 8.7 | Cache hit/miss metrics | `statistics` object exists with `hits`/`misses`/`hit_rate`; `misses` grows on refresh-by-id | |
| 9.1 | Redis OFF: no Redis beans | `0` Redis init log lines | |
| 9.2 | Redis OFF: health mode=off | `cache.mode = "off"`, `redis` section is null | |
| 9.3 | Redis OFF: still functional | refresh + execute succeed | |
| 9.4 | Back to default | app recreated with Redis on (`mode: "redis"`) | |
| 10 | No errors in logs | 0 ERROR lines in last 100 log lines | |
| 11 | Clean shutdown | `docker compose down` exits cleanly | |
| 12.1 | Admin auth (P1) | no-key→401, key→200, wrong→401; blank-key docker profile fails to start | |
| 12.2 | Rate limit by IP (P2) | rotating headers don't reset the bucket | |
| 12.3 | Actuator loopback (P5) | `8081` bound to `127.0.0.1` | |
| 12.4 | Actuator w/ Redis off (P4) | `/actuator/health` = 200 when `REDIS_ENABLED=false` | |
| 12.5 | Exec timeout (P3/P7) | runaway rule → 408, service responsive; saturation → 503 | |
| 12.6 | RULE_DELETED (S5) | pub/sub delete removes rule (17→16), refresh restores 17 | |
| 12.7 | Path bypass (ADR-024) | `/admin;x/…`, `/%61dmin/…`, `/%65xecute-rule` → 400 | |

---

## Notes
- Port 6379 may conflict with a locally running Redis — stop it first
- App typically takes ~30 seconds to become healthy after container starts
- Testcontainers tests (`S3StorageIntegrationTest`, `RedisCachedStorageIntegrationTest`,
  `RedisPubSubIntegrationTest`) are permanently excluded from default `mvn test` runs via
  the surefire config in `pom.xml`. To run them, you need Java 25 + a native Docker daemon
  (Linux/CI). Docker Desktop on macOS proxies its socket and breaks Testcontainers from
  inside a dockerized Maven container.
- App port `8080` is published on all interfaces (dev access); actuator `8081`, LocalStack `4566` and Redis `6379` are bound to `127.0.0.1` only
- Admin endpoints require the `X-Admin-API-Key` header (`admin-secret` in docker-compose; required on the `docker`/`prod` profiles)
- GC log and heap dumps live on named volumes: `docker compose exec app tail -f /tmp/gc-logs/gc.log`, `docker compose cp app:/tmp/heap-dumps ./heap-dumps`
- New Redis env vars (2026-05-20): `REDIS_DRL_RULES_TTL_MINUTES`, `REDIS_DRL_RULES_KEY_PREFIX`,
  `REDIS_PUBSUB_ENABLED`, `REDIS_REFRESH_CHANNEL`. Deprecated: `REDIS_TTL_MINUTES`, `LRU_CACHE_MAX_SIZE`.
