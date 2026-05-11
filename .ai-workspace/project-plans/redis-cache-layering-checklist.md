# Redis-only DRL Cache Deployment — Implementation Checklist

**Status:** Not started
**Plan:** [`redis-cache-layering-plan.md`](redis-cache-layering-plan.md)
**Branch:** TBD (suggest `feature/redis-cache-decorator`)
**Target effort:** ~8.5 dev-days
**Target calendar time:** 2–3 weeks including stage soak

---

## Phase 0 — Pre-flight & audit

Goal: confirm what's actually used and lock the deletion scope before writing code.

- [ ] `git checkout -b feature/redis-cache-decorator`
- [ ] Confirm 597 tests pass on main (baseline)
- [ ] Audit `CacheStatistics` usage — `grep -r "CacheStatistics" src/` — verify no consumer outside `cache/` package; document if any
- [ ] Audit `/admin/rules` `cached` field consumers:
  - [ ] Search CI scripts, monitoring dashboards, external clients
  - [ ] Document everyone affected by removing/renaming the field
- [ ] Audit `REDIS_TTL_MINUTES` consumers in any deployment config or runbook
- [ ] Confirm existing Redis circuit breaker beans (`redisCircuitBreaker`) wired in `CircuitBreakerConfig.java`
- [ ] Capture baseline metrics: `drools.cache.*` shapes today (so we can compare after)
- [ ] **Decision gate:** if anything in the audit reveals a hidden consumer of dead-cache code, scope expands; otherwise proceed

---

## Phase 1 — Build `RedisCachedRuleStorage`

Goal: new decorator class with full test coverage, but not yet wired in.

### 1.1 Skeleton + constructor

- [ ] Create `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`
- [ ] `implements RuleStorage`
- [ ] `@Component @ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`
- [ ] Constructor injection (final fields):
  - [ ] `RuleStorage delegate` (qualifier strategy TBD — see Phase 2)
  - [ ] `RedisTemplate<String, Rule> redisTemplate`
  - [ ] `@Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker`
  - [ ] `@Value("${redis.drl-rules.ttl-minutes:15}") long ttlMinutes`
  - [ ] `@Value("${redis.drl-rules.key-prefix:drools:rule:}") String keyPrefix`
  - [ ] `MeterRegistry meterRegistry`
- [ ] Compute `Duration ttl` from `ttlMinutes` in constructor
- [ ] `redisKey(String ruleId)` private helper: `keyPrefix + ruleId`
- [ ] `ruleIdFromKey(String key)` private helper: strip `keyPrefix`

### 1.2 Read methods

- [ ] `Optional<Rule> getRule(String ruleId)`:
  - [ ] Wrap Redis GET with circuit breaker
  - [ ] On hit: increment `drools.cache.hit{layer=redis}` and return
  - [ ] On miss: increment `drools.cache.miss{layer=redis}`, delegate, populate cache, return
  - [ ] On circuit-open: fall through to delegate, no cache populate
- [ ] `List<Rule> getAllRules()` — bulk SCAN:
  - [ ] Get expected `ruleIds` via `delegate.getRuleIds()`
  - [ ] SCAN `keyPrefix*` with COUNT=1000, collect found keys
  - [ ] MGET found keys (single round-trip), build map
  - [ ] Compute `missing` set
  - [ ] If empty: increment bulk hit, return cached values
  - [ ] Otherwise: fetch missing from delegate, populate cache via pipeline/MSET, return combined
  - [ ] If circuit open: pure delegate call
- [ ] `boolean ruleExists(String ruleId)`:
  - [ ] Redis `EXISTS` (circuit-breaker wrapped) — return true on hit
  - [ ] Otherwise delegate

### 1.3 Write methods

- [ ] `void saveRule(Rule rule)`:
  - [ ] `delegate.saveRule(rule)` first
  - [ ] On success: SET with TTL via circuit breaker (best-effort, log WARN on fail)
- [ ] `void deleteRule(String ruleId)`:
  - [ ] `delegate.deleteRule(ruleId)` first
  - [ ] On success: DEL Redis key (best-effort)
- [ ] `void refreshCache()`:
  - [ ] SCAN `keyPrefix*` + DEL via pipeline (with circuit breaker)
  - [ ] Then `delegate.refreshCache()`
- [ ] `void refreshRule(String ruleId)`:
  - [ ] `DEL redisKey(ruleId)` (with circuit breaker)
  - [ ] Then `delegate.refreshRule(ruleId)`

### 1.4 Pass-through methods

- [ ] `long getTotalRuleCount()` → `delegate.getTotalRuleCount()` (don't trust cache)
- [ ] `List<String> getRuleIds()` → `delegate.getRuleIds()` (authoritative)

### 1.5 Metrics

- [ ] Register counters: `drools.cache.hit{layer=redis}`, `drools.cache.miss{layer=redis}`, `drools.cache.bulk.hit`, `drools.cache.bulk.miss`
- [ ] Register timers: `drools.cache.read.duration{layer=redis}`, `drools.cache.write.duration{layer=redis}`
- [ ] Register counter: `drools.cache.invalidation{scope=bulk|single}`
- [ ] Register gauge: `drools.cache.size{layer=redis}` via periodic SCAN COUNT

### 1.6 Unit tests

Create `src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java`:

- [ ] Mock `RuleStorage delegate`, `RedisTemplate`, `CircuitBreaker`, `MeterRegistry`
- [ ] Test: `getRule` Redis hit returns cached Rule, no delegate call, hit metric incremented
- [ ] Test: `getRule` Redis miss → delegate called → Redis populated with correct TTL
- [ ] Test: `getRule` circuit-breaker open → falls through to delegate, no cache write attempted
- [ ] Test: `getRule` delegate returns empty → no cache write
- [ ] Test: `getAllRules` warm cache (all keys present) → no delegate call
- [ ] Test: `getAllRules` partial cache → delegate called only for missing, missing populated
- [ ] Test: `getAllRules` circuit open → pure delegate
- [ ] Test: `saveRule` write-through populates Redis after delegate success
- [ ] Test: `saveRule` delegate throws → Redis not touched
- [ ] Test: `deleteRule` deletes Redis key after delegate success
- [ ] Test: `refreshCache` SCAN+DEL invoked before delegate
- [ ] Test: `refreshRule(id)` DEL invoked then delegate

### 1.7 Phase 1 gate

- [ ] All new tests pass
- [ ] `mvn compile spotbugs:check` clean on the new class
- [ ] Existing 597 tests still pass (no wiring change yet)
- [ ] `git commit` — "feat(cache): Phase 1 — RedisCachedRuleStorage decorator with unit tests"

---

## Phase 2 — Wire `StorageFactory`

Goal: when `REDIS_ENABLED=true`, the `RuleStorage` bean injected everywhere is the decorator.

### 2.1 Wiring strategy

Pick one (decide during implementation):

**Option A — Decorator chooses delegate at construction:**
- `RedisCachedRuleStorage` constructor takes `@Qualifier("s3RuleStorage") RuleStorage delegate`
- Drop `@Primary` from `S3RuleStorage`
- `@Primary` on `RedisCachedRuleStorage` (only created when Redis enabled)
- `StorageFactory` unchanged

**Option B — Factory wraps at runtime:**
- `StorageFactory.primaryRuleStorage()` reads `REDIS_ENABLED` and wraps if true
- More explicit but adds factory complexity

- [ ] Pick A or B; document in commit message
- [ ] If A:
  - [ ] Add `@Qualifier("s3RuleStorage")` (or similar) on relevant beans
  - [ ] Remove `@Primary` from `S3RuleStorage` / `LocalFileStorage` / `InMemoryRuleStorage`
  - [ ] Add `@Primary` to `RedisCachedRuleStorage`
- [ ] If B:
  - [ ] Add `@Bean @Primary primaryRuleStorage(...)` method in `StorageFactory`
  - [ ] Conditional logic on `redis.enabled`

### 2.2 Verify wiring

- [ ] Start app with `REDIS_ENABLED=false` → log confirms `S3RuleStorage` (or appropriate base) injected as primary
- [ ] Start app with `REDIS_ENABLED=true` → log confirms `RedisCachedRuleStorage` injected as primary
- [ ] Smoke test: execute a rule in both modes

### 2.3 Phase 2 gate

- [ ] Wiring correct in both modes
- [ ] Existing 597 tests pass
- [ ] `git commit` — "feat(cache): Phase 2 — wire RedisCachedRuleStorage as primary when REDIS_ENABLED=true"

---

## Phase 3 — Delete dead code

Goal: remove `RuleCache` and its implementations completely. Atomic phase — many compilation errors will surface; resolve all.

### 3.1 Pre-deletion: remove call sites

- [ ] `AdminController.java`:
  - [ ] Remove `RuleCache ruleCache` field
  - [ ] Remove from constructor
  - [ ] Remove `ruleCache.warmUp()` calls (~line 426)
  - [ ] Remove `ruleCache.clear()` calls (~line 416)
  - [ ] Remove `ruleCache.put()` calls (~line 486)
  - [ ] Remove `ruleCache.contains()` calls (~line 528)
  - [ ] Replace `ruleCache.getStatistics()` calls (~lines 248, 339) with Redis-aware helper (new method `getCacheStatistics()` that returns Redis stats when enabled or null)
- [ ] `RuleLoadingConfig.java`:
  - [ ] Remove `RuleCache ruleCache` constructor param
  - [ ] Remove warm-up block (~lines 40–44)
- [ ] Any other `@Autowired RuleCache` injection — find via `grep -r "@Autowired RuleCache\|RuleCache ruleCache\|RuleCache cache" src/main/`
- [ ] Test class equivalents — find via `grep -rn "RuleCache" src/test/`

### 3.2 Delete classes

- [ ] `git rm src/main/java/com/company/drools/cache/RuleCache.java`
- [ ] `git rm src/main/java/com/company/drools/cache/LocalLRUCache.java`
- [ ] `git rm src/main/java/com/company/drools/cache/RedisRuleCache.java`
- [ ] `git rm src/main/java/com/company/drools/cache/CacheStatistics.java` (verify no other consumers first, per Phase 0 audit)
- [ ] `git rm src/test/java/com/company/drools/cache/LocalLRUCacheTest.java`
- [ ] `git rm src/test/java/com/company/drools/cache/RedisRuleCacheTest.java`
- [ ] `git rm src/test/java/com/company/drools/cache/CacheStatisticsTest.java`
- [ ] If `cache/` directory becomes empty: `rmdir src/main/java/com/company/drools/cache/`

### 3.3 Resolve compilation errors

- [ ] `mvn compile` — fix all errors
- [ ] `mvn test` — fix all test failures (likely `AdminControllerTest`, `RuleLoadingConfigTest`)

### 3.4 Phase 3 gate

- [ ] `mvn clean compile` succeeds
- [ ] `mvn test` succeeds (~573 tests pass, down from 597 — the deleted ones)
- [ ] No reference to `RuleCache`, `LocalLRUCache`, `RedisRuleCache`, `CacheStatistics` in production code
- [ ] `git commit` — "refactor(cache): Phase 3 — delete dead RuleCache abstraction and impls"

---

## Phase 4 — Adapt `AdminController` + endpoints

Goal: cache observability endpoints reflect reality.

### 4.1 `/admin/rules` response

- [ ] Decide final shape:
  - [ ] **Recommended:** drop `cached` field entirely
  - [ ] **Alternative:** keep as `in_redis` only when `REDIS_ENABLED=true`; omit otherwise
- [ ] Update `AdminController.listRules()` accordingly
- [ ] Update `RuleListResponse` DTO
- [ ] Update OpenAPI spec at `project-documentation/api-reference/openapi.yml`

### 4.2 `/admin/cache/stats` endpoint

- [ ] When `REDIS_ENABLED=false`:
  - [ ] Return 404 OR return `200 {"enabled": false}` (pick one for consistency with health)
- [ ] When `REDIS_ENABLED=true`:
  - [ ] Return Redis-only stats: hit rate, miss count, current size (via SCAN COUNT), circuit breaker state
- [ ] Update test `AdminControllerTest.cacheStats*` cases

### 4.3 `/admin/health` cache section

- [ ] `REDIS_ENABLED=false`: omit `cache` component OR show `cache: { enabled: false }`
- [ ] `REDIS_ENABLED=true`: show Redis status only (no LRU section)

### 4.4 Refresh endpoints

- [ ] `/admin/refresh-rules` no longer needs separate `ruleCache.clear()` call — `storage.refreshCache()` handles it (decorator)
- [ ] `/admin/refresh-rules/{ruleId}` same — `storage.refreshRule(ruleId)` handles it
- [ ] Verify in logs that Redis keys are deleted then re-fetched

### 4.5 Update tests

- [ ] `AdminControllerTest.java`:
  - [ ] Remove `RuleCache` mock
  - [ ] Test `/admin/rules` shape (no `cached`)
  - [ ] Test `/admin/cache/stats` 404/empty in disabled mode
  - [ ] Test `/admin/cache/stats` with Redis stats in enabled mode
  - [ ] Test `/admin/health` cache section behaviour
- [ ] `AdminControllerIntegrationTest` (if exists)

### 4.6 Phase 4 gate

- [ ] All admin endpoint behaviours verified in both modes
- [ ] `mvn test` clean
- [ ] `git commit` — "refactor(cache): Phase 4 — AdminController and observability endpoints reflect Redis-only model"

---

## Phase 5 — Config + env var migration

Goal: rename env vars to namespaced form, drop dead ones.

### 5.1 `application.yml`

- [ ] Remove `drools.cache.lru-max-size`
- [ ] Replace `redis.ttl-minutes` with nested:
  ```yaml
  redis:
    drl-rules:
      ttl-minutes: ${REDIS_DRL_RULES_TTL_MINUTES:15}
      key-prefix: ${REDIS_DRL_RULES_KEY_PREFIX:drools:rule:}
  ```
- [ ] Keep `redis.enabled`, `redis.url`

### 5.2 Per-profile yml fragments

- [ ] Check `application-*.yml` files (dev, docker, prod, test)
- [ ] Update each to match new schema

### 5.3 `.env.example`

- [ ] Remove `LRU_CACHE_MAX_SIZE`
- [ ] Remove `REDIS_TTL_MINUTES`
- [ ] Add `REDIS_DRL_RULES_TTL_MINUTES=15`
- [ ] Add `REDIS_DRL_RULES_KEY_PREFIX=drools:rule:`

### 5.4 `docker-compose.yml`

- [ ] Remove `LRU_CACHE_MAX_SIZE` env entry in `app` service
- [ ] Add `REDIS_DRL_RULES_TTL_MINUTES=15` (optional)

### 5.5 Search-and-replace across deployment artifacts

- [ ] `grep -rn "REDIS_TTL_MINUTES\|LRU_CACHE_MAX_SIZE"` — fix every hit:
  - [ ] `scripts/docker-compose.loadtest.yml`
  - [ ] `scripts/lib/stack.sh` (if any)
  - [ ] Documentation files
- [ ] Update `project-documentation/09-environment-variables-reference.md`

### 5.6 Optional: backward-compat env var read

- [ ] If we want to be nice: read `REDIS_TTL_MINUTES` as fallback with WARN log:
  ```java
  @Value("${redis.drl-rules.ttl-minutes:${redis.ttl-minutes:15}}")
  long ttlMinutes;
  ```
- [ ] Document deprecation timeline (remove fallback after 1 release)

### 5.7 Phase 5 gate

- [ ] App boots with new env var names
- [ ] App boots with old env var name + warning (if backward-compat enabled)
- [ ] `mvn test` clean
- [ ] `git commit` — "config(cache): Phase 5 — namespace TTL/prefix env vars under redis.drl-rules, drop LRU config"

---

## Phase 6 — Integration tests

Goal: end-to-end coverage with real Redis (TestContainers).

### 6.1 New integration test

- [ ] Create `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`
- [ ] `@SpringBootTest` with TestContainers Redis (use existing pattern if Redis containers are already wired in tests; otherwise add Testcontainers dep)
- [ ] `@TestPropertySource(properties = {"redis.enabled=true", "redis.url=redis://...", ...})`
- [ ] Tests:
  - [ ] Cold cache: `storage.getRule(id)` → hits S3 (mock or LocalStack) → populates Redis → next call hits Redis
  - [ ] `getAllRules()` bulk SCAN with N rules — verify all returned, hit/miss counts
  - [ ] `refreshCache()` clears Redis keys
  - [ ] `refreshRule(id)` clears single key
  - [ ] Redis kill mid-test → circuit breaker opens → reads still succeed via S3
  - [ ] Stop and start Redis → breaker recovers, reads from Redis again

### 6.2 Update existing integration tests

- [ ] `RuleRefreshIntegrationTest`:
  - [ ] Assert Redis is cleared on refresh (when Redis enabled)
  - [ ] Assert Redis is repopulated on next read
- [ ] `RuleExecutionIntegrationTest`:
  - [ ] No change needed (execution doesn't touch cache)

### 6.3 Smoke test in dev compose

- [ ] `docker compose up -d` with `REDIS_ENABLED=true`
- [ ] Manually:
  - [ ] Hit `/admin/refresh-rules`
  - [ ] Verify Redis has `drools:rule:*` keys: `docker exec redis redis-cli KEYS 'drools:rule:*' | wc -l`
  - [ ] Hit `/admin/cache/stats` — verify hit counts grow with subsequent reads
  - [ ] Kill Redis container — verify service still serves
  - [ ] Restart Redis — verify breaker recovers

### 6.4 Phase 6 gate

- [ ] All integration tests pass
- [ ] Manual smoke test in dev compose passes
- [ ] `mvn clean verify -Dtest='!S3StorageIntegrationTest'` clean
- [ ] `git commit` — "test(cache): Phase 6 — integration tests for RedisCachedRuleStorage"

---

## Phase 7 — Documentation

### 7.1 New ADR

- [ ] Add **ADR-016: Redis decorator pattern over RuleStorage** in `project-documentation/36-architecture-decision-records.md`:
  - [ ] Status: Accepted (date when merged)
  - [ ] Context: dead `RuleCache` layer, no real multi-instance caching
  - [ ] Decision: `RedisCachedRuleStorage` decorator on `RuleStorage` interface, feature-flagged
  - [ ] Consequences: positive (real cache, ~600 LOC removed, cross-service consumers), negative (env var rename, `/admin/rules` shape change)
  - [ ] Alternatives: keep LRU as L1, keep dead code, full delete without replacement
- [ ] Mark **ADR-004 (LocalLRUCache uses WRITE lock on `get()`)** as Superseded by ADR-016
- [ ] Mark **ADR-005 (Redis bean exists but is dormant by default)** as Superseded by ADR-016

### 7.2 Update existing docs

- [ ] `README.md`:
  - [ ] Caching architecture section: replace LRU+Redis story with Redis-only decorator
  - [ ] Rule Capacity & Memory Sizing section: remove LRU references
- [ ] `CLAUDE.md`:
  - [ ] Caching Strategy bullet: simplified to "Redis cache (optional decorator over S3)"
  - [ ] Add Recent change log entry
- [ ] `project-documentation/00-system-overview.md`:
  - [ ] Architecture overview: simpler caching tier
- [ ] `project-documentation/01-project-overview.md`:
  - [ ] Caching strategy row: update to Redis-only decorator
- [ ] `project-documentation/04-architecture.md`:
  - [ ] Caching layer section: remove L1, simplify to Redis-or-direct
  - [ ] Update ASCII diagrams
- [ ] `project-documentation/09-environment-variables-reference.md`:
  - [ ] Remove `REDIS_TTL_MINUTES`, `LRU_CACHE_MAX_SIZE`
  - [ ] Add `REDIS_DRL_RULES_TTL_MINUTES`, `REDIS_DRL_RULES_KEY_PREFIX`
- [ ] `project-documentation/29-circuit-breakers-and-resilience.md`:
  - [ ] Update Redis section: cache is now actually exercised, breaker is meaningful
- [ ] `project-documentation/30-runbooks-and-monitoring.md`:
  - [ ] Update Redis stats explanation
  - [ ] Add runbook for "Redis cache stale" scenario
- [ ] `project-documentation/35-faq.md`:
  - [ ] Remove or rewrite LRU questions
  - [ ] Update Redis questions for new behaviour
- [ ] `project-documentation/37-glossary.md`:
  - [ ] Remove "LRU cache" entry
  - [ ] Update "RedisRuleCache" entry → "RedisCachedRuleStorage" with new behaviour
- [ ] `project-documentation/api-reference/openapi.yml`:
  - [ ] Update `/admin/rules` schema (drop `cached` or rename `in_redis`)
  - [ ] Update `/admin/cache/stats` schema for Redis-only stats

### 7.3 Phase 7 gate

- [ ] All docs reviewed and updated
- [ ] `grep -rn "LocalLRUCache\|RuleCache " project-documentation/` returns only historical / ADR references
- [ ] `git commit` — "docs(cache): Phase 7 — documentation update for Redis decorator + ADR-016"

---

## Phase 8 — Load test

Goal: prove no regression and validate multi-instance savings.

### 8.1 Single-instance regression test

- [ ] Run `./scripts/run-load-test.sh` with `REDIS_ENABLED=false`
- [ ] Compare against historical baseline (157,754 reqs, 0 errors, ~518 RPS)
- [ ] Acceptance: P99 latency within 10% of baseline

### 8.2 Multi-instance benefit test

- [ ] Run `./scripts/run-load-test.sh` with `REDIS_ENABLED=true`
- [ ] Optional: spin up 2 app containers to simulate multi-instance refresh
- [ ] Verify:
  - [ ] Second container's refresh shows Redis hits (via `drools.cache.hit{layer=redis}` counter)
  - [ ] Refresh time on second + tasks is substantially less than first task

### 8.3 Failure-mode load test

- [ ] Start load test with `REDIS_ENABLED=true`
- [ ] Mid-test: kill Redis container
- [ ] Verify: error rate stays at 0% (circuit breaker engages, falls through to S3)
- [ ] Restart Redis
- [ ] Verify: breaker closes, Redis hits resume

### 8.4 Phase 8 gate

- [ ] Single-instance: no regression
- [ ] Multi-instance: measurable Redis hit benefit
- [ ] Failure mode: graceful degradation
- [ ] `git commit` — "perf(cache): Phase 8 — load test results — Redis caching live, no regression"

---

## Phase 9 — Phased rollout

### 9.1 Stage

- [ ] Deploy to stage with `REDIS_ENABLED=false` first (sanity check that disabled path works post-refactor)
- [ ] After 24h clean, flip stage to `REDIS_ENABLED=true`
- [ ] Monitor for 1 week:
  - [ ] Error rate
  - [ ] P99 latency
  - [ ] Redis hit/miss metrics
  - [ ] Circuit breaker state
  - [ ] Heap usage
- [ ] Trigger refresh, verify Redis invalidation working
- [ ] Kill Redis briefly, verify graceful fallback

### 9.2 Prod

- [ ] Schedule prod deploy
- [ ] Deploy with `REDIS_ENABLED=false` first
- [ ] After 24h soak, flip to `REDIS_ENABLED=true`
- [ ] Monitor for 48h
- [ ] Declare success

### 9.3 Phase 9 gate

- [ ] Prod running on Redis-enabled mode for 1 week with no incidents
- [ ] Cross-service consumer (if any) confirms freshness

---

## Phase 10 — Backward-compat cleanup (optional, +1 release)

- [ ] Remove `REDIS_TTL_MINUTES` fallback if Phase 5.6 was implemented
- [ ] Remove deprecation log line
- [ ] Document in release notes

---

## Sign-offs (fill in as you go)

| Phase | Owner | Date completed | Notes |
|---|---|---|---|
| 0. Pre-flight | | | |
| 1. Build decorator | | | |
| 2. Wire factory | | | |
| 3. Delete dead code | | | |
| 4. Adapt endpoints | | | |
| 5. Config migration | | | |
| 6. Integration tests | | | |
| 7. Documentation | | | |
| 8. Load test | | | |
| 9. Rollout | | | |
| 10. Backward-compat cleanup | | | (target +1 release) |

---

## Risk log (update as risks emerge)

| Date | Risk | Severity | Mitigation | Status |
|---|---|---|---|---|
| 2026-05-11 | `/admin/rules` `cached` field consumers might break | Med | Phase 0 audit, optional one-release deprecation period | Open |
| 2026-05-11 | `REDIS_TTL_MINUTES` rename silently uses default for operators with old config | Med | Release note + optional fallback (Phase 5.6) | Open |
| 2026-05-11 | Bulk SCAN at 10k rules slow if naive | Med | Use MGET / pipelining in implementation | Open |
| 2026-05-11 | Circuit breaker thrash on flaky Redis | Low | Tune `DROOLS_CB_REDIS_*`; observe in stage | Open |
| 2026-05-11 | Redis memory pressure at 10k × 10 KB = ~100 MB | Low | Document expected sizing; provision properly | Open |
| 2026-05-11 | `CacheStatistics` used outside cache package — would block delete | Low | Phase 0 audit | Open |

---

## Verification commands

```bash
# Phase 0 — audit
grep -rn "CacheStatistics" src/
grep -rn "REDIS_TTL_MINUTES\|LRU_CACHE_MAX_SIZE" .

# Phase 1 — build decorator
mvn test -Dtest=RedisCachedRuleStorageTest

# Phase 3 — verify dead code gone
grep -rn "RuleCache " src/main/java/  # interface usages
grep -rn "LocalLRUCache\|RedisRuleCache\|CacheStatistics" src/

# Phase 6 — integration
mvn clean verify -Dtest='!S3StorageIntegrationTest'

# Phase 8 — load test
./scripts/run-load-test.sh                          # REDIS_ENABLED=false baseline
REDIS_ENABLED=true ./scripts/run-load-test.sh       # with Redis

# Phase 9 — smoke
curl -X POST http://stage/admin/refresh-rules -H "X-Admin-API-Key: $KEY"
docker exec redis redis-cli KEYS 'drools:rule:*' | head
curl http://stage/admin/cache/stats | jq
```

---

## Migration checklist for operators

When promoting this change:

- [ ] Update `.env` / ECS task definitions:
  - [ ] Replace `REDIS_TTL_MINUTES` → `REDIS_DRL_RULES_TTL_MINUTES` (or accept default 15)
  - [ ] Remove `LRU_CACHE_MAX_SIZE` (no-op)
  - [ ] Optionally set `REDIS_DRL_RULES_KEY_PREFIX` (default `drools:rule:`)
- [ ] Verify `REDIS_ENABLED` matches intent
- [ ] Update monitoring dashboards: cache hit-rate metric source is now Redis-only
- [ ] Update any CI scripts that parse `/admin/rules` `cached` field (if kept, becomes `in_redis`; if dropped, remove field reference)
- [ ] Provision Redis memory if using 10k+ rules (~100 MB at 10k rules)
- [ ] Document for downstream services that consume `drools:rule:*`: TTL contract is 15 min; freshness improves on writer's refresh
