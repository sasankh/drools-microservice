# Full Docker Integration Test Plan
**Purpose**: Verify all application functionality works end-to-end in Docker.
**Usage**: Tell Claude "run the full docker test plan from full-docker-test-plan.md"
**Last Validated**: 2026-08-19 (production-readiness fixes — see [`.ai-workspace/project-plans/production-readiness-fixes-checklist.md`](.ai-workspace/project-plans/production-readiness-fixes-checklist.md))

> ⚠️ **Admin auth is now enforced.** As of the 2026-08-19 hardening, **all `/admin/*` endpoints
> require the `X-Admin-API-Key` header** (default `admin-secret`, set in `docker-compose.yml`). Add
> `-H "X-Admin-API-Key: admin-secret"` to every `/admin/*` curl below — without it they return `401`.
> In the `docker`/`prod` profiles the app **fails to start** if `ADMIN_API_KEY` is blank.

---

## Prerequisites

```bash
# Ensure Docker is running
docker info > /dev/null 2>&1 && echo "Docker OK" || echo "Docker NOT running"

# Stop local Redis if running (conflicts with Docker Redis on port 6379)
brew services stop redis 2>/dev/null || true

# Clean up any existing containers
docker-compose down -v 2>/dev/null
```

## Step 1: Build & Start Docker Stack

```bash
# Build fresh image and start all services
docker-compose up -d --build

# Wait for app to become healthy (localstack + redis must be healthy first)
# App depends_on both with condition: service_healthy
# Typical healthy time: ~30 seconds after containers start
for i in $(seq 1 30); do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' drools-microservice-app-1 2>/dev/null)
  if [ "$STATUS" = "healthy" ]; then echo "App healthy after ~$((i*5))s"; break; fi
  sleep 5
  echo "  [$i] status=$STATUS"
done

docker-compose ps
```

**Expected**: All 3 services (app, localstack, redis) show status "Up ... (healthy)".

**If app stays unhealthy**: Check logs with `docker-compose logs --tail=50 app` for startup errors.

---

## Step 2: Health & Admin Endpoints

### 2.1 Health Check
```bash
curl -s http://localhost:8080/admin/health | jq .
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
curl -s http://localhost:8080/admin/info | jq .
```
**Expected**: JSON with keys `application`, `java_version`, `version`, `timestamp`.

### 2.3 Thread Pool Status
```bash
curl -s http://localhost:8080/admin/thread-pools | jq .
```
**Expected**: JSON with keys `rule_execution_pool`, `storage_pool`, `timestamp`.

### 2.4 Memory Info
```bash
curl -s http://localhost:8080/admin/memory/info | jq .
```
**Expected**: JSON with `heap` object containing `usedMB`, `maxMB`, `usagePercent`.

### 2.5 Memory Snapshot
```bash
curl -s http://localhost:8080/admin/memory/snapshot | jq .
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
curl -s http://localhost:8080/admin/rules | jq '.total_rules'
curl -s http://localhost:8080/admin/rules | jq '.rules[].rule_id'
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
curl -s http://localhost:8080/admin/rules | jq '.total_rules'
```
**Expected**: `17` rules still present.

---

## Step 4: Rule Execution (10 Representative Sample Rules)

> The 7 rules added 2026-05-10 (accumulate, exists, salience, temporal, forall, notempty,
> compound) are covered by `RuleExecutionIntegrationTest` and the load-test orchestrator
> — they are not re-exercised here.

All rule execution responses follow this format:
```json
{
  "rule_id": "...",
  "result": { ... },
  "error": null,
  "execution_time_ms": ...
}
```
**Pass criteria**: `error` is `null` and `result` is a non-null object.

### 4.1 Simple Discount
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100}}' | jq .
```
**Expected**: `result` contains `discount`, `discountPercent`, `discountReason`. Amount modified.

### 4.2 VIP Discount
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.vip", "data": {"customerType": "VIP", "amount": 200}}' | jq .
```
**Expected**: `result` contains VIP-specific discount fields.

### 4.3 Bulk Discount
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.bulk", "data": {"quantity": 50, "unitPrice": 10}}' | jq .
```
**Expected**: `result` contains `quantity` and `unitPrice` (rule may add discount fields if conditions met).

### 4.4 First-Time Discount
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.first-time", "data": {"isFirstOrder": true, "amount": 100}}' | jq .
```
**Expected**: `result` contains `discount`, `discountPercent`, `discountReason` for first-time customer.

### 4.5 Standard Shipping
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.shipping.standard", "data": {"weight": 5, "distance": 100}}' | jq .
```
**Expected**: `result` contains `weight` and `distance` (rule may add shipping cost fields).

### 4.6 Express Shipping
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.shipping.express", "data": {"weight": 5, "distance": 100}}' | jq .
```
**Expected**: `result` contains `weight` and `distance` (rule may add express shipping fields).

### 4.7 Black Friday
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "seasonal.holiday.blackfriday", "data": {"amount": 200, "category": "electronics"}}' | jq .
```
**Expected**: `result` contains `discount`, `discountPercent`, `discountReason` with Black Friday discount.

### 4.8 Holiday Discount
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "seasonal.holiday.discount", "data": {"amount": 150, "holiday": "christmas"}}' | jq .
```
**Expected**: `result` contains holiday discount fields.

### 4.9 Age Validation
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "validation.customer.age", "data": {"age": 25, "product": "alcohol"}}' | jq .
```
**Expected**: `result` contains `age` and `product` (rule may add validation result fields).

### 4.10 Credit Validation
```bash
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "validation.customer.credit", "data": {"creditScore": 750, "requestedAmount": 5000}}' | jq .
```
**Expected**: `result` contains `creditScore` and `requestedAmount` (rule may add approval fields).

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
**Expected**: Error response with `error.code` = `"INTERNAL_ERROR"`.

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
HEAP_BEFORE=$(curl -s http://localhost:8080/admin/memory/info | jq '.heap.usedMB')
echo "Heap before: ${HEAP_BEFORE}MB"

for i in 1 2 3; do
  curl -s -X POST http://localhost:8080/admin/refresh-rules > /dev/null
  sleep 2
done

HEAP_AFTER=$(curl -s http://localhost:8080/admin/memory/info | jq '.heap.usedMB')
echo "Heap after 3 refreshes: ${HEAP_AFTER}MB"
```
**Expected**: Heap usage is stable and under 500MB. Should not grow by more than ~100MB per refresh cycle (temporary allocation is normal; KieContainer disposal prevents leaks).

---

## Step 7: GC Trigger

```bash
curl -s -X POST http://localhost:8080/admin/memory/gc | jq .
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
curl -s http://localhost:8080/admin/health | jq '.components.cache, .components.redis'
```
**Expected**: cache section shows `mode: "redis"` and `enabled: true`; redis section shows
`connected: true` and `cache_decorator: "RedisCachedRuleStorage"`.

### 8.2 Bean wiring (startup logs)

```bash
docker-compose logs --tail=200 app 2>&1 | grep -E "RedisCachedRuleStorage|RuleRefreshPublisher|RuleRefreshSubscriber|pub/sub listener|Drools instance ID" | head -10
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
curl -s http://localhost:8080/admin/health | jq '.components.cache.details.statistics'
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
# Patch compose to REDIS_ENABLED=false (temporary; reverted at the end)
sed -i.bak 's/- REDIS_ENABLED=true/- REDIS_ENABLED=false/' docker-compose.yml
docker-compose stop app && docker-compose up -d app
for i in $(seq 1 30); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health 2>/dev/null)
  if [ "$STATUS" = "200" ]; then echo "App UP"; break; fi; sleep 3
done
```

### 9.1 No Redis beans created
```bash
docker-compose logs --tail=200 app 2>&1 | grep -cE "RedisCachedRuleStorage initialized|RuleRefreshPublisher initialized"
```
**Expected**: `0` — no Redis classes initialize when disabled.

### 9.2 Health cache section shows mode=off
```bash
curl -s http://localhost:8080/admin/health | jq '.components.cache, .components.redis'
```
**Expected**: cache section shows `mode: "off"`, `enabled: false`. redis section is `null`.

### 9.3 Refresh + execute still work
```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules -H "X-Admin-API-Key: admin-secret" | jq '.rules_loaded'
curl -s -X POST http://localhost:8080/execute-rule -H "Content-Type: application/json" \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":150,"customer_tier":"gold"}}' | jq '{rule_id, error}'
```
**Expected**: `rules_loaded` = 17. Rule execution returns `error: null`.

### 9.4 Restore docker-compose
```bash
mv docker-compose.yml.bak docker-compose.yml
docker-compose stop app && docker-compose up -d app
```
**Expected**: `docker-compose.yml` restored to `REDIS_ENABLED=true` default.

---

## Step 10: Docker Logs Check

```bash
docker-compose logs --tail=100 app 2>&1 | grep "ERROR" | grep -v "stack_trace" | head -10
```
**Expected**: 0 ERROR lines. Some WARN lines may be acceptable (e.g., initial connection retries during startup).

---

## Step 11: Cleanup

```bash
docker-compose down

# Restart local Redis if it was running before testing
# brew services start redis
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
| 4.1 | Simple discount | `error` is null, `result` has discount fields | |
| 4.2 | VIP discount | `error` is null, `result` has discount fields | |
| 4.3 | Bulk discount | `error` is null, `result` is non-null | |
| 4.4 | First-time discount | `error` is null, `result` has discount fields | |
| 4.5 | Standard shipping | `error` is null, `result` is non-null | |
| 4.6 | Express shipping | `error` is null, `result` is non-null | |
| 4.7 | Black Friday | `error` is null, `result` has discount fields | |
| 4.8 | Holiday discount | `error` is null, `result` has discount fields | |
| 4.9 | Age validation | `error` is null, `result` is non-null | |
| 4.10 | Credit validation | `error` is null, `result` is non-null | |
| 5.1 | Non-existent rule | `error.code` = `"RULE_NOT_FOUND"` | |
| 5.2 | Missing rule_id | `error.code` = `"INVALID_INPUT"` | |
| 5.3 | Empty data | `error` is null, `result` = `{}` | |
| 5.4 | Invalid JSON | `error.code` = `"INTERNAL_ERROR"` | |
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
| 9.4 | Compose restored | `REDIS_ENABLED=true` back in `docker-compose.yml` | |
| 10 | No errors in logs | 0 ERROR lines in last 100 log lines | |
| 11 | Clean shutdown | `docker-compose down` exits cleanly | |
| 12.1 | Admin auth (P1) | no-key→401, key→200, wrong→401; blank-key docker profile fails to start | |
| 12.2 | Rate limit by IP (P2) | rotating headers don't reset the bucket | |
| 12.3 | Actuator loopback (P5) | `8081` bound to `127.0.0.1` | |
| 12.4 | Actuator w/ Redis off (P4) | `/actuator/health` = 200 when `REDIS_ENABLED=false` | |
| 12.5 | Exec timeout (P3/P7) | runaway rule → 408, service responsive; saturation → 503 | |
| 12.6 | RULE_DELETED (S5) | pub/sub delete removes rule (17→16), refresh restores 17 | |

---

## Notes
- Port 6379 may conflict with a locally running Redis — stop it first with `brew services stop redis`
- App typically takes ~30 seconds to become healthy after container starts
- Testcontainers tests (`S3StorageIntegrationTest`, `RedisCachedStorageIntegrationTest`,
  `RedisPubSubIntegrationTest`) are permanently excluded from default `mvn test` runs via
  the surefire config in `pom.xml`. To run them, you need Java 25 + a native Docker daemon
  (Linux/CI). Docker Desktop on macOS proxies its socket and breaks Testcontainers from
  inside a dockerized Maven container.
- App ports `8080` and `8081` are published to all interfaces (needed for dev access); infrastructure ports (LocalStack 4566, Redis 6379) are bound to `127.0.0.1` only
- Admin endpoints require the `X-Admin-API-Key` header (default `admin-secret` for dev/local)
- New Redis env vars (2026-05-20): `REDIS_DRL_RULES_TTL_MINUTES`, `REDIS_DRL_RULES_KEY_PREFIX`,
  `REDIS_PUBSUB_ENABLED`, `REDIS_REFRESH_CHANNEL`. Deprecated: `REDIS_TTL_MINUTES`, `LRU_CACHE_MAX_SIZE`.
