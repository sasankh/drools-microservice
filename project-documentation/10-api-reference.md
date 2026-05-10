# 10 · API Reference

| | |
|---|---|
| **Audience** | Partners, developers, AI agents |
| **Purpose** | Complete prose reference for every HTTP endpoint, with request/response shapes and live curl examples |
| **Last verified against** | Controller files in [`src/main/java/com/company/drools/api/controller/`](../src/main/java/com/company/drools/api/controller/), [`api-reference/openapi.yml`](api-reference/openapi.yml) on 2026-05-10 |
| **Related docs** | [11-integration-guide.md](11-integration-guide.md), [12-error-code-catalog.md](12-error-code-catalog.md), [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md), [15-admin-authentication.md](15-admin-authentication.md), [api-reference/openapi.yml](api-reference/openapi.yml) |

---

## Servers and ports

The service exposes **two ports** with **three logical groups** of endpoints:

| Port | Path prefix | Audience | Auth |
|---:|---|---|---|
| 8080 | `/execute-rule` | All clients (the business API) | None |
| 8080 | `/admin/*` | Operators, ops automation | `X-Admin-API-Key` if `ADMIN_API_KEY` set |
| 8081 | `/actuator/*` | Monitoring, infra | None (typically firewalled to internal) |

**Note**: `/admin/*` is on the **main port** (8080), not the management port (8081). This is unusual but intentional — see [04-architecture.md](04-architecture.md) for rationale. The management port (8081) only hosts Spring Boot Actuator endpoints.

In production, you would expose 8080 to clients/operators (typically with auth at a gateway) and keep 8081 internal.

---

## Authentication

| Endpoint family | Auth required? | How |
|---|---|---|
| `POST /execute-rule` | None on the service itself | Authentication is your gateway's responsibility. The service uses rate limiting (per multi-tier client identity) for resource protection. |
| `GET/POST /admin/*` | API key when `ADMIN_API_KEY` is set | Send `X-Admin-API-Key: <value>` header. When `ADMIN_API_KEY` is empty/unset, admin endpoints are open and a warning is logged at startup. See [15-admin-authentication.md](15-admin-authentication.md). |
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

`result` is the modified `data` map after rules fire. `execution_time_ms` is the elapsed time from `KieSession.fireAllRules()` start to finish.

**Error responses**:

| Status | Code | Cause |
|---:|---|---|
| 400 | `INVALID_INPUT` | `rule_id` or `data` failed validation |
| 400 | `RULE_EXECUTION_ERROR` | Rule threw at runtime |
| 404 | `RULE_NOT_FOUND` | `rule_id` not in cache/storage |
| 408 | `TIMEOUT_ERROR` | Rule fired longer than `RULE_EXECUTION_TIMEOUT_SECONDS` (30s default) |
| 413 | `REQUEST_TOO_LARGE` | Body exceeded `DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES` (1 MiB default) |
| 429 | `RATE_LIMIT_EXCEEDED` | Per-client rate limit hit |
| 503 | `SERVICE_UNAVAILABLE` | Circuit breaker open (S3 or Redis) |

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
  "timestamp": "2026-05-10T08:57:10.549046921Z",
  "components": {
    "drools": {
      "status": "UP",
      "details": {
        "loaded_rules": 17,
        "active_rules": 17,
        "cache_hit_rate": 0.0
      }
    },
    "storage": {
      "status": "UP",
      "details": {
        "type": "S3RuleStorage",
        "rule_source": "s3",
        "s3_bucket": "local-rules",
        "s3_accessible": true,
        "total_rules": 10
      }
    },
    "cache": {
      "status": "UP",
      "details": {
        "enabled": true,
        "size": 10,
        "max_size": 100,
        "statistics": {
          "hits": 0,
          "misses": 0,
          "evictions": 0,
          "hit_rate": "0.00%"
        }
      }
    },
    "redis": {
      "status": "UP",
      "details": { "connected": true }
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

**Status semantics**:
- Overall `status` is `"UP"` if `drools` and `storage` are UP. `cache`, `redis`, `circuit-breakers` going DOWN does not flip the overall status (they are protection / acceleration mechanisms).
- `cache.statistics.hit_rate` is a string (formatted percentage); `drools.cache_hit_rate` is a float.

**Source**: [`AdminController.java:89-155`](../src/main/java/com/company/drools/api/controller/AdminController.java#L89-L155).

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

**Source**: [`AdminController.java:316-325`](../src/main/java/com/company/drools/api/controller/AdminController.java#L316-L325).

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
      "cached": true,
      "version": "1.0"
    },
    ...
  ]
}
```

| Field | Notes |
|---|---|
| `status` | Enum: `ACTIVE`, `INACTIVE`, `ERROR`. |
| `loaded_at` | ISO-8601 timestamp when this rule was last loaded into the KieContainer. |
| `last_modified` | ISO-8601 timestamp from the storage layer (S3 LastModified). **`null` if the rule has never been refetched** — it's set by storage operations, not on initial load. |
| `execution_count` | Increments per `/execute-rule` call. Starts at 0 for unused rules. |
| `avg_execution_time_ms` | Tracked in [`RuleMetadata`](../src/main/java/com/company/drools/core/model/RuleMetadata.java) using **incremental averaging** (Welford's method) to avoid numeric overflow. `0.0` for unused rules. |
| `cached` | True if the rule is in `LocalLRUCache`. |
| `version` | Source-defined version. Currently always `"1.0"` — versioning is roadmap. |

**Source**: [`AdminController.java:479-520`](../src/main/java/com/company/drools/api/controller/AdminController.java#L479-L520).

---

### `POST /admin/refresh-rules` — Reload all rules

Re-fetches all rules from storage, sandbox-scans them, recompiles, and updates the long-lived `KieContainer` in place via Drools 10's `KieContainer.updateToVersion(ReleaseId)`. Existing `/execute-rule` traffic continues during compilation — the compile happens outside the write lock, and the lock is held only briefly for the version swap. See [04-architecture.md](04-architecture.md), [ADR-003 2026-05-10 update](36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal), and [39-load-test-findings.md](39-load-test-findings.md) for measured behavior at 1000 rules.

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/refresh-rules | jq
```

**Response (success)**:
```json
{
  "status": "completed",
  "rules_loaded": 17,
  "rules_failed": 0,
  "duration_ms": 1234,
  "errors": [],
  "cache_updated_at": "2026-05-10T08:57:10Z"
}
```

**Response (partial failure — sandbox rejection)**:
```json
{
  "status": "completed_with_errors",
  "rules_loaded": 16,
  "rules_failed": 1,
  "errors": [
    {
      "rule_id": "dangerous.rule",
      "error": "DRL content contains blocked import: java.io.File"
    }
  ]
}
```

**Response (full failure)**: HTTP 500 with `INTERNAL_ERROR` envelope.

> When this returns, the new rule set is live for subsequent requests. In-flight requests use whichever `KieBase` was current when the `KieSession` was created — Drools 10's `updateToVersion` is non-blocking for in-flight sessions.

**Source**: [`AdminController.java:368-414`](../src/main/java/com/company/drools/api/controller/AdminController.java#L368-L414).

---

### `POST /admin/refresh-rules/{ruleId}` — Reload one rule

Targeted refresh for a single rule.

```bash
curl -X POST \
  -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules/pricing.discount.simple | jq
```

**Path parameter**: `ruleId` — must match `^[a-zA-Z0-9._-]+$`, max 255 chars.

**Response (200 OK)**:
```json
{
  "rule_id": "pricing.discount.simple",
  "status": "success",
  "previous_version": "1.0",
  "current_version": "1.0",
  "compilation_time_ms": 87
}
```

**Response (404 Not Found)** if the rule isn't in storage:
```json
{
  "rule_id": "doesnt.exist",
  "status": "not_found",
  "error": "Rule not found in storage"
}
```

**Response (500)** on compilation/sandbox failure:
```json
{
  "rule_id": "broken.rule",
  "status": "error",
  "error": "DRL content contains blocked class reference: ProcessBuilder"
}
```

**Source**: [`AdminController.java:417-476`](../src/main/java/com/company/drools/api/controller/AdminController.java#L417-L476).

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

**Source**: [`AdminController.java:328-365`](../src/main/java/com/company/drools/api/controller/AdminController.java#L328-L365).

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

**Source**: [`MemoryController.java:43-141`](../src/main/java/com/company/drools/api/controller/MemoryController.java#L43-L141). See [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) for diagnostic flow.

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

**Source**: [`MemoryController.java:183-195`](../src/main/java/com/company/drools/api/controller/MemoryController.java#L183-L195).

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

**Source**: [`MemoryController.java:143-181`](../src/main/java/com/company/drools/api/controller/MemoryController.java#L143-L181).

---

## Spring Actuator endpoints (port 8081)

These are managed by Spring Boot, not by us. Documented here for completeness.

| Endpoint | Purpose | Notes |
|---|---|---|
| `GET /actuator/health` | Spring's view of overall health | Less detail than `/admin/health`. Used by load balancers as a simple liveness probe. |
| `GET /actuator/info` | App identity (matches Spring's metadata) | Plain. |
| `GET /actuator/metrics` | List metric names | Browse with `/actuator/metrics/{name}`. |
| `GET /actuator/metrics/{name}` | Metric values | E.g., `/actuator/metrics/jvm.memory.used`. |
| `GET /actuator/prometheus` | Metrics in Prometheus format | Only present if `micrometer-registry-prometheus` is on the classpath (currently not — only `cloudwatch2`). |
| `GET /actuator/configprops` | Resolved configuration properties | Useful for verifying which env vars / profile values are actually applied. |

These run on **port 8081** (`management.server.port`). Configured exposure in [application.yml:13-24](../src/main/resources/application.yml#L13-L24):

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,metrics,info
      base-path: /actuator
```

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
# health
curl -fsS http://localhost:8080/admin/health | jq '.status'        # → "UP"

# rules listed
curl -fsS http://localhost:8080/admin/rules | jq '.total_rules'    # → 10

# basic rule execution
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | jq '.result.discount'                                          # → 10

# memory check
curl -fsS http://localhost:8080/admin/memory/info | jq '.heap.usagePercent'

# threads
curl -fsS http://localhost:8080/admin/thread-pools | jq '.rule_execution_pool.completed_task_count'

# Spring Actuator
curl -fsS http://localhost:8081/actuator/health | jq '.status'     # → "UP"
```

If any of these fail, see [31-troubleshooting.md](31-troubleshooting.md).
