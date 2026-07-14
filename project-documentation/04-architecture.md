# Drools Rule Engine Microservice - Architecture Documentation

**Version**: 1.2.0
**Last Updated**: 2026-05-24 (post-modernization, post-load-test, post-Redis-cache + pub/sub + Phase-9.4-hardening)
**Status**: Production-Ready Architecture (load tested at 1,000 rules single-container 2026-05-10; 3-replica + Redis-kill failure mode 2026-05-24 — see [39-load-test-findings.md](39-load-test-findings.md))

---

## Table of Contents

1. [System Overview](#system-overview)
2. [High-Level Architecture](#high-level-architecture)
3. [Component Architecture](#component-architecture)
4. [Request Flow Architecture](#request-flow-architecture)
5. [Data Flow Architecture](#data-flow-architecture)
6. [Storage Architecture](#storage-architecture)
7. [Caching Architecture](#caching-architecture)
8. [Thread Model & Concurrency](#thread-model--concurrency)
9. [Security Architecture](#security-architecture)
10. [Performance Architecture](#performance-architecture)
11. [Monitoring Architecture](#monitoring-architecture)
12. [Deployment Architecture](#deployment-architecture)
13. [Technology Stack](#technology-stack)
14. [Design Patterns & Principles](#design-patterns--principles)

---

## System Overview

### Purpose

The Drools Rule Engine Microservice is a high-performance, cloud-native business rule execution platform designed to externalize business logic from application code. It enables non-technical users to manage business rules through simple .drl files stored in S3, while providing developers with a robust REST API for rule execution.

### Design Goals

1. **High Performance**: Support 100-1000 requests/second with P99 latency < 100ms
2. **Scalability**: Horizontal scaling across multiple instances, support 1000+ concurrent rules
3. **Reliability**: Production-grade fault tolerance, circuit breakers, graceful degradation
4. **Maintainability**: Clean architecture, separation of concerns, comprehensive monitoring
5. **Flexibility**: Support multiple storage backends (S3, local files), optional Redis caching
6. **Security**: Multi-layer input validation, rate limiting, log sanitization

### Key Characteristics

- **Stateless Design**: Each request is independent, enabling horizontal scaling
- **Polyglot Rule Storage**: Rules stored as .drl files in S3 with hierarchical organization
- **Single-Tier Shared Cache + Pub/Sub Fan-Out (ADR-016, 2026-05-20)**: Refresh path: S3 ↔ `RedisCachedRuleStorage` decorator (read-through Redis cache of DRL text, opt-in via `REDIS_ENABLED`); cross-instance refresh coherence via Redis pub/sub on `drools:rule:events`. Execution path: compiled KieBases in a single long-lived `KieContainer` (never touches Redis).
- **API Architecture**: All API endpoints on port 8080, Actuator on port 8081
- **Memory Stable**: Proper resource disposal prevents memory leaks and OOM errors
- **Cloud-Native**: Containerized, 12-factor app compliant, AWS-ready

---

## High-Level Architecture

### Layered Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT LAYER                              │
│  (External Applications, Admin Tools, Monitoring Dashboards)     │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ HTTP/REST
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    API GATEWAY LAYER                             │
│  ┌──────────────┐          ┌──────────────┐                     │
│  │  Main API    │          │  Admin API   │                     │
│  │  Port 8080   │          │  Port 8080   │                     │
│  │ /execute-rule│          │ /admin/*     │                     │
│  └──────────────┘          └──────────────┘                     │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    SECURITY LAYER                                │
│  [Rate Limiting] [Input Validation] [CORS] [Size Limits]        │
│  [Request Timeout] [Log Sanitization]                           │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    APPLICATION LAYER                             │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │         DroolsEngineService (Core Business Logic)        │   │
│  │  - Rule Compilation    - Rule Execution                  │   │
│  │  - Session Management  - Metadata Tracking               │   │
│  │  - KieContainer Lifecycle (Memory Management)            │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              REFRESH / STARTUP PATH (DRL text only)              │
│  ┌──────────────────────────┐    ┌────────────────────────────┐ │
│  │ RedisCachedRuleStorage   │ ←→ │ DroolsEngineService        │ │
│  │ (decorator, opt-in via   │    │ kieContainer (compiled) +  │ │
│  │  REDIS_ENABLED=true)     │    │ pub/sub fan-out via        │ │
│  │  └ read-through Redis    │    │ drools:rule:events on      │ │
│  │    cache of DRL text     │    │ refresh                    │ │
│  └──────────────────────────┘    └────────────────────────────┘ │
│  Note: Redis stores raw DRL text. Compiled KieBases live in      │
│  kieContainer only. Execution bypasses Redis entirely.           │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    STORAGE LAYER                                 │
│  ┌──────────────────────┐         ┌──────────────────────┐      │
│  │     AWS S3           │         │   Local FileSystem   │      │
│  │  (Production)        │         │   (Development)      │      │
│  │  - Rule Repository   │         │   - Rule Files       │      │
│  │  - Versioning        │         │   - Quick Testing    │      │
│  └──────────────────────┘         └──────────────────────┘      │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                CROSS-CUTTING CONCERNS                            │
│  [Logging] [Metrics] [Health Checks] [Circuit Breakers]         │
│  [Thread Pools] [Request Correlation] [Error Handling]          │
└─────────────────────────────────────────────────────────────────┘
```

### Architecture Principles

1. **Separation of Concerns**: Each layer has a single, well-defined responsibility
2. **Dependency Inversion**: Higher layers depend on abstractions, not implementations
3. **Fail-Safe Defaults**: System degrades gracefully when external services unavailable
4. **Observability First**: Comprehensive logging, metrics, and health checks built-in
5. **Resource Management**: Explicit lifecycle management for expensive resources (KieContainer)

---

## Component Architecture

### 1. API Layer (`com.company.drools.api`)

**Purpose**: Handle HTTP requests, input validation, and response formatting

#### Controllers

**RuleExecutionController** (Main API - Port 8080) — see [RuleExecutionController.java](../src/main/java/com/company/drools/api/controller/RuleExecutionController.java)
- **Endpoint**: `POST /execute-rule`
- **Responsibility**: Execute business rules with provided data
- **Input**: `{ "rule_id": "pricing.discount.vip", "data": {...} }` — JSON uses **snake_case** (`rule_id`) via `@JsonProperty("rule_id")` on the `ruleId` Java field; see [RuleExecutionRequest.java:12](../src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java#L12)
- **Output**: Rule execution results or error response
- **Validation**: Request size, data structure, rule ID format (custom `@ValidRuleId`, `@ValidRuleData` annotations)
- **Security**: Rate limiting, input sanitization

**AdminController** (Admin API - Port 8080) — see [AdminController.java](../src/main/java/com/company/drools/api/controller/AdminController.java)
- **Endpoints**:
  - `GET /admin/health` — Component health checks (lines 89-155)
  - `GET /admin/info` — Application info (lines 316-325)
  - `GET /admin/thread-pools` — Thread pool stats (lines 328-365)
  - `GET /admin/rules` — List all loaded rules with metadata (lines 479-520)
  - `POST /admin/refresh-rules` — Reload all rules from storage (lines 368-414)
  - `POST /admin/refresh-rules/{ruleId}` — Reload specific rule (lines 417-476)
- **Responsibility**: System administration and monitoring
- **Security**: Admin endpoints are **exempt from rate limiting** but require the `X-Admin-API-Key` header when `ADMIN_API_KEY` env var is set (verified by [AdminAuthFilterTest](../src/test/java/com/company/drools/api/filter/AdminAuthFilterTest.java) and [RateLimitingFilterTest:120-127](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java#L120-L127))

**MemoryController** (Admin API - Port 8080)
- **Endpoints**:
  - `GET /admin/memory/info` - Comprehensive memory statistics
  - `POST /admin/memory/gc` - Manual garbage collection trigger
  - `GET /admin/memory/snapshot` - Quick memory snapshot
- **Responsibility**: Real-time memory diagnostics and monitoring
- **Metrics**: Heap usage, GC stats, memory pools, automatic warnings

**(no separate `ThreadPoolController`)** — `GET /admin/thread-pools` is handled by `AdminController` (the project has only 3 controller classes: `RuleExecutionController`, `AdminController`, `MemoryController`).

#### DTOs (Data Transfer Objects)

> **Note on naming**: Java fields use camelCase (`ruleId`); JSON serialization uses snake_case (`rule_id`) via Jackson's `@JsonProperty` annotation. The mapping is in [RuleExecutionRequest.java:12](../src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java#L12) and similar DTOs.

**RuleExecutionRequest** — Java type / JSON shape
```java
// Java: src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java
class RuleExecutionRequest {
  @JsonProperty("rule_id")  String ruleId;     // JSON: "rule_id"
  @JsonProperty("data")     Map<String,Object> data;
}
```
```json
// Wire format
{ "rule_id": "pricing.discount.vip", "data": {...} }
```

**RuleExecutionResponse** — JSON shape
```json
{
  "rule_id": "pricing.discount.vip",
  "result": { ... },
  "error": null,
  "execution_time_ms": 15
}
```

**RuleInfo** (entry inside `GET /admin/rules` response)
```json
{
  "rule_id": "pricing.discount.vip",
  "status": "ACTIVE",
  "loaded_at": "2026-05-08T08:57:10Z",
  "last_modified": "2026-05-08T08:57:10Z",
  "execution_count": 42,
  "avg_execution_time_ms": 15.3,
  "version": "1.0"
}
```

#### Exception Handling

**GlobalExceptionHandler**
- Catches all exceptions and converts to standardized error responses
- Logs errors with correlation IDs for tracing
- Sanitizes error messages to prevent sensitive data exposure
- HTTP status code mapping:
  - 400: Validation errors, malformed requests
  - 404: Rule not found
  - 429: Rate limit exceeded
  - 500: Internal errors, rule execution failures
  - 503: Service unavailable (circuit breaker open)

#### Filters (4 total — see [`api/filter/`](../src/main/java/com/company/drools/api/filter/))

**SecurityHeadersFilter** `@Order(-1)`
- Adds 7 response headers (X-Content-Type-Options, X-Frame-Options, X-XSS-Protection, Referrer-Policy, Cache-Control, CSP, HSTS) to every response.

**AdminAuthFilter** `@Order(0)`
- Matches `/admin/*` paths; requires `X-Admin-API-Key` header when `ADMIN_API_KEY` env var is set; SKIPS check (with WARN log at startup) when unset.

**RateLimitingFilter** `@Order(1)`
- Per-client rate limiting (default: 1000 req/min, 10000 req/hr); admin endpoints exempt.
- Returns HTTP 429 with `X-RateLimit-*` + `Retry-After` headers.
- Configurable via `DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE`, etc.

**RequestSizeValidationFilter** (no `@Order` — runs last)
- 1 MB default body cap; wraps chunked-transfer streams with `SizeLimitedInputStream` to prevent bypass.

**Note on log sanitization**: `LogSanitizer` is a utility class in [`com.company.drools.common`](../src/main/java/com/company/drools/common/LogSanitizer.java), NOT a filter. It's called explicitly by controllers/services before logging request/response payloads.

**Note on request timeout**: rule-execution timeout is enforced inside `RuleExecutor` via `CompletableFuture.get(30s)` + `future.cancel(true)`, not a separate filter.

---

### 2. Core Layer (`com.company.drools.core`)

**Purpose**: Business logic for rule compilation, execution, and lifecycle management

#### DroolsEngineService (Primary Service)

**Responsibilities**:
1. **Rule Compilation**: Convert .drl files to executable KieBase objects
2. **Rule Execution**: Execute rules with provided data in isolated sessions
3. **KieContainer Lifecycle**: Manage KieContainer creation and disposal (CRITICAL for memory stability)
4. **Metadata Tracking**: Track rule status, execution counts, timestamps
5. **Error Handling**: Capture compilation and execution errors

**Key Methods**:

```java
// Load all rules from storage
public boolean loadRulesFromStorage()

// Load single rule by ID
public boolean loadRule(String ruleId)

// Execute rule with data
public Map<String, Object> executeRule(String ruleId, Map<String, Object> data)

// Get rule metadata
public RuleMetadata getRuleMetadata(String ruleId)

// List all loaded rules
public List<Rule> getAllLoadedRules()

// Refresh rules (reload from storage)
public void refreshRules()
```

**Memory Management Architecture** (Critical):

```java
// CURRENT APPROACH (Drools 10 incremental update via updateToVersion + explicit cleanup):
//
// 1. RuleCompiler emits a versioned KieModule with a fresh ReleaseId, registered
//    in the singleton KieRepository. Compilation runs OUTSIDE the rulesLock so
//    reads against the OLD KieBase are not blocked.
ReleaseId newReleaseId = ruleCompiler.compileRules(rules).getReleaseId();

// 2. Take the write lock, capture the prior ReleaseId, swap the running KieBase
//    via Drools 10's incremental update. This is atomic; in-flight sessions keep
//    the old definitions, new sessions see the new ones.
ReleaseId oldReleaseId;
rulesLock.writeLock().lock();
try {
    oldReleaseId = kieContainer.getReleaseId();
    kieContainer.updateToVersion(newReleaseId);
    // ... update loadedRules / ruleMetadata maps
} finally {
    rulesLock.writeLock().unlock();
}

// 3. Outside the write lock, evict the prior KieModule from KieRepository.
//    Drools 10 does NOT auto-clean — without this, every refresh accumulates a
//    KieModule + ProjectClassLoader + compiled rule classes until LRU caps evict.
//    Verified by load test: 1 MB heap drift over 98 refreshes (see 39-load-test-findings.md).
kieRepository.removeKieModule(oldReleaseId);
```

**Why This Matters**:
- Each `KieModule` retained in `KieRepository` holds a `ProjectClassLoader` + every compiled rule class (10–100 MB at scale).
- Without explicit `removeKieModule`, refreshes accumulate compiled bytecode in the singleton repository → OOM under sustained hot reloads.
- With explicit cleanup: heap stable across thousands of refreshes (load tested 2026-05-10).
- The earlier "two-container atomic-swap with `oldContainer.dispose()`" pattern was superseded on 2026-05-10 — Drools 10's `updateToVersion` swaps the internal `KieBase` in place, and the explicit `dispose()` of an old container is no longer the right model. See [ADR-003 2026-05-10 update](36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal).

**Thread Safety**:
- Uses `ConcurrentHashMap` for thread-safe rule and metadata storage
- Each execution creates a new `KieSession` (stateless, thread-safe)
- `KieContainer.updateToVersion(ReleaseId)` is atomic; the brief swap window is held under `rulesLock.writeLock()` while the long compile happens outside the lock

#### RuleCompiler

**Responsibility**: Compile .drl files to executable KieBase objects

**Compilation Process**:
```
.drl File (Text)
    ↓
KieFileSystem (Drools API)
    ↓
KieBuilder.buildAll()
    ↓
KieContainer (Compiled Rules)
    ↓
KieBase (Executable Rule Set)
```

**Error Handling**:
- Syntax errors: Returns compilation error messages
- Semantic errors: Returns validation error messages
- Successful compilation: Returns KieContainer ready for execution

**Caching**:
- Compiled KieBase objects cached in DroolsEngineService
- Avoids re-compilation on every request
- Cache invalidation on rule refresh

---

### 3. Storage Layer (`com.company.drools.storage`)

**Purpose**: Abstract rule storage backends with pluggable implementations

#### Storage Architecture

```
┌─────────────────────────────────────────────────────────┐
│              RuleStorage (Interface)                    │
│  - getRule(ruleId): Optional<Rule>                      │
│  - getAllRules(): List<Rule>                            │
│  - getRuleIds(): List<String>                           │
│  - getTotalRuleCount(): long                            │
│  - ruleExists(ruleId): boolean                          │
│  - saveRule(rule): void                                 │
│  - deleteRule(ruleId): void                             │
│  - refreshCache(): void                                 │
│  - refreshRule(ruleId): void                            │
└─────────────────────────────────────────────────────────┘
                         ▲
                         │ implements
        ┌────────────────┼────────────────┬───────────────┐
        │                │                │               │
┌───────────────┐ ┌─────────────────┐ ┌─────────────┐ ┌────────────────────────┐
│ S3RuleStorage │ │ LocalFileStorage│ │ InMemory-   │ │ RedisCachedRuleStorage │
│ (production)  │ │ (dev)           │ │ RuleStorage │ │ (decorator, opt-in)    │
└───────────────┘ └─────────────────┘ └─────────────┘ └────────────────────────┘
```

`StorageFactory` selects the base via `RULE_SOURCE`, then wraps it in `RedisCachedRuleStorage` when `REDIS_ENABLED=true`. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20).

#### S3RuleStorage (Production)

**Architecture**:
- AWS SDK v2 with async client for high performance
- Connection pooling (default: 50 connections)
- Circuit breaker protection (Resilience4j)
- Automatic retry with exponential backoff

**Rule ID Transformation**:
```
Rule ID: "pricing.discount.vip"
    ↓
S3 Key: "pricing/discount/vip.drl"
    ↓
S3 Path: s3://bucket-name/pricing/discount/vip.drl
```

**Configuration**:
- `RULE_BUCKET_NAME`: S3 bucket name
- `AWS_REGION`: AWS region (default: us-east-1)
- `AWS_ENDPOINT`: Optional override for LocalStack
- `AWS_S3_MAX_CONNECTIONS`: Connection pool size

**Error Handling**:
- `NoSuchKeyException` → Rule not found (404)
- `S3Exception` → Circuit breaker may open
- Connection timeout → Retry with backoff
- Circuit breaker open → Fallback to cache

#### LocalFileStorage (Development)

**Architecture**:
- Local file system storage for quick testing
- No external dependencies
- Path-traversal-safe (`normalize()` + `startsWith()` check before every read)

**Rule ID Transformation**:
```
Rule ID: "pricing.discount.vip"
    ↓
File Path: "{LOCAL_RULES_DIRECTORY}/pricing/discount/vip.drl"
```

**Configuration**:
- `LOCAL_RULES_DIRECTORY`: Local directory path (default: `src/main/resources/rules`)

**Use Cases**:
- Local development without AWS credentials
- CI/CD testing without S3
- Quick rule prototyping

---

### 4. Pub/Sub Refresh Fan-Out (`com.company.drools.cache`)

**Purpose**: cross-instance compiled-state coherence after a rule refresh. The `cache/` package no longer contains a cache (per ADR-016, the in-process LRU was removed 2026-05-20); the actual Redis cache lives in [`storage/RedisCachedRuleStorage.java`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java). Today's `cache/` package contains `RefreshEvent`, `RuleRefreshPublisher`, `RuleRefreshSubscriber` — the pub/sub fan-out mechanism.

#### Caching Architecture

**Execution path** (POST /execute-rule — Redis is NOT consulted):
```
Request for Rule ID
    ↓
DroolsEngineService.loadedRules (ConcurrentHashMap — all loaded rules in memory)
    ↓ rule found → kieContainer.newKieSession()
RuleExecutor.fireAllRules()
    ↓
Response
```

**Refresh / startup path** (POST /admin/refresh-rules, startup warm-up):
```
S3 (source of truth)
    ↓
┌──────────────────────────────────────────────────────────┐
│  RedisCachedRuleStorage (decorator, when REDIS_ENABLED)  │
│  - Read-through: GET → SETEX on miss                     │
│  - Bulk path: SCAN(prefix) + MGET, fall-through for misses│
│  - Write-through: saveRule/deleteRule update Redis + base│
│  - Stores Rule JSON (DRL text + metadata, Jackson)       │
│  - TTL: REDIS_DRL_RULES_TTL_MINUTES (default 15)         │
│  - Scope: All instances (shared)                         │
│  - Circuit breaker: Redis op fails → fall through to base│
└──────────────────────────────────────────────────────────┘
    ↓ (compiled into)
DroolsEngineService.kieContainer
    Single long-lived container, updated in place via
    KieContainer.updateToVersion(ReleaseId) on refresh.
    All loaded rules live here; no eviction.
```

When `REDIS_ENABLED=false` the decorator is not constructed; `StorageFactory` returns the base storage (S3 / file / memory) directly. There is no longer a per-instance LRU layer — base storage is consulted on every cache miss (it has its own connection pooling and circuit breaker).

#### RedisCachedRuleStorage (read-through decorator)

**Implementation** ([`RedisCachedRuleStorage.java`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java)):
- Implements `RuleStorage`; wraps the base `S3RuleStorage` / `LocalFileStorage` / `InMemoryStorage` via `setDelegate(...)` called by `StorageFactory` after construction
- Read-through: `getRule(id)` does Redis `GET key` → on miss, delegate `.getRule(id)` then Redis `SETEX key ttl value`
- Bulk path: `getAllRules()` does `SCAN(prefix*)` then a single `MGET`; rule IDs not present in the bulk hit are fetched from base storage and re-cached
- Write-through: `saveRule` / `deleteRule` propagate to both Redis and base
- All Redis operations are wrapped in the `redisCircuitBreaker` — any failure falls through to base storage with a WARN log; the cache miss is counted but does not surface as a user error
- Stores `Rule` as JSON (Jackson `GenericJackson2JsonRedisSerializer` with `BasicPolymorphicTypeValidator`) — same shape as the in-memory record

**Configuration**:
- `REDIS_ENABLED`: Enable/disable Redis layer (default: false)
- `REDIS_URL`: Redis connection string
- `REDIS_DRL_RULES_TTL_MINUTES`: Cache TTL (default: 15)
- `REDIS_DRL_RULES_KEY_PREFIX`: Key prefix (default: `drools:rule:`)

**Error Handling**:
- Connection errors / timeouts → circuit breaker opens (default `60% / 50` window)
- Circuit breaker open → skip Redis call, fall through to base storage
- Graceful degradation: system works fully without Redis; metrics record the fall-through

**Cache Invalidation**:
- TTL-based expiration (Redis SETEX)
- Manual via `/admin/refresh-rules` — `refreshCache()` deletes all keys matching `key-prefix*` and re-populates from base storage
- Cross-instance: `RuleRefreshPublisher` emits a `RULE_REFRESHED` / `RULE_REFRESHED_BULK` event on channel `drools:rule:events`; other instances' `RuleRefreshSubscriber` reloads from the base storage (which sees the freshly-warm Redis after the originator's write-through)

#### RuleRefreshPublisher / RuleRefreshSubscriber (multi-instance fan-out)

**Why** ([ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20)): Rule **execution** reads from `DroolsEngineService.kieContainer`, not Redis. So a `POST /admin/refresh-rules` against instance A only updates A's `kieContainer`; instances B and C still execute the old compiled rules until their next refresh. Pub/sub fans the refresh out so every instance recompiles.

**Wire format** ([`RefreshEvent.java`](../src/main/java/com/company/drools/cache/RefreshEvent.java)):
```json
{
  "event_type": "RULE_REFRESHED" | "RULE_REFRESHED_BULK" | "RULE_DELETED",
  "rule_id": "pricing.discount.vip",        // null for BULK
  "source_instance_id": "5f4d9a31-...",     // self-dedup
  "timestamp": "2026-05-20T08:57:10Z"
}
```

**Publisher** ([`RuleRefreshPublisher.java`](../src/main/java/com/company/drools/cache/RuleRefreshPublisher.java)):
- `@ConditionalOnExpression("${redis.enabled:false} and ${redis.pubsub.enabled:true}")`
- Called by `AdminController` after each successful refresh: `publishRefresh(id)`, `publishBulkRefresh()`, `publishDelete(id)`
- Fire-and-forget via `StringRedisTemplate.convertAndSend(channel, json)` wrapped in `redisCircuitBreaker`; failures log WARN and increment `drools.refresh.failed{layer=publisher}`

**Subscriber** ([`RuleRefreshSubscriber.java`](../src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java)):
- Registered on `RedisMessageListenerContainer` for channel `drools:rule:events`
- Self-dedup: skips events where `source_instance_id == this.instanceId` (`drools.refresh.skipped_self` counter); `instanceId` is a `UUID.randomUUID()` bean from [`InstanceIdConfig.java`](../src/main/java/com/company/drools/config/InstanceIdConfig.java)
- Dispatch:
  - `RULE_REFRESHED` → `storage.getRule(id)` (warm Redis miss) → `engine.loadOrReplaceRule(rule)`
  - `RULE_REFRESHED_BULK` → `storage.getAllRules()` → `engine.loadRules(rules)`
  - `RULE_DELETED` → log INFO (no compiled-state delete yet; deferred to v2)

#### Compiled KieBase (execution hot path)

#### Compiled KieBase Cache

**Purpose**: Cache expensive compiled rule objects (not rule text)

**Storage**:
- Stored in `DroolsEngineService` as `Map<String, KieBase>`
- In-memory only (not serializable to Redis)

**Lifecycle**:
- Created on first rule load
- Updated on rule refresh
- Old KieBase discarded (garbage collected)

**Performance Impact**:
- Rule compilation: 50-500ms per rule (expensive)
- Compiled execution (cached `kieContainer`): 1-10ms (fast)
- Redis cache hit rate (post-2026-05-20 architecture): tracked via `drools.cache.hit{layer=redis}` Micrometer counter; depends on multi-instance fan-out activity, not a fixed steady-state number. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) and [39-load-test-findings.md](39-load-test-findings.md) Phase 9 addendum.

---

## Request Flow Architecture

### Rule Execution Flow (Main API)

```
1. CLIENT REQUEST
   POST /execute-rule
   {
     "rule_id": "pricing.discount.vip",
     "data": {"amount": 100, "customerType": "VIP"}
   }
        ↓
2. SECURITY FILTERS  (in @Order)
   - SecurityHeadersFilter  @Order(-1)  - adds 7 headers to response
   - AdminAuthFilter        @Order(0)   - skipped for /execute-rule
   - RateLimitingFilter     @Order(1)   - per-client check
   - RequestSizeValidationFilter        - request body size + chunked stream limit
        ↓
3. CONTROLLER LAYER
   - RuleExecutionController receives request
   - Input validation (@ValidRuleId, @ValidRuleData)
   - Extract rule_id and data
        ↓
4. CORE SERVICE
   - DroolsEngineService.executeRule(ruleId, data)
   - Acquire READ lock; atomic loadedRules.get(ruleId) + null check (TOCTOU-safe)
   - Verify metadata.status == ACTIVE
   - Use the long-lived kieContainer (a single instance for the lifetime of the JVM,
     updated in place via updateToVersion(ReleaseId) on refresh)
   - Release READ lock
        ↓
5. RULE EXECUTION (RuleExecutor)
   - Submit to ruleExecutionExecutor (CompletableFuture)
   - Create new KieSession (stateless, thread-isolated)
   - Insert data as Map into session
   - kieSession.fireAllRules(maxRuleFirings = 10000)  // cap prevents runaway loops
   - Extract results from modified data Map
   - Dispose session
   - future.get(timeoutSeconds, SECONDS); on timeout: future.cancel(true)
        ↓
6. METRICS & LOGGING
   - Record execution time on RuleMetadata (incremental averaging)
   - Emit metrics to Micrometer; unknown rule IDs tagged "unknown" to prevent cardinality explosion
   - Log result via LogSanitizer (sensitive data masked)
        ↓
7. RESPONSE
   {
     "rule_id": "pricing.discount.vip",
     "result": {"amount": 80.0, "discountPercent": 20, ...},
     "error": null,
     "execution_time_ms": 5
   }
```

**Note**: `RULE_COMPILATION` is **not** in the request path — rules are compiled once at startup and on `POST /admin/refresh-rules`, then held in the long-lived `kieContainer`. Each request creates a new `KieSession` from the current `KieBase`. See "Compile-outside-the-lock Rule Loading Pattern" below.

### Admin Flow - Rule Refresh

```
1. ADMIN REQUEST
   POST /admin/refresh-rules
        ↓
2. ADMIN CONTROLLER
   - AdminController.refreshRules()
   - Admin auth via X-Admin-API-Key (skipped if ADMIN_API_KEY unset; warning logged at startup)
        ↓
3. STORAGE REFRESH (REDIS_ENABLED=true only)
   - RedisCachedRuleStorage.refreshCache():
       SCAN(key-prefix*) → DEL keys
       fetch all from base storage → SETEX each (write-through warms cache)
   - If REDIS_ENABLED=false, this step is a no-op (StorageFactory returned base directly)
        ↓
4. STORAGE LAYER (base)
   - S3RuleStorage.getAllRules() (or LocalFileStorage / InMemoryStorage)
        ↓
5. RELOAD RULES
   - RuleCompiler.compileRules(rules) builds a fresh versioned KieModule via
     KieFileSystem + generateAndWritePomXML(releaseId) + KieBuilder.buildAll().
     Auto-registered in the singleton KieRepository under the bumped ReleaseId.
        ↓
6. ATOMIC SWAP (under write lock)
   ReleaseId oldReleaseId = kieContainer.getReleaseId();
   Results r = kieContainer.updateToVersion(newReleaseId);  // Drools 10 in-place swap
   // ... update loadedRules + ruleMetadata maps to ACTIVE
        ↓
7. CLEANUP (outside the write lock)
   kieRepository.removeKieModule(oldReleaseId);  // free prior compiled bytecode
   // (Drools 10 does NOT auto-clean; without this, refreshes leak ProjectClassLoaders)
        ↓
8. PUB/SUB FAN-OUT (when REDIS_ENABLED + REDIS_PUBSUB_ENABLED)
   - RuleRefreshPublisher.publishBulkRefresh()
   - PUBLISH drools:rule:events {event_type: RULE_REFRESHED_BULK, source_instance_id: <self>}
   - Other instances' subscribers fetch from storage and call engine.loadRules(); self-dedup skips this instance
        ↓
9. RESPONSE
   {
     "message": "Refreshed 47 rules",
     "totalRules": 47,
     "errors": []
   }
```

### Memory Monitoring Flow

```
1. MONITORING REQUEST
   GET /admin/memory/info
        ↓
2. MEMORY CONTROLLER
   - MemoryController.getMemoryInfo()
   - Access JVM MemoryMXBean
   - Access GarbageCollectorMXBeans
        ↓
3. COLLECT METRICS
   - Heap memory (used, max, usage %)
   - Non-heap memory (metaspace, code cache)
   - Memory pools (G1 Old Gen, Eden Space, etc.)
   - GC statistics (count, time)
        ↓
4. GENERATE WARNINGS
   - If heap > 90%: CRITICAL warning
   - If heap > 80%: WARNING
   - If heap > 70%: CAUTION
   - If GC time > 50%: High GC pressure
        ↓
5. RESPONSE
   {
     "heap": {
       "usedMB": 512,
       "maxMB": 2048,
       "usagePercent": 25
     },
     "warnings": ["CAUTION: Heap usage above 70%"]
   }
```

---

## Data Flow Architecture

### Rule Storage to Execution Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    RULE AUTHORING                            │
│  Business User writes .drl file                              │
│  Example: pricing/discount/vip.drl                           │
└─────────────────────────────────────────────────────────────┘
                          ↓ Upload to S3
┌─────────────────────────────────────────────────────────────┐
│                    AWS S3 STORAGE                            │
│  s3://bucket-name/pricing/discount/vip.drl                   │
│  - Versioning enabled                                        │
│  - Lifecycle policies                                        │
└─────────────────────────────────────────────────────────────┘
                          ↓ Application startup or refresh
┌─────────────────────────────────────────────────────────────┐
│                    RULE LOADING                              │
│  1. S3RuleStorage.getAllRuleIds()                            │
│  2. Fetch each rule content (via RedisCachedRuleStorage if   │
│     REDIS_ENABLED=true — read-through cache + populate)      │
└─────────────────────────────────────────────────────────────┘
                          ↓ Compilation
┌─────────────────────────────────────────────────────────────┐
│                    RULE COMPILATION                          │
│  1. RuleCompiler receives .drl content                       │
│  2. Create KieFileSystem                                     │
│  3. Build KieContainer                                       │
│  4. Extract KieBase                                          │
└─────────────────────────────────────────────────────────────┘
                          ↓ Caching
┌─────────────────────────────────────────────────────────────┐
│                    COMPILED CACHE                            │
│  Map<String, KieBase> compiledRules                          │
│  - In-memory only                                            │
│  - Fast execution (1-10ms)                                   │
└─────────────────────────────────────────────────────────────┘
                          ↓ Request arrives
┌─────────────────────────────────────────────────────────────┐
│                    RULE EXECUTION                            │
│  1. Create KieSession from KieBase                           │
│  2. Insert request data                                      │
│  3. Fire rules                                               │
│  4. Extract results                                          │
│  5. Dispose session                                          │
└─────────────────────────────────────────────────────────────┘
                          ↓ Response
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT RESPONSE                           │
│  JSON with results and execution metadata                    │
└─────────────────────────────────────────────────────────────┘
```

---

## Storage Architecture

### S3 Storage Design

#### Bucket Structure

```
rule-bucket-name/
├── pricing/
│   ├── discount/
│   │   ├── vip.drl
│   │   ├── seasonal.drl
│   │   └── bulk.drl
│   └── surcharge/
│       ├── rush.drl
│       └── weekend.drl
├── validation/
│   ├── credit-limit.drl
│   └── fraud-detection.drl
└── inventory/
    ├── stock-check.drl
    └── reorder.drl
```

#### S3 Configuration

**Production**:
- **Versioning**: Enabled for rule history and rollback
- **Lifecycle Policies**: Archive old versions to Glacier after 90 days
- **Encryption**: Server-side encryption (SSE-S3 or SSE-KMS)
- **Access Control**: IAM role with least-privilege permissions

**Development (LocalStack)**:
- **Endpoint**: http://localhost:4566
- **Bucket**: local-rules
- **No versioning**: Simpler for testing
- **No encryption**: Faster for local dev

#### S3 Client Architecture

```
┌───────────────────────────────────────────────────┐
│          S3AsyncClient (AWS SDK v2)               │
│  - Async I/O (non-blocking)                       │
│  - Connection pooling (50 connections)            │
│  - HTTP/2 multiplexing                            │
└───────────────────────────────────────────────────┘
                    ↓
┌───────────────────────────────────────────────────┐
│        Apache HTTP Client (Connection Pool)       │
│  - Max connections: 50                            │
│  - Connection timeout: 10s                        │
│  - Socket timeout: 30s                            │
└───────────────────────────────────────────────────┘
                    ↓
┌───────────────────────────────────────────────────┐
│          Circuit Breaker (Resilience4j)           │
│  - Failure threshold: 50%                         │
│  - Wait duration: 60s                             │
│  - Sliding window: 10 requests                    │
└───────────────────────────────────────────────────┘
```

---

## Caching Architecture

### Single-Tier Shared Cache + Pub/Sub Fan-Out

The 2026-05-20 refactor (ADR-016) collapsed the legacy L1+L2 model into a single shared Redis tier wrapped by `RedisCachedRuleStorage`, plus a pub/sub channel for cross-instance refresh coherence.

#### Topology

```
   Instance A          Instance B          Instance C
   ┌─────────┐         ┌─────────┐         ┌─────────┐
   │ kieCont │         │ kieCont │         │ kieCont │  ← execution hot path
   │ (compiled)        │ (compiled)        │ (compiled)   (per-instance, no cache)
   └────┬────┘         └────┬────┘         └────┬────┘
        │ refresh           │ refresh           │ refresh
        ▼                   ▼                   ▼
   ┌──────────────────────────────────────────────────┐
   │  RedisCachedRuleStorage  (decorator)             │
   │  ─ GET / MGET on read, SETEX on miss             │
   │  ─ write-through saveRule / deleteRule           │
   │  ─ Redis op failures → fall through to base      │
   └────────────────────┬─────────────────────────────┘
                        ▼
   ┌──────────────────────────────────────────────────┐
   │       Redis  (shared DRL JSON store)             │
   │       channel: drools:rule:events                │ ←── pub/sub fan-out
   └────────────────────┬─────────────────────────────┘
                        ▼
   ┌──────────────────────────────────────────────────┐
   │       Base RuleStorage (S3 / file / memory)      │
   └──────────────────────────────────────────────────┘
```

A refresh on instance A (`POST /admin/refresh-rules`) (1) deletes A's Redis keys, (2) reloads them from S3 via write-through, (3) atomically swaps A's `kieContainer`, (4) publishes `RULE_REFRESHED_BULK` to `drools:rule:events`. Instances B and C's subscribers receive the event, fetch from `RedisCachedRuleStorage` (which is now a hot cache hit since A warmed it), and atomically swap their own `kieContainer`. Without step (4), B and C would keep executing stale compiled rules.

#### Cache Invalidation Strategies

1. **Manual Invalidation** (`POST /admin/refresh-rules`): clears Redis keys, reloads from base, swaps local `kieContainer`, publishes `RULE_REFRESHED_BULK`.
2. **Single-rule Invalidation** (`POST /admin/refresh-rules/{id}`): deletes one Redis key, reloads from base, swaps in via `loadOrReplaceRule()`, publishes `RULE_REFRESHED`.
3. **TTL Expiration**: `REDIS_DRL_RULES_TTL_MINUTES` (default 15) bounds staleness even if no admin action happens.
4. **Self-dedup**: subscribers compare `source_instance_id == this.instanceId` and skip their own events to prevent loop-back recompilation. Counter: `drools.refresh.skipped_self`.

#### Cache Performance Characteristics

| Layer | Latency | Hit Rate | Scope | Durability |
|---|---|---|---|---|
| Redis (decorator) | 1–5 ms | ~95% on refresh / startup paths | All instances | Volatile + TTL |
| Base storage (S3) | 50–200 ms | ~5% (miss + refresh) | Global | Durable |

**Note**: Caches are NOT in the execution hot path. Once compiled, `kieContainer` serves `POST /execute-rule` directly in ~1–10 ms — no Redis or S3 touch per request. The cache exists only to make refresh and warm-start cheap on the hot S3 path.

---

## Thread Model & Concurrency

### Thread Pool Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    MAIN REQUEST THREADS                      │
│  Tomcat Thread Pool (Spring Boot Embedded)                   │
│  - Max threads: 200 (default)                                │
│  - Min threads: 10                                           │
│  - Queue size: 100                                           │
│  - Handles incoming HTTP requests                            │
└─────────────────────────────────────────────────────────────┘
                          ↓ Delegates to
┌─────────────────────────────────────────────────────────────┐
│               RULE EXECUTION THREAD POOL                     │
│  Custom ThreadPoolExecutor                                   │
│  - Core threads: 10                                          │
│  - Max threads: 50                                           │
│  - Queue size: 100                                           │
│  - Keep-alive: 60s                                           │
│  - Rejection policy: CallerRunsPolicy                        │
│  Purpose: Isolate rule execution from request threads        │
└─────────────────────────────────────────────────────────────┘
                          ↓ Parallel
┌─────────────────────────────────────────────────────────────┐
│               STORAGE THREAD POOL                            │
│  Custom ThreadPoolExecutor                                   │
│  - Core threads: 5                                           │
│  - Max threads: 20                                           │
│  - Queue size: 50                                            │
│  - Keep-alive: 60s                                           │
│  Purpose: Async S3 operations, cache writes                  │
└─────────────────────────────────────────────────────────────┘
```

### Concurrency Model

#### Request Concurrency

```
Request 1 (Thread 1)          Request 2 (Thread 2)
      ↓                              ↓
  executeRule("pricing.vip")    executeRule("pricing.bulk")
      ↓                              ↓
  Get KieBase (shared, immutable)    Get KieBase (shared, immutable)
      ↓                              ↓
  Create KieSession (isolated)   Create KieSession (isolated)
      ↓                              ↓
  Insert data (thread-local)     Insert data (thread-local)
      ↓                              ↓
  Fire rules (isolated)          Fire rules (isolated)
      ↓                              ↓
  Return results                 Return results
```

**Key Points**:
- **KieBase**: Shared, immutable, thread-safe
- **KieSession**: Created per request, stateless, thread-isolated
- **No locking required**: Each request has own session
- **High concurrency**: Limited only by thread pool size

#### Cache Concurrency

**(No in-process LRU as of 2026-05-20 ADR-016.)**

**Redis (via `RedisCachedRuleStorage` decorator, when `REDIS_ENABLED=true`)**:
- Lettuce client with connection pooling (`spring.data.redis.lettuce.pool.*`)
- Redis is single-threaded (serializes operations); client-side multiplexing for concurrency
- All ops wrapped in `redisCircuitBreaker` (including SCAN as of 2026-05-24); failures fall through to base storage

**Compiled `kieContainer` (execution hot path)**:
- Single long-lived `KieContainer`; `loadedRules: ConcurrentHashMap<String, Rule>` for ACTIVE-flag lookup
- Lock-free reads on `loadedRules.get(ruleId)` (TOCTOU-safe atomic get + null check)
- Atomic in-place `KieContainer.updateToVersion(ReleaseId)` on refresh; brief `ReentrantReadWriteLock` write-lock window during the swap only; compile happens outside the lock

### Thread Safety Guarantees

1. **Stateless Design**: No shared mutable state in request processing
2. **Immutable KieBase**: Once compiled, never modified
3. **Session Isolation**: Each request gets own KieSession
4. **Concurrent Collections**: Thread-safe data structures for caches
5. **Atomic Updates**: KieContainer replacement is atomic

### Compile-outside-the-lock Rule Loading Pattern (Drools 10 `updateToVersion`)

A defining choice in this service: **rule compilation happens *outside* the write lock**, then a brief in-place swap via `KieContainer.updateToVersion(ReleaseId)` installs the new `KieBase`. Readers are not blocked during compilation, even though full-set compile can take ~50 s cold-JIT / ~1 s warm-JIT for 1,000 rules.

```
                BEFORE 2026-05-10 (atomic-swap):      AFTER 2026-05-10 (Drools 10 update):
                ─────────────────────────────────     ────────────────────────────────────
                newContainer = compile(rules)         newReleaseId = compile(rules)
                writeLock.lock()                      writeLock.lock()
                  ├── oldContainer = current            ├── oldReleaseId = container.getReleaseId()
                  ├── current = newContainer            ├── container.updateToVersion(newReleaseId)
                  └── oldContainer.dispose()            └── update loadedRules / metadata maps
                writeLock.unlock()                    writeLock.unlock()
                                                      kieRepository.removeKieModule(oldReleaseId)
                Two-container window                  Single long-lived container.
                during the swap.                      KieRepository cleanup is the
                                                      explicit leak-stopper.
```

Two architectural fixes from the load test on 2026-05-10:

1. **`KieRepository.removeKieModule(oldReleaseId)`** is called outside the write lock after a successful `updateToVersion`. Drools 10 does not auto-clean — without this, every refresh accumulates a `KieModule` + `ProjectClassLoader` + every compiled rule class until LRU caps evict (~1000 entries for our single-GA app). Verified leak-free under 98 refreshes during a 15-min soak.
2. **No upfront LOADING-state pre-mark**. An earlier version set every rule's metadata to `LOADING` before the compile, then back to `ACTIVE` after. With 10-rule corpora the LOADING window was sub-millisecond and never observable; at 1,000 rules and a 46-second cold-JIT compile, ~1.5 % of concurrent execute requests hit the LOADING window and got `400 "Rule is not active"` — defeating the goal of non-blocking reads. Removed; rules stay ACTIVE in the OLD KieBase during compile, and metadata is updated under the write lock only after `updateToVersion` succeeds.

Implementation: [`DroolsEngineService.loadRules`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java). See also [ADR-003 2026-05-10 update](36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal) and [39-load-test-findings.md](39-load-test-findings.md).

### TOCTOU-Safe Rule Lookup

Lookup avoids the classic time-of-check-vs-time-of-use race by using an atomic `get() + null check` instead of `containsKey() + get()`:

```java
// In DroolsEngineService.executeRule():
Rule rule = loadedRules.get(ruleId);          // single atomic get
RuleMetadata meta = ruleMetadata.get(ruleId);
if (rule == null || !meta.isActive()) { ... } // null check, no race
```

Even if a concurrent `loadRules()` triggers `updateToVersion` between the check and use, this code is safe: the long-lived `kieContainer` reference is stable, and the in-flight `KieSession` keeps its existing rule definitions until disposal. New sessions created after the swap see the new `KieBase`.

### Component Dependency Map

Internal dependencies. **What breaks if X fails?**

```
        ┌──────────────────────────────────┐
        │  RuleExecutionController         │
        │  AdminController                 │
        │  MemoryController                │
        └────────────┬─────────────────────┘
                     │
            ┌────────┴────────┐
            ▼                 ▼
   ┌─────────────────┐  ┌─────────────────┐
   │ DroolsEngine    │  │ Filter Chain    │
   │   Service       │  │ (4 filters)     │
   └────────┬────────┘  └─────────────────┘
            │
            │
            ▼
  ┌─────────────────────────────────────────────────┐
  │ RuleStorage (interface)                          │
  │   ↑ when REDIS_ENABLED=true, StorageFactory     │
  │   ↑ wraps the base in RedisCachedRuleStorage    │
  │   ↑ (read-through + write-through + CB)         │
  └─────────────────────────┬───────────────────────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
       ┌───────────┐  ┌────────────┐  ┌──────────┐
       │S3RuleStorg│  │LocalFile   │  │InMemory  │
       │ (+ CB)    │  │  Storage   │  │  Storage │
       └─────┬─────┘  └────────────┘  └──────────┘
             │
             ▼
       ┌──────────────┐
       │ AWS S3 / LS  │
       └──────────────┘

  Side channel (when REDIS_ENABLED + REDIS_PUBSUB_ENABLED):
  AdminController ── publishes ──▶ RuleRefreshPublisher ──▶ Redis channel
                                                              │
  Other instances' RuleRefreshSubscriber ◀─── subscribes ─────┘
    → storage.getRule / getAllRules → engine.loadOrReplaceRule / loadRules
```

| Component down | What breaks | What still works |
|---|---|---|
| Redis (when enabled) | Cross-instance fan-out + shared cache | Everything — circuit breaker opens, decorator falls through to base storage; instances refresh independently |
| Redis pub/sub | Cross-instance refresh coherence | Local refresh still works; other instances stay on old compiled rules until their own refresh or TTL expiry |
| AWS S3 | Cold rule loads, refresh | Already-loaded rules (served from `kieContainer`, no cache hit needed) |
| KieContainer module cleanup | Memory leak risk | Logged; `KieRepository.removeKieModule` is best-effort after swap |
| `ADMIN_API_KEY` not set | Admin auth (disabled — warning logged) | Everything else; admin endpoints become open |
| Single thread pool exhausted | New requests queued or rejected (CallerRunsPolicy) | Existing requests; ops endpoints |

---

## Security Architecture

### Multi-Layer Security Model

```
┌─────────────────────────────────────────────────────────────┐
│  LAYER 0: SECURITY HEADERS                                   │
│  SecurityHeadersFilter @Order(-1) — adds 7 headers to every  │
│  response. Source: SecurityHeadersFilter.java:21-27          │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 1: NETWORK                                            │
│  - CORS: empty default; wildcard only in local/dev/docker    │
│    profiles (CorsConfig.java)                                │
│  - HTTPS termination at load balancer                        │
│  - IP whitelisting at infrastructure level                   │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 2: ADMIN AUTHENTICATION                               │
│  AdminAuthFilter @Order(0). Path match: uri.startsWith       │
│  ("/admin/"). Header: X-Admin-API-Key. When ADMIN_API_KEY    │
│  env var is empty/null, **auth is SKIPPED** (warning logged  │
│  at startup). 401 + JSON error on failure.                   │
│  Source: AdminAuthFilter.java:36-71                          │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 3: RATE LIMITING                                      │
│  RateLimitingFilter @Order(1). Per-client (NOT global).      │
│  Defaults: 1000/min, 10000/hr, burst 100, max-clients 10000. │
│  Client identification — multi-tier (priority order):        │
│    1. X-API-Key header     → "api-key:{key}"                 │
│    2. Authorization Bearer → "bearer:{hash}"                 │
│    3. X-Client-Id header   → "client-id:{id}"                │
│    4. request.getRemoteAddr() → "ip:{addr}"  (FALLBACK)      │
│  X-Forwarded-For is **explicitly ignored** (spoofable).      │
│  /admin/* paths are **exempt** from rate limiting.           │
│  Source: RateLimitingFilter.java:65-94                       │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 4: REQUEST VALIDATION                                 │
│  RequestSizeValidationFilter (no @Order — runs last).        │
│  - Default body limit 1 MB; configurable via                 │
│    DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES                  │
│  - Wraps chunked-transfer streams with SizeLimitedInputStream│
│    to prevent bypass via chunked encoding                    │
│  - 30s rule execution timeout; future.cancel(true) on fire   │
│  - maxRuleFirings = 10000 cap (RuleExecutor.java:22)         │
│  Source: RequestSizeValidationFilter.java                    │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 5: INPUT SANITIZATION                                 │
│  - @ValidRuleId — pattern ^[a-zA-Z0-9._-]+$, max 255 chars,  │
│    rejects "..", "/", "\" (RuleIdValidator.java)             │
│  - @ValidRuleData — max 100 fields, string max 10K chars,    │
│    number magnitude max 1B, dangerous-pattern checks         │
│    (RuleDataValidator.java)                                  │
│  - Path traversal protection in S3RuleStorage.java:339-341   │
│    and LocalFileStorage.java:161 (normalize + startsWith)    │
│  - SSRF protection on AWS_ENDPOINT (S3Config.java)           │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 6: DRL SANDBOXING                                     │
│  DrlSanitizer scans every rule before compilation:           │
│  - Allowed imports (20 prefixes): java.util.*, java.math.*,  │
│    java.time.*, java.lang primitives, java.text formatters   │
│  - Blocked imports (19 prefixes): java.io/net/reflect/invoke,│
│    Runtime, ProcessBuilder, ClassLoader, Thread,             │
│    SecurityManager, javax.script/naming, sun.*, com.sun.*    │
│  - Blocked classes (12): Runtime, ProcessBuilder, ClassLoader│
│    Thread, ScriptEngine, MethodHandle, Unsafe, ...           │
│  - Blocked methods (19): System.exit, getenv, setProperty,   │
│    Class.forName, getMethod, getDeclaredField, newInstance...│
│  - eval() blocked via \beval\s*\( pattern                    │
│  - Static imports rejected                                   │
│  Source: DrlSanitizer.java (full)                            │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 7: LOG SANITIZATION                                   │
│  LogSanitizer masks sensitive data before logging:           │
│  - Credit card → ****-****-****-NNNN (last 4 visible)        │
│  - SSN → ***-**-NNNN (last 4 visible)                        │
│  - Email → a***@example.com (first char + domain)            │
│  - Long tokens (20+ chars): redacted unless UUID/class name  │
│  - Recursive nested map sanitization (depth limit 5)         │
│  - Word-boundary regex prevents false positives              │
│    (e.g., "shipping" no longer matched as "pin")             │
│  Source: LogSanitizer.java                                   │
└─────────────────────────────────────────────────────────────┘
```

#### Actual security header values (verified in [SecurityHeadersFilter.java:21-27](../src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java#L21-L27))

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `X-XSS-Protection` | `0` *(modern recommendation; legacy `1; mode=block` is unsafe)* |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Cache-Control` | `no-store` |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |

### Input Validation Architecture

#### Custom Validation Annotations

**@ValidRuleId**:
```java
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = RuleIdValidator.class)
public @interface ValidRuleId {
    String message() default "Invalid rule ID format";
}

// Validates:
// - Not null or empty
// - Contains only: a-z, 0-9, dot, hyphen, underscore
// - Max length: 255 characters
// - Format: "category.subcategory.rule-name"
```

**@ValidRuleData** (note: actual annotation name is `ValidRuleData`, not `ValidData`):
```java
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = RuleDataValidator.class)
public @interface ValidRuleData { ... }

// Validates ([RuleDataValidator.java:32-62](../src/main/java/com/company/drools/api/validation/RuleDataValidator.java#L32-L62)):
// - Not null
// - Max field count (default 100, env: DROOLS_VALIDATION_DATA_MAX_FIELDS)
// - Per-key: not empty, length ≤ 100, no dangerous patterns
// - String values: length ≤ 10K (default), regex ^[^<>"';&|]*$, no dangerous patterns
// - Number values: |value| ≤ 1B (default)
// - Other values: converted to string and re-checked
```

### Rate Limiting Architecture

**Algorithm**: In-memory bucket per client; minute and hour windows.

**Client identification — multi-tier** (priority order, [RateLimitingFilter.java:69-94](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java#L69-L94) `getClientIdentifier()`):

```
Header          Format                       Example
─────────────── ──────────────────────────── ────────────────────
X-API-Key       api-key:{value}              api-key:abc123
Authorization:  bearer:{sha256(token)[0:8]}  bearer:9f4a2b71
  Bearer
X-Client-Id     client-id:{value}            client-id:partner-A
(none)          ip:{getRemoteAddr()}         ip:203.0.113.42
                                             ↑ FALLBACK only
```

`X-Forwarded-For` is **explicitly ignored** because it is spoofable from the client side. If the service runs behind a trusted reverse proxy, the proxy must inject `X-API-Key` or `X-Client-Id` based on validated identity.

**Defaults** (`application.yml`, overridable per profile):
- `DROOLS_RATE_LIMITING_ENABLED=true`
- `DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=1000`
- `DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR=10000`
- `DROOLS_RATE_LIMITING_BURST_SIZE=100`
- `DROOLS_RATE_LIMITING_MAX_CLIENTS=10000` (memory protection — when full, new clients use a shared bucket)
- `DROOLS_RATE_LIMITING_CLEANUP_INTERVAL=5` (minutes)

**Admin exemption**: `/admin/*` paths bypass rate limiting entirely — verified by [RateLimitingFilterTest:120-127](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java#L120-L127).

**Response on limit hit**: HTTP 429 with `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `X-RateLimit-Reset-After` headers and JSON body containing the wait time.

---

## Performance Architecture

### Performance Optimization Strategies

#### 1. Connection Pooling

**S3 Client Pool**:
```
Max Connections: 50
Connection Timeout: 10s
Socket Timeout: 30s

Benefits:
- Reuse TCP connections
- Reduce connection overhead
- Support high concurrency
```

**Redis Client Pool**:
```
Max Connections: 20
Min Idle: 5
Max Idle: 10

Benefits:
- Persistent connections
- Reduced latency
- Connection reuse
```

#### 2. Thread Pool Tuning

**Rule Execution Pool**:
```
Core Threads: 10
Max Threads: 50
Queue Size: 100
Keep-Alive: 60s

Rationale:
- Core threads handle baseline load
- Max threads handle spikes
- Queue prevents thread exhaustion
- Keep-alive reduces resource waste
```

#### 3. JVM Tuning

**Garbage Collector**: G1GC (Garbage-First)
```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UseStringDeduplication
-XX:G1HeapRegionSize=16m

Benefits:
- Low-latency GC pauses (<200ms)
- Predictable pause times
- Efficient large heap handling (2GB+)
- String deduplication saves memory
```

**Heap Configuration**:
```
-Xms512m          # Initial heap
-Xmx2048m         # Max heap
-XX:MaxRAMPercentage=75.0  # Container-aware

Rationale:
- Large enough for compiled rules
- Small enough to avoid long GC pauses
- 75% of container memory for safety margin
```

#### 4. Circuit Breaker Pattern

**S3 Circuit Breaker**:
```
Failure Threshold: 50%
Wait Duration: 60s
Sliding Window: 10 requests

States:
- CLOSED: Normal operation
- OPEN: All requests fail fast (no S3 calls)
- HALF_OPEN: Test if S3 recovered

Benefits:
- Prevent cascading failures
- Fast failure when S3 unavailable
- Automatic recovery detection
```

### Performance Targets vs Actual

| Metric | Target | Actual (Measured) |
|--------|--------|-------------------|
| RPS | 100-1000 | Sustained 518 RPS @ 1000 rules in 2026-05-10 single-container baseline (157,754 reqs, 0 errors); see [39-load-test-findings.md](39-load-test-findings.md) |
| P99 Latency (cached) | < 100ms | **9ms** @ 50 RPS / 1000 rules (2026-05-10 baseline) |
| P99 Latency (miss) | < 500ms | ~100-300ms (expected); not separately measured |
| Concurrent Rules | 1000+ | Architecture supports; 1000-rule load test verified |
| Cache Hit Rate | > 90% | depends on REDIS_ENABLED; tracked via `drools.cache.hit{layer=redis}` / `drools.cache.miss{layer=redis}` Micrometer counters (no L1+L2 stack — single Redis tier as of 2026-05-20 ADR-016) |
| Memory Stability | Indefinite | ✅ Stable (1 MB drift over 98 refreshes / 15-min mixed-workload soak; `KieRepository.removeKieModule` cleanup verified leak-free) |

---

## Monitoring Architecture

### Observability Stack

```
┌─────────────────────────────────────────────────────────────┐
│                    APPLICATION LAYER                         │
│  - Micrometer metrics                                        │
│  - Structured JSON logging                                   │
│  - MDC correlation IDs                                       │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    METRICS COLLECTION                        │
│  Micrometer → Prometheus/CloudWatch/Datadog                  │
│  - Vendor-agnostic abstraction                               │
│  - Multiple backend support                                  │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    VISUALIZATION                             │
│  Grafana / CloudWatch Dashboards                             │
│  - Rule execution metrics                                    │
│  - Cache hit rates                                           │
│  - Thread pool stats                                         │
│  - Memory metrics                                            │
└─────────────────────────────────────────────────────────────┘
```

### Metrics Architecture

#### Custom Metrics

**Rule Execution Metrics** (verified from code):
- `drools.rule.execution.time` (Timer, tagged `rule_id`): Per-rule execution latency. Unknown rule IDs tagged as `unknown` to prevent cardinality explosion.
- `drools.rule.execution.success` (Counter, tagged `rule_id`): Successful executions
- `drools.rule.execution.error` (Counter, tagged `rule_id`, `error_type`): Failed executions
- `drools.rules.loaded` (Gauge): Currently loaded rule count

**API Metrics**:
- `drools.api.requests` (Counter, tagged `endpoint`): Per-endpoint request count
- `drools.api.errors` (Counter, tagged `endpoint`, `error_type`): Per-endpoint error count
- `drools.api.response.time` (Timer, tagged `endpoint`, `status`): Per-endpoint latency

**Cache Metrics** (post-2026-05-20; the legacy LRU `evictions` / `size` gauges were removed with `LocalLRUCache`):
- `drools.cache.hit` (Counter, tagged `layer=redis`): Redis cache hits
- `drools.cache.miss` (Counter, tagged `layer=redis`): Redis cache misses
- `drools.cache.bulk.hit` / `drools.cache.bulk.miss` (Counters): bulk SCAN+MGET hit/miss counts
- `drools.cache.read.duration`, `drools.cache.write.duration` (Timers, tagged `layer=redis,result=hit|miss|cb_open`)
- `drools.cache.invalidation` (Counter, tagged `scope=bulk|single`)
- `drools.refresh.published`, `drools.refresh.received` (Counters, tagged `event`): pub/sub fan-out
- `drools.refresh.skipped_self` (Counter): self-dedup count
- `drools.refresh.processing.duration` (Timer, tagged `event`): subscriber-side handler latency
- `drools.refresh.failed` (Counter, tagged `layer=publisher|subscriber`)

**Storage Metrics**:
- `drools.storage.operation.time` (Timer): Storage operation latency (S3 / file / memory)

**Thread pool metrics**: Spring Boot auto-instruments executors via Micrometer. Look at metric names starting with `executor.` (e.g., `executor.active`, `executor.queued`, `executor.completed`) — see `/actuator/metrics`.

**Resilience4j metrics** (from `TaggedCircuitBreakerMetrics`):
- `resilience4j.circuitbreaker.state` (Gauge per state, tagged `name=s3`/`name=redis`)
- `resilience4j.circuitbreaker.calls` (Counter, tagged `name`, `kind=successful`/`failed`/`not_permitted`/`ignored`)
- `resilience4j.circuitbreaker.failure.rate`, `slow.call.rate`, `buffered.calls` (Gauges)

#### Health Checks

**Component Health**:
```
GET /admin/health

Response:
{
  "status": "UP",
  "components": {
    "drools": {
      "status": "UP",
      "details": {
        "loadedRules": 47,
        "activeRules": 47
      }
    },
    "s3": {
      "status": "UP",
      "details": {
        "circuitBreakerState": "CLOSED"
      }
    },
    "redis": {
      "status": "UP",  // or DOWN if unavailable
      "details": {
        "enabled": true
      }
    }
  }
}
```

### Logging Architecture

**Structured JSON Logging**:
```json
{
  "timestamp": "2026-02-19T10:30:45.123Z",
  "level": "INFO",
  "thread": "http-nio-8080-exec-1",
  "logger": "DroolsEngineService",
  "message": "Executed rule pricing.discount.vip",
  "correlationId": "abc123-def456-ghi789",
  "ruleId": "pricing.discount.vip",
  "executionTimeMs": 5,
  "success": true
}
```

**Correlation IDs**:
- Generated per request
- Propagated through MDC (Mapped Diagnostic Context)
- Included in all logs for request tracing
- Useful for distributed tracing

---

## Deployment Architecture

### Containerization Architecture

#### Multi-Stage Docker Build

```
┌─────────────────────────────────────────────────────────────┐
│                    STAGE 1: BUILD                            │
│  Base: maven:3.9-eclipse-temurin-25                          │
│  - Copy pom.xml and source code                              │
│  - Run: mvn clean package -DskipTests                        │
│  - Output: target/drools-rule-engine.jar (~50MB)             │
└─────────────────────────────────────────────────────────────┘
                          ↓ Copy JAR only
┌─────────────────────────────────────────────────────────────┐
│                    STAGE 2: RUNTIME                          │
│  Base: amazoncorretto:25-alpine (~180MB)                     │
│  - Create non-root user (drools:1000)                        │
│  - Copy JAR from build stage                                 │
│  - Expose ports 8080, 8081                                   │
│  - Health check: curl /admin/health                          │
│  - Entrypoint: java -jar app.jar                             │
│  Final Size: ~347MB                                          │
└─────────────────────────────────────────────────────────────┘
```

**Security Hardening**:
- Non-root user execution (UID 1000)
- Minimal Alpine base image
- No shell in container (security)
- Read-only file system (except /tmp)

#### Docker Compose Stack (Development)

```
┌─────────────────────────────────────────────────────────────┐
│                    LOCALSTACK (S3 Emulation)                 │
│  Image: localstack/localstack:latest                         │
│  Ports: 4566 (AWS API)                                       │
│  Services: S3                                                │
│  Volumes: ./localstack-data                                  │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    REDIS (Optional Cache)                    │
│  Image: redis:7-alpine                                       │
│  Port: 6379                                                  │
│  Volume: ./redis-data                                        │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    APPLICATION                               │
│  Build: ./Dockerfile                                         │
│  Ports: 8080 (API + Admin), 8081 (Actuator)                   │
│  Volumes: ./heap-dumps, ./gc-logs                            │
│  Environment: Docker profile, AWS endpoint override          │
└─────────────────────────────────────────────────────────────┘
```

### AWS Deployment Architecture (Production)

```
┌─────────────────────────────────────────────────────────────┐
│                    INTERNET                                  │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│              APPLICATION LOAD BALANCER (ALB)                 │
│  - HTTPS termination                                         │
│  - Health checks: /admin/health                              │
│  - Sticky sessions: Disabled (stateless)                     │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│              ECS FARGATE CLUSTER                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │ Task 1      │  │ Task 2      │  │ Task 3      │          │
│  │ CPU: 2 vCPU │  │ CPU: 2 vCPU │  │ CPU: 2 vCPU │          │
│  │ Mem: 4GB    │  │ Mem: 4GB    │  │ Mem: 4GB    │          │
│  └─────────────┘  └─────────────┘  └─────────────┘          │
│  Auto-scaling: 2-10 tasks based on CPU/RPS                   │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    AWS S3 (Rule Storage)                     │
│  Bucket: production-rules-bucket                             │
│  - Versioning enabled                                        │
│  - Encryption: SSE-S3                                        │
│  - Lifecycle: Archive to Glacier after 90 days               │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    ELASTICACHE REDIS                         │
│  Instance: cache.r6g.large                                   │
│  - Multi-AZ replication                                      │
│  - Automatic failover                                        │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    CLOUDWATCH                                │
│  - Application logs                                          │
│  - Metrics and alarms                                        │
│  - Dashboards                                                │
└─────────────────────────────────────────────────────────────┘
```

**Auto-Scaling Configuration**:
- Min tasks: 2 (high availability)
- Max tasks: 10 (cost control)
- Target CPU: 70%
- Target RPS: 500 per task
- Scale-out cooldown: 60s
- Scale-in cooldown: 300s (prevent flapping)

---

## Technology Stack

### Core Technologies

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Runtime** | Java | 25 LTS | Application runtime (enforced) |
| **Framework** | Spring Boot | 3.5.3 | Application framework |
| **Rule Engine** | Drools | 10.2.0 | Business rule execution |
| **Build Tool** | Maven | 3.9+ | Dependency management, build |
| **Container** | Docker | 24.0+ | Containerization |
| **Orchestration** | Docker Compose | 2.0+ | Local dev environment |

### Infrastructure Technologies

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Storage** | AWS S3 | - | Rule file storage |
| **Cache** | Redis | 7.x | Distributed caching (optional) |
| **Metrics** | Micrometer | 1.12+ | Vendor-agnostic metrics |
| **Monitoring** | Prometheus | 2.x | Metrics collection |
| **Visualization** | Grafana | 10.x | Dashboards |
| **Logging** | Logback | 1.4+ | Structured JSON logging |
| **Cloud** | AWS ECS Fargate | - | Container orchestration |

### Libraries & Frameworks

| Component | Library | Purpose |
|-----------|---------|---------|
| **Resilience** | Resilience4j | Circuit breakers, retry, rate limiting |
| **AWS SDK** | AWS SDK v2 | S3 client, async operations |
| **Redis Client** | Lettuce | Async Redis client (Spring Data Redis) |
| **Validation** | Jakarta Validation | Input validation framework |
| **Testing** | JUnit 5 | 548 unit tests + 14 Testcontainers integration tests |
| **Mocking** | Mockito | Used throughout `*Test.java` |
| **Performance** | JMeter | Load test orchestrator at `scripts/run-load-test.sh` (Phases 0–9 — see [39-load-test-findings.md](39-load-test-findings.md)) |

---

## Design Patterns & Principles

### Architectural Patterns

#### 1. Layered Architecture
- **API Layer**: Request handling, validation, response formatting
- **Service Layer**: Business logic, rule execution
- **Data Layer**: Storage, caching
- **Separation of Concerns**: Each layer has single responsibility

#### 2. Dependency Injection (Spring Framework)
- Constructor injection (immutable dependencies)
- Interface-based abstractions (testability)
- Configuration via Java classes (type-safe)

#### 3. Strategy Pattern (Storage Abstraction)
```java
interface RuleStorage {
    Optional<Rule> getRule(String ruleId);
    List<Rule> getAllRules();
    // ... 7 more methods (see RuleStorage.java)
}

class S3RuleStorage         implements RuleStorage { }
class LocalFileStorage      implements RuleStorage { }
class InMemoryRuleStorage   implements RuleStorage { }
class RedisCachedRuleStorage implements RuleStorage { }  // decorator (opt-in via REDIS_ENABLED)

// Selected at runtime by StorageFactory based on RULE_SOURCE;
// wrapped in RedisCachedRuleStorage when REDIS_ENABLED=true.
```

#### 4. Circuit Breaker Pattern
- Prevent cascading failures
- Fail-fast when external service down
- Automatic recovery detection

#### 5. Repository Pattern (Storage Layer)
- Abstract data access
- Decouple business logic from storage
- Testable (mock storage in tests)

#### 6. Factory Pattern (Rule Compilation)
- `RuleCompiler.compile()` creates KieContainer
- Encapsulates complex Drools API
- Single point for compilation logic

### Design Principles

#### SOLID Principles

**Single Responsibility**:
- `DroolsEngineService`: Rule execution only
- `RuleCompiler`: Compilation only
- `S3RuleStorage`: S3 access only

**Open/Closed**:
- `RuleStorage` interface open for extension (new implementations)
- Closed for modification (existing code unchanged)

**Liskov Substitution**:
- `S3RuleStorage`, `LocalFileStorage`, `InMemoryRuleStorage`, and the `RedisCachedRuleStorage` decorator are all interchangeable via the `RuleStorage` interface
- No behavioral surprises when swapping implementations

**Interface Segregation**:
- Small, focused interfaces (no bloated interfaces)
- `RuleStorage` has only essential methods

**Dependency Inversion**:
- High-level `DroolsEngineService` depends on `RuleStorage` abstraction
- Not concrete `S3RuleStorage` implementation

#### 12-Factor App Compliance

1. **Codebase**: Single Git repository
2. **Dependencies**: Maven manages all dependencies
3. **Config**: Environment variables (no hardcoded config)
4. **Backing Services**: S3, Redis as attached resources
5. **Build/Release/Run**: Maven build, Docker image, container run
6. **Processes**: Stateless (no session state on disk)
7. **Port Binding**: Self-contained (embedded Tomcat)
8. **Concurrency**: Horizontal scaling via multiple containers
9. **Disposability**: Fast startup (<30s), graceful shutdown
10. **Dev/Prod Parity**: Same Docker image, different env vars
11. **Logs**: Stdout/stderr, structured JSON
12. **Admin Processes**: Admin API endpoints for management tasks

---

## Summary

This Drools Rule Engine Microservice is architected for:

✅ **High Performance**: Redis-decorator caching (opt-in), connection pooling, thread pool optimization
✅ **Scalability**: Stateless design, horizontal scaling, shared caching
✅ **Reliability**: Circuit breakers, health checks, graceful degradation
✅ **Maintainability**: Clean architecture, separation of concerns, comprehensive monitoring
✅ **Security**: Admin auth, DRL sandboxing, security headers, multi-layer validation, rate limiting, log sanitization
✅ **Memory Stability**: Proper resource disposal (KieContainer lifecycle)

**Key Architectural Achievements**:
- **Zero Memory Leaks**: Fixed via KieContainer disposal (2026-02-19)
- **Redis-decorator caching** when `REDIS_ENABLED=true` (single shared tier per ADR-016; hit rate tracked via Micrometer)
- **Sub-100ms Latency**: Compiled rule caching, optimized execution
- **Cloud-Native**: 12-factor compliant, containerized, AWS-ready
- **Production-Grade**: Circuit breakers, health checks, structured logging

**Current Architecture Status**: Production-ready. Stack modernized 2026-05-09 (Java 25 / Spring Boot 3.5.3 / Drools 10.2.0); Drools 10 rule-loading rework + 1000-rule load test 2026-05-10; Sonar quality gates cleared 2026-05-11; Redis cache + pub/sub layer 2026-05-20; Phase 9 load-test harness + Phase 9.4 hardening 2026-05-24.

---

**Last Updated**: 2026-05-24
**Architecture Version**: 1.2.0
**Related Docs**: deployment.md, configuration.md, memory-monitoring-guide.md
