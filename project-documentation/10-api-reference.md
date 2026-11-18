# 10 · API Reference

| | |
|---|---|
| **Audience** | Partners, developers, AI agents |
| **Purpose** | Complete prose reference for every HTTP endpoint, with request/response shapes and live curl examples |
| **Last verified against** | Controller files in [`src/main/java/com/company/drools/api/controller/`](../src/main/java/com/company/drools/api/controller/), [`api-reference/openapi.yml`](api-reference/openapi.yml) on 2026-11-18 |
| **Related docs** | [11-integration-guide.md](11-integration-guide.md), [12-error-code-catalog.md](12-error-code-catalog.md), [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md), [15-admin-authentication.md](15-admin-authentication.md), [api-reference/openapi.yml](api-reference/openapi.yml) |

---

## Servers and ports

The service exposes **two ports** with **three logical groups** of endpoints:

| Port | Path prefix | Audience | Auth |
|---:|---|---|---|
| 8080 | `/execute-rule` | All clients (the business API) | None |
| 8080 | `/admin/*` | Operators, ops automation | `X-Admin-API-Key` if `ADMIN_API_KEY` set (required in `prod`/`docker` — app fails to start without it) |
| 8081 | `/actuator/*` | Monitoring, infra | None (typically firewalled to internal) |

**Note**: `/admin/*` is on the **main port** (8080), not the management port (8081). This is unusual but intentional — see [04-architecture.md](04-architecture.md) for rationale. The management port (8081) only hosts Spring Boot Actuator endpoints.

In production, you would expose 8080 to clients/operators (typically with auth at a gateway) and keep 8081 internal.

---

## Authentication

| Endpoint family | Auth required? | How |
|---|---|---|
| `POST /execute-rule` | None on the service itself | Authentication is your gateway's responsibility. The service uses rate limiting (keyed on source IP only) for resource protection. |
| `GET/POST /admin/*` | API key when `ADMIN_API_KEY` is set | Send `X-Admin-API-Key: <value>` header. When `ADMIN_API_KEY` is empty/unset: `local`/`dev` leave admin endpoints open with a startup WARN, but `prod`/`docker` **fail to start**. See [15-admin-authentication.md](15-admin-authentication.md). |
| `GET /actuator/*` | None | Bind to internal-only network. Don't expose externally. |

---

## Common request/response patterns

### Success response shape (rule execution)

```json
{
  "rule_id": "pricing.discount.simple",
  "result": {
    "amount": 90.0,
    "discount": 10.0,
    "discountPercent": 10,
    "discountReason": "Order over $50 discount"
  },
  "error": null,
  "execution_time_ms": 5
}
```

### Error response shape (all error paths)

```json
{
  "rule_id": "<rule_id or null>",
  "result": null,
  "error": {
    "code": "<ERROR_CODE>",
    "message": "<human-readable>",
    "details": "<optional supplementary text>",
    "timestamp": "<ISO-8601>"
  }
}
```

See [12-error-code-catalog.md](12-error-code-catalog.md) for every error code.

### Rate limit headers (all `/execute-rule` responses)

| Header | Example | Meaning |
|---|---|---|
| `X-RateLimit-Limit` | `1000` | Per-minute limit for this client |
| `X-RateLimit-Remaining` | `999` | Remaining in current window |
| `X-RateLimit-Reset` | `1715173025` | Unix epoch (seconds) when window resets |
| `X-RateLimit-Reset-After` | `60` | Seconds until reset |

See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) for client-identification details.

### Security headers (all responses)

7 headers added by [`SecurityHeadersFilter`](../src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java) — `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `Cache-Control`, `Content-Security-Policy`, `Strict-Transport-Security`. Full values: see [14-security-architecture.md](14-security-architecture.md).

---

## Endpoint reference

### `POST /execute-rule` — Execute a business rule

The single business API. Submits data to a named rule and returns the modified data.

**Request**

```http
POST /execute-rule HTTP/1.1
Content-Type: application/json

{
  "rule_id": "pricing.discount.simple",
  "data": {
    "amount": 100
  }
}
```

| Body field | Type | Required | Constraints |
|---|---|---|---|
| `rule_id` | string | yes | `^[a-zA-Z0-9._-]+$`, max 255 chars (configurable). No path-traversal patterns. |
| `data` | object | yes | Max 100 top-level keys. String values max 10K chars. Number values max 1B (absolute). Configurable. |

> **Field name is `rule_id` (snake_case)**, not `ruleId`. The Java DTO uses `@JsonProperty("rule_id")`.

**Response (200 OK)**

```json
{
  "rule_id": "pricing.discount.simple",
  "result": {
    "amount": 90.0,
    "discount": 10.0,
    "discountPercent": 10,
    "discountReason": "Order over $50 discount"
  },
  "error": null,
  "execution_time_ms": 5
}
```

`result` is the modified `data` map after rules fire. Only the rules in the requested `rule_id`'s DRL file run; other loaded rules never fire, even if their conditions match ([ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)). `execution_time_ms` is the elapsed time from `KieSession.fireAllRules()` start to finish.

**Error responses**:

| Status | Code | Cause |
|---:|---|---|
| 400 | `INVALID_INPUT` | `rule_id` or `data` failed validation |
| 400 | `RULE_EXECUTION_ERROR` | Rule threw at runtime, or the rule ID is known but has no compiled version (`ERROR`: its first compile failed) |
| 404 | `RULE_NOT_FOUND` | `rule_id` is not loaded on this instance |
| 408 | `TIMEOUT_ERROR` | Execution took longer than `DROOLS_RULE_EXECUTION_TIMEOUT` (30s default; 25s on `docker`) |
| 413 | `REQUEST_TOO_LARGE` | Body exceeded `DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES` (1 MiB default) |
| 422 | `RULE_LIMIT_EXCEEDED` | The rules fired more than `DROOLS_MAX_RULE_FIRINGS` (10,000 default) times for this request, usually a rule loop. No result is returned (`result: null`). |
| 429 | `RATE_LIMIT_EXCEEDED` | Per-source-IP rate limit hit |
| 503 | `SERVICE_UNAVAILABLE` | Circuit breaker open (S3 or Redis), OR rule-execution thread pool saturated |

**Live curl**

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq
```

Expected:
```json
{
  "rule_id": "pricing.discount.simple",
  "result": {
    "amount": 90.0,
    "discount": 10.0,
    "discountPercent": 10,
    "discountReason": "Order over $50 discount"
  },
  "error": null,
  "execution_time_ms": 5
}
```

**Source**: [`RuleExecutionController.java`](../src/main/java/com/company/drools/api/controller/RuleExecutionController.java).

---

### `GET /admin/health` — Component health

Aggregates health of the service's internal components (Drools, storage, cache, Redis, circuit breakers). Different from the simpler `/actuator/health` which Spring Boot manages.

**Request**

```bash
curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/health
```

**Response (200 OK)**

```json
{
  "status": "UP",
  "timestamp": "2026-11-18T08:57:10.549046921Z",
  "components": {
    "drools": {
      "status": "UP",
      "details": {
        "phase": "LOADED",
        "loaded_rules": 17,
        "rules_with_errors": 0,
        "last_refresh_at": "2026-11-18T08:56:40.112Z",
        "last_refresh_source": "startup",
        "last_refresh_result": "ok",
        "active_rules": 17,
        "cache_hit_rate": 0.0
      }
    },
    "storage": {
      "status": "UP",
      "details": {
        "type": "RedisCachedRuleStorage",
        "rule_source": "s3",
        "s3_bucket": "local-rules",
        "s3_accessible": true,
        "total_rules": 17
      }
    },
    "cache": {
      "status": "UP",
      "details": {
        "mode": "redis",
        "enabled": true,
        "statistics": {
          "hits": 0,
          "misses": 0,
          "hit_rate": "0.00%"
        }
      }
    },
    "redis": {
      "status": "UP",
      "details": { "connected": true, "cache_decorator": "RedisCachedRuleStorage" }
    },
    "circuit-breakers": {
      "status": "UP",
      "details": {
        "circuit_breakers_enabled": true,
        "s3_state": "CLOSED",
        "s3_metrics": {
          "successful_calls": 1,
          "failed_calls": 0,
          "not_permitted_calls": 0,
          "failure_rate": -1.0
        },
        "redis_state": "CLOSED",
        "redis_metrics": { ... }
      }
    }
  }
}
```

**Status semantics** (2026-11-18):

| Overall `status` | HTTP | When |
|---|---:|---|
| `UP` | 200 | Rules serve and storage is reachable |
| `DEGRADED` | 200 | Rules serve, but storage is unreachable. Compiled rules keep serving; refreshes fail until storage is back. |
| `DOWN` | **503** | This instance can't serve rules: the startup load hasn't succeeded yet (still loading, or storage was unreadable), or 0 rules are compiled (unless `DROOLS_REFRESH_ALLOW_EMPTY=true`) |

- `drools` uses the same logic as the actuator `rules` readiness indicator ([`RulesHealthIndicator`](../src/main/java/com/company/drools/config/RulesHealthIndicator.java)). When DOWN, its details include `reason` and `error`.
- `drools.details.phase`: `NOT_LOADED` (startup load not finished), `LOADED` (a full load has succeeded), `FAILED` (the startup load failed and no full load has succeeded since).
- Rule IDs that failed to compile do **not** make the instance DOWN. They are counted in `rules_with_errors` and listed (first 20) in `error_rule_ids`. `last_error` holds the last refresh error, if any.
- `cache`, `redis` and `circuit-breakers` going DOWN never change the overall status.
- The Docker `HEALTHCHECK` and the compose healthcheck call this endpoint, so a container with no rules is reported unhealthy.
- `cache.statistics.hit_rate` is a string (formatted percentage); `drools.cache_hit_rate` is a float.

**Source**: [`AdminController.java:113-184`](../src/main/java/com/company/drools/api/controller/AdminController.java#L113-L184).

---

### `GET /admin/info` — Application info

Static metadata about the running service.

```bash
curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/info | jq
```

**Response**:
```json
{
  "application": "drools-rule-engine",
  "version": "1.0.0",
  "java_version": "25.0.1",
  "timestamp": "2026-05-10T08:57:10Z"
}
```

**Source**: [`AdminController.java:358-367`](../src/main/java/com/company/drools/api/controller/AdminController.java#L358-L367).

---

### `GET /admin/rules` — List loaded rules

Returns metadata for every rule currently in memory.

```bash
curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/rules | jq
```

**Response** (verified against running stack 2026-05-10):
```json
{
  "total_rules": 17,
  "rules": [
    {
      "rule_id": "pricing.discount.simple",
      "status": "ACTIVE",
      "loaded_at": "2026-05-10T11:58:40.752099511Z",
      "last_modified": null,
      "execution_count": 0,
      "avg_execution_time_ms": 0.0,
      "version": "1.0"
    },
    ...
  ]
}
```

| Field | Notes |
|---|---|
| `status` | Enum: `ACTIVE`, `INACTIVE`, `ERROR`. |
| `loaded_at` | ISO-8601 timestamp when this rule was last compiled and installed (its own KieBase). |
| `last_modified` | ISO-8601 timestamp of the DRL in storage (S3 `LastModified` or file mtime), recorded when the rule compiles. `null` when the storage doesn't provide one (the in-memory `local` source). |
| `execution_count` | Increments per `/execute-rule` call. Starts at 0 for unused rules. |
| `avg_execution_time_ms` | Tracked in [`RuleMetadata`](../src/main/java/com/company/drools/core/model/RuleMetadata.java) using **incremental averaging** (Welford's method) to avoid numeric overflow. `0.0` for unused rules. |
| `version` | Currently always `"1.0"`. A rule's content identity is its SHA-256 digest, shown (first 12 hex chars) in `AUDIT` log lines. |

> The `cached` boolean was removed on 2026-05-20 when the dead `LocalLRUCache` layer was deleted. Cache state now lives in Redis (when `REDIS_ENABLED=true`) and is observable via `drools.cache.hit` / `drools.cache.miss` counters in `/admin/health` and `/actuator/metrics`. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20).

**Source**: [`AdminController.java:530-569`](../src/main/java/com/company/drools/api/controller/AdminController.java#L530-L569).

---

### `POST /admin/refresh-rules` — Reload all rules

Pipeline ([`RuleRefreshService`](../src/main/java/com/company/drools/core/engine/RuleRefreshService.java)). The storage read and the apply run under one lock, so the newest read is always the last one installed.

1. When `REDIS_ENABLED=true`, invalidate the Redis DRL cache.
2. Read the **complete** rule set from storage. A failed or incomplete read (an S3 object or listing error, a missing rules directory, an open circuit breaker) changes nothing → **500 `failed`**.
3. An empty read while rules are loaded is refused → **409 `rejected_empty`** (set `DROOLS_REFRESH_ALLOW_EMPTY=true` to allow it).
4. Otherwise, rule IDs no longer in storage are removed, and each other rule ID compiles into its own `KieBase` ([ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)). A rule whose DRL is byte-identical to the serving version (same SHA-256) is not recompiled. A rule ID that fails to compile keeps its previous version (or is recorded as `ERROR`); other IDs are unaffected.
5. When `REDIS_PUBSUB_ENABLED=true`, publish `RULE_REFRESHED_BULK` on `drools:rule:events` so sibling instances refresh too. This happens after every complete read, even if nothing changed here. Nothing is published after a 500 or 409.

In-flight `/execute-rule` requests finish on the handle they took. See [04-architecture.md](04-architecture.md), [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20), and [39-load-test-findings.md](39-load-test-findings.md).

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/refresh-rules | jq
```

| HTTP | `status` | Meaning |
|---:|---|---|
| 200 | `completed` | Every rule ID compiled (or was unchanged) |
| 200 | `completed_with_errors` | Some rule IDs failed to compile; they are listed in `errors` and keep their previous version, if any |
| 500 | `failed` | Storage couldn't be read completely; nothing changed |
| 409 | `rejected_empty` | Storage returned 0 rules while rules are loaded; nothing changed |

| Field | Meaning |
|---|---|
| `rules_loaded` | Rule IDs serving from this snapshot: newly compiled **plus** unchanged |
| `rules_unchanged` | Of `rules_loaded`, how many had identical DRL and were not recompiled |
| `rules_removed` | Rule IDs no longer in storage, removed from the engine |
| `rules_failed` | Rule IDs that failed to compile |
| `errors` | One entry per failed rule ID; for 500/409, a single `SYSTEM` entry with the reason |

**Response (200, nothing changed in storage)**:
```json
{
  "status": "completed",
  "rules_loaded": 17,
  "rules_failed": 0,
  "rules_unchanged": 17,
  "rules_removed": 0,
  "duration_ms": 41,
  "errors": [],
  "cache_updated_at": "2026-11-18T08:57:10Z",
  "timestamp": "2026-11-18T08:57:10Z"
}
```

**Response (200, one rule edited, one deleted and one rejected in storage)**:
```json
{
  "status": "completed_with_errors",
  "rules_loaded": 15,
  "rules_failed": 1,
  "rules_unchanged": 14,
  "rules_removed": 1,
  "duration_ms": 63,
  "errors": [
    {
      "rule_id": "dangerous.rule",
      "error": "DRL security violations: [Blocked import: 'java.io.File']"
    }
  ],
  "cache_updated_at": "2026-11-18T08:57:10Z",
  "timestamp": "2026-11-18T08:57:10Z"
}
```

**Response (500, storage unreadable)**:
```json
{
  "status": "failed",
  "rules_loaded": 0,
  "rules_failed": 0,
  "rules_unchanged": 0,
  "rules_removed": 0,
  "duration_ms": 2003,
  "errors": [
    { "rule_id": "SYSTEM", "error": "Storage read failed; nothing changed: ..." }
  ],
  "cache_updated_at": "...",
  "timestamp": "..."
}
```
In a 500 or 409 response the counts are `0`: they describe this refresh, which changed nothing. Every previously loaded rule keeps serving.

**Response (409, empty storage)**: same shape with `"status": "rejected_empty"` and the error `Storage returned 0 rules while 17 are loaded; refusing to remove them all (set drools.refresh.allow-empty=true if this is intended)`.

> When this returns 200, the new rule set is live for subsequent requests. Execution takes no lock, so a refresh never blocks requests.

**Source**: [`AdminController.refreshAllRules`](../src/main/java/com/company/drools/api/controller/AdminController.java).

---

### `POST /admin/refresh-rules/{ruleId}` — Reload one rule

Re-reads one rule from storage (invalidating its Redis entry first) and recompiles only that rule ID.

```bash
curl -X POST \
  -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules/pricing.discount.simple | jq
```

**Path parameter**: `ruleId` — must match `^[a-zA-Z0-9._-]+$`, max 255 chars.

| HTTP | `status` | Meaning | Published to siblings |
|---:|---|---|---|
| 200 | `success` | A new version compiled and is serving | `RULE_REFRESHED` |
| 200 | `unchanged` | Same DRL as the serving version; not recompiled | `RULE_REFRESHED` (a sibling may still be stale) |
| 200 | `removed` | Gone from storage but was loaded: removed from the engine | `RULE_DELETED` |
| 404 | `not_found` | Not in storage and not loaded | — |
| 500 | `error` | Compile failed (the previous version, if any, keeps serving), or storage couldn't be read | — |

**Response (200 OK)**:
```json
{
  "rule_id": "pricing.discount.simple",
  "status": "success",
  "previous_version": "2026-11-18T08:40:02Z",
  "current_version": "2026-11-18T08:55:31Z",
  "compilation_time_ms": 12,
  "error": null,
  "timestamp": "2026-11-18T08:55:33Z"
}
```
`previous_version` / `current_version` are the storage `last_modified` timestamps before and after (see `GET /admin/rules`); `null` when the storage has none. `compilation_time_ms` is the whole refresh time, including the storage read.

**Response (200, deleted in storage)**:
```json
{
  "rule_id": "pricing.discount.vip",
  "status": "removed",
  "error": "Rule no longer exists in storage; removed from the engine",
  ...
}
```

**Response (404 Not Found)**:
```json
{
  "rule_id": "doesnt.exist",
  "status": "not_found",
  "error": "Rule not found in storage",
  ...
}
```

**Response (500)** on a compile or policy-check failure:
```json
{
  "rule_id": "broken.rule",
  "status": "error",
  "error": "Failed to compile rule (previous version, if any, keeps serving): DRL security violations: [Blocked class reference: 'ProcessBuilder']",
  ...
}
```

**Source**: [`AdminController.refreshRule`](../src/main/java/com/company/drools/api/controller/AdminController.java).

---

### `GET /admin/thread-pools` — Thread pool stats

Live snapshot of the two custom thread pools.

```bash
curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/thread-pools | jq
```

**Response**:
```json
{
  "rule_execution_pool": {
    "core_size": 8,
    "max_size": 20,
    "active_count": 0,
    "completed_task_count": 42,
    "queue_size": 0,
    "largest_pool_size": 3
  },
  "storage_pool": {
    "core_size": 4,
    "max_size": 10,
    "active_count": 0,
    "completed_task_count": 12,
    "queue_size": 0,
    "largest_pool_size": 1
  }
}
```

**Source**: [`AdminController.java:370-412`](../src/main/java/com/company/drools/api/controller/AdminController.java#L370-L412).

> Use this to spot pool saturation. If `active_count` ≈ `max_size` and `queue_size` is growing, you're under-provisioned. See [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md).

---

### `GET /admin/memory/info` — Memory diagnostics (full)

Comprehensive heap, non-heap, memory pool, and GC statistics.

```bash
curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/memory/info | jq
```

**Response** (truncated):
```json
{
  "heap": {
    "usedMB": 245,
    "committedMB": 512,
    "maxMB": 2048,
    "usagePercent": "11.96"
  },
  "nonHeap": {
    "usedMB": 85,
    "committedMB": 90,
    "maxMB": 512
  },
  "runtime": {
    "maxMemoryMB": 2048,
    "totalMemoryMB": 512,
    "usedMemoryMB": 245,
    "freeMemoryMB": 267,
    "usagePercent": "11.96"
  },
  "memoryPools": [
    { "name": "G1 Old Gen", "type": "HEAP", "usedMB": 156, "maxMB": 2048, "usagePercent": "7.62" },
    { "name": "G1 Eden Space", "type": "HEAP", "usedMB": 45, "maxMB": -1, "usagePercent": "N/A" },
    { "name": "Metaspace", "type": "NON_HEAP", "usedMB": 65, "maxMB": 512, "usagePercent": "12.70" }
  ],
  "garbageCollectors": [
    { "name": "G1 Young Generation", "collectionCount": 12, "collectionTimeMs": 45 },
    { "name": "G1 Old Generation", "collectionCount": 2, "collectionTimeMs": 15 }
  ],
  "warnings": []
}
```

**`warnings` field** populates when:
- Heap > 90% used: `"CRITICAL: Heap usage above 90% — OOM risk HIGH"`
- Heap > 80%: `"WARNING: Heap usage above 80%"`
- Heap > 70%: `"CAUTION: Heap usage above 70%"`

**Source**: [`MemoryController.java:49-141`](../src/main/java/com/company/drools/api/controller/MemoryController.java#L49-L141). See [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) for diagnostic flow.

---

### `GET /admin/memory/snapshot` — Lightweight memory snapshot

Same data as `/admin/memory/info` but only the heap summary. Cheap to poll frequently.

```bash
curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/memory/snapshot | jq
```

**Response**:
```json
{
  "timestamp": 1715173025123,
  "heapUsedMB": 245,
  "heapMaxMB": 2048,
  "heapUsagePercent": 11.96
}
```

Use for time-series collection. **Don't poll `/admin/memory/info` every second** — it's heavier.

**Source**: [`MemoryController.java:193-205`](../src/main/java/com/company/drools/api/controller/MemoryController.java#L193-L205).

---

### `POST /admin/memory/gc` — Trigger garbage collection (diagnostic)

Asks the JVM to run GC. **Diagnostic only — do not call from monitoring or production load**.

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/memory/gc | jq
```

**Response**:
```json
{
  "message": "Garbage collection triggered",
  "usedBeforeMB": 450,
  "usedAfterMB": 250,
  "freedMemoryMB": 200,
  "note": "This is a suggestion to JVM, actual GC timing is not guaranteed"
}
```

The JVM is free to ignore the request (it's a hint via `System.gc()`). Useful for forcing GC before taking a heap dump or measuring "true" working set after a load test.

**Source**: [`MemoryController.java:153-185`](../src/main/java/com/company/drools/api/controller/MemoryController.java#L153-L185).

---

## Spring Actuator endpoints (port 8081)

These are managed by Spring Boot, not by us. Documented here for completeness.

| Endpoint | Purpose | Notes |
|---|---|---|
| `GET /actuator/health` | Spring's view of overall health | Less detail than `/admin/health`. Includes the `rules` indicator. |
| `GET /actuator/health/readiness` | Readiness probe: `readinessState` + `rules` | 200 `UP` once the startup load has succeeded and rules are compiled. **503** while starting, after a failed startup load, or with 0 rules. Use it to take instances out of rotation. |
| `GET /actuator/health/liveness` | Liveness probe: `livenessState` only | 200 while the process is alive. Rule state does **not** affect it, so an instance with no rules isn't restarted in a loop. |
| `GET /actuator/info` | App identity (matches Spring's metadata) | Plain. |
| `GET /actuator/metrics` | List metric names | Browse with `/actuator/metrics/{name}`. |
| `GET /actuator/metrics/{name}` | Metric values | E.g., `/actuator/metrics/jvm.memory.used`. |
| `GET /actuator/prometheus` | Metrics in Prometheus format | Only present if `micrometer-registry-prometheus` is on the classpath (currently not — only `cloudwatch2`). |
| `GET /actuator/configprops` | Resolved configuration properties | Useful for verifying which env vars / profile values are actually applied. |

These run on **port 8081** (`management.server.port`). Configured exposure in [application.yml:11-24](../src/main/resources/application.yml#L11-L24):

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,metrics,info
      base-path: /actuator
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,rules
        liveness:
          include: livenessState
```

Details are shown only to authorized callers (`show-details: when-authorized`); anonymous callers see just `status`.

---

## OpenAPI specification

The machine-readable spec is at [`api-reference/openapi.yml`](api-reference/openapi.yml). It is the canonical contract — if this prose doc and the OpenAPI disagree, the OpenAPI is authoritative for API shape (the code is authoritative for behavior).

To browse the spec interactively, paste the content into [Swagger Editor](https://editor.swagger.io/) or run a local Swagger UI:

```bash
docker run -p 8888:8080 -e SWAGGER_JSON=/openapi.yml \
  -v "$(pwd)/project-documentation/api-reference/openapi.yml:/openapi.yml" \
  swaggerapi/swagger-ui
# then open http://localhost:8888
```

---

## What's NOT in the API surface (deliberate)

- **No batch endpoint.** To execute many rules, the client makes many `POST /execute-rule` calls.
- **No streaming / WebSocket / SSE.** Synchronous request/response only.
- **No GraphQL / gRPC.** REST only.
- **No `PUT /admin/rules/{id}` or `DELETE`.** Rule writes are not done through the API. Upload `.drl` to S3, then call `POST /admin/refresh-rules`.
- **No multi-tenancy / scoping.** Every rule is in one global namespace per deployment.
- **No webhook callbacks.** Rule execution is purely synchronous.
- **No SDK.** Just HTTP. See [11-integration-guide.md](11-integration-guide.md) for code examples.

---

## Live verification

To verify these endpoints against your running stack:

```bash
# admin endpoints require the key when ADMIN_API_KEY is set (always in prod/docker)
export ADMIN_API_KEY=admin-secret

# health
curl -fsS -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/health | jq '.status'        # → "UP"

# rules listed
curl -fsS -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/rules | jq '.total_rules'    # → 17

# basic rule execution (public API — no admin key)
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | jq '.result.discount'                                          # → 10

# memory check
curl -fsS -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/memory/info | jq '.heap.usagePercent'

# threads
curl -fsS -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/thread-pools | jq '.rule_execution_pool.completed_task_count'

# Spring Actuator (management port 8081 — no admin key)
curl -fsS http://localhost:8081/actuator/health | jq '.status'     # → "UP"
```

If any of these fail, see [31-troubleshooting.md](31-troubleshooting.md).
