# 29 · Circuit Breakers and Resilience

| | |
|---|---|
| **Audience** | Operators, on-call engineers, developers debugging external-dependency failures |
| **Purpose** | Complete behavior of the two Resilience4j circuit breakers (S3 and Redis) — how they trip, what they protect, what state transitions look like, how to recover |
| **Last verified against** | [`CircuitBreakerConfig.java`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java), [`S3RuleStorage.java`](../src/main/java/com/company/drools/storage/S3RuleStorage.java), [`RedisCachedRuleStorage.java`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java), [`RuleRefreshPublisher.java`](../src/main/java/com/company/drools/cache/RuleRefreshPublisher.java) on 2026-05-20 |
| **Related docs** | [09-environment-variables-reference.md](09-environment-variables-reference.md), [12-error-code-catalog.md](12-error-code-catalog.md), [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md), [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) |

---

## TL;DR

Two circuit breakers wrap calls to external dependencies:

- **`s3CircuitBreaker`** wraps every call into `S3RuleStorage` (`getRule`, `getAllRules`, etc.)
- **`redisCircuitBreaker`** wraps every Redis op in `RedisCachedRuleStorage` (get / set / DEL / SCAN / MGET / EXISTS) and in `RuleRefreshPublisher.publish()` (`convertAndSend`)

They use the standard 3-state machine (CLOSED → OPEN → HALF_OPEN → CLOSED). When OPEN, calls return `CallNotPermittedException` immediately, which the service maps to **HTTP 503 `SERVICE_UNAVAILABLE`**.

**Key trigger conditions** (defaults, configurable per-profile):
- S3 trips at **50% failures** in a 100-call sliding window (40% in `prod`, 60% in `dev`).
- Redis trips at **60% failures** in a 50-call sliding window (50% in `prod`, 70% in `dev`).
- Both also trip on **slow calls**: S3 at 70%/5s, Redis at 80%/2s.

Recovery is automatic. After the wait duration (S3: 60s, Redis: 30s), the breaker transitions to HALF_OPEN and tests with a few real calls. If they succeed, breaker closes; if they fail, it re-opens.

---

## Why circuit breakers exist

Without them, a slow or failing dependency drags the whole service down. If S3 is taking 30 seconds to respond and 50 RPS arrive, after 30 seconds you have 1500 threads blocked on S3 — thread pool exhausted, no requests can complete, the service is effectively offline.

The circuit breaker says: **after a threshold of failures, stop calling the broken dependency entirely.** Fast-fail with HTTP 503 instead of timing out. The dependency gets time to recover (it's not being pounded), the service stays responsive, and clients get clear "try again later" signals instead of timeouts.

---

## State machine

```
              ┌──────────────────────┐
              │       CLOSED         │
              │  (normal operation;  │
              │   failures counted   │
              │   in sliding window) │
              └─────────┬────────────┘
                        │
        Failure rate ≥ threshold
        (or slow call rate ≥ threshold)
                        │
                        ▼
              ┌──────────────────────┐
              │        OPEN          │
              │  (all calls fast-    │
              │   fail with          │
              │   CallNotPermitted)  │
              └─────────┬────────────┘
                        │
            wait-duration elapsed
            (auto-transition: ENABLED)
                        │
                        ▼
              ┌──────────────────────┐
              │     HALF_OPEN        │
              │  (N test calls       │
              │   permitted; result  │
              │   determines next    │
              │   state)             │
              └─┬─────────────────┬──┘
       N successes         N failures
                │                 │
                ▼                 ▼
              CLOSED            OPEN
```

**Permitted calls in HALF_OPEN**:
- S3: 5 test calls
- Redis: 3 test calls

If all succeed, the breaker closes. If any threshold is breached, it re-opens.

---

## What's wrapped

### S3 circuit breaker — `s3CircuitBreaker`

[`S3RuleStorage.java:62-90, 135-142`](../src/main/java/com/company/drools/storage/S3RuleStorage.java) wraps two methods:

```java
// getRule(ruleId)  — fetches a single rule's content
Supplier<Optional<Rule>> s3Operation =
    CircuitBreaker.decorateSupplier(s3CircuitBreaker, () -> { ... });
return s3Operation.get();

// getAllRules()  — list all rules in the bucket
Supplier<List<Rule>> s3Operation =
    CircuitBreaker.decorateSupplier(s3CircuitBreaker, this::loadAllRulesFromS3);
return s3Operation.get();
```

When the breaker is OPEN:
- The decorated supplier doesn't run.
- A `CallNotPermittedException` is thrown immediately.
- `S3RuleStorage` catches it, logs `S3 Circuit breaker call not permitted`, and rethrows as a project `CircuitBreakerException`.
- `GlobalExceptionHandler` maps that to HTTP **503 SERVICE_UNAVAILABLE**.

### Redis circuit breaker — `redisCircuitBreaker`

[`RedisCachedRuleStorage.java`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) and [`RuleRefreshPublisher.java`](../src/main/java/com/company/drools/cache/RuleRefreshPublisher.java) wrap every Redis op in the breaker — GET, SET, DEL, HASKEY, MGET, batch-DEL, **and SCAN** (the last added 2026-05-24 after a Phase 9.4 audit found SCAN was bypassing the CB, leaving bulk-path failures uncounted in the sliding window). On open breaker, reads silently fall through to the delegate (S3 / local file / in-memory); writes are silently dropped (best-effort). Publisher emits a WARN log and increments `drools.refresh.failed{layer=publisher,reason=circuit_breaker_open}` but does NOT propagate — the local refresh has already succeeded. **The service degrades gracefully** to direct base-storage reads when Redis is unavailable.

This is a deliberate difference: S3 outage = user-visible 503 (because S3 is the source of truth). Redis outage = silent degradation (because Redis is just acceleration).

---

## Configuration

All values are configurable via env vars (see [09-environment-variables-reference.md](09-environment-variables-reference.md)).

### S3 breaker

| Property | Default | Profile overrides |
|---|---:|---|
| `failureRateThreshold` (%) | 50 | `prod`: 40 (stricter), `dev`: 60, `docker`: 50 |
| `waitDurationInOpenState` (ms) | 60,000 (60s) | `prod`: 120,000 (2 min), `dev`: 30,000 (30s) |
| `slidingWindowSize` (calls) | 100 | `prod`: 200, `dev`: 50, `docker`: 75 |
| `minimumNumberOfCalls` | 10 | `prod`: 20, `dev`: 5, `docker`: 8 |
| `slowCallRateThreshold` (%) | **70** (hardcoded) | — |
| `slowCallDurationThreshold` | **5s** (hardcoded) | — |
| `permittedNumberOfCallsInHalfOpenState` | **5** (hardcoded) | — |
| Sliding window type | `COUNT_BASED` (hardcoded) | — |

### Redis breaker

| Property | Default | Profile overrides |
|---|---:|---|
| `failureRateThreshold` (%) | 60 | `prod`: 50, `dev`: 70 (looser), `docker`: 65 |
| `waitDurationInOpenState` (ms) | 30,000 (30s) | `prod`: 60,000, `dev`: 15,000 |
| `slidingWindowSize` | 50 | `prod`: 100, `dev`: 25, `docker`: 40 |
| `minimumNumberOfCalls` | 5 | `prod`: 10, `dev`: 3, `docker`: 5 |
| `slowCallRateThreshold` (%) | **80** (hardcoded) | — |
| `slowCallDurationThreshold` | **2s** (hardcoded) | — |
| `permittedNumberOfCallsInHalfOpenState` | **3** (hardcoded) | — |

> **Note**: slow-call thresholds and half-open permitted calls are hardcoded in [`CircuitBreakerConfig.java`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java). They are not exposed as env vars. If you need to tune these in production, it requires a code change.

---

## Failure rate vs slow call rate

Both breakers trip on **either** condition independently:

### Failure rate threshold

A "failure" means an exception was thrown. Specifically:

**S3 records as failures** ([`CircuitBreakerConfig.java:73-76`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java#L73-L76)):
- `software.amazon.awssdk.core.exception.SdkException`
- `java.util.concurrent.TimeoutException`
- `java.io.IOException`

**S3 ignores** ([`CircuitBreakerConfig.java:77-80`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java#L77-L80)):
- `NoSuchKeyException` — a missing rule is a **normal** outcome, not a service failure. Critical: this means a rule that doesn't exist in S3 doesn't trip the breaker.

**Redis records as failures** ([`CircuitBreakerConfig.java:125-129`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java#L125-L129)):
- `RedisConnectionFailureException`
- `RedisSystemException`
- `TimeoutException`
- `ConnectException`

Other exception types don't count toward the breaker (they propagate normally to error handlers).

### Slow call rate threshold

A "slow call" is one that took longer than the configured `slowCallDurationThreshold`:
- S3: any call > 5 seconds is a slow call. If 70% of recent calls were slow, the breaker trips.
- Redis: any call > 2 seconds. Threshold 80%.

This catches "the dependency is technically responding, just unbearably slow" — which is often worse than outright failure. Without this, a degraded but-not-failed S3 would let calls pile up indefinitely.

### Sliding window

Both breakers use a `COUNT_BASED` window (not time-based). The most recent N calls are considered, regardless of how long ago they happened.

For S3: the most recent 100 calls (or 200 in `prod`). At least 10 must have been made before failure rate is computed (otherwise 1 failure out of 1 call would trip immediately).

### Lettuce timeout vs CB slow-call threshold

The Redis CB's `slowCallDurationThreshold` (2s, hardcoded) interacts with the **Lettuce command timeout** (`spring.data.redis.timeout`, env var `REDIS_TIMEOUT`, default `500ms`). The two are intentionally decoupled:

- A Lettuce command that takes **< 500ms** → completes normally (no failure, no slow-call count).
- A Lettuce command that hits the **500ms timeout** → throws `RedisCommandTimeoutException` (wrapped as `QueryTimeoutException`, a subtype of `RedisSystemException` — in the CB's `recordExceptions`) → counted as a **failure** by the CB.
- A pre-existing `2000ms` Lettuce timeout (before 2026-05-24) sat at an exact match with the 2s slow-call threshold, putting timeouts in an ambiguous classification window and delaying CB engagement. The Phase 9.4 load test surfaced this — the CB never opened during a 60s Redis outage. The 500ms default cleanly separates the two so Lettuce timeouts unambiguously count as CB failures.

**Operator note**: if production sees `RedisCommandTimeoutException` storms after rollout (e.g., distant or high-latency Redis), raise `REDIS_TIMEOUT` to 1000–1500ms via env var. Keep it below the 2s slow-call threshold to preserve the failures-vs-slow-calls separation.

### Pub/sub listener recovery backoff (related Redis resilience config)

Adjacent to the CB, [`RedisConfig.redisMessageListenerContainer`](../src/main/java/com/company/drools/config/RedisConfig.java) configures an explicit `FixedBackOff(2_000L, Long.MAX_VALUE)` on the dedicated Lettuce pub/sub connection. When Redis dies and is later restored, the listener container re-subscribes within ≤2s of Redis becoming reachable. This is **separate from the CB** — the CB controls which Redis ops execute; the recovery backoff controls when the pub/sub subscription itself reconnects after a transport-level disconnect. Both matter:

- **CB-only fix**: would let normal Redis ops fail-fast while Redis is down, but a subscription dropped at TCP level might never come back without explicit reconnect logic.
- **Recovery-backoff-only fix**: would re-subscribe quickly but admin/refresh ops would still hang on the unbroken CB until enough failures accumulate.

Added 2026-05-24 as a Phase 9.4 follow-up (see [39-load-test-findings.md](39-load-test-findings.md) Phase 9.4 addendum).

---

## Triggering and recovery — concrete examples

### Scenario: S3 returns 5xx for 30 seconds

1. **CLOSED, normal**. Calls succeed, failures < 50%.
2. S3 starts returning 5xx (transient outage). Each failed call is recorded.
3. After ≥ 10 calls in the window AND failures ≥ 50%, breaker → **OPEN**.
4. Log line: `INFO S3 Circuit breaker state transition: CLOSED -> OPEN`
5. For the next 60 seconds (default `waitDurationInOpenState`):
   - All calls into `S3RuleStorage` fast-fail with `CallNotPermittedException`.
   - HTTP responses are 503 `SERVICE_UNAVAILABLE`.
   - Log line on each rejection: `WARN S3 Circuit breaker call not permitted`.
6. After 60 seconds, breaker auto-transitions → **HALF_OPEN** (because `automaticTransitionFromOpenToHalfOpenEnabled = true`).
7. Up to 5 test calls are permitted. They go to S3 for real.
   - If S3 is healthy now: failure rate over the test calls is below threshold → **CLOSED**.
     - Log: `INFO S3 Circuit breaker state transition: HALF_OPEN -> CLOSED`
   - If S3 is still broken: → **OPEN** again, 60s wait restarts.
     - Log: `INFO S3 Circuit breaker state transition: HALF_OPEN -> OPEN`

### Scenario: Redis is slow but not failing

1. Redis responses are slow (> 2 seconds each) due to network or pressure.
2. After 5+ calls in the window AND 80%+ are "slow" → breaker → **OPEN**.
3. For 30 seconds, Redis cache calls return immediately as if it were a miss.
4. Service continues serving requests via direct base storage (S3/file/memory). **No user-visible errors.**
5. After 30s, HALF_OPEN with 3 test calls. If Redis recovered → CLOSED.

### Scenario: S3 NoSuchKeyException (missing rule)

This is **NOT** a circuit breaker event. The exception is in the `ignoreExceptions` list. Returns a normal 404 `RULE_NOT_FOUND` to the client.

---

## Observability

### State in `/admin/health`

```bash
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  | jq '.components."circuit-breakers"'
```

Output:
```json
{
  "status": "UP",
  "details": {
    "circuit_breakers_enabled": true,
    "s3_state": "CLOSED",
    "s3_metrics": {
      "successful_calls": 142,
      "failed_calls": 0,
      "not_permitted_calls": 0,
      "failure_rate": -1.0
    },
    "redis_state": "CLOSED",
    "redis_metrics": {
      "successful_calls": 28,
      "failed_calls": 0,
      "not_permitted_calls": 0,
      "failure_rate": -1.0
    }
  }
}
```

`failure_rate: -1.0` means "not enough calls in the window yet to compute" — that's expected at startup or after a period of low traffic.

`not_permitted_calls` increments on every call rejected because the breaker is OPEN — useful for "how much fast-failure did the breaker save us".

### Micrometer metrics

The breakers are wired to the meter registry via `TaggedCircuitBreakerMetrics` ([`CircuitBreakerConfig.java:53`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java#L53)). Metric names:

- `resilience4j.circuitbreaker.state` (gauge per state, tagged by `name=s3` / `name=redis`)
- `resilience4j.circuitbreaker.calls` (counter per outcome: `successful`, `failed`, `not_permitted`, `ignored`)
- `resilience4j.circuitbreaker.failure.rate` (gauge)
- `resilience4j.circuitbreaker.slow.call.rate` (gauge)
- `resilience4j.circuitbreaker.buffered.calls` (gauge — current sliding window contents)

Available at `GET /actuator/metrics/resilience4j.circuitbreaker.calls?tag=name:s3,kind:successful`.

### Logs

Every state transition is logged at INFO:
```
2026-05-10 12:34:56.789 INFO  S3 Circuit breaker state transition: CLOSED -> OPEN
2026-05-10 12:35:56.123 INFO  S3 Circuit breaker state transition: OPEN -> HALF_OPEN
2026-05-10 12:35:56.456 INFO  S3 Circuit breaker state transition: HALF_OPEN -> CLOSED
```

Every fast-failure is logged at WARN:
```
WARN  S3 Circuit breaker call not permitted
```

These are excellent alert candidates — see Recommended alerts below.

---

## Operational runbook

### Diagnose: "Why is the service returning 503s?"

1. Check breaker state:
   ```bash
   curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" \
     | jq '.components."circuit-breakers".details | {s3_state, redis_state}'
   ```
2. If `s3_state == "OPEN"`: S3 has been failing. Check S3 metrics, AWS health, network connectivity.
3. If `redis_state == "OPEN"`: Redis has been failing — but this should NOT be causing 503s (Redis breaker degrades silently). If 503s coincide, look elsewhere.
4. Look at the app log for state transitions and the underlying exception:
   ```bash
   docker compose logs app | grep -E 'Circuit breaker|S3|Redis' | tail -50
   ```

### Diagnose: "S3 breaker keeps re-opening"

1. The half-open test calls keep failing. The underlying S3 issue isn't resolved.
2. Common causes:
   - AWS credentials expired or misconfigured
   - VPC endpoint / IAM policy issue
   - S3 region misconfigured
   - Bucket policy denies the role
3. Verify directly:
   ```bash
   docker compose exec app sh -c "echo \$AWS_ENDPOINT; echo \$AWS_REGION; echo \$RULE_BUCKET_NAME"
   # then try to list manually inside the container if possible
   ```

### Diagnose: "I see 'not permitted' but s3_state is CLOSED"

The `not_permitted_calls` counter is **cumulative** — it never resets. A non-zero value means *at some point in this process's lifetime* a call was rejected. Look at state transitions in logs to see when.

### Recover: Force breaker reset

There is **no admin endpoint** to reset a breaker. Two options:

1. **Wait** — after `waitDurationInOpenState`, it auto-transitions to HALF_OPEN.
2. **Restart the service** — fresh process, fresh breaker state. Use only if waiting is unacceptable.

```bash
docker compose restart app
```

### Tune: "We're getting too many false-positive trips"

If S3 is reliable but the breaker keeps tripping due to occasional slow responses:

1. Raise `DROOLS_CB_S3_FAILURE_RATE` (e.g., from 50 → 70).
2. Raise `DROOLS_CB_S3_MIN_CALLS` to require more calls before tripping.
3. Note: **slow call thresholds (70%/5s for S3) are hardcoded**. To tune them, edit [`CircuitBreakerConfig.java`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java) and rebuild.

### Tune: "We want stricter protection"

In production, the `prod` profile already sets stricter thresholds. To go further:

```bash
DROOLS_CB_S3_FAILURE_RATE=30
DROOLS_CB_S3_WAIT_DURATION=180000   # 3 minutes
DROOLS_CB_S3_MIN_CALLS=30
DROOLS_CB_S3_SLIDING_WINDOW=300
```

Trade-off: stricter = quicker to fast-fail (good for protection), but more sensitive to transient blips (false positives that disrupt traffic). Start with the `prod` defaults and tighten only if your data shows S3 outages where the breaker should have tripped earlier.

---

## Recommended alerts

| Signal | Severity | Threshold | Action |
|---|---|---|---|
| S3 breaker state = OPEN | P1 | > 30s open | Page on-call. Likely real S3 outage or misconfig. |
| Redis breaker state = OPEN | P3 | > 5 min open | Investigate Redis. Service still functioning via direct base storage (S3/file/memory) — `RedisCachedRuleStorage` falls through on every call. |
| `not_permitted_calls` rate > 0 | P2 | sustained > 1 min | Breaker is still rejecting traffic — alert immediate; this is "503s being sent to clients". |
| Slow call rate > 50% on S3 | P2 | sustained 5 min | S3 is degraded — pre-trip warning. |
| Frequent CLOSED→OPEN→CLOSED flapping | P2 | > 3 transitions / 10 min | Either S3 is unstable or your thresholds are too sensitive. |

---

## Why these defaults

The S3 breaker is more permissive (`50%/100`) than Redis (`60%/50`) because:
- S3 is the source of truth — losing it is more disruptive, so we want to be sure before tripping.
- 100-call window reduces false positives from a single bad batch.

The Redis breaker is faster to trip (`60%/50`) because:
- Redis is acceleration only — losing it has no user-visible effect.
- Faster trip = faster fallback to S3 path.

The `prod` profile makes both stricter (40% / 50% threshold, larger windows) because:
- Production traffic is high enough that 200 calls is a small time window.
- Real outages cause real customer-visible failures, which we want to detect quickly.

---

## What this layer does NOT protect

- **Compilation failures**: a bad `.drl` doesn't trip the breaker. It's reported in the refresh response under `errors[]`.
- **Rule execution exceptions**: those happen after the rule is loaded; they don't go through S3/Redis breakers.
- **Memory pressure / OOM**: not a circuit-breaker concern. See [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md).
- **Rate-limited clients**: that's the rate limiter's job (see [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md)), not the breaker's.
- **DNS / connectivity**: if the entire AWS region is unreachable, you'll see breaker open quickly. But the *cause* is upstream — check infrastructure first.

---

## Verification

```bash
# Inspect current state
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  | jq '.components."circuit-breakers"'

# Inspect via Spring Actuator (requires Actuator port exposed)
curl -fsS http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.state | jq

# Force an S3 failure manually (point at unreachable endpoint)
docker compose exec app sh -c 'export AWS_ENDPOINT=http://nonexistent:9999; ...'
# (would require restart to take effect; this is illustrative)

# Watch state transitions in logs
docker compose logs -f app | grep 'Circuit breaker'
```

---

## Source-of-truth citations

- Configuration class: [`CircuitBreakerConfig.java`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java)
- S3 breaker wiring: [`S3RuleStorage.java:62-90`](../src/main/java/com/company/drools/storage/S3RuleStorage.java#L62-L90), [`:135-142`](../src/main/java/com/company/drools/storage/S3RuleStorage.java#L135-L142)
- Redis breaker wiring: [`RedisCachedRuleStorage.java:120`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L120) (get), [`:184`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L184) (hasKey), [`:254`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L254) (save), [`:275`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L275) (delete), [`:293`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L293) (batch delete), [`:313`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L313) (bulk MGET), [`:344`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L344) (SCAN — wrapped 2026-05-24); publisher wrap: [`RuleRefreshPublisher.java`](../src/main/java/com/company/drools/cache/RuleRefreshPublisher.java)
- Profile-specific overrides: [`application.yml:165-350`](../src/main/resources/application.yml#L165-L350)
- Tests: [`CircuitBreakerConfigTest.java`](../src/test/java/com/company/drools/config/CircuitBreakerConfigTest.java)
