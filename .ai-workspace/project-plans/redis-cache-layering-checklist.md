# Redis DRL Cache + Pub/Sub — Implementation Checklist

**Status:** Not started
**Plan:** [`redis-cache-layering-plan.md`](redis-cache-layering-plan.md)
**Branch:** TBD (suggest `feature/redis-cache-pubsub`)
**Target effort:** ~13 dev-days
**Target calendar time:** 3–4 weeks including stage soak

---

## Phase 0 — Pre-flight & audit

Goal: lock the deletion scope and verify assumptions.

### 0.1 Baseline

- [ ] `git checkout -b feature/redis-cache-pubsub`
- [ ] Confirm 597 tests pass on `main` (baseline)
- [ ] Capture baseline metrics:
  - [ ] `drools.cache.*` shapes today
  - [ ] Memory at rest
  - [ ] Load test summary: 157,754 reqs / 0 errors / ~518 RPS

### 0.2 Audit usage

- [ ] `CacheStatistics` audit: `grep -r "CacheStatistics" src/`
  - [ ] Verify no consumer outside `cache/` package
  - [ ] Document if any external user (will need to keep or migrate)
- [ ] `/admin/rules` `cached` field consumers:
  - [ ] Search CI scripts, monitoring dashboards, external clients
  - [ ] Document everyone affected
- [ ] `REDIS_TTL_MINUTES` consumers in deployment config or runbook
- [ ] `LRU_CACHE_MAX_SIZE` consumers in deployment config or runbook
- [ ] Confirm Redis circuit breaker beans (`redisCircuitBreaker`) wired in `CircuitBreakerConfig.java`
- [ ] Confirm `RedisTemplate<String, Rule>` bean exists in `RedisConfig.java`
- [ ] Verify Spring Data Redis pub/sub support is available (no extra dependency needed)

### 0.3 Phase 0 gate

- [ ] All audits complete; surprises documented
- [ ] No hidden consumer would block plan execution
- [ ] Decision: proceed with deletion scope as specced in plan section 4.2

---

## Phase 1 — Build `RedisCachedRuleStorage` decorator

Goal: new decorator class with full test coverage, not yet wired in.

### 1.1 Skeleton

- [ ] Create `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`
- [ ] `implements RuleStorage`
- [ ] `@Component @ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`
- [ ] Constructor injection (final fields):
  - [ ] `RuleStorage delegate` (qualifier strategy TBD — see Phase 3)
  - [ ] `RedisTemplate<String, Rule> redisTemplate`
  - [ ] `@Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker`
  - [ ] `@Value("${redis.drl-rules.ttl-minutes:15}") long ttlMinutes`
  - [ ] `@Value("${redis.drl-rules.key-prefix:drools:rule:}") String keyPrefix`
  - [ ] `MeterRegistry meterRegistry`
- [ ] Compute `Duration ttl` from `ttlMinutes` in constructor
- [ ] Helper: `redisKey(String ruleId)` → `keyPrefix + ruleId`
- [ ] Helper: `ruleIdFromKey(String key)` → strip prefix

### 1.2 Read methods

- [ ] `Optional<Rule> getRule(String ruleId)`:
  - [ ] Wrap Redis GET with circuit breaker
  - [ ] On hit: increment `drools.cache.hit{layer=redis}` and return
  - [ ] On miss: increment `drools.cache.miss{layer=redis}`, delegate, populate cache, return
  - [ ] On circuit-open: fall through to delegate, no cache populate
- [ ] `List<Rule> getAllRules()` — bulk SCAN + MGET:
  - [ ] Get expected `ruleIds` via `delegate.getRuleIds()`
  - [ ] SCAN `keyPrefix*` with COUNT=1000
  - [ ] MGET batched (1000 keys per call)
  - [ ] Compute `missing` set
  - [ ] If empty: increment bulk hit, return cached values
  - [ ] Otherwise: fetch missing from delegate, populate cache via pipeline/MSET, return combined
  - [ ] If circuit open: pure delegate call
- [ ] `boolean ruleExists(String ruleId)`:
  - [ ] Redis `EXISTS` (CB wrapped) — return true on hit
  - [ ] Otherwise delegate

### 1.3 Write methods

- [ ] `void saveRule(Rule rule)`:
  - [ ] `delegate.saveRule(rule)` first
  - [ ] On success: SET with TTL via CB (best-effort, log WARN on fail)
- [ ] `void deleteRule(String ruleId)`:
  - [ ] `delegate.deleteRule(ruleId)` first
  - [ ] On success: DEL Redis key (best-effort)
- [ ] `void refreshCache()`:
  - [ ] SCAN `keyPrefix*` + DEL via pipeline (with CB)
  - [ ] Then `delegate.refreshCache()`
- [ ] `void refreshRule(String ruleId)`:
  - [ ] `DEL redisKey(ruleId)` (with CB)
  - [ ] Then `delegate.refreshRule(ruleId)`

### 1.4 Pass-through methods

- [ ] `long getTotalRuleCount()` → `delegate.getTotalRuleCount()`
- [ ] `List<String> getRuleIds()` → `delegate.getRuleIds()`

### 1.5 Metrics

- [ ] Counter: `drools.cache.hit{layer=redis}`
- [ ] Counter: `drools.cache.miss{layer=redis}`
- [ ] Counter: `drools.cache.bulk.hit`
- [ ] Counter: `drools.cache.bulk.miss{count=N}`
- [ ] Timer: `drools.cache.read.duration{layer=redis}`
- [ ] Timer: `drools.cache.write.duration{layer=redis}`
- [ ] Counter: `drools.cache.invalidation{scope=bulk|single}`
- [ ] Gauge: `drools.cache.size{layer=redis}` via periodic SCAN COUNT

### 1.6 Unit tests

Create `src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java` (~12 tests):

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
- [ ] `mvn spotless:check` clean
- [ ] JaCoCo: ≥85% line coverage on `RedisCachedRuleStorage`
- [ ] Existing 597 tests still pass (no wiring change yet)
- [ ] `git commit` — "feat(cache): Phase 1 — RedisCachedRuleStorage decorator with unit tests"

---

## Phase 2 — Build pub/sub publisher and subscriber

Goal: cross-task refresh fan-out infrastructure built and tested in isolation.

### 2.1 RefreshEvent DTO

- [ ] Create `src/main/java/com/company/drools/cache/RefreshEvent.java`
- [ ] Java record: `event`, `ruleId`, `sourceInstanceId`, `timestamp`
- [ ] Enum `EventType`: `RULE_REFRESHED`, `RULE_REFRESHED_BULK`, `RULE_DELETED`
- [ ] Jackson serialisation annotations as needed

### 2.2 RefreshEventTest

- [ ] Create `src/test/java/com/company/drools/cache/RefreshEventTest.java`
- [ ] Test: JSON serialise + deserialise roundtrip for each event type
- [ ] Test: unknown event type deserialises gracefully (forward-compat)
- [ ] Test: null ruleId allowed for BULK

### 2.3 InstanceIdConfig

- [ ] Create `src/main/java/com/company/drools/config/InstanceIdConfig.java`
- [ ] `@Configuration` class
- [ ] `@Bean public String droolsInstanceId() { return UUID.randomUUID().toString(); }`
- [ ] Log INFO at startup with instance ID

### 2.4 RuleRefreshPublisher

- [ ] Create `src/main/java/com/company/drools/cache/RuleRefreshPublisher.java`
- [ ] `@Component @ConditionalOnExpression("${redis.enabled:false} && ${redis.pubsub.enabled:true}")`
- [ ] Constructor: `RedisTemplate`, `@Qualifier("redisCircuitBreaker") CircuitBreaker`, `String droolsInstanceId`, `@Value("${redis.refresh.channel:drools:rule:events}") String channel`, `MeterRegistry`
- [ ] Method: `publishRefresh(String ruleId)` → builds RULE_REFRESHED event, calls private `publish()`
- [ ] Method: `publishBulkRefresh()` → builds RULE_REFRESHED_BULK event
- [ ] Method: `publishDelete(String ruleId)` → builds RULE_DELETED event
- [ ] Private `publish(RefreshEvent)`:
  - [ ] Wrap `redisTemplate.convertAndSend(channel, event)` in circuit breaker
  - [ ] Log INFO on success
  - [ ] On CB open or failure: log WARN, increment `drools.refresh.failed{layer=publisher}`, don't propagate
  - [ ] Increment `drools.refresh.published{event=<type>}` on success

### 2.5 RuleRefreshPublisherTest

- [ ] Create `src/test/java/com/company/drools/cache/RuleRefreshPublisherTest.java` (~6 tests)
- [ ] Test: `publishRefresh(id)` builds correct event with instance ID + timestamp + sends to channel
- [ ] Test: `publishBulkRefresh()` builds event with null ruleId
- [ ] Test: `publishDelete(id)` builds DELETE event
- [ ] Test: CB open → no publish attempted, WARN logged
- [ ] Test: RedisTemplate throws → caught, logged, doesn't propagate
- [ ] Test: metric incremented on success

### 2.6 RuleRefreshSubscriber

- [ ] Create `src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java`
- [ ] `@Component @ConditionalOnExpression("${redis.enabled:false} && ${redis.pubsub.enabled:true}")`
- [ ] `implements MessageListener` (Spring Data Redis)
- [ ] Constructor: `RuleStorage storage`, `DroolsEngineService droolsEngineService`, `String droolsInstanceId`, `ObjectMapper objectMapper`, `MeterRegistry`
- [ ] `onMessage(Message, byte[] pattern)`:
  - [ ] Deserialize body to `RefreshEvent`
  - [ ] If `event.sourceInstanceId.equals(instanceId)`:
    - [ ] Increment `drools.refresh.skipped_self`
    - [ ] Return
  - [ ] Increment `drools.refresh.received{event=<type>}`
  - [ ] Time the processing with `drools.refresh.processing.duration{event=<type>}`
  - [ ] Switch on event type:
    - [ ] `RULE_REFRESHED`: `Optional<Rule> rule = storage.getRule(event.ruleId)`; if present, `droolsEngineService.loadOrReplaceRule(rule.get())`
    - [ ] `RULE_REFRESHED_BULK`: `List<Rule> rules = storage.getAllRules()`; `droolsEngineService.loadRules(rules)`
    - [ ] `RULE_DELETED`: `droolsEngineService.removeRule(event.ruleId)` (or equivalent)
    - [ ] Unknown: log WARN, don't crash
  - [ ] Log INFO on success: instance ID, event type, rule ID, duration
  - [ ] On exception: log ERROR with full context, increment `drools.refresh.failed{layer=subscriber}`, don't crash

### 2.7 RuleRefreshSubscriberTest

- [ ] Create `src/test/java/com/company/drools/cache/RuleRefreshSubscriberTest.java` (~8 tests)
- [ ] Test: self-message (same instance ID) → skipped, `skipped_self` incremented
- [ ] Test: RULE_REFRESHED → `storage.getRule()` then `droolsEngineService.loadOrReplaceRule()`
- [ ] Test: RULE_REFRESHED_BULK → `storage.getAllRules()` then `droolsEngineService.loadRules()`
- [ ] Test: RULE_DELETED → `droolsEngineService.removeRule()` (or equivalent)
- [ ] Test: unknown event type → WARN logged, no exception
- [ ] Test: deserialise failure → ERROR logged, subscriber alive
- [ ] Test: refresh failure → ERROR logged, metric incremented, subscriber alive
- [ ] Test: metrics incremented correctly across paths

### 2.8 Phase 2 gate

- [ ] All new tests pass
- [ ] `mvn compile spotbugs:check` clean
- [ ] `mvn spotless:check` clean
- [ ] JaCoCo: ≥85% line coverage on new classes
- [ ] `git commit` — "feat(cache): Phase 2 — pub/sub publisher and subscriber"

---

## Phase 3 — Wire StorageFactory + RedisConfig

Goal: when `REDIS_ENABLED=true`, the decorator is the primary `RuleStorage`. When `REDIS_PUBSUB_ENABLED=true`, the subscriber is wired to the channel.

### 3.1 StorageFactory wiring

Pick wiring strategy:
- [ ] Option A — Decorator constructor injects qualified delegate, decorator is `@Primary`, base storage loses `@Primary`
- [ ] Option B — `StorageFactory.primaryRuleStorage()` `@Bean` wraps at runtime
- [ ] Pick one, document in commit message
- [ ] Implement chosen approach
- [ ] Update affected `@Autowired RuleStorage` injection sites if needed

### 3.2 RedisConfig — message listener container

- [ ] Add to `src/main/java/com/company/drools/config/RedisConfig.java`:
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
- [ ] Verify connection error handling: container should auto-reconnect on Redis restart

### 3.3 Verify wiring

- [ ] App boots with `REDIS_ENABLED=false`:
  - [ ] No Redis beans
  - [ ] No decorator
  - [ ] No publisher/subscriber
- [ ] App boots with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false`:
  - [ ] Decorator wired as primary
  - [ ] No publisher/subscriber
- [ ] App boots with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true`:
  - [ ] Decorator wired
  - [ ] Publisher + subscriber + listener container all created
  - [ ] Subscriber connects to channel; log line confirms

### 3.4 Phase 3 gate

- [ ] All three wiring modes verified
- [ ] Existing 597 tests still pass
- [ ] `git commit` — "feat(cache): Phase 3 — wire decorator + pub/sub conditionally"

---

## Phase 4 — Delete dead code

Goal: remove `RuleCache` and dead impls. Atomic phase — resolve all compilation errors.

### 4.1 Pre-deletion: remove call sites

- [ ] `AdminController.java`:
  - [ ] Remove `RuleCache ruleCache` field
  - [ ] Remove from constructor
  - [ ] Remove `ruleCache.warmUp()`, `.clear()`, `.put()`, `.contains()` calls
  - [ ] Replace `ruleCache.getStatistics()` calls with Redis-aware helper (new method `getCacheStatistics()`)
- [ ] `RuleLoadingConfig.java`:
  - [ ] Remove `RuleCache ruleCache` constructor param
  - [ ] Remove warm-up block
- [ ] `grep -rn "@Autowired RuleCache\|RuleCache ruleCache\|RuleCache cache" src/main/` — fix all
- [ ] `grep -rn "RuleCache" src/test/` — fix all

### 4.2 Delete classes

- [ ] `git rm src/main/java/com/company/drools/cache/RuleCache.java`
- [ ] `git rm src/main/java/com/company/drools/cache/LocalLRUCache.java`
- [ ] `git rm src/main/java/com/company/drools/cache/RedisRuleCache.java`
- [ ] `git rm src/main/java/com/company/drools/cache/CacheStatistics.java` (verify no other consumers first)
- [ ] `git rm src/test/java/com/company/drools/cache/LocalLRUCacheTest.java`
- [ ] `git rm src/test/java/com/company/drools/cache/RedisRuleCacheTest.java`
- [ ] `git rm src/test/java/com/company/drools/cache/CacheStatisticsTest.java`

### 4.3 Resolve compilation

- [ ] `mvn compile` — fix all errors
- [ ] `mvn test` — fix all test failures

### 4.4 Phase 4 gate

- [ ] `mvn clean compile` succeeds
- [ ] `mvn test` succeeds (~573 tests pass, down from 597)
- [ ] No reference to `RuleCache`, `LocalLRUCache`, `RedisRuleCache`, `CacheStatistics` in production code
- [ ] `git commit` — "refactor(cache): Phase 4 — delete dead RuleCache abstraction"

---

## Phase 5 — Adapt AdminController + endpoints

Goal: cache observability endpoints reflect reality + publisher wired to admin actions.

### 5.1 `/admin/rules` response

- [ ] Decide final shape (recommended: drop `cached` field entirely)
- [ ] Update `AdminController.listRules()` accordingly
- [ ] Update `RuleListResponse` DTO
- [ ] Update OpenAPI spec at `project-documentation/api-reference/openapi.yml`

### 5.2 `/admin/cache/stats` endpoint

- [ ] When `REDIS_ENABLED=false`: 404 or `{enabled: false}`
- [ ] When `REDIS_ENABLED=true`: Redis-only stats (hit rate, miss count, size, breaker state)
- [ ] Update `AdminControllerTest.cacheStats*` cases

### 5.3 `/admin/health` cache section

- [ ] `REDIS_ENABLED=false`: omit `cache` component
- [ ] `REDIS_ENABLED=true`: Redis status only
- [ ] When `REDIS_PUBSUB_ENABLED=true`: add `pubsub` component with `last_event_age_seconds` and `subscriber.connected`

### 5.4 Wire publisher

- [ ] Inject `Optional<RuleRefreshPublisher> publisher` into `AdminController`
- [ ] After `loadOrReplaceRule()` in `refreshRule(id)`: `publisher.ifPresent(p -> p.publishRefresh(id))`
- [ ] After `loadRules()` in `refreshAllRules()`: `publisher.ifPresent(p -> p.publishBulkRefresh())`
- [ ] After rule deletion (if endpoint exists): `publisher.ifPresent(p -> p.publishDelete(id))`

### 5.5 Update tests

- [ ] `AdminControllerTest.java`:
  - [ ] Remove `RuleCache` mock
  - [ ] Add `RuleRefreshPublisher` mock
  - [ ] Test `/admin/rules` shape (no `cached`)
  - [ ] Test `/admin/cache/stats` 404/empty in disabled mode
  - [ ] Test `/admin/cache/stats` with Redis stats in enabled mode
  - [ ] Test `/admin/health` cache section behaviour
  - [ ] Assert publisher called after successful refresh
  - [ ] Assert publisher NOT called when refresh fails

### 5.6 Phase 5 gate

- [ ] All admin endpoint behaviours verified in all three modes (off / cache-only / full)
- [ ] `mvn test` clean
- [ ] `git commit` — "refactor(cache): Phase 5 — AdminController endpoints + publisher wired"

---

## Phase 6 — Config + env var migration

Goal: rename env vars, drop dead ones, add new ones.

### 6.1 application.yml

- [ ] Remove `drools.cache.lru-max-size`
- [ ] Replace `redis.ttl-minutes` with:
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

- [ ] Update `application-*.yml` files (dev, docker, prod, test)

### 6.3 .env.example

- [ ] Remove `LRU_CACHE_MAX_SIZE`
- [ ] Remove `REDIS_TTL_MINUTES`
- [ ] Add `REDIS_DRL_RULES_TTL_MINUTES=15`
- [ ] Add `REDIS_DRL_RULES_KEY_PREFIX=drools:rule:`
- [ ] Add `REDIS_PUBSUB_ENABLED=true`
- [ ] Add `REDIS_REFRESH_CHANNEL=drools:rule:events`

### 6.4 docker-compose files

- [ ] `docker-compose.yml`: remove `LRU_CACHE_MAX_SIZE`; add `REDIS_PUBSUB_ENABLED=true` if running multi-instance dev
- [ ] `scripts/docker-compose.loadtest.yml`: same

### 6.5 Search-and-replace

- [ ] `grep -rn "REDIS_TTL_MINUTES\|LRU_CACHE_MAX_SIZE"` — fix every hit
- [ ] Update `project-documentation/09-environment-variables-reference.md`

### 6.6 Optional backward-compat

- [ ] Decide: read old `REDIS_TTL_MINUTES` as fallback with WARN log?
- [ ] If yes: implement with `@Value("${redis.drl-rules.ttl-minutes:${redis.ttl-minutes:15}}")`
- [ ] Document deprecation timeline

### 6.7 Phase 6 gate

- [ ] App boots with new env var names
- [ ] App boots with old env var name + warning (if backward-compat enabled)
- [ ] `mvn test` clean
- [ ] `git commit` — "config(cache): Phase 6 — namespace env vars, add pub/sub config"

---

## Phase 7 — Integration tests

Goal: end-to-end coverage with real Redis (Testcontainers).

### 7.1 RedisCachedStorageIntegrationTest

- [ ] Create `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`
- [ ] `@SpringBootTest` with Testcontainers Redis
- [ ] `@TestPropertySource(properties = {"redis.enabled=true", "redis.pubsub.enabled=false", ...})`
- [ ] Tests:
  - [ ] Cold cache: `storage.getRule(id)` → S3 → populates Redis → next call hits Redis
  - [ ] `getAllRules()` bulk SCAN with N rules — verify all returned, hit/miss counts
  - [ ] `refreshCache()` clears Redis keys
  - [ ] `refreshRule(id)` clears single key
  - [ ] Redis kill mid-test → circuit breaker opens → reads still succeed via S3
  - [ ] Restart Redis → breaker recovers, reads from Redis again

### 7.2 RedisPubSubIntegrationTest

- [ ] Create `src/test/java/com/company/drools/integration/RedisPubSubIntegrationTest.java`
- [ ] Use two Spring contexts in same JVM simulating 2 ECS tasks (or two separate test classes that share the same Redis)
- [ ] `redis.enabled=true, redis.pubsub.enabled=true`
- [ ] Tests:
  - [ ] Task 1 refreshes single rule → Task 2 receives event and updates its kieContainer within 2 sec
  - [ ] Self-dedup: task does NOT process its own event
  - [ ] Bulk event: both tasks receive and recompile
  - [ ] Subscriber disconnect mid-event: kill Redis briefly, restart, verify recovery
  - [ ] Malformed event: subscriber logs ERROR, stays alive

### 7.3 Existing integration test updates

- [ ] `RuleRefreshIntegrationTest`:
  - [ ] Assert Redis cleared on refresh (when Redis enabled)
  - [ ] Assert Redis repopulated on next read
  - [ ] Assert pub/sub event emitted

### 7.4 Smoke test in dev compose

- [ ] `docker compose up -d` with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true`
- [ ] Manually:
  - [ ] Hit `/admin/refresh-rules` → verify Redis populated: `redis-cli KEYS 'drools:rule:*' | wc -l`
  - [ ] Hit `/admin/cache/stats` → verify hit counts grow
  - [ ] `redis-cli SUBSCRIBE drools:rule:events` in another terminal → trigger refresh → verify event appears
  - [ ] Kill Redis → service still serves rules
  - [ ] Restart Redis → breaker closes, normal again

### 7.5 Phase 7 gate

- [ ] All integration tests pass
- [ ] Manual smoke test passes
- [ ] `mvn clean verify -Dtest='!S3StorageIntegrationTest'` clean
- [ ] `git commit` — "test(cache): Phase 7 — integration tests for decorator + pub/sub"

---

## Phase 8 — Documentation

Goal: ALL docs accurate before merge. Definition of done.

### 8.1 New ADR

- [ ] Add **ADR-016: Redis Decorator + Pub/Sub for Multi-instance DRL Cache** in `36-architecture-decision-records.md`
  - [ ] Status: Accepted (date)
  - [ ] Context section
  - [ ] Decision section
  - [ ] Consequences (positive + negative)
  - [ ] Alternatives considered
- [ ] Mark ADR-004 (LocalLRUCache uses WRITE lock on `get()`) as Superseded → link to ADR-016
- [ ] Mark ADR-005 (Redis bean exists but is dormant by default) as Superseded → link to ADR-016

### 8.2 README.md

- [ ] Caching architecture section: replace LRU+Redis story with Redis decorator + pub/sub
- [ ] Rule Capacity & Memory Sizing section: remove LRU references
- [ ] Add cross-task convergence note in Performance Targets section

### 8.3 CLAUDE.md

- [ ] Caching Strategy bullet: simplified to "Redis cache + pub/sub when enabled, direct-S3 when disabled"
- [ ] Add Recent change log entry with this work

### 8.4 project-documentation files (14 affected)

- [ ] `00-system-overview.md` — caching tier updated, architecture overview
- [ ] `01-project-overview.md` — caching strategy row
- [ ] `02-project-structure.md` — package list (cache contents shrinks; storage gains RedisCachedRuleStorage)
- [ ] `04-architecture.md`:
  - [ ] Replace L1/L2/L3 section
  - [ ] Add Redis decorator diagram
  - [ ] Add pub/sub fan-out sequence diagram
- [ ] `09-environment-variables-reference.md`:
  - [ ] Remove `REDIS_TTL_MINUTES`, `LRU_CACHE_MAX_SIZE`
  - [ ] Add `REDIS_DRL_RULES_TTL_MINUTES`, `REDIS_DRL_RULES_KEY_PREFIX`, `REDIS_PUBSUB_ENABLED`, `REDIS_REFRESH_CHANNEL`
- [ ] `10-api-reference.md`:
  - [ ] `/admin/rules` schema change
  - [ ] `/admin/cache/stats` schema
  - [ ] `/admin/health` shape
- [ ] `29-circuit-breakers-and-resilience.md`:
  - [ ] Update Redis section: cache is now actually exercised
  - [ ] Add pub/sub circuit breaker behaviour
- [ ] `30-runbooks-and-monitoring.md`:
  - [ ] Update Redis stats explanation
  - [ ] New runbook: Redis cache stale
  - [ ] New runbook: Pub/sub event loss recovery
- [ ] `31-troubleshooting.md`:
  - [ ] New entries: cross-task divergence symptoms
  - [ ] Pub/sub debugging steps
- [ ] `35-faq.md`:
  - [ ] Remove LRU questions
  - [ ] Update Redis questions
  - [ ] Add: "Why does my second task serve stale rules?" → pub/sub explanation
- [ ] `37-glossary.md`:
  - [ ] Remove "LRU cache" entry
  - [ ] Rename "RedisRuleCache" → "RedisCachedRuleStorage"
  - [ ] Add `RefreshEvent`, `RuleRefreshPublisher`, `RuleRefreshSubscriber`
- [ ] `api-reference/openapi.yml`:
  - [ ] Update affected endpoint schemas
  - [ ] Add new response shapes for `/admin/cache/stats`

### 8.5 New diagrams

- [ ] Sequence: multi-task refresh with pub/sub fan-out (in `04-architecture.md`)
- [ ] Cache layer: Redis decorator (in `04-architecture.md`)
- [ ] Failure mode: Redis down circuit breaker fallback (in `29-circuit-breakers-and-resilience.md`)

### 8.6 Phase 8 gate

- [ ] All docs reviewed
- [ ] `grep -rn "LocalLRUCache\|RuleCache " project-documentation/` returns only historical/ADR references
- [ ] No stale env var references
- [ ] Diagrams render correctly
- [ ] `git commit` — "docs(cache): Phase 8 — documentation update for Redis decorator + pub/sub"

---

## Phase 9 — Load test

Goal: prove no regression and validate multi-instance + pub/sub benefits.

### 9.1 Single-instance regression test

- [ ] Run `./scripts/run-load-test.sh` with `REDIS_ENABLED=false`
- [ ] Compare against historical baseline (157,754 reqs, 0 errors, ~518 RPS)
- [ ] Acceptance: P99 latency within ±10% of baseline

### 9.2 Cache-only mode test

- [ ] Run `./scripts/run-load-test.sh` with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false`
- [ ] Spin up 2 app containers
- [ ] Verify task 2's refresh shows Redis hits

### 9.3 Full mode test

- [ ] Run with `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true`
- [ ] Spin up 3 app containers
- [ ] Trigger single-rule refresh on container 1
- [ ] Verify containers 2 and 3 update kieContainer within 2 sec (via metrics)
- [ ] Trigger bulk refresh on container 1
- [ ] Verify containers 2 and 3 recompile within expected time

### 9.4 Failure-mode load test

- [ ] Start load test with full mode enabled
- [ ] Mid-test: kill Redis container
- [ ] Verify error rate stays at 0% (CB engages)
- [ ] Restart Redis
- [ ] Verify breaker closes; Redis hits resume

### 9.5 Phase 9 gate

- [ ] All three modes: no regression
- [ ] Multi-instance pub/sub: measurable cross-task convergence
- [ ] Failure mode: graceful degradation
- [ ] `git commit` — "perf(cache): Phase 9 — load test results documented"

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

- [ ] Remove `REDIS_TTL_MINUTES` fallback if Phase 6.6 was implemented
- [ ] Remove deprecation log line
- [ ] Document in release notes

---

## Sign-offs (fill in as you go)

| Phase | Owner | Date completed | Notes |
|---|---|---|---|
| 0. Pre-flight | | | |
| 1. Decorator | | | |
| 2. Pub/sub publisher + subscriber | | | |
| 3. Wire factory + config | | | |
| 4. Delete dead code | | | |
| 5. AdminController endpoints | | | |
| 6. Config migration | | | |
| 7. Integration tests | | | |
| 8. Documentation | | | |
| 9. Load test | | | |
| 10. Rollout | | | |
| 11. Backward-compat cleanup | | | (target +1 release) |

---

## Risk log (update as risks emerge)

| Date | Risk | Severity | Mitigation | Status |
|---|---|---|---|---|
| 2026-05-11 | `/admin/rules` `cached` field consumers might break | Med | Phase 0 audit; optional deprecation period | Open |
| 2026-05-11 | `REDIS_TTL_MINUTES` rename silently uses default | Med | Release note; optional backward-compat | Open |
| 2026-05-11 | Bulk SCAN at 10k rules slow if naive | Med | MGET + pipelining in impl | Open |
| 2026-05-11 | Pub/sub message loss on subscriber reconnect | Med | Logging; v2 Redis Streams | Open |
| 2026-05-11 | Bulk-refresh stampede across N tasks | Med | Documented; v2 jitter | Open |
| 2026-05-11 | Circuit breaker thrash on flaky Redis | Low | Tune `DROOLS_CB_REDIS_*` | Open |
| 2026-05-11 | Redis memory pressure at 10k × 10 KB | Low | Document sizing; maxmemory policy | Open |
| 2026-05-11 | `CacheStatistics` used outside cache package | Low | Phase 0 audit | Open |
| 2026-05-11 | Subscriber refresh failure silent | Med | ERROR log; alerting | Open |

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

When promoting this change:

- [ ] Update `.env` / ECS task definitions:
  - [ ] Replace `REDIS_TTL_MINUTES` → `REDIS_DRL_RULES_TTL_MINUTES`
  - [ ] Remove `LRU_CACHE_MAX_SIZE` (no-op)
  - [ ] Optionally set `REDIS_DRL_RULES_KEY_PREFIX` (default `drools:rule:`)
  - [ ] Set `REDIS_PUBSUB_ENABLED=true` for multi-task ECS deployments
  - [ ] Optionally set `REDIS_REFRESH_CHANNEL` if naming collision
- [ ] Verify `REDIS_ENABLED` matches intent
- [ ] Update monitoring dashboards: cache hit-rate metric source is Redis-only now
- [ ] Update any CI scripts that parse `/admin/rules` `cached` field (now dropped or renamed)
- [ ] Provision Redis memory: ~100 MB at 10k rules
- [ ] Document for downstream services that consume `drools:rule:*`:
  - [ ] TTL contract is 15 min
  - [ ] Subscribe to `drools:rule:events` channel for instant invalidation
  - [ ] Freshness improves on writer's refresh

---

## Definition of Done

This work is DONE only when ALL of the following are true:

- [ ] All 11 phase gates passed
- [ ] All sign-offs signed
- [ ] All risks in the log either Closed or accepted with mitigation noted
- [ ] All quality gates green
- [ ] All 16 documentation files updated
- [ ] OpenAPI spec updated
- [ ] ADR-016 added; ADR-004 and ADR-005 marked Superseded
- [ ] Operator migration checklist published (release notes)
- [ ] Prod running on full mode for ≥ 1 week with no incidents
- [ ] Cross-service consumer (if any) verified working
