# 29 · Circuit Breakers and Resilience

| | |
|---|---|
| **Audience** | Operators, on-call engineers, developers debugging external-dependency failures |
| **Purpose** | Complete behavior of the two Resilience4j circuit breakers (S3 and Redis) — how they trip, what they protect, what state transitions look like, how to recover |
| **Last verified against** | [`CircuitBreakerConfig.java`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java), [`S3RuleStorage.java`](../src/main/java/com/company/drools/storage/S3RuleStorage.java), [`RedisCachedRuleStorage.java`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java), [`RuleRefreshPublisher.java`](../src/main/java/com/company/drools/cache/RuleRefreshPublisher.java), [`RuleExecutor.java`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java), [`DroolsEngineService.java`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java), [`RedisConfig.java`](../src/main/java/com/company/drools/config/RedisConfig.java), [`RuleRefreshService.java`](../src/main/java/com/company/drools/core/engine/RuleRefreshService.java) on 2026-11-18 |
| **Related docs** | [09-environment-variables-reference.md](09-environment-variables-reference.md), [12-error-code-catalog.md](12-error-code-catalog.md), [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md), [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) |

---

## TL;DR

Two circuit breakers wrap calls to external dependencies:

- **`s3CircuitBreaker`** wraps every call into `S3RuleStorage` (`getRule`, `getAllRules`, etc.)
- **`redisCircuitBreaker`** wraps every Redis op in `RedisCachedRuleStorage` (get / set / DEL / SCAN / MGET / EXISTS) and in `RuleRefreshPublisher.publish()` (`convertAndSend`)

They use the standard 3-state machine (CLOSED → OPEN → HALF_OPEN → CLOSED). When OPEN, calls fail immediately with `CallNotPermittedException` (rethrown as `CircuitBreakerException`).

**Storage is not on the request path.** `/execute-rule` runs on rules already compiled in memory, so an open S3 breaker doesn't fail requests. It makes **refreshes** fail safely: a bulk refresh returns 500 `failed` and changes nothing, and `/admin/health` reports `DEGRADED` (still 200). See [Refresh safety](#refresh-safety-all-or-nothing-storage-reads).

Beyond the two dependency breakers, the service has **in-process resilience mechanisms** that are not circuit breakers but belong in the same mental model — see [In-process load-shedding and refresh resilience](#in-process-load-shedding-and-refresh-resilience):
- **Rule-execution pool load-shedding** — the rule-exec thread pool uses `AbortPolicy`; saturation throws `ServiceUnavailableException` → **HTTP 503** (a second, breaker-independent 503 source).
- **Timeout halt + interrupt** — on rule timeout the executor calls `KieSession.halt()` and interrupts the worker → **HTTP 408**. A worker that ignores both is counted in `drools.rule.execution.stuck`.
- **Firing cap** — more than `DROOLS_MAX_RULE_FIRINGS` firings for one request → **HTTP 422** `RULE_LIMIT_EXCEEDED`.
- **Dedicated pub/sub recompile executor** — refresh events are handled on a single-thread bounded executor, off the Lettuce dispatch thread (S6). Overflow is dropped and counted; the scheduled reconcile repairs it.
- **All-or-nothing refreshes** — a failed or incomplete storage read changes nothing; an empty read can't remove loaded rules.
- **Lock-free execution** — refreshes are serialized; `/execute-rule` takes no lock, so it is never blocked by a refresh.

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
- `GlobalExceptionHandler` would map it to HTTP **503 SERVICE_UNAVAILABLE**, but today no request path lets it escape: every storage call happens inside a refresh, health check or reconcile, which catch it.

**Bulk reads are all-or-nothing (2026-11-18).** `loadAllRulesFromS3` runs inside the breaker. A listing error, or a read error on any single object, aborts the whole read with `RuleStorageException`. An object deleted between the listing and the read (`NoSuchKey`) is skipped. The refresh then changes nothing (see [Refresh safety](#refresh-safety-all-or-nothing-storage-reads)).

> **Caveat: bulk-read errors don't raise the S3 failure rate.** The breaker records only `SdkException`, `TimeoutException` and `IOException` as failures, and Resilience4j checks the exception that leaves the decorated call, not its cause. `loadAllRulesFromS3` wraps the `SdkException` in `RuleStorageException` *inside* the breaker, so Resilience4j counts the call as **successful** (the same trap as the Redis `QueryTimeoutException` bug in Phase 9.4). Single-rule reads (`getRule`) throw `SdkException` from inside the breaker and do count. A bulk read slower than 5 s still counts as a slow call. `S3RuleStorageTest` uses a mocked breaker, so no test covers this classification.

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

**Redis records as failures** ([`CircuitBreakerConfig.java:121-125`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java#L121-L125)):
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
   - `/execute-rule` keeps serving the compiled rules (200). Admin refreshes return 500 (`failed` / `error`) and change nothing; a scheduled reconcile is skipped with a WARN. `/admin/health` reports `DEGRADED` (200).
   - Log lines on each rejection: `WARN S3 Circuit breaker call not permitted` (and, for a bulk read, `WARN S3 circuit breaker is open — cannot load all rules`).
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

This is **NOT** a circuit breaker event. The exception is in the `ignoreExceptions` list. A single-rule admin refresh for a key that doesn't exist returns 404 `not_found` (or `removed`, if the rule was loaded).

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
2. If `s3_state == "OPEN"`: S3 has been failing. Check S3 metrics, AWS health, network connectivity. This does **not** cause 503s on `/execute-rule` (storage isn't on the request path); it makes refreshes fail with 500.
3. If `redis_state == "OPEN"`: Redis has been failing — but this should NOT be causing 503s (Redis breaker degrades silently). If 503s coincide, look elsewhere.
4. The two real 503 sources today: rule-execution pool saturation (`/admin/thread-pools`, WARN `Rule execution pool saturated`) and `/admin/health` / readiness reporting DOWN (no rules loaded — see [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md)).
5. Look at the app log for state transitions and the underlying exception:
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
| `not_permitted_calls` rate > 0 | P2 | sustained > 1 min | Breaker is still rejecting calls: refreshes and reconciles are failing, so rule changes aren't being picked up. `/execute-rule` keeps serving the compiled rules. |
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

## In-process load-shedding and refresh resilience

These are not Resilience4j circuit breakers, but they are the other mechanisms that keep the service responsive under load and during refreshes. Operators debugging 503s/408s or refresh latency need them alongside the breakers.

### Rule-execution pool → 503 load-shedding (`AbortPolicy`)

[`RuleExecutor`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java) submits each rule execution (a `FutureTask`) to a dedicated `ruleExecutionExecutor`. That pool is configured with a **`ThreadPoolExecutor.AbortPolicy`**: when its queue is full and all threads are busy, `execute(task)` throws `RejectedExecutionException`. `RuleExecutor` catches it and throws `ServiceUnavailableException` → **HTTP 503**:

```java
} catch (RejectedExecutionException e) {
  // Pool saturated (queue full + all threads busy). Shed load with 503 instead of running the
  // rule body on the Tomcat request thread (which would silently bypass the timeout).
  throw new ServiceUnavailableException("Rule execution capacity exceeded; please retry shortly", e);
}
```

This is a **second, breaker-independent 503 source**. If you see 503s with every circuit breaker CLOSED, this is why — check thread-pool saturation (`/admin/thread-pools`) and the `Rule execution pool saturated` WARN log. The design choice is deliberate: shedding the request is safer than running the rule body on the caller (Tomcat) thread, which would bypass the per-request execution timeout. Note the **storage** thread pool still uses `CallerRunsPolicy` — only the rule-execution pool sheds load with `AbortPolicy`.

### Timeout → `KieSession.halt()` + interrupt → 408

When a rule execution exceeds `DROOLS_RULE_EXECUTION_TIMEOUT` (30 s default), `RuleExecutor` halts the session, then interrupts the worker:

```java
} catch (java.util.concurrent.TimeoutException e) {
  haltQuietly(sessionRef);   // KieSession.halt() — stops fireAllRules at the next rule boundary
  task.cancel(true);         // FutureTask: really interrupts the worker thread
  awaitBriefly(state);       // up to ~200 ms for an interrupted worker to finish
  // still running → counted in drools.rule.execution.stuck
  throw new TimeoutException("Rule execution: " + ruleId, timeoutSeconds, e);   // → HTTP 408
}
```

- `halt()` stops the agenda between firings. The interrupt stops code that checks for it (sleep, wait, blocking IO). Since 2026-11-18 the task is a `FutureTask`; the old `CompletableFuture.cancel(true)` never interrupted.
- **Limitation**: a consequence that never yields (`then while(true){} end`) ignores both and keeps its thread until the process restarts. The request still returns 408 promptly. The gauge **`drools.rule.execution.stuck`** counts such threads and drops back when one finishes. Only process isolation can kill it (`SECURITY.md`, threat model; ADR-019 addendum).

### Firing cap → 422

`fireAllRules(cap + 1)`: if more than `DROOLS_MAX_RULE_FIRINGS` (10,000 default) rules fire for one request, the request fails with **422 `RULE_LIMIT_EXCEEDED`** and no result. This bounds a rule loop (a consequence that re-triggers itself) without tying up the worker until the timeout.

### Dedicated single-thread pub/sub recompile executor (S6)

[`RedisConfig`](../src/main/java/com/company/drools/config/RedisConfig.java) wires the `RedisMessageListenerContainer` to a dedicated `ruleRefreshListenerExecutor` rather than letting message handling run on the Lettuce subscription/dispatch thread:

```java
ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
executor.setCorePoolSize(1);
executor.setMaxPoolSize(1);
executor.setQueueCapacity(50);
executor.setThreadNamePrefix("rule-refresh-");
executor.setRejectedExecutionHandler((task, pool) -> { /* log ERROR + count drools.refresh.failed{layer=listener,reason=queue_full} */ });
```

Because a refresh event triggers a (possibly long) rule recompile, running it on the Lettuce thread would block delivery of subsequent events. The single-thread + bounded-queue (50) design serializes recompiles off the dispatch thread and caps pile-up under bursts. If the queue fills, the event is dropped, logged at ERROR and counted in `drools.refresh.failed{layer=listener,reason=queue_full}`; the scheduled reconcile repairs the missed change. (Before 2026-11-18 it used `CallerRunsPolicy`, which ran the handler on the Redis dispatch thread and broke the one-handler-at-a-time assumption.) Spring manages init/shutdown. This is separate from the `FixedBackOff(2s, ∞)` re-subscribe recovery backoff documented above.

### Refresh serialization — mutations serialized, execution lock-free

- [`RuleRefreshService`](../src/main/java/com/company/drools/core/engine/RuleRefreshService.java) owns every refresh (startup, admin, pub/sub, reconcile). It holds one lock around the storage read **and** the apply, so two refreshes can't install an older snapshot last.
- [`DroolsEngineService`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) also serializes its own mutations (`loadRulesReport`, `replaceRule`, `removeRule`) with `refreshLock`.
- There is no read-write lock ([ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)). Each rule ID has its own immutable compiled handle in the `CompiledRulesetRegistry`, installed with an atomic per-ID map put. `/execute-rule` takes its handle without any lock, so a refresh never blocks live traffic, and a single-rule refresh recompiles only that ID.

### Refresh safety: all-or-nothing storage reads

Every `RuleStorage.getAllRules()` returns the **complete** set or throws. Before 2026-11-18, an S3 per-object error was swallowed and the partial list was treated as the full set, so a refresh silently dropped healthy rules (and an empty list wiped them all, then fanned the wipe out over pub/sub).

| Storage | Throws when |
|---|---|
| `S3RuleStorage` | The listing fails, or any listed object can't be read (`NoSuchKey` mid-listing is skipped), or the breaker is open |
| `LocalFileStorage` | The rules directory is missing or not a directory, or a file can't be read |
| `RedisCachedRuleStorage` | The delegate throws (never mixes cached values over a failed listing) |

What a bulk refresh does with the result:
- **Read failed** → nothing changes; admin gets 500 `failed`; no pub/sub event; reconcile logs `Reconcile skipped`.
- **Read complete but empty while rules are loaded** → refused unless `DROOLS_REFRESH_ALLOW_EMPTY=true`; admin gets 409 `rejected_empty`.
- **Read complete** → per-rule-ID apply. A rule that fails to compile keeps its previous version.

---

## What this layer does NOT protect

- **Rule-execution timeouts / pool saturation**: handled in-process by `RuleExecutor` (408 on timeout via `halt()`; 503 on pool saturation via `AbortPolicy`), not by the S3/Redis breakers — see the section above.
- **Compilation failures**: a bad `.drl` doesn't trip the breaker. It's reported in the refresh response under `errors[]`, and that rule ID keeps its previous version.
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
