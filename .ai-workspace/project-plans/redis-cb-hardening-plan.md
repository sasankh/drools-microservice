# Redis CB + pub/sub hardening — Phase 9.4 follow-ups

**Status:** Approved — ready to implement
**Created:** 2026-05-24
**Owner:** TBD
**Driver:** Phase 9.4 of the load-test harness surfaced two real production findings — the Redis circuit breaker doesn't engage during a Redis outage (real coverage gap in `scanKeys`), and `RedisMessageListenerContainer` doesn't deterministically re-subscribe after Redis restarts. Both degrade admin paths and pub/sub fan-out cleanly today, but the CB engagement gap is a real bug worth closing and the recovery backoff is a small, defensive win.

---

## 1. Context & motivation

### 1.1 What Phase 9.4 found

`./scripts/run-load-test.sh --quick --phase 9.4` ran on 2026-05-23 with all four sub-tests:

| Sub-test | Result | Headline |
|---|---|---|
| 9.1 baseline regression (REDIS_ENABLED=false) | PASS | P99=9ms, err=0%, count=2991 |
| 9.2 cache-only 3-replica | PASS | P99=10ms, err=0% |
| 9.3 full mode + pub/sub convergence | PASS | single 47ms / bulk 45ms / under-load 42ms (deadline 2000ms), 20/20 rounds, skipped_self=9 |
| **9.4 Redis kill failure-mode** | **FAIL** | JMeter err=0% (good — graceful degradation works), but CB never opened on any of 3 replicas; post-restart convergence didn't fire within 2s |

9.4 service-facing behavior is actually fine — `/execute-rule` doesn't touch Redis (kieContainer is in-memory), so a 60s Redis outage doesn't produce any user-facing failures. The two findings worth fixing:

### 1.2 The two real findings

**Finding A — Redis CB never opened.** Audit traced the cause to a **real coverage gap**: `RedisCachedRuleStorage.scanKeys()` calls `redisTemplate.execute(RedisCallback<Set<String>>)` *without* wrapping in `redisCircuitBreaker`. Every other Redis-touching call IS wrapped (get, set, del, hasKey, multiGet, batch-delete). SCAN failures during outages don't count toward the CB's sliding window. Additionally, the existing `spring.data.redis.timeout=2000ms` is an exact match for the CB's `slowCallDurationThreshold=2s`, putting timed-out calls in an ambiguous classification window that delays CB engagement even when calls do reach the CB.

**Finding B — Pub/sub didn't re-subscribe within 2s of Redis restart.** `RedisConfig.redisMessageListenerContainer` is built with only `setConnectionFactory()` + `addMessageListener()`. There's no explicit `setRecoveryBackoff(...)`. Spring Data Redis's dedicated pub/sub Lettuce connection can lose subscriptions when dropped and the listener container won't always re-subscribe deterministically. The convergence-recovery round in Phase 9.4 (`CONVERGENCE_DEADLINE_MS=2000`) timed out.

### 1.3 Outcome of this change

- Redis CB engages cleanly during Redis outages within the existing `CB_OPEN_DEADLINE_S=30` window.
- Pub/sub re-subscribes within ~2s of Redis restart (FixedBackOff cadence).
- Phase 9.4 passes end-to-end on the next run without harness changes.
- Operators can tune `REDIS_TIMEOUT` per environment without rebuilding.

### 1.4 Out of scope

- `/admin/health` exposing a `pubsub` component (a third Phase 9.4 finding — surfaced via `[warn] /admin/health does not expose pubsub component; skipping wait`). Tracked separately.
- Tuning CB slow-call threshold (`2s` hardcoded in `CircuitBreakerConfig.java:118`). Operators tune via env var only after this change.
- Per-profile `REDIS_TIMEOUT` overrides. Base default + env var only.
- Adding pub/sub re-subscribe count metrics or an `/admin/reset-cb` endpoint.

---

## 2. Current state — verified code trace

| Component | File | Behaviour today |
|---|---|---|
| SCAN call | [`RedisCachedRuleStorage.scanKeys()`](../../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) (~line 332) | Executes `redisTemplate.execute(RedisCallback)` directly — **NOT wrapped in `redisCircuitBreaker`**. Failures don't count toward CB. |
| Other Redis calls | Same file, lines 120-121, 184-185, 254-256, 275-276, 293, 313-314 | All wrapped via `CircuitBreaker.decorateSupplier(redisCircuitBreaker, ...).get()` or `decorateRunnable(...).run()`. Pattern is correct — SCAN is the lone gap. |
| Lettuce timeout | [`application.yml:48`](../../src/main/resources/application.yml) | `spring.data.redis.timeout: 2000ms` — hardcoded, no env var. Exact match for CB `slowCallDurationThreshold=2s`. |
| CB config | [`CircuitBreakerConfig.java`](../../src/main/java/com/company/drools/config/CircuitBreakerConfig.java) | `failureRateThreshold=60%, slidingWindowSize=50, minimumNumberOfCalls=5, waitDurationInOpenState=30s, slowCallDurationThreshold=2s, slowCallRateThreshold=80%`. `recordExceptions={RedisConnectionFailureException, RedisSystemException, TimeoutException, ConnectException}`. |
| Pub/sub listener container | [`RedisConfig.redisMessageListenerContainer`](../../src/main/java/com/company/drools/config/RedisConfig.java) (~line 87-96) | Built with `setConnectionFactory()` + `addMessageListener()` only. No explicit `setRecoveryBackoff()`. |
| Decorator CB-open fallback | Same decorator file | Reads catch `CallNotPermittedException` → log DEBUG → fall through to delegate (S3/file/memory). Writes catch → log DEBUG → silently skip. Publishes catch → log WARN → drop event. **Graceful degradation by design**. |
| Verification of `QueryTimeoutException` classification | Spring Data Redis source | `RedisCommandTimeoutException` (Lettuce) is wrapped as `QueryTimeoutException`, a subtype of `RedisSystemException`, which IS in the CB's `recordExceptions`. So Lettuce timeouts already count as failures. No CB config change needed. |

---

## 3. Target — three coordinated changes

### 3.1 SCAN wrap (Finding A part 1)

`scanKeys()` body wrapped in `CircuitBreaker.decorateSupplier(redisCircuitBreaker, () -> { ... }).get()`. Catch `CallNotPermittedException` and generic `Exception` → return `Set.of()`. Callers already short-circuit on empty key set — `invalidateAll` returns silently, `collectFromRedis` forces delegate fetch. No caller changes required.

### 3.2 Lettuce timeout (Finding A part 2)

`spring.data.redis.timeout: 2000ms` → `${REDIS_TIMEOUT:500ms}`. 500ms sits well below CB `slowCallDurationThreshold=2s`, so Lettuce command timeouts are unambiguously classified as failures (not "slow calls"). Externalize as env var so operators can raise without rebuilding (e.g., distant Redis or high p99 latency).

### 3.3 Recovery backoff (Finding B)

`container.setRecoveryBackoff(new FixedBackOff(2_000L, Long.MAX_VALUE))` added to `redisMessageListenerContainer`. 2s fixed interval bounds the worst-case re-subscribe latency and aligns with the Phase 9.4 `CONVERGENCE_DEADLINE_MS=2000` recovery-round assertion. FixedBackOff over ExponentialBackOff because that deadline is fixed (post-CB-closed) and an exponential window could happen to be in a long wait when Redis comes back.

### 3.4 Failure modes after the fix

| Failure | Behaviour | Recovery |
|---|---|---|
| Redis dies — single-rule refresh path | DEL fails fast (500ms), CB tracks; after ~5 calls/replica with >60% failure rate, CB opens; subsequent DELs fall through to S3-only delegate, no hang | CB auto-transitions to half-open after `waitDurationInOpenState=30s`; trial calls verify Redis; CB closes on success |
| Redis dies — bulk refresh path (SCAN) | SCAN now CB-tracked; on CB-open returns `Set.of()`; `invalidateAll`/`collectFromRedis` degrade to delegate (S3 fetch) | Same as above |
| Redis dies — pub/sub publish | `RuleRefreshPublisher.publish()` already CB-wrapped; drops event on CB-open | Future publishes resume when CB closes; siblings catch up via TTL safety net |
| Redis restart — listener reconnect | `FixedBackOff(2s)` retries every 2s indefinitely | Re-subscribe within ≤2s of Redis being reachable (matches Phase 9.4 convergence-recovery deadline) |
| Sibling-task missed event during outage | Event lost (by design — fire-and-forget pub/sub) | TTL-bounded staleness (default 15min) self-heals via next read |

---

## 4. Code changes — file by file

### 4.1 New files

**In-repo planning artifacts** (this PR):
- `.ai-workspace/project-plans/redis-cb-hardening-plan.md` (this file)
- `.ai-workspace/project-plans/redis-cb-hardening-checklist.md` (execution tracker)

No new production or test source files.

### 4.2 Modified files

#### Production
| Path | LOC delta | Change |
|---|---|---|
| [`src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`](../../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) | +25 / -10 | Wrap `scanKeys()` body in `CircuitBreaker.decorateSupplier(redisCircuitBreaker, ...).get()`; catch `CallNotPermittedException` and generic `Exception` → return `Set.of()`. Leave caller-side catches as defense-in-depth. |
| [`src/main/java/com/company/drools/config/RedisConfig.java`](../../src/main/java/com/company/drools/config/RedisConfig.java) | +8 | Add `container.setRecoveryBackoff(new FixedBackOff(2_000L, Long.MAX_VALUE))` + import + INFO log line. |
| [`src/main/resources/application.yml`](../../src/main/resources/application.yml) | 1 line modified | `timeout: 2000ms` → `timeout: ${REDIS_TIMEOUT:500ms}` |

#### Tests
| Path | LOC delta | Change |
|---|---|---|
| [`src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java`](../../src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java) | +90 (3 tests) | `scanKeysCircuitBreakerOpenReturnsEmpty`, `refreshCacheWhenScanCircuitBreakerOpenStillDelegates`, `scanKeysRedisExceptionReturnsEmpty` |
| [`src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`](../../src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java) | +40 (1 test) | `scanKeysCircuitBreakerFallback` — Testcontainers Redis killed mid-test, CB pre-tuned to trip after 1 failure, assert `refreshCache()` completes in <2s |

#### Documentation
| Path | LOC delta | Change |
|---|---|---|
| [`project-documentation/29-circuit-breakers-and-resilience.md`](../../project-documentation/29-circuit-breakers-and-resilience.md) | +45 | New subsection "Lettuce timeout vs CB slow-call threshold". SCAN now wrapped. Operator note on `REDIS_TIMEOUT`. |
| [`project-documentation/09-environment-variables-reference.md`](../../project-documentation/09-environment-variables-reference.md) | +5 | Add `REDIS_TIMEOUT` row (default 500ms, rationale, when to raise) to Redis section. |
| [`.env.example`](../../.env.example) | +4 | Add `REDIS_TIMEOUT=500ms` after `REDIS_REFRESH_CHANNEL` with explanatory comment. |
| [`CLAUDE.md`](../../CLAUDE.md) | +6 | New "Recent change log" entry above the 2026-05-20 Redis cache layer entry. |

**No changes** to `scripts/run-load-test.sh` or any harness file.

---

## 5. Configuration & env variables

### 5.1 New env vars

| Env var | Default | Description |
|---|---|---|
| `REDIS_TIMEOUT` | `500ms` | Lettuce command timeout. Lowered from 2000ms to sit below CB `slowCallDurationThreshold=2s` and unambiguously classify command timeouts as failures. Raise to 1000-1500ms for distant or high-latency Redis. |

### 5.2 Modified defaults

| Setting | Before | After | Reason |
|---|---|---|---|
| `spring.data.redis.timeout` | `2000ms` | `${REDIS_TIMEOUT:500ms}` | Sub-CB-threshold + env-var tunable |

### 5.3 Migration

Operators with no `REDIS_TIMEOUT` set inherit the new 500ms default. If prod hits `RedisCommandTimeoutException` after deploy, set `REDIS_TIMEOUT=1000ms` (or higher) in ECS task definition. No code change needed. Document the knob in release notes.

---

## 6. Phases & effort

| Phase | Scope | Effort | Risk |
|---|---|---|---|
| 1. Repo plan + checklist | Two files in `.ai-workspace/project-plans/` | 0.5 h | Low |
| 2. SCAN wrap | Code + 3 unit tests | 1.5 h | Low |
| 3. Lettuce timeout | YAML edit + runtime resolution check | 0.5 h | Low |
| 4. Recovery backoff | Code + 1 integration test | 1.0 h | Low |
| 5. Docs | 4 files | 1.0 h | Low |
| 6. Verification + addendum | `mvn test` + Phase 9.4 re-run + `39-load-test-findings.md` addendum | 1.0 h | Low |
| Buffer | Spring Data Redis exception-class verification, Spring Boot Lettuce reconnect quirks | 1.0 h | — |
| **Total** | | **6.5 h (~0.8 dev-day)** | |

---

## 7. Risks & open questions

### 7.1 Risk: 500ms too aggressive for prod cold starts
First Redis call after deploy may exceed 500ms (cold pool). **Mitigation:** CB needs `minimumNumberOfCalls=5` to trip — one slow cold call won't open it. If observed, operators raise `REDIS_TIMEOUT=1000ms` via env var (no code change). Document in [`29-circuit-breakers-and-resilience.md`](../../project-documentation/29-circuit-breakers-and-resilience.md).

### 7.2 Risk: FixedBackOff(2s) on flapping Redis
Listener container retries every 2s if Redis is unreachable. **Mitigation:** 5s is one TCP attempt per listener, not a query storm. Spring's internal locking ensures concurrent reconnect attempts aren't stacked.

### 7.3 Risk: Phase 9.4 `CONVERGENCE_DEADLINE_MS=2000` still tight
If the listener's 2s backoff coincides with the recovery convergence round, the round could time out. **Mitigation:** the round fires after CB-closed (requires recent successful Redis ops → listener already reconnected). If flake observed, raise harness deadline as a follow-up if needed.

### 7.4 Risk: `RedisCommandTimeoutException` classification
Lettuce throws `RedisCommandTimeoutException`; Spring wraps it as `QueryTimeoutException` (subtype of `RedisSystemException`, already in `recordExceptions`). **Verify by reading Spring Data Redis source** before claiming done.

### 7.5 Risk: Empty `Set.of()` from SCAN causes silent data inconsistency
SCAN returning `Set.of()` on CB-open means `invalidateAll` returns silently (fine) and `collectFromRedis` returns empty map → forces delegate fetch (strictly safer than the pre-fix "scan hung silently" behaviour). The "risk" is silent **degradation** to delegate, not corruption. Documented in `scanKeys()` Javadoc.

---

## 8. Critical files — summary

### Created
- `.ai-workspace/project-plans/redis-cb-hardening-plan.md` (this)
- `.ai-workspace/project-plans/redis-cb-hardening-checklist.md` (execution tracker)

### Modified — production
- [`src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`](../../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java)
- [`src/main/java/com/company/drools/config/RedisConfig.java`](../../src/main/java/com/company/drools/config/RedisConfig.java)
- [`src/main/resources/application.yml`](../../src/main/resources/application.yml)

### Modified — tests
- [`src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java`](../../src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java)
- [`src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`](../../src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java)

### Modified — docs
- [`project-documentation/29-circuit-breakers-and-resilience.md`](../../project-documentation/29-circuit-breakers-and-resilience.md)
- [`project-documentation/09-environment-variables-reference.md`](../../project-documentation/09-environment-variables-reference.md)
- [`.env.example`](../../.env.example)
- [`CLAUDE.md`](../../CLAUDE.md)
- [`project-documentation/39-load-test-findings.md`](../../project-documentation/39-load-test-findings.md) — append Phase 9.4 addendum after re-run succeeds

---

## 9. Verification — acceptance criteria

### 9.1 Unit tests
- `mvn -q test` passes; count grows from 545 → 548 (3 new SCAN tests).

### 9.2 Integration tests
- `mvn -q test -Dtest='Redis*IntegrationTest'` passes; one new test (`scanKeysCircuitBreakerFallback`) — total integration tests 13 → 14.

### 9.3 Runtime config
- `REDIS_TIMEOUT=1500ms mvn spring-boot:run` produces `spring.data.redis.timeout=PT1.5S` in `/actuator/configprops`.
- Boot logs include `"Redis pub/sub recovery backoff: fixed 2s interval, infinite attempts"`.

### 9.4 Phase 9.4 re-run
`./scripts/run-load-test.sh --quick --phase 9.4` produces:
- CB opens on all 3 replicas within `CB_OPEN_DEADLINE_S=30` of `docker kill drools-redis`
- JMeter error rate `0%` across full kill+restart window
- CB closes on all 3 replicas within `CB_CLOSE_DEADLINE_S=90` of restart
- Post-restart convergence `delta_ms <= 2000`
- `phase-9-4/result.txt`: `PASS`

### 9.5 SCAN-failure CB observability
After Redis kill + bulk refresh:
```
curl -fsS http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.calls?tag=name:redis,kind:failed | jq '.measurements[0].value'
```
Returns > 0 (would have been 0 pre-fix because SCAN failures didn't count).

### 9.6 Documentation
- [`project-documentation/39-load-test-findings.md`](../../project-documentation/39-load-test-findings.md) has a Phase 9.4 addendum noting the CB+recovery hardening landed and the sub-test now passes cleanly.

---

## 10. Companion files

- Execution tracker: [`redis-cb-hardening-checklist.md`](redis-cb-hardening-checklist.md)
- Upstream context: [`redis-cache-layering-plan.md`](redis-cache-layering-plan.md), [`redis-cache-layering-checklist.md`](redis-cache-layering-checklist.md)
