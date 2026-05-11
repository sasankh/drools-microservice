# Full Docker Integration Test Plan
**Purpose**: Verify all application functionality works end-to-end in Docker.
**Usage**: Tell Claude "run the full docker test plan from full-docker-test-plan.md"
**Last Validated**: 2026-05-09 (full e2e on Java 25 + Spring Boot 3.5.3 + Drools 10.2.0 stack — see [`.ai-workspace/project-plans/e2e-validation-checklist.md`](.ai-workspace/project-plans/e2e-validation-checklist.md))

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
**Expected**: `total_rules` = 10. Response is an object with keys `rules` (array), `total_rules`, `timestamp`. Each rule has:
```json
{
  "rule_id": "pricing.discount.simple",
  "status": "ACTIVE",
  "loaded_at": "...",
  "execution_count": 0,
  "cached": true,
  "version": "1.0"
}
```
The 10 rule IDs should be:
- `pricing.discount.simple`
- `pricing.discount.vip`
- `pricing.discount.bulk`
- `pricing.discount.first-time`
- `pricing.shipping.standard`
- `pricing.shipping.express`
- `seasonal.holiday.blackfriday`
- `seasonal.holiday.discount`
- `validation.customer.age`
- `validation.customer.credit`

### 3.2 Refresh All Rules
```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules | jq .
```
**Expected**: JSON with `"status": "completed"`, `"rules_loaded": 10`, `"rules_failed": 0`.
```json
{
  "status": "completed",
  "rules_loaded": 10,
  "rules_failed": 0,
  "duration_ms": ...,
  "errors": []
}
```

### 3.3 Refresh Single Rule
```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules/pricing.discount.simple | jq .
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
**Expected**: `10` rules still present.

---

## Step 4: Rule Execution (All 10 Sample Rules)

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

## Step 8: Docker Logs Check

```bash
docker-compose logs --tail=100 app 2>&1 | grep "ERROR" | grep -v "stack_trace" | head -10
```
**Expected**: 0 ERROR lines. Some WARN lines may be acceptable (e.g., initial connection retries during startup).

---

## Step 9: Cleanup

```bash
docker-compose down

# Restart local Redis if it was running before testing
# brew services start redis
```
**Expected**: All containers stopped and removed cleanly.

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
| 3.1 | List rules | `total_rules` = 10 | |
| 3.2 | Refresh all rules | `status` = `"completed"`, `rules_loaded` = 10 | |
| 3.3 | Refresh single rule | `status` = `"success"` | |
| 3.4 | Rules after refresh | `total_rules` still 10 | |
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
| 8 | No errors in logs | 0 ERROR lines in last 100 log lines | |
| 9 | Clean shutdown | `docker-compose down` exits cleanly | |

---

## Notes
- Port 6379 may conflict with a locally running Redis — stop it first with `brew services stop redis`
- App typically takes ~30 seconds to become healthy after container starts
- The `S3StorageIntegrationTest` unit test error (when running `mvn test` locally) is expected without LocalStack running — it is NOT related to Docker testing
- App ports `8080` and `8081` are published to all interfaces (needed for dev access); infrastructure ports (LocalStack 4566, Redis 6379) are bound to `127.0.0.1` only
