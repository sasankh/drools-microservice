# 26 · Performance Tuning Runbook

| | |
|---|---|
| **Audience** | Operators, on-call engineers, performance engineers |
| **Purpose** | Decision-tree runbook for diagnosing and tuning performance issues. Each branch leads to a concrete tuning action. |
| **Last verified against** | Running stack on 2026-08-20 (load-tested at 1000 rules + 3-replica Phase 9 multi-instance harness — see [39-load-test-findings.md](39-load-test-findings.md) for measured numbers) |
| **Related docs** | [09-environment-variables-reference.md](09-environment-variables-reference.md), [24-jvm-optimization.md](24-jvm-optimization.md), [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md), [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md), [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md), [39-load-test-findings.md](39-load-test-findings.md) |

---

## How to use this doc

You're here because **the service is slow, throttling, OOMing, or otherwise underperforming**. Start at [Step 1: Symptom Triage](#step-1-symptom-triage), follow the branches, and stop at the matching scenario for tuning actions.

> Every command here works against the running stack. If a command fails, the underlying assumption may be off — check [31-troubleshooting.md](31-troubleshooting.md) first.

---

## Step 1: Symptom triage

Pick the symptom that best matches what you're seeing.

| Symptom | Go to |
|---|---|
| Latency is too high (P50/P95/P99) | [Branch A: High latency](#branch-a-high-latency) |
| Throughput is too low (RPS plateaued) | [Branch B: Low throughput](#branch-b-low-throughput) |
| Service returns 429 too often | [Branch C: Rate limiting issues](#branch-c-rate-limiting-issues) |
| Service returns 503 too often | [Branch D: 503 / circuit breaker](#branch-d-503--circuit-breaker) |
| Service returns 408 too often | [Branch E: Rule execution timeouts](#branch-e-rule-execution-timeouts) |
| GC pauses are visible / heap usage growing | [Branch F: Memory & GC](#branch-f-memory--gc) |
| Out of memory (exit code 137) | [Branch G: OOM](#branch-g-oom-exit-137) |
| Thread pool saturating | [Branch H: Thread pool exhaustion](#branch-h-thread-pool-exhaustion) |
| Cold-start is too slow | [Branch I: Slow startup](#branch-i-slow-startup) |
| Rule refresh takes forever | [Branch J: Slow refresh](#branch-j-slow-refresh) |

---

## Branch A: High latency

### Diagnose

```bash
# 1. Get a current latency picture from Spring metrics
curl -fsS http://localhost:8081/actuator/metrics/http.server.requests | jq '.measurements'

# 2. Check thread pool saturation
curl -fsS http://localhost:8080/admin/thread-pools | jq

# 3. Check cache hit rate
curl -fsS http://localhost:8080/admin/health | jq '.components.cache.details.statistics'

# 4. Check breaker state
curl -fsS http://localhost:8080/admin/health | jq '.components."circuit-breakers".details'
```

### Decision tree

#### A1: Cache hit rate < 80% → refresh churn or TTL too short

Note: `/execute-rule` never reads from Redis — it reads from the compiled `kieContainer`. Cache hit/miss metrics (`drools.cache.hit` / `drools.cache.miss`) reflect refresh and warm-start paths only. If they're poor:

- **TTL too short**: increase `REDIS_DRL_RULES_TTL_MINUTES` (default 15) so refresh hits stay warm between admin-driven refreshes.
- **Stop rule churn**: If `POST /admin/refresh-rules` is being called frequently in production, every refresh deletes and re-warms the Redis keys. Reduce refresh frequency, or use `AUTO_REFRESH_ENABLED=false` and refresh only on rule changes.
- **Redis breaker tripping**: check `resilience4j_circuitbreaker_state{name=redis}`. Open → decorator is falling through to S3 on every call. Investigate Redis latency or connectivity.

#### A2: Thread pool saturated (`active_count` ≈ `max_size`) → not enough workers

Rule execution requests are queued because all worker threads are busy.

```bash
DROOLS_THREAD_POOL_CORE_SIZE=20
DROOLS_THREAD_POOL_MAX_SIZE=100   # match prod profile
DROOLS_THREAD_POOL_QUEUE_CAPACITY=200
```

Caveat: more threads = more concurrent rule execution = more memory. Each thread holds a `KieSession`. Don't go infinite — the pool size should be ~2-3× CPU cores for CPU-bound rule logic.

#### A3: Storage thread pool saturated → S3 calls queuing

```bash
DROOLS_STORAGE_THREAD_POOL_CORE_SIZE=10
DROOLS_STORAGE_THREAD_POOL_MAX_SIZE=50
AWS_S3_MAX_CONNECTIONS=100   # match prod
```

Also check S3 region — if your service is in `us-east-1` and the bucket in `eu-west-1`, RTT alone is 80ms+. Co-locate them.

#### A4: GC pauses visible → tune heap

See [Branch F: Memory & GC](#branch-f-memory--gc).

#### A5: Slow rule logic itself

Some rules just take a long time. Identify them via Drools' own metrics or by adding `System.out.println` in the rule's `then` block (see [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) for the metrics path).

```bash
# Enable Drools' metric module (project doesn't currently include it)
# Or temporarily add System.out.println("rule X start: " + System.currentTimeMillis())
# at the start and end of the rule's `then` block.
```

If a single rule takes > 1 second consistently:
- Simplify pattern matching (move conditions from `eval()` into `Map(...)` patterns — but `eval()` is **blocked by the sandbox** anyway, so existing rules don't have this issue)
- Avoid expensive operations in `then` (heavy math, large list iteration)
- Cache static lookups in the rule logic

#### A6: All of the above are fine but latency is still high

Check `RULE_EXECUTION_TIMEOUT_SECONDS`. If it's set to 30 (default) and you're seeing P99 near 30s, you have rules hitting the timeout. On timeout the executor now `halt()`s the session at the next rule boundary, so a well-behaved (frequently-refiring) rule is cut short before the full budget — but a rule stuck in a single non-yielding consequence can still ride the timeout to ~30s. See [Branch E](#branch-e-rule-execution-timeouts).

---

## Branch B: Low throughput

### Diagnose

```bash
# Thread pool current state
curl -fsS http://localhost:8080/admin/thread-pools | jq

# CPU usage of the JVM
curl -fsS http://localhost:8081/actuator/metrics/system.cpu.usage | jq '.measurements[0].value'
# (Multiply by 100 for percent, or by core count for absolute)

# Connection pool utilization (S3)
curl -fsS http://localhost:8081/actuator/metrics/http.client.requests | jq
```

### Decision tree

#### B1: CPU < 50% but throughput is plateaued → thread pool too small

Same as [A2](#a2-thread-pool-saturated-active_count--max_size--not-enough-workers). Increase pool size.

#### B2: CPU pegged at 100% → really CPU-bound

Two options:
- **Scale horizontally**: deploy more replicas behind a load balancer. The service is stateless; horizontal scaling is the right answer for sustained throughput.
- **Scale vertically**: more vCPUs per container. Adjust `docker-compose.yml` `deploy.resources.limits.cpus` or your ECS task definition.

#### B3: I/O-bound (lots of cache misses + S3 calls)

Same as [A1](#a1-cache-hit-rate--80--cache-is-too-small-or-rules-churning) and [A3](#a3-storage-thread-pool-saturated--s3-calls-queuing).

#### B4: Network bandwidth saturated

Rare but possible if response payloads are huge. Check container `network` metrics. Consider compressing responses (Spring Boot has `server.compression.enabled=true`).

---

## Branch C: Rate limiting issues

### Diagnose

```bash
# Look for rate limit warnings in logs
docker compose logs app | grep -E 'Rate limit|client map'

# Check if client identity is what you expect
# Send a probe and inspect the bucket key being used. NOTE: application headers like
# X-Client-Id / X-API-Key are NOT read — the client key is always the remote IP
# (ip:<addr>), or the left-most X-Forwarded-For entry ONLY if trust-proxy=true.
curl -sI -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | grep -i RateLimit
```

### Decision tree

#### C1: Legitimate traffic exceeds limits → raise limits

```bash
DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=5000
DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR=200000
DROOLS_RATE_LIMITING_BURST_SIZE=500
```

#### C2: All clients share one bucket (you're behind a load balancer)

The rate limiter keys **only on `request.getRemoteAddr()`** — application headers (`X-API-Key`, `Authorization`, `X-Client-Id`) are deliberately **not** read (they're unauthenticated on the public `/execute-rule` API; keying on them would let any caller mint unlimited buckets or fill the client map). So behind an LB that terminates the connection, every client shares the LB's IP and therefore one bucket. Injecting a per-client header at the gateway will **not** split them — the filter ignores those headers.

The supported fix is to make the **remote IP** carry the real client identity:

- Set `DROOLS_RATE_LIMITING_TRUST_PROXY=true` **only** behind a proxy/LB that overwrites inbound `X-Forwarded-For`. The filter then keys on the left-most `X-Forwarded-For` entry (the originating client IP). Never enable this if `X-Forwarded-For` can reach the service unfiltered — it becomes spoofable.
- Or preserve the client source IP at L4 (e.g. proxy protocol / `X-Forwarded-For` preservation) so `getRemoteAddr()` already differs per client.

See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) for the identification flow.

#### C3: Client map fills up → max-clients LRU eviction / churn

Log line: rate-limiter client-map-at-capacity warning.

Because buckets are keyed on IP, a distinct-IP flood (or a spoofable `X-Forwarded-For` when `trust-proxy=true` is misconfigured) can churn the client map. At the `max-clients` cap the limiter **LRU-evicts the oldest bucket** to admit the new client (it does not reject the new client outright), so a flood can evict legitimate clients' buckets and effectively reset their counters.

Two responses:
- **Don't trust spoofable headers**: leave `DROOLS_RATE_LIMITING_TRUST_PROXY=false` unless a trusted proxy overwrites `X-Forwarded-For`. With it false the key is the true TCP peer and can't be rotated per request.
- **Increase capacity**:
  ```bash
  DROOLS_RATE_LIMITING_MAX_CLIENTS=50000
  ```
  But this just delays churn. The real fix is upstream (WAF / gateway L4 filtering).

---

## Branch D: 503 / circuit breaker

### Diagnose

```bash
curl -fsS http://localhost:8080/admin/health | jq '.components."circuit-breakers"'

# 503s with all breakers CLOSED? Check for rule-execution pool saturation:
curl -fsS http://localhost:8080/admin/thread-pools | jq
docker compose logs app | grep -i 'Rule execution pool saturated'
```

If `s3_state == "OPEN"` or `redis_state == "OPEN"`: a breaker has tripped. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) for the full diagnostic flow.

> **Two distinct 503 sources.** A 503 (`SERVICE_UNAVAILABLE`) is emitted both when a circuit breaker is open **and** when the rule-execution thread pool is saturated. The rule-execution pool uses an **`AbortPolicy`**: when its queue is full and all threads are busy, task submission throws `RejectedExecutionException`, which `RuleExecutor` maps to `ServiceUnavailableException` → HTTP 503. This deliberately sheds load rather than running the rule body on the Tomcat request thread (which would silently bypass the execution timeout). If you see 503s with every breaker CLOSED, look at pool saturation ([Branch H](#branch-h-thread-pool-exhaustion)), not the breakers.

### Decision tree (summary)

#### D1: S3 breaker open → S3 issue

- AWS region issue: check AWS health dashboard.
- Credentials: verify the IAM role / env vars are correct.
- Network: VPC endpoint, security groups, NAT gateway issues.
- Rate-limited by AWS: check CloudTrail for AccessDenied / Throttle errors.

After resolving, breaker auto-recovers within `wait-duration` (60s default, 120s in prod).

#### D2: Redis breaker open → degraded but not failed

This should NOT cause user-visible 503s — the Redis breaker degrades silently. If 503s coincide with Redis breaker open, look elsewhere (S3, downstream, etc.).

#### D3: Breaker is flapping (rapidly OPEN ↔ CLOSED)

Either:
- Real intermittent dependency issue — fix it upstream.
- Threshold too sensitive — tune. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) tuning section.

---

## Branch E: Rule execution timeouts

### Diagnose

```bash
docker compose logs app | grep 'Operation .*timed out'
```

Look at the count — is it consistently the same rule(s) timing out, or random?

### Decision tree

#### E1: One specific rule keeps timing out

That rule has a runtime issue. Likely causes:
- **Missing `no-loop true`** — rule modifies its own match condition and re-fires indefinitely. On timeout the executor now calls `KieSession.halt()`, which stops `fireAllRules` at the **next rule boundary** — so a rapidly-refiring rule is interrupted promptly and the request returns 408 without necessarily burning the full 30s. (The `maxRuleFirings = 10000` cap is a separate backstop.)
- **Heavy computation in `then` block** — log timing inside the rule, identify the hot loop, optimize. **Caveat**: `halt()` only interrupts *between* firings, so a single consequence that never yields (`then while(true){} end`) can't be interrupted — that worker thread stays busy until recycled even though the request already returned 408 (finding B1, deferred).
- **Unbounded recursion** through other rule activations.

#### E2: Many rules timeout simultaneously → external dependency slow

Probably S3 / Redis being slow but not yet broken enough to trip the breaker. The `slowCallRateThreshold` (70% / 5s for S3) should catch this — if it's not catching, the slow calls aren't lasting long enough.

#### E3: Timeout is too short for real workload

```bash
RULE_EXECUTION_TIMEOUT_SECONDS=60   # double from default 30
```

Caveat: longer timeout means slower fast-fail. Threads can stay blocked on a hung rule for longer, leading to thread pool exhaustion under load.

---

## Branch F: Memory & GC

### Diagnose

```bash
# Comprehensive memory snapshot
curl -fsS http://localhost:8080/admin/memory/info | jq '.heap, .warnings'

# Quick repeated snapshot to see trend
for i in {1..10}; do
  curl -fsS http://localhost:8080/admin/memory/snapshot | jq -c '{ts:.timestamp, heap:.heapUsedMB, pct:.heapUsagePercent}'
  sleep 5
done
```

Watch the trend. Climbing steadily = leak. Stable = healthy. Sawtooth = normal GC.

### Decision tree

#### F1: Heap usage growing steadily (looks like a leak)

The KieContainer lifecycle was overhauled on 2026-05-10: a single long-lived container is updated in place via `updateToVersion(ReleaseId)`, and old `KieModule`s are explicitly removed with `kieRepository.removeKieModule(oldReleaseId)`. Verify the current pattern is in place:

- Look at `DroolsEngineService.loadRules` — confirm it calls `kieRepository.removeKieModule(oldReleaseId)` after `container.updateToVersion(newReleaseId)`. The old `oldContainer.dispose()` two-container pattern is superseded.
- Run a refresh storm test:
  ```bash
  for i in {1..20}; do
    curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
      http://localhost:8080/admin/refresh-rules > /dev/null
    sleep 3
    curl -fsS http://localhost:8080/admin/memory/snapshot | jq -c '{i:'$i', heap:.heapUsedMB}'
  done
  ```
  Memory should stabilize, not grow.

If it does grow:
- Take a heap dump:
  ```bash
  curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/memory/gc
  # then trigger a heap dump via JMX or jcmd inside the container
  ```
- Analyze with VisualVM, Eclipse MAT, or similar.

#### F2: Heap usage warns at >80%

```bash
JAVA_OPTS="$JAVA_OPTS -Xms2g -Xmx4g"   # double the heap
```

Or in containerized envs adjust `MAX_RAM_PERCENTAGE`. The Dockerfile defaults to 75% of the cgroup limit. If your container has 2 GB and you want ~1.5 GB heap, that's already configured. Bumping the container memory bumps the heap proportionally.

#### F3: GC pauses are too long

```bash
# Look at GC log if available
docker compose exec app tail -50 /tmp/gc-logs/gc.log
```

If pauses > 200ms regularly:
- Increase `MaxGCPauseMillis` from default 100 to 200 (relax the target — G1 will trade pause for less concurrent work)
- Increase heap (more headroom = less frequent GC)
- Consider a different GC. The Dockerfile uses G1; ZGC or Shenandoah have lower pauses but may not be tested with this codebase.

#### F4: Metaspace growing

```bash
curl -fsS http://localhost:8080/admin/memory/info | jq '.memoryPools[] | select(.name == "Metaspace")'
```

If metaspace is climbing, it's a classloader leak. Drools generates classes per rule — if rule reloads aren't disposing the old `KieContainer` properly, metaspace accumulates. See F1.

---

## Branch G: OOM (exit 137)

The container was OOM-killed by the kernel.

### Diagnose

```bash
# Last 100 lines of the previous (crashed) container
docker compose logs --tail 100 app

# Check if heap dump was written (configured in JAVA_OPTS)
ls -la heap-dumps/
```

### Decision tree

#### G1: Heap dump exists → analyze with MAT / VisualVM

Open the `.hprof` file in a heap analyzer. Look for:
- Many `KieContainer` instances → disposal bug regressed
- Many `ProjectClassLoader` instances → `KieRepository.removeKieModule` not called after `updateToVersion` (Drools 10 does NOT auto-clean)
- Lots of pending requests → thread pool runaway

#### G2: No heap dump (OOM happened too fast)

The Dockerfile sets `-XX:+HeapDumpOnOutOfMemoryError` but writes to `/tmp/heap-dumps/`. If the container was killed by the cgroup before Java could write, you may not have a dump.

Options:
- Increase container memory.
- Reduce heap percentage (`MAX_RAM_PERCENTAGE=60`) so cgroup kill is less aggressive.
- Add monitoring to alert on `> 80%` heap before OOM.

#### G3: Recurring OOM → root cause investigation

If the service OOMs repeatedly:
1. Restart with bigger heap (buy time).
2. Capture metrics over a 24-hour window.
3. Look for the trigger — usually correlated with refresh activity, traffic spike, or specific request patterns.

---

## Branch H: Thread pool exhaustion

### Diagnose

```bash
curl -fsS http://localhost:8080/admin/thread-pools | jq
```

Look for:
- `active_count` near `max_size`
- `queue_size` > 0 (or close to `queue_capacity`)
- `largest_pool_size` close to or equal to `max_size`

### Decision tree

#### H1: Rule execution pool exhausted

```bash
DROOLS_THREAD_POOL_MAX_SIZE=100
DROOLS_THREAD_POOL_QUEUE_CAPACITY=200
```

Rule execution rejects (when both pool and queue are full) hit the **`AbortPolicy`**: submission throws `RejectedExecutionException`, which `RuleExecutor` turns into `ServiceUnavailableException` → **HTTP 503**. The service **sheds** the excess request rather than running the rule body on the Tomcat request thread — deliberately, because a caller-runs execution would silently bypass the per-request execution timeout. So under sustained saturation you'll see 503s (see [Branch D](#branch-d-503--circuit-breaker)); the fix is to add capacity (below) or scale out.

> This is a change from the older `CallerRunsPolicy` on this pool. The **storage** thread pool still uses `CallerRunsPolicy` (see [H2](#h2-storage-pool-exhausted)) — only the rule-execution pool switched to `AbortPolicy`/503 load-shedding.

#### H2: Storage pool exhausted

Storage pool handles S3/file lookups. It still uses `CallerRunsPolicy` (rejected tasks run on the caller thread). Defaults are smaller because rule storage is only consulted on cache miss:

```bash
DROOLS_STORAGE_THREAD_POOL_MAX_SIZE=50
AWS_S3_MAX_CONNECTIONS=100
```

#### H3: Tomcat threads exhausted

Tomcat has its own pool (default `server.tomcat.threads.max=200`). If even Tomcat is saturated, you're getting connection refused / read timeout client-side. Either:
- Scale horizontally (more replicas behind LB)
- Bump Tomcat: `SERVER_TOMCAT_THREADS_MAX=400`

---

## Branch I: Slow startup

The service takes too long to become healthy.

### Diagnose

```bash
# Time from container start to first /admin/health 200
docker compose logs app | grep -E 'Started Application|admin endpoint|loaded \d+ rules'
```

Typical breakdown for cold start:
- Spring Boot init: ~3-5s
- Drools KieBase compile: ~1s for sample rules; can be many seconds for hundreds of rules
- LocalStack S3 connection: ~1s

Total expected: ~10-30s. The healthcheck `start-period: 60s` accounts for this.

### Decision tree

#### I1: Compilation phase is slow

Cause: many rules, complex DRL.

Mitigations:
- Reduce rule count (consolidate similar rules into one with conditional logic)
- Move from `local` (in-memory) to `s3` only when needed
- Pre-bake compiled rules into the image (would require code change)

#### I2: S3 list is slow

If the bucket has thousands of objects, listing all keys at startup is slow. The AWS SDK's `ListObjectsV2` paginator handles this but adds RTT per page.

Consider:
- Smaller buckets (separate buckets for separate rule sets)
- Faster S3 region (co-locate with the service)

#### I3: Healthcheck `start-period` too short

If your reality is consistently >60s startup, bump `start_period` in `docker-compose.yml` or your orchestrator's healthcheck config.

---

## Branch J: Slow refresh

### Diagnose

```bash
# Watch a refresh
time curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules
```

### Decision tree

#### J1: Compilation dominates

For each rule, DrlSanitizer scans + Drools compiles. Compilation is per-rule, not parallelized.

Mitigations:
- Reduce rule count
- Profile: which rule is slowest? (Currently no per-rule timing in the refresh response — would be a feature add)

#### J2: S3 fetch dominates

If you have hundreds of rules, that's hundreds of S3 GETs. Each adds RTT.

Mitigations:
- Co-locate service and bucket
- Increase `AWS_S3_MAX_CONNECTIONS` for parallel fetches
- Increase `DROOLS_STORAGE_THREAD_POOL_MAX_SIZE`

#### J3: `updateToVersion` works as designed; refresh is inherently O(rules)

This is **fine**. The current pattern (Drools 10 `KieContainer.updateToVersion(ReleaseId)`) compiles **outside** the write lock — existing `/execute-rule` traffic is **not** blocked during compilation. Read traffic continues uninterrupted, and the write lock is held only briefly for the in-place version swap.

**Caveat at scale**: full refresh wall-clock time scales with rule count. Measured 2026-05-10:
- 10 rules → ~1.5s
- 1000 rules → ~46s (compile dominates; scales **superlinearly** because Drools rebuilds the whole RETE network)

A 30-second refresh is acceptable as long as it's not happening every minute. See [39-load-test-findings.md](39-load-test-findings.md) §"Compile time scaling" for the full curve.

If you need faster turnaround for individual rule updates, use **per-rule refresh**:
```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules/pricing.discount.vip
```

This still recompiles the **full rule set** (Drools 10 has no public per-rule incremental compile API), but it's a useful single-call admin shape. Single-rule refresh under load was load-tested at 1000 rules: 0 errors across 360 swaps, P99 spike during compile windows. See [39-load-test-findings.md](39-load-test-findings.md).

---

## Performance targets — what's "good"

| Metric | Target | Achieved (sample-rules workload) |
|---|---|---|
| P50 latency, cached rule | < 10ms | 1-5ms |
| P99 latency, cached rule | < 100ms | 9ms (Phase 9 single-replica), 10ms (3-replica cache-only) |
| P99 latency, cache miss | < 500ms | < 100ms (LocalStack) |
| Sustained RPS per replica | 100-1000 | 518 RPS (Phase 9 single-replica, 0% errors over 3000 samples) |
| Redis cache hit rate (when `REDIS_ENABLED=true`) | > 90% on multi-instance | depends on fan-out activity; see `drools.cache.hit{layer=redis}` Micrometer counter; the pre-2026-05-20 "~95%" figure was for the deleted LRU and is no longer meaningful |
| Startup time | < 60s | ~1.3s for sample workload |
| Memory baseline | stable | yes (Drools 10 `updateToVersion` + `removeKieModule` pattern verified by Phase 9 long-running load test) |
| GC pause P99 | < 200ms | yes with default G1GC config |
| Pub/sub convergence (single-rule refresh, 3-replica) | < 2000ms | max 47ms across 3 sibling instances (Phase 9.3) |

If you're hitting these on synthetic load but the production workload is failing, the workload is probably not what you think. Profile first.

---

## Tuning starter pack — production

Apply these on top of the `prod` profile defaults:

```bash
# Heap
JAVA_OPTS="-Xms2g -Xmx4g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/heap-dumps/"

# Thread pools (raise from prod defaults if needed)
DROOLS_THREAD_POOL_CORE_SIZE=20
DROOLS_THREAD_POOL_MAX_SIZE=200
DROOLS_THREAD_POOL_QUEUE_CAPACITY=400
DROOLS_STORAGE_THREAD_POOL_MAX_SIZE=100

# S3 client
AWS_S3_MAX_CONNECTIONS=200
AWS_S3_CONNECTION_TIMEOUT=10
AWS_S3_SOCKET_TIMEOUT=30

# Rate limits
DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=10000
DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR=500000

# Cache (shared Redis + pub/sub)
REDIS_ENABLED=true
REDIS_DRL_RULES_TTL_MINUTES=15
REDIS_PUBSUB_ENABLED=true
```

Then load-test, observe, and adjust based on real metrics — not these defaults.

---

## Diagnostic commands cheat sheet

```bash
# Health summary
curl -fsS http://localhost:8080/admin/health | jq '{status, components: (.components | map_values(.status))}'

# Heap snapshot
curl -fsS http://localhost:8080/admin/memory/snapshot | jq

# Thread pools
curl -fsS http://localhost:8080/admin/thread-pools | jq

# Circuit breaker state
curl -fsS http://localhost:8080/admin/health | jq '.components."circuit-breakers".details | {s3: .s3_state, redis: .redis_state}'

# Spring Actuator metric (e.g., HTTP request stats)
curl -fsS http://localhost:8081/actuator/metrics/http.server.requests | jq

# Force a GC (diagnostic only)
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/memory/gc | jq

# Tail logs for slow rules / errors
docker compose logs -f app | grep -E 'WARN|ERROR|timed out|Circuit breaker'
```

---

## When this runbook fails you

If you've worked through every relevant branch and still don't know what's wrong:

1. Capture metrics for 30 minutes:
   ```bash
   while true; do
     date +%s
     curl -fsS http://localhost:8080/admin/health | jq -c
     curl -fsS http://localhost:8080/admin/memory/snapshot | jq -c
     curl -fsS http://localhost:8080/admin/thread-pools | jq -c
     sleep 30
   done > /tmp/diagnostics.jsonl
   ```
2. Take a heap dump and analyze.
3. Take a thread dump (`jcmd <pid> Thread.print`).
4. Capture a Java Flight Recorder profile (`jcmd <pid> JFR.start duration=120s filename=profile.jfr`).
5. File a bug with all of the above.
