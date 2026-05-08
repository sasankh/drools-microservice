# Docker Race Condition Fix & Validation

**Date**: 2026-02-20
**Session**: 11

## Problem

App container started before LocalStack finished initializing (creating S3 bucket + uploading .drl rules). This meant `/admin/rules` returned 0 rules on first startup, requiring a manual `POST /admin/refresh-rules` after waiting for LocalStack.

## Fix

Two changes to `docker-compose.yml`:

### 1. LocalStack healthcheck (new)
```yaml
localstack:
  healthcheck:
    test: ["CMD", "bash", "-c", "awslocal s3 ls s3://local-rules/ --recursive 2>/dev/null | grep -q .drl"]
    interval: 5s
    timeout: 5s
    retries: 30
    start_period: 10s
```
Checks that `.drl` files exist in the bucket — meaning `init-localstack.sh` has completed.

### 2. App depends_on with condition (changed)
```yaml
app:
  depends_on:
    localstack:
      condition: service_healthy
    redis:
      condition: service_healthy
```
App won't start until both LocalStack (rules uploaded) and Redis (responding to pings) are healthy.

## Gotcha

First attempt used `awslocal s3 ls s3://local-rules/` without `--recursive`. This only returns directory prefixes (`pricing/`, `seasonal/`, `validation/`), not `.drl` files. The `--recursive` flag is required to list files in subdirectories.

## Scope

This fix is **local dev only** (docker-compose with LocalStack). In production, the S3 bucket already exists — no race condition.

## Validation Results

### Startup Sequence (verified)
```
LocalStack  Waiting
Redis       Waiting
Redis       Healthy
LocalStack  Healthy       <-- init script finished, .drl files in S3
App         Starting      <-- app starts AFTER both healthy
App         Healthy
```

### Endpoint Tests (all pass)
| Endpoint | Result |
|----------|--------|
| GET /admin/health | UP (5 components) |
| GET /admin/rules | 10 rules loaded |
| GET /admin/memory/info | Heap 20.7%, 0 warnings |
| GET /admin/thread-pools | Pools ready |
| GET /actuator/health (8081) | UP |
| POST /execute-rule | Working (1-11ms) |
| POST /admin/refresh-rules | 10 rules, 0 failures |

### 3000 Refresh Load Test
- All 3000 returned HTTP 200, zero failures
- 10 concurrent refreshes per wave, 30 batches
- Duration: ~3 minutes
- Memory: 73MB initial -> 437MB peak -> 424MB final (20.7%)
- GC Old Gen: 0 collections (no memory leak)

### Rule Execution (all 10 rules)
| Rule | Input | Result |
|------|-------|--------|
| pricing.discount.simple | amount=100 | 10% off -> $90 |
| pricing.discount.vip | VIP, amount=200 | discount -> $144 |
| pricing.discount.bulk | qty=100, amount=500 | 15% off -> $382.50 |
| pricing.discount.first-time | firstTime, amount=150 | discount -> $128.25 |
| pricing.shipping.standard | weight=5, dist=100 | executed |
| pricing.shipping.express | weight=3, dist=50 | executed |
| seasonal.holiday.discount | holiday, amount=200 | discount -> $180 |
| seasonal.holiday.blackfriday | blackFriday, amount=300 | discount -> $270 |
| validation.customer.age | age=25 | validated |
| validation.customer.credit | score=750 | validated |

## Files Modified
- `docker-compose.yml` — Added LocalStack healthcheck, changed app depends_on to use `condition: service_healthy`
