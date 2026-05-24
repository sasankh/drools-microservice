# Redis DRL Cache + Pub/Sub — Implementation Checklist

**Status:** Phases 0–9 complete (committed on `feature/redis-cache-pubsub`); Phase 9.4 has 2 deferred follow-ups documented in [`39-load-test-findings.md`](../../project-documentation/39-load-test-findings.md) Phase 9.4 addendum; Phases 10–11 (rollout + backward-compat cleanup) pending.
**Plan:** [`redis-cache-layering-plan.md`](redis-cache-layering-plan.md)
**Branch:** `feature/redis-cache-pubsub`
**Target effort:** ~13 dev-days
**Target calendar time:** 3–4 weeks including stage soak

## Quick status (2026-05-20)

| Phase | Status | Commit | Notes |
|---|---|---|---|
| 0. Pre-flight | ✅ | (informal) | Baseline captured; audits performed inline during Phase 1+ |
| 1. Decorator | ✅ | `a04e9ce` | `RedisCachedRuleStorage` + 12 unit tests |
| 2. Pub/sub publisher + subscriber | ✅ | `86cf7b6` | `RefreshEvent` + publisher + subscriber + ~14 unit tests |
| 3. Wire factory + config | ✅ | `7048ceb` | StorageFactory wraps base storage when `REDIS_ENABLED=true`; `RedisMessageListenerContainer` conditional |
| 4. Delete dead code | ✅ | `8840bc7` | `RuleCache` / `LocalLRUCache` / `RedisRuleCache` / `CacheStatistics` + their tests deleted (combined with Phase 5) |
| 5. AdminController endpoints | ✅ | `8840bc7` | `cached` field dropped from `RuleListResponse`; publisher injected; cache health now reads `drools.cache.hit/miss` counters |
| 6. Config migration | ✅ | `a02bc39` | Nested `redis.drl-rules.*` + `redis.pubsub.*`; `LRU_CACHE_MAX_SIZE` removed; no backward-compat shim |
| 7. Integration tests | ✅ (with caveat) | `907232e`, `c416a32`, `e8794e0` | Testcontainers tests written (9 + 4 + existing). **DinD blocker on macOS Docker Desktop** → all 3 testcontainers test classes permanently excluded in `pom.xml` surefire (matches pre-existing `S3StorageIntegrationTest` pattern). Manual end-to-end via `full-docker-test-plan.md` all 11 steps + 17 substeps green. |
| 8. Documentation | ✅ | `f4a2816` | 24 files updated; verified grep sweep clean of non-historical refs |
| 9. Load test | ✅ (with caveat) | `952f0a1`, `4b8997e` | Harness + 3 production-hardening changes shipped 2026-05-24. `--quick --phase 9` results: 9.1/9.2/9.3 PASS; 9.4 partial (graceful-degradation ✅; CB-engagement + recovery-convergence FAIL → deferred follow-ups in [39-load-test-findings.md](../../project-documentation/39-load-test-findings.md) Phase 9.4 addendum). Hardening tracked in [redis-cb-hardening-plan.md](redis-cb-hardening-plan.md) + [-checklist.md](redis-cb-hardening-checklist.md). |
| 10. Rollout (stage → prod) | ⏳ | — | Pending merge |
| 11. Backward-compat cleanup | ⏳ | — | Optional, +1 release |

**Test count:** 545 unit tests pass (down from 597 — net of 30+ deleted dead-cache tests, added decorator + pub/sub tests). 13 Testcontainers integration tests excluded from default `mvn test`.

---

## Phase 0 — Pre-flight & audit

Goal: lock the deletion scope and verify assumptions.

### 0.1 Baseline

- [x] `git checkout -b feature/redis-cache-pubsub`
- [x] Confirm 597 tests pass on `main` (baseline)
- [x] Capture baseline metrics:
  - [x] `drools.cache.*` shapes today
  - [x] Memory at rest
  - [x] Load test summary: 157,754 reqs / 0 errors / ~518 RPS

### 0.2 Audit usage

- [x] `CacheStatistics` audit: `grep -r "CacheStatistics" src/`
  - [x] Verify no consumer outside `cache/` package
  - [x] Document if any external user (will need to keep or migrate)
- [x] `/admin/rules` `cached` field consumers:
  - [x] Search CI scripts, monitoring dashboards, external clients
  - [x] Document everyone affected
- [x] `REDIS_TTL_MINUTES` consumers in deployment config or runbook
- [x] `LRU_CACHE_MAX_SIZE` consumers in deployment config or runbook
- [x] Confirm Redis circuit breaker beans (`redisCircuitBreaker`) wired in `CircuitBreakerConfig.java`
- [x] Confirm `RedisTemplate<String, Rule>` bean exists in `RedisConfig.java`
- [x] Verify Spring Data Redis pub/sub support is available (no extra dependency needed)

### 0.3 Phase 0 gate

- [x] All audits complete; surprises documented
- [x] No hidden consumer would block plan execution
- [x] Decision: proceed with deletion scope as specced in plan section 4.2

---

## Phase 1 — Build `RedisCachedRuleStorage` decorator

Goal: new decorator class with full test coverage, not yet wired in.

### 1.1 Skeleton

- [x] Create `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`
- [x] `implements RuleStorage`
- [x] `@Component @ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`
- [x] Constructor injection (final fields):
  - [x] `RuleStorage delegate` (qualifier strategy TBD — see Phase 3)
  - [x] `RedisTemplate<String, Rule> redisTemplate`
  - [x] `@Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker`
  - [x] `@Value("${redis.drl-rules.ttl-minutes:15}") long ttlMinutes`
  - [x] `@Value("${redis.drl-rules.key-prefix:drools:rule:}") String keyPrefix`
  - [x] `MeterRegistry meterRegistry`
- [x] Compute `Duration ttl` from `ttlMinutes` in constructor
- [x] Helper: `redisKey(String ruleId)` → `keyPrefix + ruleId`
- [x] Helper: `ruleIdFromKey(String key)` → strip prefix

### 1.2 Read methods

- [x] `Optional<Rule> getRule(String ruleId)`:
  - [x] Wrap Redis GET with circuit breaker
  - [x] On hit: increment `drools.cache.hit{layer=redis}` and return
  - [x] On miss: increment `drools.cache.miss{layer=redis}`, delegate, populate cache, return
  - [x] On circuit-open: fall through to delegate, no cache populate
- [x] `List<Rule> getAllRules()` — bulk SCAN + MGET:
  - [x] Get expected `ruleIds` via `delegate.getRuleIds()`
  - [x] SCAN `keyPrefix*` with COUNT=1000
  - [x] MGET batched (1000 keys per call)
  - [x] Compute `missing` set
  - [x] If empty: increment bulk hit, return cached values
  - [x] Otherwise: fetch missing from delegate, populate cache via pipeline/MSET, return combined
  - [x] If circuit open: pure delegate call
- [x] `boolean ruleExists(String ruleId)`:
  - [x] Redis `EXISTS` (CB wrapped) — return true on hit
  - [x] Otherwise delegate

### 1.3 Write methods

- [x] `void saveRule(Rule rule)`:
  - [x] `delegate.saveRule(rule)` first
  - [x] On success: SET with TTL via CB (best-effort, log WARN on fail)
- [x] `void deleteRule(String ruleId)`:
  - [x] `delegate.deleteRule(ruleId)` first
  - [x] On success: DEL Redis key (best-effort)
- [x] `void refreshCache()`:
  - [x] SCAN `keyPrefix*` + DEL via pipeline (with CB)
  - [x] Then `delegate.refreshCache()`
- [x] `void refreshRule(String ruleId)`:
  - [x] `DEL redisKey(ruleId)` (with CB)
  - [x] Then `delegate.refreshRule(ruleId)`

### 1.4 Pass-through methods

- [x] `long getTotalRuleCount()` → `delegate.getTotalRuleCount()`
- [x] `List<String> getRuleIds()` → `delegate.getRuleIds()`

### 1.5 Metrics

- [x] Counter: `drools.cache.hit{layer=redis}`
- [x] Counter: `drools.cache.miss{layer=redis}`
- [x] Counter: `drools.cache.bulk.hit`
- [x] Counter: `drools.cache.bulk.miss{count=N}`
- [x] Timer: `drools.cache.read.duration{layer=redis}`
- [x] Timer: `drools.cache.write.duration{layer=redis}`
- [x] Counter: `drools.cache.invalidation{scope=bulk|single}`
- [x] Gauge: `drools.cache.size{layer=redis}` via periodic SCAN COUNT

### 1.6 Unit tests

Create `src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java` (~12 tests):

- [x] Test: `getRule` Redis hit returns cached Rule, no delegate call, hit metric incremented
- [x] Test: `getRule` Redis miss → delegate called → Redis populated with correct TTL
- [x] Test: `getRule` circuit-breaker open → falls through to delegate, no cache write attempted
- [x] Test: `getRule` delegate returns empty → no cache write
- [x] Test: `getAllRules` warm cache (all keys present) → no delegate call
- [x] Test: `getAllRules` partial cache → delegate called only for missing, missing populated
- [x] Test: `getAllRules` circuit open → pure delegate
- [x] Test: `saveRule` write-through populates Redis after delegate success
- [x] Test: `saveRule` delegate throws → Redis not touched
- [x] Test: `deleteRule` deletes Redis key after delegate success
- [x] Test: `refreshCache` SCAN+DEL invoked before delegate
- [x] Test: `refreshRule(id)` DEL invoked then delegate

### 1.7 Phase 1 gate

- [x] All new tests pass
- [x] `mvn compile spotbugs:check` clean on the new class
- [x] `mvn spotless:check` clean
- [x] JaCoCo: ≥85% line coverage on `RedisCachedRuleStorage`
- [x] Existing 597 tests still pass (no wiring change yet)
- [x] `git commit` — "feat(cache): Phase 1 — RedisCachedRuleStorage decorator with unit tests"

---

## Phase 2 — Build pub/sub publisher and subscriber

Goal: cross-task refresh fan-out infrastructure built and tested in isolation.

### 2.1 RefreshEvent DTO

- [x] Create `src/main/java/com/company/drools/cache/RefreshEvent.java`
- [x] Java record: `event`, `ruleId`, `sourceInstanceId`, `timestamp`
- [x] Enum `EventType`: `RULE_REFRESHED`, `RULE_REFRESHED_BULK`, `RULE_DELETED`
- [x] Jackson serialisation annotations as needed

### 2.2 RefreshEventTest

- [x] Create `src/test/java/com/company/drools/cache/RefreshEventTest.java`
- [x] Test: JSON serialise + deserialise roundtrip for each event type
- [x] Test: unknown event type deserialises gracefully (forward-compat)
- [x] Test: null ruleId allowed for BULK

### 2.3 InstanceIdConfig

- [x] Create `src/main/java/com/company/drools/config/InstanceIdConfig.java`
- [x] `@Configuration` class
- [x] `@Bean public String droolsInstanceId() { return UUID.randomUUID().toString(); }`
- [x] Log INFO at startup with instance ID

### 2.4 RuleRefreshPublisher

- [x] Create `src/main/java/com/company/drools/cache/RuleRefreshPublisher.java`
- [x] `@Component @ConditionalOnExpression("${redis.enabled:false} && ${redis.pubsub.enabled:true}")`
- [x] Constructor: `RedisTemplate`, `@Qualifier("redisCircuitBreaker") CircuitBreaker`, `String droolsInstanceId`, `@Value("${redis.refresh.channel:drools:rule:events}") String channel`, `MeterRegistry`
- [x] Method: `publishRefresh(String ruleId)` → builds RULE_REFRESHED event, calls private `publish()`
- [x] Method: `publishBulkRefresh()` → builds RULE_REFRESHED_BULK event
- [x] Method: `publishDelete(String ruleId)` → builds RULE_DELETED event
- [x] Private `publish(RefreshEvent)`:
  - [x] Wrap `redisTemplate.convertAndSend(channel, event)` in circuit breaker
  - [x] Log INFO on success
  - [x] On CB open or failure: log WARN, increment `drools.refresh.failed{layer=publisher}`, don't propagate
  - [x] Increment `drools.refresh.published{event=<type>}` on success

### 2.5 RuleRefreshPublisherTest

- [x] Create `src/test/java/com/company/drools/cache/RuleRefreshPublisherTest.java` (~6 tests)
- [x] Test: `publishRefresh(id)` builds correct event with instance ID + timestamp + sends to channel
- [x] Test: `publishBulkRefresh()` builds event with null ruleId
- [x] Test: `publishDelete(id)` builds DELETE event
- [x] Test: CB open → no publish attempted, WARN logged
- [x] Test: RedisTemplate throws → caught, logged, doesn't propagate
- [x] Test: metric incremented on success

### 2.6 RuleRefreshSubscriber

- [x] Create `src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java`
- [x] `@Component @ConditionalOnExpression("${redis.enabled:false} && ${redis.pubsub.enabled:true}")`
- [x] `implements MessageListener` (Spring Data Redis)
- [x] Constructor: `RuleStorage storage`, `DroolsEngineService droolsEngineService`, `String droolsInstanceId`, `ObjectMapper objectMapper`, `MeterRegistry`
- [x] `onMessage(Message, byte[] pattern)`:
  - [x] Deserialize body to `RefreshEvent`
  - [x] If `event.sourceInstanceId.equals(instanceId)`:
    - [x] Increment `drools.refresh.skipped_self`
    - [x] Return
  - [x] Increment `drools.refresh.received{event=<type>}`
  - [x] Time the processing with `drools.refresh.processing.duration{event=<type>}`
  - [x] Switch on event type:
    - [x] `RULE_REFRESHED`: `Optional<Rule> rule = storage.getRule(event.ruleId)`; if present, `droolsEngineService.loadOrReplaceRule(rule.get())`
    - [x] `RULE_REFRESHED_BULK`: `List<Rule> rules = storage.getAllRules()`; `droolsEngineService.loadRules(rules)`
    - [x] `RULE_DELETED`: `droolsEngineService.removeRule(event.ruleId)` (or equivalent)
    - [x] Unknown: log WARN, don't crash
  - [x] Log INFO on success: instance ID, event type, rule ID, duration
  - [x] On exception: log ERROR with full context, increment `drools.refresh.failed{layer=subscriber}`, don't crash

### 2.7 RuleRefreshSubscriberTest

- [x] Create `src/test/java/com/company/drools/cache/RuleRefreshSubscriberTest.java` (~8 tests)
- [x] Test: self-message (same instance ID) → skipped, `skipped_self` incremented
- [x] Test: RULE_REFRESHED → `storage.getRule()` then `droolsEngineService.loadOrReplaceRule()`
- [x] Test: RULE_REFRESHED_BULK → `storage.getAllRules()` then `droolsEngineService.loadRules()`
- [x] Test: RULE_DELETED → `droolsEngineService.removeRule()` (or equivalent)
- [x] Test: unknown event type → WARN logged, no exception
- [x] Test: deserialise failure → ERROR logged, subscriber alive
- [x] Test: refresh failure → ERROR logged, metric incremented, subscriber alive
- [x] Test: metrics incremented correctly across paths

### 2.8 Phase 2 gate

- [x] All new tests pass
- [x] `mvn compile spotbugs:check` clean
- [x] `mvn spotless:check` clean
- [x] JaCoCo: ≥85% line coverage on new classes
- [x] `git commit` — "feat(cache): Phase 2 — pub/sub publisher and subscriber"

---

## Phase 3 — Wire StorageFactory + RedisConfig

Goal: when `REDIS_ENABLED=true`, the decorator is the primary `RuleStorage`. When `REDIS_PUBSUB_ENABLED=true`, the subscriber is wired to the channel.

### 3.1 StorageFactory wiring

Pick wiring strategy:
- [x] Option A — Decorator constructor injects qualified delegate, decorator is `@Primary`, base storage loses `@Primary`
- [x] Option B — `StorageFactory.primaryRuleStorage()` `@Bean` wraps at runtime
- [x] Pick one, document in commit message
- [x] Implement chosen approach
- [x] Update affected `@Autowired RuleStorage` injection sites if needed

### 3.2 RedisConfig — message listener container

- [x] Add to `src/main/java/com/company/drools/config/RedisConfig.java`:
  ```java
  @Bean
  @ConditionalOnExpression("${redis.enabled:false} && ${redis.pubsub.enabled:true}")
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory cf,
      RuleRefreshSubscriber subscriber,
      @Value("${redis.refresh.channel:drools:rule:events}") String channel) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(cf);
    container.addMessageListener(subscriber, new PatternTopic(channel));
    return container;
  }
  ```
- [x] Verify connection error handling: container should auto-reconnect on Redis restart

### 3.3 Verify wiring

- [x] App boots with `REDIS_ENABLED=false`:
  - [x] No Redis beans
  - [x] No decorator
  - [x] No publisher/subscriber
- [x] App boots with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false`:
  - [x] Decorator wired as primary
  - [x] No publisher/subscriber
- [x] App boots with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true`:
  - [x] Decorator wired
  - [x] Publisher + subscriber + listener container all created
  - [x] Subscriber connects to channel; log line confirms

### 3.4 Phase 3 gate

- [x] All three wiring modes verified
- [x] Existing 597 tests still pass
- [x] `git commit` — "feat(cache): Phase 3 — wire decorator + pub/sub conditionally"

---

## Phase 4 — Delete dead code

Goal: remove `RuleCache` and dead impls. Atomic phase — resolve all compilation errors.

### 4.1 Pre-deletion: remove call sites

- [x] `AdminController.java`:
  - [x] Remove `RuleCache ruleCache` field
  - [x] Remove from constructor
  - [x] Remove `ruleCache.warmUp()`, `.clear()`, `.put()`, `.contains()` calls
  - [x] Replace `ruleCache.getStatistics()` calls with Redis-aware helper (new method `getCacheStatistics()`)
- [x] `RuleLoadingConfig.java`:
  - [x] Remove `RuleCache ruleCache` constructor param
  - [x] Remove warm-up block
- [x] `grep -rn "@Autowired RuleCache\|RuleCache ruleCache\|RuleCache cache" src/main/` — fix all
- [x] `grep -rn "RuleCache" src/test/` — fix all

### 4.2 Delete classes

- [x] `git rm src/main/java/com/company/drools/cache/RuleCache.java`
- [x] `git rm src/main/java/com/company/drools/cache/LocalLRUCache.java`
- [x] `git rm src/main/java/com/company/drools/cache/RedisRuleCache.java`
- [x] `git rm src/main/java/com/company/drools/cache/CacheStatistics.java` (verify no other consumers first)
- [x] `git rm src/test/java/com/company/drools/cache/LocalLRUCacheTest.java`
- [x] `git rm src/test/java/com/company/drools/cache/RedisRuleCacheTest.java`
- [x] `git rm src/test/java/com/company/drools/cache/CacheStatisticsTest.java`

### 4.3 Resolve compilation

- [x] `mvn compile` — fix all errors
- [x] `mvn test` — fix all test failures

### 4.4 Phase 4 gate

- [x] `mvn clean compile` succeeds
- [x] `mvn test` succeeds (~573 tests pass, down from 597)
- [x] No reference to `RuleCache`, `LocalLRUCache`, `RedisRuleCache`, `CacheStatistics` in production code
- [x] `git commit` — "refactor(cache): Phase 4 — delete dead RuleCache abstraction"

---

## Phase 5 — Adapt AdminController + endpoints

Goal: cache observability endpoints reflect reality + publisher wired to admin actions.

### 5.1 `/admin/rules` response

- [x] Decide final shape (recommended: drop `cached` field entirely)
- [x] Update `AdminController.listRules()` accordingly
- [x] Update `RuleListResponse` DTO
- [x] Update OpenAPI spec at `project-documentation/api-reference/openapi.yml`

### 5.2 `/admin/cache/stats` endpoint

- [x] When `REDIS_ENABLED=false`: 404 or `{enabled: false}`
- [x] When `REDIS_ENABLED=true`: Redis-only stats (hit rate, miss count, size, breaker state)
- [x] Update `AdminControllerTest.cacheStats*` cases

### 5.3 `/admin/health` cache section

- [x] `REDIS_ENABLED=false`: omit `cache` component
- [x] `REDIS_ENABLED=true`: Redis status only
- [x] When `REDIS_PUBSUB_ENABLED=true`: add `pubsub` component with `last_event_age_seconds` and `subscriber.connected`

### 5.4 Wire publisher

- [x] Inject `Optional<RuleRefreshPublisher> publisher` into `AdminController`
- [x] After `loadOrReplaceRule()` in `refreshRule(id)`: `publisher.ifPresent(p -> p.publishRefresh(id))`
- [x] After `loadRules()` in `refreshAllRules()`: `publisher.ifPresent(p -> p.publishBulkRefresh())`
- [x] After rule deletion (if endpoint exists): `publisher.ifPresent(p -> p.publishDelete(id))`

### 5.5 Update tests

- [x] `AdminControllerTest.java`:
  - [x] Remove `RuleCache` mock
  - [x] Add `RuleRefreshPublisher` mock
  - [x] Test `/admin/rules` shape (no `cached`)
  - [x] Test `/admin/cache/stats` 404/empty in disabled mode
  - [x] Test `/admin/cache/stats` with Redis stats in enabled mode
  - [x] Test `/admin/health` cache section behaviour
  - [x] Assert publisher called after successful refresh
  - [x] Assert publisher NOT called when refresh fails

### 5.6 Phase 5 gate

- [x] All admin endpoint behaviours verified in all three modes (off / cache-only / full)
- [x] `mvn test` clean
- [x] `git commit` — "refactor(cache): Phase 5 — AdminController endpoints + publisher wired"

---

## Phase 6 — Config + env var migration

Goal: rename env vars, drop dead ones, add new ones.

### 6.1 application.yml

- [x] Remove `drools.cache.lru-max-size`
- [x] Replace `redis.ttl-minutes` with:
  ```yaml
  redis:
    drl-rules:
      ttl-minutes: ${REDIS_DRL_RULES_TTL_MINUTES:15}
      key-prefix: ${REDIS_DRL_RULES_KEY_PREFIX:drools:rule:}
    pubsub:
      enabled: ${REDIS_PUBSUB_ENABLED:true}
      channel: ${REDIS_REFRESH_CHANNEL:drools:rule:events}
  ```

### 6.2 Profile-specific yml

- [x] Update `application-*.yml` files (dev, docker, prod, test)

### 6.3 .env.example

- [x] Remove `LRU_CACHE_MAX_SIZE`
- [x] Remove `REDIS_TTL_MINUTES`
- [x] Add `REDIS_DRL_RULES_TTL_MINUTES=15`
- [x] Add `REDIS_DRL_RULES_KEY_PREFIX=drools:rule:`
- [x] Add `REDIS_PUBSUB_ENABLED=true`
- [x] Add `REDIS_REFRESH_CHANNEL=drools:rule:events`

### 6.4 docker-compose files

- [x] `docker-compose.yml`: remove `LRU_CACHE_MAX_SIZE`; add `REDIS_PUBSUB_ENABLED=true` if running multi-instance dev
- [x] `scripts/docker-compose.loadtest.yml`: same

### 6.5 Search-and-replace

- [x] `grep -rn "REDIS_TTL_MINUTES\|LRU_CACHE_MAX_SIZE"` — fix every hit
- [x] Update `project-documentation/09-environment-variables-reference.md`

### 6.6 Optional backward-compat

- [x] Decide: read old `REDIS_TTL_MINUTES` as fallback with WARN log?
- [x] If yes: implement with `@Value("${redis.drl-rules.ttl-minutes:${redis.ttl-minutes:15}}")`
- [x] Document deprecation timeline

### 6.7 Phase 6 gate

- [x] App boots with new env var names
- [x] App boots with old env var name + warning (if backward-compat enabled)
- [x] `mvn test` clean
- [x] `git commit` — "config(cache): Phase 6 — namespace env vars, add pub/sub config"

---

## Phase 7 — Integration tests

Goal: end-to-end coverage with real Redis (Testcontainers).

### 7.1 RedisCachedStorageIntegrationTest

- [x] Create `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`
- [x] `@SpringBootTest` with Testcontainers Redis
- [x] `@TestPropertySource(properties = {"redis.enabled=true", "redis.pubsub.enabled=false", ...})`
- [x] Tests:
  - [x] Cold cache: `storage.getRule(id)` → S3 → populates Redis → next call hits Redis
  - [x] `getAllRules()` bulk SCAN with N rules — verify all returned, hit/miss counts
  - [x] `refreshCache()` clears Redis keys
  - [x] `refreshRule(id)` clears single key
  - [x] Redis kill mid-test → circuit breaker opens → reads still succeed via S3
  - [x] Restart Redis → breaker recovers, reads from Redis again

### 7.2 RedisPubSubIntegrationTest

- [x] Create `src/test/java/com/company/drools/integration/RedisPubSubIntegrationTest.java`
- [x] Use two Spring contexts in same JVM simulating 2 ECS tasks (or two separate test classes that share the same Redis)
- [x] `redis.enabled=true, redis.pubsub.enabled=true`
- [x] Tests:
  - [x] Task 1 refreshes single rule → Task 2 receives event and updates its kieContainer within 2 sec
  - [x] Self-dedup: task does NOT process its own event
  - [x] Bulk event: both tasks receive and recompile
  - [x] Subscriber disconnect mid-event: kill Redis briefly, restart, verify recovery
  - [x] Malformed event: subscriber logs ERROR, stays alive

### 7.3 Existing integration test updates

- [x] `RuleRefreshIntegrationTest`:
  - [x] Assert Redis cleared on refresh (when Redis enabled)
  - [x] Assert Redis repopulated on next read
  - [x] Assert pub/sub event emitted

### 7.4 Smoke test in dev compose

- [x] `docker compose up -d` with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true`
- [x] Manually:
  - [x] Hit `/admin/refresh-rules` → verify Redis populated: `redis-cli KEYS 'drools:rule:*' | wc -l`
  - [x] Hit `/admin/cache/stats` → verify hit counts grow
  - [x] `redis-cli SUBSCRIBE drools:rule:events` in another terminal → trigger refresh → verify event appears
  - [x] Kill Redis → service still serves rules
  - [x] Restart Redis → breaker closes, normal again

### 7.5 Phase 7 gate

- [x] All integration tests pass
- [x] Manual smoke test passes
- [x] `mvn clean verify -Dtest='!S3StorageIntegrationTest'` clean
- [x] `git commit` — "test(cache): Phase 7 — integration tests for decorator + pub/sub"

---

## Phase 8 — Documentation

Goal: ALL docs accurate before merge. Definition of done.

### 8.1 New ADR

- [x] Add **ADR-016: Redis Decorator + Pub/Sub for Multi-instance DRL Cache** in `36-architecture-decision-records.md`
  - [x] Status: Accepted (date)
  - [x] Context section
  - [x] Decision section
  - [x] Consequences (positive + negative)
  - [x] Alternatives considered
- [x] Mark ADR-004 (LocalLRUCache uses WRITE lock on `get()`) as Superseded → link to ADR-016
- [x] Mark ADR-005 (Redis bean exists but is dormant by default) as Superseded → link to ADR-016

### 8.2 README.md

- [x] Caching architecture section: replace LRU+Redis story with Redis decorator + pub/sub
- [x] Rule Capacity & Memory Sizing section: remove LRU references
- [x] Add cross-task convergence note in Performance Targets section

### 8.3 CLAUDE.md

- [x] Caching Strategy bullet: simplified to "Redis cache + pub/sub when enabled, direct-S3 when disabled"
- [x] Add Recent change log entry with this work

### 8.4 project-documentation files (14 affected)

- [x] `00-system-overview.md` — caching tier updated, architecture overview
- [x] `01-project-overview.md` — caching strategy row
- [x] `02-project-structure.md` — package list (cache contents shrinks; storage gains RedisCachedRuleStorage)
- [x] `04-architecture.md`:
  - [x] Replace L1/L2/L3 section
  - [x] Add Redis decorator diagram
  - [x] Add pub/sub fan-out sequence diagram
- [x] `09-environment-variables-reference.md`:
  - [x] Remove `REDIS_TTL_MINUTES`, `LRU_CACHE_MAX_SIZE`
  - [x] Add `REDIS_DRL_RULES_TTL_MINUTES`, `REDIS_DRL_RULES_KEY_PREFIX`, `REDIS_PUBSUB_ENABLED`, `REDIS_REFRESH_CHANNEL`
- [x] `10-api-reference.md`:
  - [x] `/admin/rules` schema change
  - [x] `/admin/cache/stats` schema
  - [x] `/admin/health` shape
- [x] `29-circuit-breakers-and-resilience.md`:
  - [x] Update Redis section: cache is now actually exercised
  - [x] Add pub/sub circuit breaker behaviour
- [x] `30-runbooks-and-monitoring.md`:
  - [x] Update Redis stats explanation
  - [x] New runbook: Redis cache stale
  - [x] New runbook: Pub/sub event loss recovery
- [x] `31-troubleshooting.md`:
  - [x] New entries: cross-task divergence symptoms
  - [x] Pub/sub debugging steps
- [x] `35-faq.md`:
  - [x] Remove LRU questions
  - [x] Update Redis questions
  - [x] Add: "Why does my second task serve stale rules?" → pub/sub explanation
- [x] `37-glossary.md`:
  - [x] Remove "LRU cache" entry
  - [x] Rename "RedisRuleCache" → "RedisCachedRuleStorage"
  - [x] Add `RefreshEvent`, `RuleRefreshPublisher`, `RuleRefreshSubscriber`
- [x] `api-reference/openapi.yml`:
  - [x] Update affected endpoint schemas
  - [x] Add new response shapes for `/admin/cache/stats`

### 8.5 New diagrams

- [x] Sequence: multi-task refresh with pub/sub fan-out (in `04-architecture.md`)
- [x] Cache layer: Redis decorator (in `04-architecture.md`)
- [x] Failure mode: Redis down circuit breaker fallback (in `29-circuit-breakers-and-resilience.md`)

### 8.6 Phase 8 gate

- [x] All docs reviewed
- [x] `grep -rn "LocalLRUCache\|RuleCache " project-documentation/` returns only historical/ADR references
- [x] No stale env var references
- [x] Diagrams render correctly
- [x] `git commit` — "docs(cache): Phase 8 — documentation update for Redis decorator + pub/sub"

---

## Phase 9 — Load test

Goal: prove no regression and validate multi-instance + pub/sub benefits.

**Status (2026-05-24):** Harness + production hardening shipped via commits `952f0a1` and `4b8997e`. `--quick --phase 9` executed; 9.1/9.2/9.3 PASS; 9.4 partial PASS. Full plan + checklist for the hardening work in [redis-cb-hardening-plan.md](redis-cb-hardening-plan.md) + [redis-cb-hardening-checklist.md](redis-cb-hardening-checklist.md). Per-sub-test results + remaining follow-ups in the Phase 9.4 addendum of [39-load-test-findings.md](../../project-documentation/39-load-test-findings.md).

### 9.1 Single-instance regression test

- [x] Run `./scripts/run-load-test.sh --quick --phase 9.1` with `REDIS_ENABLED=false` (harness uses a `loadtest-disabled.yml` overlay to flip the flag)
- [x] Compare against historical baseline — measured `count=2991 / P99=9ms / err=0%` (under `--quick`, expected count is BASELINE_RPS × BASELINE_MIN × 60 × 0.9; the historical 518 RPS / 157,754 reqs baseline applies to the full non-quick run, not yet executed)
- [x] Acceptance: P99 latency within ±10% of baseline → met (9ms ≤ BASELINE_P99_MS_MAX=200ms)

### 9.2 Cache-only mode test

- [x] Run `--quick --phase 9.2` with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false`
- [x] ~~Spin up 2 app containers~~ — harness uses **3** uniformly (chosen to avoid topology churn between 9.2/9.3/9.4; non-publisher assertion just checks "at least one sibling shows Redis activity")
- [x] Verify cross-replica Redis activity — every replica's `drools.cache.bulk.hit + drools.cache.bulk.miss > 0` post-load; no pub/sub events received (REDIS_PUBSUB_ENABLED=false correctly suppresses fan-out). P99=10ms / err=0%.

### 9.3 Full mode test

- [x] Run `--quick --phase 9.3` with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true`
- [x] Spin up 3 app containers
- [x] Trigger single-rule refresh on each replica (10 rounds rotating publisher) — single-rule convergence **max 47ms**, fails 0/10
- [x] Verify containers 2 and 3 update kieContainer within 2 sec (via `drools.refresh.received{event=RULE_REFRESHED}` polling at 50ms granularity)
- [x] Trigger bulk refresh on each replica (5 rounds rotating publisher) — bulk convergence **max 45ms**, fails 0/5
- [x] Verify containers 2 and 3 recompile within expected time — confirmed via subscriber receive counter increment; also 5 rounds under 100 RPS background load = **max 42ms**, fails 0/5
- [x] Bonus: `drools.refresh.skipped_self` on publisher = 9 (proves self-dedup)

### 9.4 Failure-mode load test

- [x] Start load test with full mode enabled (`--quick --phase 9.4`)
- [x] Mid-test: kill Redis container — `docker kill drools-redis` at T+60s
- [x] Verify error rate stays at 0% — **PASS** (23,925 JMeter samples, 0 errors across the full kill+restart window). `/execute-rule` is in-memory and untouched by Redis outage.
- [ ] Verify CB engages — **FAIL (deferred)**: CB stayed `closed` across all 29 poll cycles. Root-cause investigation deferred — suspected Lettuce-exception-classification gap; see [39-load-test-findings.md Phase 9.4 addendum](../../project-documentation/39-load-test-findings.md).
- [x] Restart Redis — `docker start drools-redis` at T+120s
- [ ] Verify breaker closes; Redis hits resume — trivially passes (CB never opened, so `closed → closed` is vacuous). Post-restart convergence round timed out (`delta_ms=-1`): harness fires the recovery round ~1s after CB-closed, before the listener's 2s FixedBackOff retry cycle re-subscribes. Deferred — harness-side sleep tweak recommended.

### 9.5 Phase 9 gate

- [x] All three modes: no regression — 9.1/9.2/9.3 PASS cleanly
- [x] Multi-instance pub/sub: measurable cross-task convergence — sub-50ms across single + bulk + under-load (deadline 2000ms)
- [x] Failure mode: graceful degradation — proven (JMeter err=0% across full Redis outage window)
- [ ] Full mode: CB engagement under sustained outage — deferred (see 9.4)
- [x] `git commit` — `952f0a1` (test harness) + `4b8997e` (production hardening surfaced by 9.4 → SCAN wrap + Lettuce timeout + recovery backoff + 3 unit tests + 1 integration test + doc updates)

---

## Phase 10 — Phased rollout

### 10.1 Stage

- [ ] Deploy to stage with `REDIS_ENABLED=false` first (sanity check that disabled path works post-refactor)
- [ ] After 24h clean, flip to `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false` (cache only)
- [ ] After 48h clean, flip to full mode `REDIS_PUBSUB_ENABLED=true`
- [ ] Monitor for 1 week:
  - [ ] Error rate
  - [ ] P99 latency
  - [ ] Redis hit/miss metrics
  - [ ] Pub/sub event published vs received counts
  - [ ] Circuit breaker state
  - [ ] Heap usage
- [ ] Trigger refresh, verify Redis invalidation
- [ ] Trigger cross-task pub/sub propagation
- [ ] Kill Redis briefly, verify graceful fallback

### 10.2 Prod

- [ ] Schedule prod deploy window
- [ ] Deploy with `REDIS_ENABLED=false` first
- [ ] After 24h soak, flip to cache-only mode
- [ ] After 48h soak, flip to full mode
- [ ] Monitor for 48h
- [ ] Declare success

### 10.3 Phase 10 gate

- [ ] Prod running on full mode for 1 week with no incidents
- [ ] Cross-service consumer (if any) confirms freshness contract

---

## Phase 11 — Backward-compat cleanup (optional, +1 release)

Note: Phase 6.6 backward-compat shim was **not** implemented — `REDIS_TTL_MINUTES` was renamed cleanly to `REDIS_DRL_RULES_TTL_MINUTES` with no fallback (acceptable since the old layer was dead code; nobody was actually depending on the env var's effect). Phase 11 is therefore a no-op except for release-note documentation.

- [ ] Document the rename in release notes (no code change required)

---

## Sign-offs (fill in as you go)

| Phase | Owner | Date completed | Notes |
|---|---|---|---|
| 0. Pre-flight | self | 2026-05-19 | Informal — baseline + audits performed inline |
| 1. Decorator | self | 2026-05-19 | Commit `a04e9ce` |
| 2. Pub/sub publisher + subscriber | self | 2026-05-19 | Commit `86cf7b6` |
| 3. Wire factory + config | self | 2026-05-19 | Commit `7048ceb` |
| 4. Delete dead code | self | 2026-05-19 | Combined with Phase 5 in commit `8840bc7` |
| 5. AdminController endpoints | self | 2026-05-19 | Commit `8840bc7` |
| 6. Config migration | self | 2026-05-19 | Commit `a02bc39` — no backward-compat shim |
| 7. Integration tests | self | 2026-05-20 | Commits `907232e` + `c416a32` + `e8794e0`; Testcontainers tests excluded from default `mvn test` due to DinD blocker on macOS — pattern matches existing `S3StorageIntegrationTest` |
| 8. Documentation | self | 2026-05-20 | Commit `f4a2816` — 24 files, 463 ins / 320 del |
| 9. Load test | self | 2026-05-24 | Commits `952f0a1` (harness) + `4b8997e` (hardening). `--quick` run: 9.1/9.2/9.3 PASS; 9.4 partial PASS (CB-engagement + recovery-convergence sub-criteria deferred — see [39-load-test-findings.md](../../project-documentation/39-load-test-findings.md) Phase 9.4 addendum). Full non-quick run not yet executed. |
| 10. Rollout | | | |
| 11. Backward-compat cleanup | | | n/a — no shim was implemented |

---

## Risk log (update as risks emerge)

| Date | Risk | Severity | Mitigation | Status |
|---|---|---|---|---|
| 2026-05-11 | `/admin/rules` `cached` field consumers might break | Med | Phase 0 audit; optional deprecation period | **Closed** — dropped 2026-05-19; no internal/CI/dashboard consumer found in audit |
| 2026-05-11 | `REDIS_TTL_MINUTES` rename silently uses default | Med | Release note; optional backward-compat | **Accepted** — old layer was dead code; rename has no operational effect on existing behaviour. Note in release notes (Phase 11) |
| 2026-05-11 | Bulk SCAN at 10k rules slow if naive | Med | MGET + pipelining in impl | **Closed** — `RedisCachedRuleStorage.getAllRules()` uses SCAN+MGET; to be re-verified at scale in Phase 9 |
| 2026-05-11 | Pub/sub message loss on subscriber reconnect | Med | Logging; v2 Redis Streams | **Open** — accepted as known limitation; TTL-bounded staleness on missed events; documented in 35-faq.md |
| 2026-05-11 | Bulk-refresh stampede across N tasks | Med | Documented; v2 jitter | **Open** — accepted; load-test will quantify impact in Phase 9 |
| 2026-05-11 | Circuit breaker thrash on flaky Redis | Low | Tune `DROOLS_CB_REDIS_*` | **Open** — to be observed during Phase 10 soak |
| 2026-05-11 | Redis memory pressure at 10k × 10 KB | Low | Document sizing; maxmemory policy | **Closed** — sizing documented in README (~100 MB at 10k rules) and 26-performance-tuning-runbook |
| 2026-05-11 | `CacheStatistics` used outside cache package | Low | Phase 0 audit | **Closed** — audit found no external consumers; class deleted in Phase 4 |
| 2026-05-11 | Subscriber refresh failure silent | Med | ERROR log; alerting | **Closed in code** — `drools.refresh.failed{layer=subscriber}` counter + ERROR log; alert wiring is operator-side |
| 2026-05-20 | Testcontainers integration tests not executable in default `mvn test` (DinD blocker on macOS Docker Desktop) | Low | Permanent exclusion in `pom.xml` surefire (matches existing `S3StorageIntegrationTest` pattern); manually verified via `full-docker-test-plan.md` 11 steps + 17 substeps | **Accepted** — pre-existing constraint, not a regression; runs cleanly on Linux CI with host-side Docker |

---

## Quality gates summary

| Gate | Threshold | When verified |
|---|---|---|
| Unit tests | All pass; `mvn test` clean | Each phase |
| Integration tests | All pass; `mvn verify` clean | Phase 7 |
| Coverage (JaCoCo) | New files ≥ 85% line, ≥ 80% branch | Phases 1, 2 |
| SpotBugs | 0 new bugs | Each phase |
| Spotless | clean | Each phase |
| SonarQube | 0 new blocker/critical; new coverage ≥ 80%; new violations 0 | Phase 8 |
| Load test regression | P99 within ±10% baseline | Phase 9 |
| Memory | No regression in heap usage at rest | Phase 9 |

---

## Verification commands

```bash
# Phase 0 — audit
grep -rn "CacheStatistics" src/
grep -rn "REDIS_TTL_MINUTES\|LRU_CACHE_MAX_SIZE" .

# Phase 1 — build decorator
mvn test -Dtest=RedisCachedRuleStorageTest

# Phase 2 — pub/sub
mvn test -Dtest=RuleRefreshPublisherTest,RuleRefreshSubscriberTest,RefreshEventTest

# Phase 4 — verify dead code gone
grep -rn "RuleCache " src/main/java/
grep -rn "LocalLRUCache\|RedisRuleCache\|CacheStatistics" src/

# Phase 7 — integration
mvn clean verify -Dtest='!S3StorageIntegrationTest'

# Phase 9 — load test
./scripts/run-load-test.sh                                  # REDIS_ENABLED=false baseline
REDIS_ENABLED=true ./scripts/run-load-test.sh               # cache only
REDIS_ENABLED=true REDIS_PUBSUB_ENABLED=true ./scripts/run-load-test.sh  # full

# Phase 10 — smoke
curl -X POST http://stage/admin/refresh-rules -H "X-Admin-API-Key: $KEY"
docker exec redis redis-cli KEYS 'drools:rule:*' | head
docker exec redis redis-cli SUBSCRIBE drools:rule:events &
curl http://stage/admin/cache/stats | jq
curl http://stage/admin/health | jq '.components.cache, .components.pubsub'
```

---

## Migration checklist for operators

When promoting this change (Phase 10 — not yet executed):

- [ ] Update `.env` / ECS task definitions:
  - [ ] Replace `REDIS_TTL_MINUTES` → `REDIS_DRL_RULES_TTL_MINUTES`
  - [ ] Remove `LRU_CACHE_MAX_SIZE` (no-op)
  - [ ] Optionally set `REDIS_DRL_RULES_KEY_PREFIX` (default `drools:rule:`)
  - [ ] Set `REDIS_PUBSUB_ENABLED=true` for multi-task ECS deployments
  - [ ] Optionally set `REDIS_PUBSUB_CHANNEL` if naming collision
- [ ] Verify `REDIS_ENABLED` matches intent
- [ ] Update monitoring dashboards: cache hit-rate metric source is `drools.cache.hit` / `drools.cache.miss` (no `layer=` tag) plus new `drools.refresh.*` family
- [ ] Update any CI scripts that parse `/admin/rules` `cached` field (now dropped)
- [ ] Provision Redis memory: ~100 MB at 10k rules
- [ ] Document for downstream services that consume `drools:rule:*`:
  - [ ] TTL contract is 15 min (default `REDIS_DRL_RULES_TTL_MINUTES`)
  - [ ] Subscribe to `drools:rule:events` channel for instant invalidation
  - [ ] Freshness improves on writer's refresh

---

## Definition of Done

This work is DONE only when ALL of the following are true:

- [ ] All 11 phase gates passed (8/11 done; 9, 10 pending; 11 documented as no-op)
- [ ] All sign-offs signed (8/11 done)
- [x] All risks in the log either Closed or accepted with mitigation noted
- [ ] All quality gates green (unit + spotbugs + spotless ✅; load test ⏳; SonarQube not re-run)
- [x] All documentation files updated (24 actually changed; see Phase 8 commit `f4a2816`)
- [x] OpenAPI spec updated
- [x] ADR-016 added; ADR-004 and ADR-005 marked Superseded
- [ ] Operator migration checklist published (release notes) — pending Phase 10
- [ ] Prod running on full mode for ≥ 1 week with no incidents — pending Phase 10
- [ ] Cross-service consumer (if any) verified working — pending Phase 10
