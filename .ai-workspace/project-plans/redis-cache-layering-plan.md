# Redis-only DRL Cache Deployment Plan

**Status:** Draft — not yet started
**Created:** 2026-05-11
**Owner:** TBD
**Driver:** Eliminate dead-code cache layer + provide real multi-instance / cross-service DRL caching via Redis with a feature flag

---

## 1. Context & motivation

### 1.1 The problem

A forensic trace of the current `cache/` package revealed:
- `LocalLRUCache` (`@Primary` `RuleCache` bean) and `RedisRuleCache` (`@ConditionalOnProperty("redis.enabled")`) are **dead code**.
- `.get()` on either is never called from production code paths.
- They are written to during refresh (`warmUp`, `put`, `clear`) and queried only for their own observability endpoints (`/admin/cache/stats`, `cached` boolean in `/admin/rules`, `cache` section of `/admin/health`).
- "Cache hit rate ~95%" reported in docs is statistically true but operationally meaningless — the cache is never on the read path.

### 1.2 Why this matters

- **Production scale target**: 3–5 ECS tasks × 10,000+ rules. Each task independently fetches all rules from S3 on cold start and on `/admin/refresh-rules`. With the dead cache, multi-instance setups don't share fetch work.
- **Cross-service ambition**: Other services should be able to read the same rule corpus from Redis without re-fetching from S3. Today they can't reliably do so because the writer's invalidation story is broken.
- **Architectural integrity**: The misleading `RuleCache` abstraction (parallel to `RuleStorage`) creates confusion when reading the code. A new contributor will assume the cache is on the hot path; it isn't.

### 1.3 Solution

Replace the dead `RuleCache` layer with a **decorator on `RuleStorage`** that implements a real read-through cache against Redis, feature-flagged. `LocalLRUCache` is removed (already proven valueless — its data is a subset of `loadedRules`, and execution never reads from it).

### 1.4 Expected outcome

- Redis is a **functional L1 cache** when enabled, not a write-only sink.
- Multi-instance ECS deployments share rule fetches: only the first instance hits S3, others hit Redis.
- Cross-service consumers can read `drools:rule:{id}` keys with a clear staleness bound (15 min TTL ceiling + immediate invalidation on writer refresh).
- Approximately ~600 LOC of dead code removed; ~250 LOC of real cache code added.
- Redis can be turned **fully off** (`REDIS_ENABLED=false`) for dev/local/single-instance, falling back to S3 direct.

### 1.5 What this does *not* fix

- Compilation cost (~46s per 1,000 rules; ~7 min per 10,000 rules). Redis caches DRL text only; rules still compile per-instance after every refresh. The compile-cost fix is the **kjar plan** (separate document).
- This plan is independent of and complementary to kjar deployment.

---

## 2. Current state — verified code trace

| Component | File | Behaviour |
|---|---|---|
| `RuleCache` interface | `src/main/java/com/company/drools/cache/RuleCache.java` | API consumers think there's a tiered cache. There isn't. |
| `LocalLRUCache` | `src/main/java/com/company/drools/cache/LocalLRUCache.java` | `@Primary`, max 100 entries, `LinkedHashMap` write-locked on `get()` (ADR-004). `.get()` never called in production. |
| `RedisRuleCache` | `src/main/java/com/company/drools/cache/RedisRuleCache.java` | `@ConditionalOnProperty("redis.enabled")`. Bean created when on but never injected (LRU is `@Primary`). |
| `CacheStatistics` | `src/main/java/com/company/drools/cache/CacheStatistics.java` | Counters for hits/misses/evictions on a cache that isn't read. |
| `@Autowired RuleCache ruleCache` | `AdminController.java:60–85`, `RuleLoadingConfig.java:22` | Used for `.warmUp()`, `.clear()`, `.put()`, `.contains()`, `.getStatistics()`. No `.get()` calls. |
| Refresh flow | `AdminController.java:401–447` | `storage.getAllRules()` → `droolsEngineService.loadRules(rules)` → `ruleCache.warmUp(rules)` (dead write). |
| Execution flow | `DroolsEngineService.executeRule()` → `RuleExecutor.execute()` | Reads `loadedRules` (ConcurrentHashMap) + `kieContainer`. Never touches `RuleCache`. |
| Existing env vars | `application.yml:127–129` | `REDIS_ENABLED:false`, `REDIS_URL:redis://localhost:6379`, `REDIS_TTL_MINUTES:60` |
| Key naming today | `RedisRuleCache.java` (constant) | `drools:rule:{ruleId}` |
| Circuit breaker | `RedisRuleCache.java:72-85, 126-134` + `CircuitBreakerConfig.java` | Wraps `get()`/`put()`. Will be preserved verbatim in the new decorator. |

---

## 3. Target architecture

### 3.1 Architecture diagram

```
REDIS_ENABLED=false:                          REDIS_ENABLED=true:
┌─────────────────────┐                        ┌────────────────────────────┐
│ DroolsEngineService │                        │    DroolsEngineService     │
│ (loadedRules,       │                        │ (loadedRules, kieContainer)│
│  kieContainer)      │                        └─────────────┬──────────────┘
└──────────┬──────────┘                                      │
           │ RuleStorage bean                                │ RuleStorage bean
           ▼                                                 ▼
┌─────────────────────┐                        ┌────────────────────────────┐
│   S3RuleStorage     │                        │  RedisCachedRuleStorage    │
│   (direct, L3)      │                        │  (read-through L1)         │
└─────────────────────┘                        └─────────────┬──────────────┘
                                                             │ miss / write
                                                             ▼
                                               ┌────────────────────────────┐
                                               │       S3RuleStorage        │
                                               │       (delegate, L3)       │
                                               └────────────────────────────┘
```

### 3.2 New class: `RedisCachedRuleStorage`

**Location:** `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`

**Implements:** `RuleStorage`

**Constructor (Spring constructor injection):**
- `RuleStorage delegate` — wraps `S3RuleStorage` (or `LocalFileStorage`, or whatever is configured as the source)
- `RedisTemplate<String, Rule> redisTemplate` — reused from existing `RedisConfig`
- `@Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker` — reused from existing `CircuitBreakerConfig`
- `Duration ttl` — from `redis.drl-rules.ttl-minutes` config
- `String keyPrefix` — from `redis.drl-rules.key-prefix` config (default `drools:rule:`)
- `MeterRegistry meterRegistry` — for metrics
- `@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`

### 3.3 Read-through behaviour

#### `Optional<Rule> getRule(String ruleId)`
```
1. key = keyPrefix + ruleId
2. Wrap with circuit breaker:
   a. rule = redisTemplate.opsForValue().get(key)
   b. if rule != null:
        meterRegistry.counter("drools.cache.hit", "layer", "redis").increment()
        return Optional.of(rule)
3. Cache miss:
   meterRegistry.counter("drools.cache.miss", "layer", "redis").increment()
   Optional<Rule> result = delegate.getRule(ruleId)
4. If result.isPresent():
   Wrap with circuit breaker:
     redisTemplate.opsForValue().set(key, result.get(), ttl)
5. Return result
```

If circuit breaker is open during step 2: skip to step 3 (fall through to delegate). Same for step 4: best-effort write, no propagation of cache write failures.

#### `List<Rule> getAllRules()` — bulk SCAN strategy

```
1. Get full key set from delegate's getRuleIds() — authoritative list of "what rules should exist"
2. existing = new HashMap<String, Rule>()
3. With circuit breaker:
   Use SCAN with MATCH=keyPrefix+"*" and COUNT=1000 to iterate
   For each found key: existing[ruleId] = redisTemplate.opsForValue().get(key)
4. missing = expectedRuleIds - existing.keys()
5. If missing.isEmpty():
   meterRegistry.counter("drools.cache.bulk.hit").increment()
   return existing.values()
6. Cache partial-miss:
   meterRegistry.counter("drools.cache.bulk.miss", "missing_count", missing.size()).increment()
   List<Rule> freshRules = delegate.getAllRulesByIds(missing)
     (or delegate.getAllRules() if more efficient — depends on S3 API)
7. For each freshRule:
   redisTemplate.opsForValue().set(keyPrefix + freshRule.id, freshRule, ttl)
   existing[freshRule.id] = freshRule
8. Return existing.values()
```

**Edge case:** If `delegate.getRuleIds()` is itself expensive (S3 LIST is ~50–100ms), cache it too with a shorter TTL (e.g. 1 min) to amortize across rapid refresh calls.

#### `void saveRule(Rule rule)` — write-through
```
1. delegate.saveRule(rule)   // S3 PUT first
2. On success:
   With circuit breaker:
     redisTemplate.opsForValue().set(keyPrefix + rule.ruleId, rule, ttl)
```

#### `void deleteRule(String ruleId)` — write-through delete
```
1. delegate.deleteRule(ruleId)   // S3 DELETE first
2. On success:
   With circuit breaker:
     redisTemplate.delete(keyPrefix + ruleId)
```

#### `boolean ruleExists(String ruleId)`
```
1. With circuit breaker:
     Boolean exists = redisTemplate.hasKey(keyPrefix + ruleId)
     if exists is Boolean.TRUE: return true
2. Fall through to delegate.ruleExists(ruleId)
```
Note: don't populate cache on a `hasKey` true — we don't have the Rule object yet.

#### `long getTotalRuleCount()` / `List<String> getRuleIds()`
- These need authoritative answers (S3 truth), not what's in cache (which may be partial)
- Always delegate to underlying storage

#### `void refreshCache()` (existing interface method)
- This is the entry point AdminController uses on `/admin/refresh-rules`
- New behaviour: `SCAN MATCH keyPrefix+* | DEL` to clear cache, then delegate's `refreshCache()`

#### `void refreshRule(String ruleId)` (existing interface method)
- `DEL keyPrefix+ruleId`, then `delegate.refreshRule(ruleId)`

### 3.4 Failure modes

| Failure | Behaviour |
|---|---|
| Redis down (timeout, conn refused) | Circuit breaker opens after N failures. Reads fall through to delegate. Writes silently dropped (best-effort). Service continues. |
| Redis returns null on hit (entry deleted between SCAN and GET) | Treat as miss. Re-fetch from delegate. |
| `SCAN` returns more keys than expected (stale prefix entries) | Trust delegate's `getRuleIds()` as authoritative. Extra Redis keys live until TTL expires. |
| `set` with TTL fails after delegate write succeeded | Logged WARN, next read repopulates. Acceptable. |
| Circuit breaker config missing (env var typo) | Spring fails on startup with clear error message. |

### 3.5 Stampede prevention

When N tasks start simultaneously with empty Redis, all hit S3. Decision: **accept it.** Reasons:
- S3 handles thousands of req/s per prefix
- Each task fetches once per cold start (not repeated)
- Adding `SETNX`-based single-leader complexity is not justified for 5-task scale
- Documented as acceptable in plan; revisit if scaling to 50+ tasks

---

## 4. Code changes — file by file

### 4.1 New files

#### `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java` (~250 LOC)
See section 3.2–3.3 above for behavioural spec.

#### `src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java` (~250 LOC, ~10 tests)
- Test: `getRule` cache hit returns Redis value, doesn't call delegate
- Test: `getRule` cache miss falls through to delegate, populates Redis with TTL
- Test: `getRule` Redis circuit breaker open — falls through to delegate, doesn't crash
- Test: `getAllRules` bulk SCAN returns complete set from cache when warm
- Test: `getAllRules` partial cache — fetches missing from delegate, populates
- Test: `saveRule` write-through writes Redis after delegate succeeds
- Test: `saveRule` delegate fails — Redis not touched
- Test: `deleteRule` deletes Redis key after delegate succeeds
- Test: `refreshCache` clears all `drools:rule:*` keys then delegates
- Test: `refreshRule(id)` deletes specific key then delegates

### 4.2 Deleted files

| File | Lines | Reason |
|---|---|---|
| `src/main/java/com/company/drools/cache/RuleCache.java` | ~70 | Interface superseded by `RuleStorage` decorator |
| `src/main/java/com/company/drools/cache/LocalLRUCache.java` | ~290 | Dead code, no longer needed |
| `src/main/java/com/company/drools/cache/RedisRuleCache.java` | ~280 | Replaced by `RedisCachedRuleStorage` |
| `src/main/java/com/company/drools/cache/CacheStatistics.java` | ~120 | Only used by deleted caches |
| `src/test/java/com/company/drools/cache/LocalLRUCacheTest.java` | ~14 tests | LRU gone |
| `src/test/java/com/company/drools/cache/RedisRuleCacheTest.java` | ~10 tests | Class gone |
| `src/test/java/com/company/drools/cache/CacheStatisticsTest.java` | ~5 tests | Class gone |

**Verify before deleting `CacheStatistics`:** grep for any usage outside the cache package. If anything else references it, either keep + repurpose or migrate consumers.

### 4.3 Modified files

#### `src/main/java/com/company/drools/storage/StorageFactory.java`

Add a `@Bean` method that wraps the chosen `RuleStorage` with `RedisCachedRuleStorage` when `REDIS_ENABLED=true`:

```java
@Bean
@Primary
public RuleStorage primaryRuleStorage(
    StorageFactory factory,
    @Autowired(required = false) RedisCachedRuleStorage redisCacheDecorator,
    @Value("${redis.enabled:false}") boolean redisEnabled) {
  RuleStorage base = factory.createStorage();   // S3RuleStorage / LocalFileStorage / InMemoryRuleStorage
  if (redisEnabled && redisCacheDecorator != null) {
    redisCacheDecorator.setDelegate(base);
    return redisCacheDecorator;
  }
  return base;
}
```

Alternative: `RedisCachedRuleStorage` takes the delegate via constructor when enabled — simpler. Decide during implementation.

#### `src/main/java/com/company/drools/api/controller/AdminController.java`
- Remove `RuleCache ruleCache` field (line ~60)
- Remove from constructor (line ~85)
- Remove `ruleCache.warmUp()` calls (line 426)
- Remove `ruleCache.clear()` calls (line 416)
- Remove `ruleCache.put()` calls (line 486)
- Remove `ruleCache.contains()` calls (line 528)
- Replace `ruleCache.getStatistics()` calls (lines 248, 339) with Redis-aware stats helper
- `/admin/rules` response: drop `cached` field OR rename to `in_redis` (only present when Redis enabled)
- `/admin/cache/stats` endpoint: return Redis stats when enabled, 404 when disabled
- `/admin/health` cache section: Redis-only when enabled, omitted when disabled
- `/admin/refresh-rules` and `/admin/refresh-rules/{id}`: now invoke `storage.refreshCache()` / `refreshRule(id)` which the decorator handles (no separate cache clear call needed)

#### `src/main/java/com/company/drools/config/RuleLoadingConfig.java`
- Remove `RuleCache ruleCache` constructor param (line ~22)
- Remove the `if (ruleCache.isEnabled() && !rules.isEmpty()) { ruleCache.warmUp(rules); }` block (lines 40–44)

#### `src/main/java/com/company/drools/config/DroolsConfig.java`
- No change. KieContainer build stays identical.

#### `src/main/resources/application.yml`
Remove:
```yaml
drools:
  cache:
    lru-max-size: ${LRU_CACHE_MAX_SIZE:100}      # delete
```
Reword Redis section:
```yaml
redis:
  enabled: ${REDIS_ENABLED:false}
  url: ${REDIS_URL:redis://localhost:6379}
  drl-rules:
    ttl-minutes: ${REDIS_DRL_RULES_TTL_MINUTES:15}
    key-prefix: ${REDIS_DRL_RULES_KEY_PREFIX:drools:rule:}
```

#### `.env.example`
- Remove `LRU_CACHE_MAX_SIZE`
- Remove `REDIS_TTL_MINUTES`
- Add `REDIS_DRL_RULES_TTL_MINUTES=15`
- Add `REDIS_DRL_RULES_KEY_PREFIX=drools:rule:`
- Keep `REDIS_ENABLED`, `REDIS_URL`

#### `docker-compose.yml`
- Remove `LRU_CACHE_MAX_SIZE` env entry
- Add `REDIS_DRL_RULES_TTL_MINUTES=15` (optional, accepts default)

#### Other env-var references
- Search and replace `REDIS_TTL_MINUTES` → `REDIS_DRL_RULES_TTL_MINUTES` across:
  - `application*.yml`
  - `.env.example`
  - `docker-compose*.yml`
  - `project-documentation/09-environment-variables-reference.md`
  - Any deployment scripts

### 4.4 Test changes (beyond new RedisCachedRuleStorageTest)

- `AdminControllerTest.java` — remove `RuleCache` mock; expect no calls to it
- `RuleLoadingConfigTest.java` — remove `RuleCache` mock and warm-up expectations
- `StorageFactoryTest.java` — add test for decorator wiring (Redis on/off)
- `MetricsConfigTest.java` — verify the new `drools.cache.*` metrics shape
- Integration tests:
  - `RuleExecutionIntegrationTest`: no change (execution doesn't touch cache)
  - `RuleRefreshIntegrationTest`: assert Redis is cleared on refresh, repopulated on next read
  - New: `RedisCachedStorageIntegrationTest` (uses TestContainers Redis): full round-trip with real Redis

---

## 5. Configuration & environment variables

### 5.1 New env vars

| Env var | Default | Description |
|---|---|---|
| `REDIS_DRL_RULES_TTL_MINUTES` | `15` | TTL for cached DRL Rule entries in Redis. Acts as safety net + cross-service freshness ceiling. |
| `REDIS_DRL_RULES_KEY_PREFIX` | `drools:rule:` | Redis key prefix for rule entries. Namespaced to support future Redis uses by this service. |

### 5.2 Removed env vars

| Env var | Reason |
|---|---|
| `REDIS_TTL_MINUTES` | Renamed to `REDIS_DRL_RULES_TTL_MINUTES`. Breaking change for operators who set it explicitly. |
| `LRU_CACHE_MAX_SIZE` | LocalLRUCache deleted. |

### 5.3 Unchanged env vars

- `REDIS_ENABLED` — feature flag (default `false`)
- `REDIS_URL` — Redis endpoint
- `DROOLS_CB_REDIS_*` — circuit breaker config (preserved verbatim)

### 5.4 Migration note

Any environment that explicitly sets `REDIS_TTL_MINUTES` or `LRU_CACHE_MAX_SIZE` will silently fall back to defaults on first deploy with new code. Document in release notes.

---

## 6. Refresh strategy (hybrid invalidation)

### 6.1 Full refresh: `POST /admin/refresh-rules`

```
1. Caller hits endpoint
2. AdminController.refreshAllRules():
   a. storage.refreshCache()   // RedisCachedRuleStorage clears `drools:rule:*` via SCAN+DEL
   b. List<Rule> rules = storage.getAllRules()  // miss in Redis, fetches all from S3, repopulates Redis
   c. droolsEngineService.loadRules(rules)  // compiles new KieBase, atomic swap
3. Return summary (rules_loaded, rules_failed, duration_ms)
```

Other ECS tasks: when their next refresh happens (or TTL expires on their reads), they fetch new rules from Redis. Cross-service consumers: see new rules on next access after writer's refresh.

### 6.2 Single-rule refresh: `POST /admin/refresh-rules/{ruleId}`

```
1. storage.refreshRule(ruleId)   // RedisCachedRuleStorage deletes `drools:rule:{id}` then delegates
2. Optional<Rule> rule = storage.getRule(ruleId)  // re-fetches from S3, repopulates Redis
3. droolsEngineService.loadOrReplaceRule(rule.get())   // recompile KieBase with new rule
```

### 6.3 Bulk vs single trade-off

- Single rule update during business hours: use single-rule refresh, fast (<1 sec for fetch + ~46 ms compile + ~1 sec kieContainer swap for 1 rule among 10k)
- Schema-wide updates: use full refresh, slow (~7 min at 10k rules due to compilation)

### 6.4 TTL safety net

15-min TTL ensures:
- Any missed invalidation (e.g., crashed admin call) self-heals within 15 min
- Cross-service consumers have an upper bound on staleness even if writer never refreshes

---

## 7. Cross-service consumer contract

### 7.1 Key naming

- Default: `drools:rule:{ruleId}` (matches today's convention)
- Configurable: `REDIS_DRL_RULES_KEY_PREFIX` env var lets operators choose a different prefix (e.g., `drools:stage:rule:` for environment-tagged keys)

### 7.2 Value format

- JSON serialization of `Rule` object via `Jackson2JsonRedisSerializer<Rule>`
- Fields: `ruleId`, `content` (DRL text), `metadata` (status, version, timestamps, etc.)
- Schema compatibility maintained — same as today's `RedisRuleCache`

### 7.3 Freshness guarantee

- Writer service guarantees: stale entries cleared within seconds after `/admin/refresh-rules` completes (explicit DEL)
- Worst-case staleness: 15 min (TTL) if writer doesn't refresh
- Cross-service consumers should treat reads as best-effort: handle null gracefully (Redis miss = rule may exist in S3 but not yet cached)

### 7.4 Out of scope (v1)

- Pub/Sub notification channel for instant cross-service invalidation
- Multi-region replication
- Per-environment key namespacing (achievable via `REDIS_DRL_RULES_KEY_PREFIX` if needed)

---

## 8. Backward compatibility & rollback

### 8.1 Feature flag

- `REDIS_ENABLED=false` (default) → no Redis bean, no decorator, behaviour identical to today's "Redis disabled" mode
- `REDIS_ENABLED=true` → wraps storage in `RedisCachedRuleStorage`

### 8.2 Rollback path

If Redis caching causes prod issues:
1. Set `REDIS_ENABLED=false` and restart tasks. Service immediately reverts to direct-S3 reads. No data loss (S3 is source of truth).
2. Redis can be left running with stale data — it'll TTL out within 15 min.
3. Code-level rollback: deploy previous service jar tag.

### 8.3 Forward-compat

If Redis schema needs to evolve (e.g., new fields on Rule):
- Bump key prefix: `drools:rule:` → `drools:rule:v2:`
- Old consumers continue reading `drools:rule:*`; new code reads `drools:rule:v2:*`
- Drift naturally on TTL expiry

---

## 9. Phases & effort

| Phase | Scope | Effort | Risk |
|---|---|---|---|
| 1. Build `RedisCachedRuleStorage` | New class + unit tests. Reuse existing `RedisTemplate` + circuit breaker beans. | 2 days | Low |
| 2. Wire `StorageFactory` | Conditional bean wiring; integration test with both flag states. | 0.5 day | Low |
| 3. Delete dead code | Remove `RuleCache` interface, `LocalLRUCache`, `RedisRuleCache`, `CacheStatistics`, related tests and autowire sites. | 1 day | Low — just deletion |
| 4. Adapt `AdminController` + endpoints | Drop `RuleCache` injection, refactor `/admin/rules`, `/admin/cache/stats`, `/admin/health`. | 1 day | Medium — public API shape changes |
| 5. Config + env var migration | Rename `REDIS_TTL_MINUTES` → `REDIS_DRL_RULES_TTL_MINUTES`, add `_KEY_PREFIX`, drop `LRU_CACHE_MAX_SIZE`. Update yml + docker-compose + .env.example. | 0.5 day | Low |
| 6. Integration tests | TestContainers Redis: write-through, read-through, invalidation, circuit-breaker. Verify 597 tests still green minus the deleted ones. | 1.5 days | Medium |
| 7. Documentation | README, CLAUDE.md, 04-architecture, 35-faq, 37-glossary, 36-ADR (new ADR-016 + supersede ADR-004 + ADR-005). | 1 day | Low |
| 8. Load test | Re-run `scripts/run-load-test.sh` with `REDIS_ENABLED=true`. Verify multi-instance refresh savings and no regression. | 1 day | Low |
| 9. Rollout | Stage soak 1 week → prod. Toggle `REDIS_ENABLED=true` in prod once verified in stage. | 1–2 weeks elapsed | Low |

**Total dev effort:** ~8.5 dev-days.
**Calendar time:** 2–3 weeks including stage soak.

---

## 10. Risks & open questions

### 10.1 Risk: AdminController API shape change

`/admin/rules` response `cached` field is removed (or renamed). Any consumer that parses this field breaks.

**Mitigation:** Audit consumers (CI scripts, monitoring dashboards). For at least 1 release, keep the field but always return `false` with a deprecation note. Remove in v2.

### 10.2 Risk: Env var rename `REDIS_TTL_MINUTES → REDIS_DRL_RULES_TTL_MINUTES`

Any deployment scripting that sets the old var will silently use the default.

**Mitigation:** Release note. Optionally, in v1 read both env vars with warning log when old one is set.

### 10.3 Risk: Bulk SCAN performance

At 10,000 rules, a full `SCAN MATCH drools:rule:*` produces 10,000 keys to GET. With Redis ~5ms per GET, that's ~50s — slower than expected for a refresh.

**Mitigation:** Use Redis `MGET` instead of N×GET. Or pipeline. Or accept 50s (still 9x faster than current 8min S3 compile).

### 10.4 Risk: Circuit breaker thrash

If Redis is intermittently slow, the breaker opens, falls through to S3 (slow), then breaker closes, reads from stale-ish Redis again. Could cause oscillation.

**Mitigation:** Existing `DROOLS_CB_REDIS_*` tuning. Validate during stage soak.

### 10.5 Risk: Memory pressure on Redis at 10k rules

10,000 × ~10 KB JSON ≈ 100 MB Redis memory. Manageable on a `cache.t3.micro` (0.5 GB) but tight if memory is shared with other workloads.

**Mitigation:** Document expected Redis memory per rule count. Provision accordingly.

### 10.6 Open question: should `RedisCachedRuleStorage` cache `getRuleIds()` results too?

`getRuleIds()` is called by bulk SCAN logic to determine expected set. If S3 LIST is slow (~100ms) and called frequently, cache it. But staleness matters — if S3 has a new rule, we want to see it.

**Recommendation:** Don't cache `getRuleIds()` in v1. Revisit if it becomes a hot path.

### 10.7 Open question: should `/admin/rules` `cached` field be kept as `in_redis`?

Possible useful field for ops: "is this rule currently in Redis?". Costs: 1 Redis `EXISTS` per rule per listing call. Listing 10k rules = 10k EXISTS = ~50s if naive.

**Recommendation:** Drop the field. Re-introduce via a dedicated `/admin/cache/keys` endpoint if needed.

---

## 11. Critical files — summary

### New
- `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`
- `src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java`
- `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`
- `project-documentation/36-architecture-decision-records.md` — new ADR-016 entry

### Modified
- `src/main/java/com/company/drools/storage/StorageFactory.java` — conditional decorator wiring
- `src/main/java/com/company/drools/api/controller/AdminController.java` — drop RuleCache; refactor stats/health endpoints
- `src/main/java/com/company/drools/config/RuleLoadingConfig.java` — drop RuleCache injection
- `src/main/resources/application.yml` — rename TTL env, drop LRU config
- `.env.example` — env var rename
- `docker-compose.yml` — env var rename
- `scripts/docker-compose.loadtest.yml` — env var rename if present
- `project-documentation/04-architecture.md` — replace caching layer section
- `project-documentation/09-environment-variables-reference.md` — env var changes
- `project-documentation/29-circuit-breakers-and-resilience.md` — update Redis section
- `project-documentation/30-runbooks-and-monitoring.md` — Redis stats reflect actual behaviour now
- `project-documentation/35-faq.md` — update LocalLRUCache/Redis FAQ entries
- `project-documentation/37-glossary.md` — update LRU + Redis glossary entries
- `project-documentation/01-project-overview.md` — caching strategy bullet
- `README.md` — caching architecture section (and Rule Capacity if affected)
- `CLAUDE.md` — caching strategy section
- Test files: `AdminControllerTest`, `RuleLoadingConfigTest`, `StorageFactoryTest`, `MetricsConfigTest`, `RuleRefreshIntegrationTest`

### Deleted
- `src/main/java/com/company/drools/cache/RuleCache.java`
- `src/main/java/com/company/drools/cache/LocalLRUCache.java`
- `src/main/java/com/company/drools/cache/RedisRuleCache.java`
- `src/main/java/com/company/drools/cache/CacheStatistics.java` (if no other consumers)
- `src/test/java/com/company/drools/cache/LocalLRUCacheTest.java`
- `src/test/java/com/company/drools/cache/RedisRuleCacheTest.java`
- `src/test/java/com/company/drools/cache/CacheStatisticsTest.java`

---

## 12. Verification — acceptance criteria

1. **`REDIS_ENABLED=false`** end-to-end:
   - Service boots without a Redis bean
   - `/admin/health` does not show a cache section (or shows `cache: { enabled: false }`)
   - `/admin/cache/stats` returns 404 (or 200 with `{enabled: false}`)
   - 597 - deleted tests = ~573 tests pass
   - Load test latency identical to today
2. **`REDIS_ENABLED=true`** end-to-end:
   - Service boots, `RedisCachedRuleStorage` injected as `RuleStorage` `@Primary`
   - First refresh hits S3, repopulates Redis
   - Second refresh (same instance, before TTL): reads from Redis (verify via metrics)
   - New instance startup: reads from Redis when populated (verify via instrumented log)
   - `/admin/cache/stats` returns Redis-only stats with non-zero hit count after warm
   - `/admin/health` cache section shows Redis status
3. **Redis-down failure mode** (kill Redis container with `REDIS_ENABLED=true`):
   - Service continues serving execution requests
   - Refresh succeeds (falls through to S3)
   - Circuit breaker metric shows OPEN state
   - When Redis comes back: breaker closes, reads resume from Redis
4. **Refresh invalidation:**
   - Populate Redis with rule v1 via refresh
   - Replace rule in S3 with v2
   - Call `/admin/refresh-rules`
   - Verify `drools:rule:{id}` in Redis now holds v2 (not v1)
   - Verify no `v1` content lingers
5. **Cross-service freshness:**
   - Instance A refreshes, Redis cleared and re-warmed
   - Instance B reads from Redis (or external service reads `drools:rule:*`): sees v2
   - TTL ceiling: even if A doesn't refresh, B reads stop returning stale entries after 15 min
6. **Migration:**
   - With old `REDIS_TTL_MINUTES=120` set: log warns "deprecated env var", uses 15-min default
   - With new `REDIS_DRL_RULES_TTL_MINUTES=120`: actually uses 120
7. **Load test** (`scripts/run-load-test.sh`):
   - P99 execution latency: identical to baseline
   - Refresh time with `REDIS_ENABLED=true` on second + tasks: <50% of `REDIS_ENABLED=false`
   - No memory regression
   - No new error patterns
8. **Stage soak** (1 week):
   - No new error patterns
   - Cache hit rate stable (expected ~95%+ for warm cache)
   - No incidents

---

## 13. Documentation impact

- **README.md**
  - Caching architecture section (currently in Performance Targets area): rewrite to describe Redis-only model
  - Rule Capacity & Memory Sizing section: update to remove LRU references
- **CLAUDE.md**
  - Caching Strategy bullet: simplified to "Redis cache (optional) decorating S3"
- **project-documentation/00-system-overview.md**
  - Architecture overview: simpler caching tier
- **project-documentation/04-architecture.md**
  - Caching layer section: remove L1, simplify to Redis-or-direct
  - Update diagrams
- **project-documentation/29-circuit-breakers-and-resilience.md**
  - Redis breaker description: now actually exercised
- **project-documentation/35-faq.md**
  - Update LRU + Redis FAQs (some entries become irrelevant)
- **project-documentation/36-architecture-decision-records.md**
  - **Mark ADR-004 (LocalLRUCache uses WRITE lock on `get()`) as Superseded** (cache deleted)
  - **Mark ADR-005 (Redis bean exists but is dormant by default) as Superseded** (Redis now actually used)
  - **Add ADR-016: Redis decorator pattern over RuleStorage** — captures the design decisions in this plan
- **project-documentation/37-glossary.md**
  - Remove "LRU cache" entry
  - Update "RedisRuleCache" entry → "RedisCachedRuleStorage"
- **project-documentation/01-project-overview.md**
  - Caching strategy bullet: refresh
- **project-documentation/09-environment-variables-reference.md**
  - Env var changes

---

## 14. Metrics & observability

### 14.1 New metrics (when `REDIS_ENABLED=true`)
- `drools.cache.hit{layer=redis}` — counter
- `drools.cache.miss{layer=redis}` — counter
- `drools.cache.bulk.hit` / `drools.cache.bulk.miss` — counter
- `drools.cache.read.duration{layer=redis}` — timer
- `drools.cache.write.duration{layer=redis}` — timer
- `drools.cache.invalidation{scope=bulk|single}` — counter
- `drools.cache.size{layer=redis}` — gauge (sampled via SCAN COUNT, every N seconds)

### 14.2 Removed metrics
- All `drools.cache.local.*` metrics (LRU is gone)

### 14.3 Health check
- `/admin/health` cache section shows Redis status when enabled, omitted when disabled
- Redis connection check via simple `PING` (existing `RedisTemplate` capability)

---

## 15. Recommendation summary

- **Do it.** The current cache layer is dead weight that misleads readers and provides no benefit. Replacing it with a real read-through decorator is mostly subtractive (deleting > adding).
- **Phase it.** Build the decorator + tests first. Don't touch execution path. Soak in stage before flipping prod.
- **Keep `REDIS_ENABLED=false` as default.** Conservative; ECS tasks opt in.
- **Coordinate with kjar plan.** Both are independent and complementary. Redis caching helps S3 fetch time; kjar eliminates compile time. Don't gate one on the other.

---

## 16. Companion files

- Detailed checklist: [`redis-cache-layering-checklist.md`](redis-cache-layering-checklist.md)
- Cross-reference: [`kjar-precompilation-plan.md`](kjar-precompilation-plan.md)
