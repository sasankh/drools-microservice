# Redis DRL Cache + Pub/Sub Deployment Plan

**Status:** Draft — not yet started
**Created:** 2026-05-11
**Owner:** TBD
**Driver:** Eliminate dead-code cache layer; provide real multi-instance DRL caching via Redis with a feature flag; ensure compiled-state convergence across ECS tasks via Redis Pub/Sub.

---

## 1. Context & motivation

### 1.1 The two problems

**Problem A — Dead cache.** Forensic trace revealed both `LocalLRUCache` and `RedisRuleCache` are dead code. `.get()` never called in production. Written to during refresh, queried only for their own observability endpoints. "Cache hit rate ~95%" is statistically true but operationally meaningless.

**Problem B — Multi-instance compiled-state divergence.** With 3–5 ECS tasks behind an ALB, when one task receives `POST /admin/refresh-rules/{id}`, only that task recompiles its `kieContainer`. Other tasks keep serving the stale compiled rule until they themselves refresh. Redis cache alone does not solve this because execution reads from `kieContainer` (compiled), not Redis (DRL text).

### 1.2 Why both matter

- 3–5 ECS tasks × 10,000+ rules: each task fetches all rules from S3 on cold start and refresh. With dead cache, no fetch sharing.
- Cross-service consumers want to read `drools:rule:*` from Redis but today's invalidation is broken.
- Multi-task ALB deployments serve inconsistent rule outputs after a single-rule refresh until all tasks happen to refresh — could be hours or never.

### 1.3 Solution

Two coordinated changes in one plan:

1. **Redis cache decorator** on `RuleStorage` (feature-flagged on `REDIS_ENABLED`). Replaces dead `RuleCache` abstraction.
2. **Redis Pub/Sub** for cross-task refresh fan-out. When task A refreshes, B and C self-refresh within seconds.

### 1.4 Expected outcome

- Redis is a real, used cache. Cross-instance DRL fetch shared.
- Cross-task compiled-state converges within ~1 sec for single-rule refresh (~7 min for bulk, gated by compile time per task).
- ~600 LOC of dead code removed; ~500 LOC of real cache + pub/sub added.
- Feature-flagged: `REDIS_ENABLED=false` reverts to direct-S3 single-instance behaviour.

### 1.5 What this does *not* fix

Compilation cost (~46s per 1,000 rules; ~7 min per 10,000 rules). Pub/sub fan-out triggers a 7-min compile on each task simultaneously when bulk refresh fires. The compile-cost fix is the **kjar plan** (separate document, independent and complementary).

---

## 2. Current state — verified code trace

| Component | File | Behaviour |
|---|---|---|
| `RuleCache` interface | `src/main/java/com/company/drools/cache/RuleCache.java` | API consumers think there's a tiered cache. There isn't. |
| `LocalLRUCache` | `src/main/java/com/company/drools/cache/LocalLRUCache.java` | `@Primary`, max 100 entries, write-locked `get()`. `.get()` never called in production. |
| `RedisRuleCache` | `src/main/java/com/company/drools/cache/RedisRuleCache.java` | `@ConditionalOnProperty("redis.enabled")`. Bean created but never injected (LRU is `@Primary`). |
| `CacheStatistics` | `src/main/java/com/company/drools/cache/CacheStatistics.java` | Counters for a cache that isn't read. |
| `@Autowired RuleCache ruleCache` | `AdminController.java:60–85`, `RuleLoadingConfig.java:22` | Used for `.warmUp()`, `.clear()`, `.put()`, `.contains()`, `.getStatistics()`. No `.get()` calls. |
| Refresh flow | `AdminController.java:401–447` | `storage.getAllRules()` → `droolsEngineService.loadRules(rules)` → `ruleCache.warmUp(rules)` (dead write). |
| Execution flow | `DroolsEngineService.executeRule()` → `RuleExecutor.execute()` | Reads `loadedRules` (ConcurrentHashMap) + `kieContainer`. Never touches `RuleCache`. |
| Existing env vars | `application.yml:127–129` | `REDIS_ENABLED:false`, `REDIS_URL:redis://localhost:6379`, `REDIS_TTL_MINUTES:60` |
| Key naming today | `RedisRuleCache.java` (constant) | `drools:rule:{ruleId}` |
| Circuit breaker | `RedisRuleCache.java:72-85, 126-134` + `CircuitBreakerConfig.java` | Wraps `get()`/`put()`. Will be preserved in new decorator + publisher. |
| Cross-task coordination | None today | No mechanism. Refresh on task A does not propagate to B/C. |

---

## 3. Target architecture

### 3.1 Architecture diagram

```
REDIS_ENABLED=false (single instance):

┌────────────────────┐
│ DroolsEngineService│
│ (loadedRules,      │
│  kieContainer)     │
└─────────┬──────────┘
          │
          ▼
┌────────────────────┐
│   S3RuleStorage    │
└────────────────────┘


REDIS_ENABLED=true (3 ECS tasks A, B, C):

┌─────────┐  ┌─────────┐  ┌─────────┐
│ Task A  │  │ Task B  │  │ Task C  │
│ D.E.S   │  │ D.E.S   │  │ D.E.S   │
│ +pubsub │  │ +pubsub │  │ +pubsub │
└────┬────┘  └────┬────┘  └────┬────┘
     │            │            │
     └────────────┼────────────┘    each task has own kieContainer
                  │
                  ▼
   ┌──────────────────────────────────┐
   │     RedisCachedRuleStorage       │  decorator each task instantiates
   │  (read-through over RedisTemplate)│
   └──────────────┬───────────────────┘
                  │  miss / write
                  ▼
       ┌────────────────────┐
       │   S3RuleStorage    │
       └────────────────────┘

Pub/sub fan-out when task A refreshes:

   Task A.AdminController.refresh()
        │
        │ 1. storage.refreshRule(id)      [DEL drools:rule:{id} in Redis]
        │ 2. storage.getRule(id)          [S3 fetch, repopulates Redis]
        │ 3. droolsEngineService.loadOrReplaceRule()
        │ 4. publisher.publishRefresh(id) [PUBLISH drools:rule:events]
        ▼
  ╔════════════════════════════════════╗
  ║   Redis channel: drools:rule:events ║
  ╚════════════════════════════════════╝
        │
        ├─── SUBSCRIBE Task A (skip self) ───┐
        ├─── SUBSCRIBE Task B → refresh own kieContainer
        └─── SUBSCRIBE Task C → refresh own kieContainer
```

### 3.2 New class: `RedisCachedRuleStorage`

**Location:** `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`

**Implements:** `RuleStorage`

**Constructor:**
- `RuleStorage delegate` — wraps `S3RuleStorage` / `LocalFileStorage` / `InMemoryRuleStorage`
- `RedisTemplate<String, Rule> redisTemplate`
- `@Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker`
- `Duration ttl` from `redis.drl-rules.ttl-minutes`
- `String keyPrefix` from `redis.drl-rules.key-prefix`
- `MeterRegistry meterRegistry`

**Annotation:** `@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`

### 3.3 Read-through behaviour

#### `Optional<Rule> getRule(String ruleId)`
```
1. key = keyPrefix + ruleId
2. With circuit breaker:
   a. rule = redisTemplate.opsForValue().get(key)
   b. if rule != null:
        meterRegistry.counter("drools.cache.hit", "layer", "redis").increment()
        return Optional.of(rule)
3. Cache miss:
   meterRegistry.counter("drools.cache.miss", "layer", "redis").increment()
   Optional<Rule> result = delegate.getRule(ruleId)
4. If result.isPresent():
   With circuit breaker:
     redisTemplate.opsForValue().set(key, result.get(), ttl)
5. Return result
```
Circuit-open: skip Redis steps, pure delegate.

#### `List<Rule> getAllRules()` — bulk SCAN + MGET
```
1. expectedIds = delegate.getRuleIds()
2. With circuit breaker:
   a. SCAN MATCH keyPrefix+"*" COUNT 1000
   b. MGET batched (1000 keys per call) → existing map
3. missing = expectedIds - existing.keys()
4. If missing.isEmpty():
   counter("drools.cache.bulk.hit").increment()
   return existing.values()
5. counter("drools.cache.bulk.miss", "count", missing.size()).increment()
6. freshRules = delegate.getAllRulesByIds(missing)
7. Pipeline SET each with TTL
8. Return existing + fresh
```

#### `saveRule`, `deleteRule` — write-through
```
saveRule(rule):
  delegate.saveRule(rule)
  if success: redisTemplate.opsForValue().set(keyPrefix+rule.id, rule, ttl)

deleteRule(id):
  delegate.deleteRule(id)
  if success: redisTemplate.delete(keyPrefix+id)
```

#### `refreshCache()`, `refreshRule(id)` — invalidation
```
refreshCache():
  SCAN keyPrefix+"*" → DEL in pipeline (with CB)
  delegate.refreshCache()

refreshRule(id):
  DEL keyPrefix+id (with CB)
  delegate.refreshRule(id)
```

#### Authoritative methods
- `getTotalRuleCount()` / `getRuleIds()` → always delegate (S3 truth)
- `ruleExists(id)` → check Redis EXISTS first (fast), fall through to delegate

### 3.4 New class: `RuleRefreshPublisher`

**Location:** `src/main/java/com/company/drools/cache/RuleRefreshPublisher.java`

**Annotation:** `@Component @ConditionalOnExpression("${redis.enabled:false} && ${redis.pubsub.enabled:true}")`

**Methods:**
```java
public void publishRefresh(String ruleId)      // single rule
public void publishBulkRefresh()                // all rules
public void publishDelete(String ruleId)        // rule removed
```

**Behaviour:**
- Builds `RefreshEvent` with current `instanceId` (UUID `@Bean`)
- `redisTemplate.convertAndSend(channel, event)` wrapped in circuit breaker
- Fire-and-forget — failure logs WARN, doesn't propagate
- Metric: `drools.refresh.published{event=<type>}`

### 3.5 New class: `RuleRefreshSubscriber`

**Location:** `src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java`

**Annotation:** Same conditional as publisher.

**Implements:** `MessageListener` (Spring Data Redis)

**Wired via:** `RedisMessageListenerContainer` in `RedisConfig`

**`onMessage(Message message, byte[] pattern)`:**
1. Deserialize to `RefreshEvent`
2. If `event.sourceInstanceId.equals(this.instanceId)`:
   - counter `drools.refresh.skipped_self`.increment()
   - return
3. counter `drools.refresh.received{event=<type>}`.increment()
4. Switch on event type:
   - `RULE_REFRESHED`:
     - `Optional<Rule> rule = storage.getRule(event.ruleId)` — reads Redis (already populated by publisher), fast
     - `droolsEngineService.loadOrReplaceRule(rule.get())` — KieBase swap
   - `RULE_REFRESHED_BULK`:
     - `List<Rule> rules = storage.getAllRules()` — reads Redis bulk
     - `droolsEngineService.loadRules(rules)` — full recompile (~7 min at 10k)
   - `RULE_DELETED`:
     - `droolsEngineService.removeRule(event.ruleId)` (or full reload omitting that rule)
5. Log: instance-id, event type, rule-id (if any), duration

### 3.6 New class: `RefreshEvent` DTO

**Location:** `src/main/java/com/company/drools/cache/RefreshEvent.java`

```java
public record RefreshEvent(
    EventType event,
    String ruleId,
    String sourceInstanceId,
    Instant timestamp
) {
  public enum EventType {
    RULE_REFRESHED,
    RULE_REFRESHED_BULK,
    RULE_DELETED
  }
}
```

JSON-serialised via Jackson.

### 3.7 Instance ID

**Location:** new `@Bean` in `DroolsConfig.java` or new `InstanceIdConfig`:

```java
@Bean
public String droolsInstanceId() {
  return UUID.randomUUID().toString();
}
```

Each task instance gets its own UUID at startup. Used for self-message dedupe.

### 3.8 Failure modes

| Failure | Behaviour | Recovery |
|---|---|---|
| Redis cache down (CB open) | Reads fall through to S3; writes silently dropped | Service continues; breaker auto-closes when Redis returns |
| Pub/sub publish fails | Local refresh succeeded; siblings miss the event | Operator can re-trigger; or AUTO_REFRESH backstop |
| Pub/sub subscriber connection dropped | Spring auto-reconnects; missed messages lost | Document. Optional v2 backstop: Redis checkpoint key |
| Subscriber refresh fails | Log ERROR, stays stale on this task | Re-trigger refresh; alerting |
| Bulk-refresh stampede across N tasks | All N tasks recompile simultaneously (CPU/memory spike) | Document. v2: jitter |
| Self-message race | Subscriber receives own publish. Self-ID check filters | Already handled |
| Operator triggers refresh mid-compile | Task A is compiling; event fires; B and C start compiling too | Acceptable — single-rule compiles are ~50ms |

### 3.9 Stampede prevention

Decision: **accept it.** S3 handles parallel reads; pub/sub bulk fan-out at 10k rules causes simultaneous compile spike — mitigated by kjar plan.

---

## 4. Code changes — file by file

### 4.1 New files

#### Production
- `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java` (~280 LOC)
- `src/main/java/com/company/drools/cache/RuleRefreshPublisher.java` (~90 LOC)
- `src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java` (~140 LOC)
- `src/main/java/com/company/drools/cache/RefreshEvent.java` (~50 LOC, record)
- `src/main/java/com/company/drools/config/InstanceIdConfig.java` (~20 LOC)

#### Tests
- `src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java` (~300 LOC, ~12 tests)
- `src/test/java/com/company/drools/cache/RuleRefreshPublisherTest.java` (~120 LOC, ~6 tests)
- `src/test/java/com/company/drools/cache/RuleRefreshSubscriberTest.java` (~180 LOC, ~8 tests)
- `src/test/java/com/company/drools/cache/RefreshEventTest.java` (~50 LOC, ~3 tests)
- `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java` (~250 LOC, ~6 tests with Testcontainers)
- `src/test/java/com/company/drools/integration/RedisPubSubIntegrationTest.java` (~200 LOC, ~5 tests with Testcontainers)

### 4.2 Deleted files

| File | Reason |
|---|---|
| `src/main/java/com/company/drools/cache/RuleCache.java` | Interface superseded by `RuleStorage` decorator |
| `src/main/java/com/company/drools/cache/LocalLRUCache.java` | Dead code |
| `src/main/java/com/company/drools/cache/RedisRuleCache.java` | Replaced by `RedisCachedRuleStorage` |
| `src/main/java/com/company/drools/cache/CacheStatistics.java` | Only used by deleted caches (verify) |
| `src/test/java/com/company/drools/cache/LocalLRUCacheTest.java` | LRU gone |
| `src/test/java/com/company/drools/cache/RedisRuleCacheTest.java` | Class gone |
| `src/test/java/com/company/drools/cache/CacheStatisticsTest.java` | Class gone |

### 4.3 Modified files

#### `src/main/java/com/company/drools/storage/StorageFactory.java`
```java
@Bean
@Primary
public RuleStorage primaryRuleStorage(
    StorageFactory factory,
    @Autowired(required = false) RedisCachedRuleStorage redisDecorator,
    @Value("${redis.enabled:false}") boolean redisEnabled) {
  RuleStorage base = factory.createStorage();
  if (redisEnabled && redisDecorator != null) {
    redisDecorator.setDelegate(base);
    return redisDecorator;
  }
  return base;
}
```

#### `src/main/java/com/company/drools/config/RedisConfig.java`
Add:
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

#### `src/main/java/com/company/drools/api/controller/AdminController.java`
- Remove `RuleCache ruleCache` field and constructor param
- Remove `ruleCache.warmUp()`, `.clear()`, `.put()`, `.contains()` call sites
- Replace `ruleCache.getStatistics()` with Redis-aware helper
- Inject `RuleRefreshPublisher` (Optional, only when pubsub enabled)
- After `loadRules()`/`loadOrReplaceRule()` succeeds:
  - Single-rule refresh: `publisher.publishRefresh(ruleId)`
  - Bulk refresh: `publisher.publishBulkRefresh()`
  - Rule delete: `publisher.publishDelete(ruleId)`
- `/admin/rules`: drop `cached` field (or rename `in_redis` only when enabled)
- `/admin/cache/stats`: Redis stats when enabled, 404 when disabled
- `/admin/health` cache section: Redis-only or omitted

#### `src/main/java/com/company/drools/config/RuleLoadingConfig.java`
- Remove `RuleCache ruleCache` injection and `warmUp()` block

#### `src/main/java/com/company/drools/config/DroolsConfig.java`
- No change

#### `src/main/resources/application.yml`
Replace:
```yaml
drools:
  cache:
    lru-max-size: ${LRU_CACHE_MAX_SIZE:100}      # DELETE
redis:
  enabled: ${REDIS_ENABLED:false}
  ttl-minutes: ${REDIS_TTL_MINUTES:60}            # DELETE
  url: ${REDIS_URL:redis://localhost:6379}
```
With:
```yaml
redis:
  enabled: ${REDIS_ENABLED:false}
  url: ${REDIS_URL:redis://localhost:6379}
  drl-rules:
    ttl-minutes: ${REDIS_DRL_RULES_TTL_MINUTES:15}
    key-prefix: ${REDIS_DRL_RULES_KEY_PREFIX:drools:rule:}
  pubsub:
    enabled: ${REDIS_PUBSUB_ENABLED:true}
    channel: ${REDIS_REFRESH_CHANNEL:drools:rule:events}
```

#### `.env.example`
- Remove `LRU_CACHE_MAX_SIZE`, `REDIS_TTL_MINUTES`
- Add `REDIS_DRL_RULES_TTL_MINUTES=15`
- Add `REDIS_DRL_RULES_KEY_PREFIX=drools:rule:`
- Add `REDIS_PUBSUB_ENABLED=true`
- Add `REDIS_REFRESH_CHANNEL=drools:rule:events`

#### `docker-compose.yml`
- Remove `LRU_CACHE_MAX_SIZE`
- Optionally add `REDIS_PUBSUB_ENABLED=true`

#### `project-documentation/api-reference/openapi.yml`
- Update `/admin/rules` schema (drop `cached` or rename)
- Update `/admin/cache/stats` schema for Redis-only stats
- Add new endpoint behaviour notes

---

## 5. Configuration & environment variables

### 5.1 New env vars

| Env var | Default | Description |
|---|---|---|
| `REDIS_DRL_RULES_TTL_MINUTES` | `15` | TTL for cached DRL Rule entries. Safety net + cross-service freshness ceiling. |
| `REDIS_DRL_RULES_KEY_PREFIX` | `drools:rule:` | Redis key prefix. Namespaced for future uses. |
| `REDIS_PUBSUB_ENABLED` | `true` | When `REDIS_ENABLED=true`, enables Pub/Sub fan-out. Set `false` to disable. |
| `REDIS_REFRESH_CHANNEL` | `drools:rule:events` | Redis pub/sub channel name. |

### 5.2 Removed env vars

| Env var | Reason |
|---|---|
| `REDIS_TTL_MINUTES` | Renamed to `REDIS_DRL_RULES_TTL_MINUTES`. |
| `LRU_CACHE_MAX_SIZE` | LocalLRUCache deleted. |

### 5.3 Unchanged env vars

- `REDIS_ENABLED`, `REDIS_URL`
- `DROOLS_CB_REDIS_*` (circuit breaker config preserved)

### 5.4 Migration

Operators with `REDIS_TTL_MINUTES` set: silently falls back to 15-min default. Document in release notes. Optional v1 backward-compat read:
```java
@Value("${redis.drl-rules.ttl-minutes:${redis.ttl-minutes:15}}")
long ttlMinutes;
```
With WARN log when old var is used.

---

## 6. Refresh & invalidation strategy

### 6.1 Full refresh `POST /admin/refresh-rules` (3-task ECS)

```
1. ALB routes to task A
2. AdminController.refreshAllRules():
   a. storage.refreshCache()        // SCAN drools:rule:* | DEL
   b. List<Rule> rules = storage.getAllRules()  // S3 fetch, repopulates Redis
   c. droolsEngineService.loadRules(rules)      // recompile A's kieContainer
   d. publisher.publishBulkRefresh()            // event to drools:rule:events
3. Tasks B and C receive event:
   - Skip self-check
   - storage.getAllRules() → reads from Redis (already warm)
   - droolsEngineService.loadRules(rules) → recompile each's kieContainer
4. Cross-task convergence: ~5 sec network + ~7 min compile per task at 10k
```

### 6.2 Single-rule refresh `POST /admin/refresh-rules/{ruleId}`

```
1. ALB routes to task A
2. AdminController.refreshRule(id):
   a. storage.refreshRule(id)        // DEL drools:rule:{id}
   b. storage.getRule(id)            // S3 fetch, repopulates Redis
   c. droolsEngineService.loadOrReplaceRule(rule)  // ~50ms KieBase swap on A
   d. publisher.publishRefresh(id)
3. Tasks B and C receive event:
   - storage.getRule(id) → Redis hit (~5ms)
   - droolsEngineService.loadOrReplaceRule(rule)  // ~50ms KieBase swap
4. Cross-task convergence: ~100ms — under 1 sec
```

### 6.3 TTL safety net

15-min TTL ensures missed pub/sub events self-heal within 15 min via Redis cache miss → S3 → fresh data on next read. Note: this doesn't repair compiled-state divergence (kieContainer doesn't refresh on Redis miss). For that, operator must re-trigger refresh OR enable AUTO_REFRESH as backstop.

---

## 7. Cross-service consumer contract

### 7.1 Key naming
- `drools:rule:{ruleId}` (configurable via `REDIS_DRL_RULES_KEY_PREFIX`)

### 7.2 Pub/sub channel
- `drools:rule:events` — consumers can subscribe to invalidate their own caches
- JSON event format documented in section 3.6

### 7.3 Value format
- `Rule` serialised via `Jackson2JsonRedisSerializer`
- Schema-compatible with today's `RedisRuleCache`

### 7.4 Freshness contract
- Stale entries cleared within seconds after `/admin/refresh-rules` (via DEL + pub/sub)
- TTL ceiling 15 min if no events
- Consumers should subscribe to pub/sub channel for instant invalidation

### 7.5 Out of scope (v1)
- AWS SNS/SQS alternative
- Multi-region replication
- Per-environment key prefix templates

---

## 8. Backward compatibility & rollback

### 8.1 Feature flags

- `REDIS_ENABLED=false` → no Redis bean, no decorator, no pub/sub. Direct-S3 mode.
- `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false` → cache only, no cross-task sync.
- `REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true` → full feature.

### 8.2 Rollback

1. Toggle `REDIS_ENABLED=false` and restart ECS tasks. Immediate revert. No data loss (S3 source of truth).
2. Or disable just pub/sub via `REDIS_PUBSUB_ENABLED=false` if pub/sub is the problem.
3. Code-level rollback: deploy previous service jar tag.

### 8.3 Forward-compat

- Bump key prefix `drools:rule:` → `drools:rule:v2:` for schema migrations
- New event types added: subscribers ignore unknown types

---

## 9. Phases & effort

| Phase | Scope | Effort | Risk |
|---|---|---|---|
| 0. Pre-flight & audit | Audit `CacheStatistics`, `/admin/rules` consumers, env var usage | 0.5 day | Low |
| 1. Build `RedisCachedRuleStorage` | New decorator class + unit tests | 2 days | Low |
| 2. Build pub/sub publisher + subscriber | `RuleRefreshPublisher`, `RuleRefreshSubscriber`, `RefreshEvent`, `InstanceIdConfig`. Tests | 2 days | Medium |
| 3. Wire `StorageFactory` + `RedisConfig` | Conditional decorator wiring; `RedisMessageListenerContainer` setup | 0.5 day | Low |
| 4. Delete dead code | Remove `RuleCache` interface, `LocalLRUCache`, `RedisRuleCache`, `CacheStatistics`, tests, autowire sites | 1 day | Low |
| 5. Adapt `AdminController` + endpoints | Drop `RuleCache` injection, wire publisher, refactor `/admin/rules`, `/admin/cache/stats`, `/admin/health` | 1 day | Medium |
| 6. Config + env var migration | Rename, drop, add env vars | 0.5 day | Low |
| 7. Integration tests | Testcontainers Redis: read-through, invalidation, pub/sub fan-out, circuit-breaker | 2 days | Medium |
| 8. Documentation | README, CLAUDE.md, all relevant project-documentation files, OpenAPI, ADR-016 | 1.5 days | Low |
| 9. Load test | Re-run `scripts/run-load-test.sh` with `REDIS_ENABLED=true` | 1.5 days | Medium |
| 10. Phased rollout | Stage soak 1 week → prod | 1–2 weeks elapsed | Low |
| 11. Backward-compat cleanup (optional) | Remove `REDIS_TTL_MINUTES` fallback after 1 release | 0.5 day | Low |

**Total dev effort:** ~13 dev-days.
**Calendar time:** 3–4 weeks including stage soak.

---

## 10. Risks & open questions

### 10.1 Risk: Pub/sub message loss
Redis pub/sub is fire-and-forget. Missed events cause silent divergence.
**Mitigation:** Connect/disconnect logging, `subscriber.connected` gauge, optional AUTO_REFRESH backstop, v2 Redis Streams.

### 10.2 Risk: Bulk refresh stampede
All N tasks compile simultaneously on bulk pub/sub event.
**Mitigation:** Document. v2: random jitter. Real fix: kjar plan.

### 10.3 Risk: AdminController API shape change
`/admin/rules` `cached` field removal breaks consumers.
**Mitigation:** Phase 0 audit. Optional 1-release deprecation period.

### 10.4 Risk: Env var rename silently uses defaults
**Mitigation:** Release note + optional backward-compat with WARN log.

### 10.5 Risk: Bulk SCAN performance at 10k
10k keys with N×GET = ~50s. MGET batched 1000 keys = ~5s.
**Mitigation:** Use MGET + pipelining.

### 10.6 Risk: Circuit breaker thrash
Flaky Redis causes oscillation.
**Mitigation:** Tune `DROOLS_CB_REDIS_*`. Validate during stage soak.

### 10.7 Risk: Subscriber refresh failure on receiving task
Silent staleness.
**Mitigation:** ERROR log; `drools.refresh.failed` counter; alerting. v2 retry.

### 10.8 Risk: Memory pressure on Redis at 10k rules
10k × ~10 KB = ~100 MB.
**Mitigation:** Document sizing. maxmemory policy.

### 10.9 Open question: persistent event log
Redis Streams instead of pub/sub for durability?
**Recommendation:** v1 stays pub/sub. v2 evaluates streams.

### 10.10 Open question: deduplication beyond instanceId
**Recommendation:** Idempotent refresh is sufficient.

---

## 11. Critical files — summary

### New (production)
- `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java`
- `src/main/java/com/company/drools/cache/RuleRefreshPublisher.java`
- `src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java`
- `src/main/java/com/company/drools/cache/RefreshEvent.java`
- `src/main/java/com/company/drools/config/InstanceIdConfig.java`

### New (tests)
- `src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java`
- `src/test/java/com/company/drools/cache/RuleRefreshPublisherTest.java`
- `src/test/java/com/company/drools/cache/RuleRefreshSubscriberTest.java`
- `src/test/java/com/company/drools/cache/RefreshEventTest.java`
- `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`
- `src/test/java/com/company/drools/integration/RedisPubSubIntegrationTest.java`

### Modified (production)
- `src/main/java/com/company/drools/storage/StorageFactory.java`
- `src/main/java/com/company/drools/api/controller/AdminController.java`
- `src/main/java/com/company/drools/config/RuleLoadingConfig.java`
- `src/main/java/com/company/drools/config/RedisConfig.java`
- `src/main/resources/application.yml`
- `.env.example`
- `docker-compose.yml`
- `scripts/docker-compose.loadtest.yml`

### Modified (tests)
- `src/test/java/com/company/drools/api/controller/AdminControllerTest.java`
- `src/test/java/com/company/drools/config/RuleLoadingConfigTest.java`
- `src/test/java/com/company/drools/storage/StorageFactoryTest.java`
- `src/test/java/com/company/drools/config/MetricsConfigTest.java`
- `src/test/java/com/company/drools/integration/RuleRefreshIntegrationTest.java`

### Modified (docs)
- `README.md`
- `CLAUDE.md`
- `project-documentation/00-system-overview.md`
- `project-documentation/01-project-overview.md`
- `project-documentation/02-project-structure.md`
- `project-documentation/04-architecture.md`
- `project-documentation/09-environment-variables-reference.md`
- `project-documentation/10-api-reference.md`
- `project-documentation/29-circuit-breakers-and-resilience.md`
- `project-documentation/30-runbooks-and-monitoring.md`
- `project-documentation/31-troubleshooting.md`
- `project-documentation/35-faq.md`
- `project-documentation/36-architecture-decision-records.md` (ADR-016 new, ADR-004/005 superseded)
- `project-documentation/37-glossary.md`
- `project-documentation/api-reference/openapi.yml`

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

### 12.1 Disabled-mode regression (`REDIS_ENABLED=false`)
- Service boots without Redis bean, decorator, publisher, subscriber
- `/admin/health` cache section absent or shows `{enabled: false}`
- `/admin/cache/stats` returns 404 or `{enabled: false}`
- All remaining tests pass
- Load test latency identical to today's baseline (157,754 req, 0 errors, ~518 RPS)

### 12.2 Cache-only mode (`REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=false`)
- `RedisCachedRuleStorage` injected as primary
- Publisher and subscriber NOT created
- First refresh hits S3 → repopulates Redis
- Second refresh: Redis hits visible via metrics
- `/admin/cache/stats` shows Redis-only stats
- Redis down: circuit breaker opens, falls through to S3, service continues

### 12.3 Full mode (`REDIS_ENABLED=true, REDIS_PUBSUB_ENABLED=true`)
- All of 12.2 plus:
- Publisher emits events after refresh
- Subscriber receives and processes events from other instances
- Self-message dedup works (skip_self counter)
- Cross-task convergence: refresh on A → B's kieContainer updates within ~1 sec (single) or ~7 min (bulk)
- Pub/sub disconnect/reconnect: subscriber auto-reconnects

### 12.4 Refresh invalidation
- Populate Redis with rule v1
- Replace rule in S3 with v2
- Call `/admin/refresh-rules`
- Verify `drools:rule:{id}` in Redis now holds v2
- Verify all 3 tasks' kieContainers serve v2 within 1 sec

### 12.5 Cross-service freshness
- Instance A refreshes → cross-service consumer sees v2 within seconds
- TTL ceiling: stale reads stop after 15 min

### 12.6 Migration
- With old `REDIS_TTL_MINUTES=120`: log warns deprecated; uses 15-min default
- With new `REDIS_DRL_RULES_TTL_MINUTES=120`: uses 120

### 12.7 Load test
- P99 execution latency: identical to baseline ±10%
- Multi-task refresh: tasks 2 and 3 fetch from Redis, not S3
- Pub/sub fan-out: refresh on one task → all 3 tasks converge < 1 sec for single rule
- No memory regression, no new errors

### 12.8 Stage soak (1 week)
- Cache hit rate stable
- Pub/sub event delivery rate stable
- No incidents

### 12.9 Code quality gates
- All existing tests pass minus ~24 deleted
- New tests: ~38, ≥85% line coverage on new files
- `mvn compile spotbugs:check` clean
- `mvn spotless:check` clean
- SonarQube: 0 new blocker/critical issues
- JaCoCo: branch coverage on new classes ≥ 80%

---

## 13. Documentation impact — comprehensive

### 13.1 New documentation

- **ADR-016: Redis Decorator + Pub/Sub for Multi-instance DRL Cache** — in `36-architecture-decision-records.md`
  - Status: Accepted
  - Context: dead `RuleCache` + cross-task divergence
  - Decision: `RedisCachedRuleStorage` decorator + Redis pub/sub
  - Consequences: positive (real cache, multi-instance convergence, cross-service consumers), negative (env var renames, API shape change, new failure modes)
  - Alternatives considered: SNS/SQS, auto-refresh, manual fan-out, status quo
- **Mark ADR-004 (LocalLRUCache uses WRITE lock on `get()`) as Superseded** by ADR-016
- **Mark ADR-005 (Redis bean exists but is dormant by default) as Superseded** by ADR-016

### 13.2 Updated documentation

| File | Section | Changes |
|---|---|---|
| `README.md` | Architecture / Caching / Performance | Replace LRU+Redis story. Update Rule Capacity. Add cross-task convergence section. |
| `CLAUDE.md` | Caching Strategy | Simplified bullet: Redis cache + pub/sub when enabled. Add Recent change log entry. |
| `00-system-overview.md` | Architecture overview | Updated caching diagram. |
| `01-project-overview.md` | Caching strategy table | Updated to decorator + pub/sub. |
| `02-project-structure.md` | Package list | Update `cache/` contents; add `RedisCachedRuleStorage` to `storage/`. |
| `04-architecture.md` | Caching layer + diagrams | Replace L1/L2/L3 misdescription. Add pub/sub fan-out diagram. Multi-instance refresh sequence diagram. |
| `09-environment-variables-reference.md` | Redis section | Add new env vars; remove old. |
| `10-api-reference.md` | `/admin/rules`, `/admin/cache/stats` | Document new response shapes. |
| `29-circuit-breakers-and-resilience.md` | Redis section | Now actually exercised; describe pub/sub circuit breaker. |
| `30-runbooks-and-monitoring.md` | Cache + pub/sub | New runbook: Redis pub/sub event loss recovery. |
| `31-troubleshooting.md` | Cache + pub/sub issues | New entries: cross-task divergence symptoms, pub/sub debugging. |
| `35-faq.md` | LRU + Redis | Remove LRU. Update Redis. Add pub/sub FAQ. |
| `37-glossary.md` | LRU, Redis | Remove LRU; rename Redis; add pub/sub terms. |
| `api-reference/openapi.yml` | Schemas | Update affected endpoints. |

### 13.3 New diagrams

- Sequence: multi-task refresh with pub/sub fan-out (`04-architecture.md`)
- Cache layer: Redis decorator (`04-architecture.md`)
- Failure mode: Redis down circuit breaker fallback (`29-circuit-breakers.md`)

---

## 14. Metrics & observability

### 14.1 New metrics

Cache:
- `drools.cache.hit{layer=redis}` counter
- `drools.cache.miss{layer=redis}` counter
- `drools.cache.bulk.hit`, `drools.cache.bulk.miss{count=N}` counters
- `drools.cache.read.duration{layer=redis}` timer
- `drools.cache.write.duration{layer=redis}` timer
- `drools.cache.invalidation{scope=bulk|single}` counter
- `drools.cache.size{layer=redis}` gauge

Pub/Sub:
- `drools.refresh.published{event=...}` counter
- `drools.refresh.received{event=...}` counter
- `drools.refresh.skipped_self` counter
- `drools.refresh.processing.duration{event=...}` timer
- `drools.refresh.failed{layer=publisher|subscriber}` counter
- `drools.refresh.subscriber.connected` gauge

### 14.2 Removed metrics
- `drools.cache.local.*` — LocalLRUCache gone

### 14.3 Health check
- `/admin/health`:
  - `cache.redis.status` (UP/DOWN) when Redis enabled
  - `cache.redis.size` (sampled key count)
  - `pubsub.status` (UP/DOWN) when pub/sub enabled
  - `pubsub.last_event_age_seconds`

### 14.4 Logging

- Publisher: `INFO` on publish, `WARN` on failure
- Subscriber: `INFO` on event received/completion, `ERROR` on failure
- Both: log `instance_id`, `event_type`, `rule_id`, `duration_ms`

---

## 15. Testing strategy

### 15.1 Unit tests

| Test class | Count | Coverage |
|---|---|---|
| `RedisCachedRuleStorageTest` | 12 | Read-through matrix, CB open/closed, write-through, invalidation |
| `RuleRefreshPublisherTest` | 6 | Event construction, CB, instance ID |
| `RuleRefreshSubscriberTest` | 8 | Deserialisation, self-dedup, event-type dispatch, error handling |
| `RefreshEventTest` | 3 | JSON roundtrip, version compat |

### 15.2 Integration tests (Testcontainers)

- `RedisCachedStorageIntegrationTest`:
  - Cold cache → S3 → populate → subsequent reads from Redis
  - `getAllRules` bulk SCAN with N rules
  - `refreshCache` SCAN+DEL clears all
  - Circuit breaker engages on Redis kill mid-test
  - Connection recovery after Redis restart
- `RedisPubSubIntegrationTest`:
  - Two Spring contexts (simulating 2 ECS tasks)
  - Single-rule refresh on context A → context B updates within 1 sec
  - Self-dedup verified
  - Bulk event: both contexts recompile
  - Subscriber disconnect/reconnect during event flight
  - Malformed event: ERROR logged, subscriber alive

### 15.3 Existing test impact

| Test | Action |
|---|---|
| `AdminControllerTest` | Remove `RuleCache` mock; assert publisher called; new `/admin/cache/stats` shape |
| `RuleLoadingConfigTest` | Remove `RuleCache` injection mock |
| `StorageFactoryTest` | Test decorator wiring under both flag states |
| `MetricsConfigTest` | Assert new metric names registered |
| `RuleRefreshIntegrationTest` | Assert Redis cleared + repopulated; pub/sub event emitted |
| `RuleExecutionIntegrationTest` | No change (execution doesn't touch cache) |
| `LocalLRUCacheTest`, `RedisRuleCacheTest`, `CacheStatisticsTest` | Delete |

### 15.4 Load test additions

`scripts/run-load-test.sh` extensions:
- New phase: multi-task pub/sub fan-out
  - Boot 3 app containers
  - Send rule refresh to one
  - Measure time-to-converge across all 3
  - Acceptance: < 2 sec single, < 7 min bulk at 10k

### 15.5 Manual smoke tests

`scripts/redis-pubsub-test.sh` (new):
- Start compose with full mode
- Refresh a rule via curl
- Verify `redis-cli SUBSCRIBE drools:rule:events` shows event
- Verify second container's metrics show subscriber received event
- Verify both containers serve consistent rule output

### 15.6 Quality gates

| Gate | Threshold |
|---|---|
| Unit tests | All pass; `mvn test` clean |
| Integration tests | All pass; `mvn verify` clean |
| Coverage (JaCoCo) | New files ≥ 85% line, ≥ 80% branch |
| SpotBugs | 0 new bugs |
| Spotless | clean |
| SonarQube | 0 new blocker/critical; new code coverage ≥ 80%; new violations 0 |
| Load test regression | P99 within ±10% baseline |
| Memory | No regression in heap usage at rest |

---

## 16. Documentation deliverables (MUST complete before merging)

Critical: this plan ships ONLY when documentation is updated. Doc work is part of the definition of done.

- [ ] `README.md` updated — caching section, Rule Capacity, cross-task convergence section
- [ ] `CLAUDE.md` updated — caching strategy bullet, Recent change log entry
- [ ] All 14 affected project-documentation files updated (section 13.2)
- [ ] OpenAPI spec updated for changed endpoints
- [ ] ADR-016 added
- [ ] ADR-004 and ADR-005 marked Superseded with links to ADR-016
- [ ] New diagrams added to `04-architecture.md`
- [ ] Migration guide drafted in `30-runbooks-and-monitoring.md`
- [ ] FAQ updated: "Why does my second task serve stale rules?" → pub/sub explanation
- [ ] Glossary updated: `RefreshEvent`, `RuleRefreshPublisher`, `RuleRefreshSubscriber`

---

## 17. Recommendation summary

- **Do it.** Solves both the dead-cache problem AND the cross-task divergence problem.
- **Phase carefully.** Build decorator + pub/sub independently with tests before wiring. Soak in stage 1 week.
- **Keep `REDIS_ENABLED=false` as default** so dev/local stays simple. Production opts in.
- **Coordinate with kjar plan.** Kjar eliminates compile cost; this plan eliminates fetch cost + adds cross-task sync. Together they cover 10k rules × 3-5 tasks.

---

## 18. Companion files

- Detailed checklist: [`redis-cache-layering-checklist.md`](redis-cache-layering-checklist.md)
- Cross-reference: [`kjar-precompilation-plan.md`](kjar-precompilation-plan.md)
