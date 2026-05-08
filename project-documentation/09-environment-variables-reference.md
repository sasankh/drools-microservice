# 09 · Environment Variables Reference

| | |
|---|---|
| **Audience** | Developers, operators, AI agents (the lookup table for "what env var controls X?") |
| **Purpose** | Exhaustive catalog of every environment variable the code reads. The single source of truth for runtime configuration. |
| **Last verified against** | [`application.yml`](../src/main/resources/application.yml) and `@Value` annotations in `src/main/java/com/company/drools/config/` on 2026-05-08 |
| **Related docs** | [05-environments-and-profiles.md](05-environments-and-profiles.md), [08-configuration.md](08-configuration.md), [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md), [14-security-architecture.md](14-security-architecture.md) |

---

## How configuration resolution works

Spring Boot resolves a property like `${RULE_SOURCE:local}` by checking sources in this priority order (highest first):

1. Command-line args: `--rule-source=memory` or `--RULE_SOURCE=memory`
2. JVM system properties: `-DRULE_SOURCE=memory`
3. **OS environment variables**: `RULE_SOURCE=memory` *(this is what production typically uses)*
4. **`.env` file** (loaded by [`DotenvConfig`](../src/main/java/com/company/drools/config/DotenvConfig.java) with `addLast` — env vars override `.env` entries)
5. Active profile YAML (e.g., `drools.rule-source: s3` in the `dev` profile block of [application.yml](../src/main/resources/application.yml))
6. Default YAML (the `${KEY:default}` literal in `application.yml`)

So: **OS env > `.env` file > profile defaults > base defaults**.

When this doc says "Default", it means the value used if nothing higher-priority sets it. When a Spring profile is active, the profile-specific value applies — see [05-environments-and-profiles.md](05-environments-and-profiles.md) for per-profile overrides.

---

## Quick lookup by category

- [Server & Spring](#server--spring) (8 vars)
- [Storage backend](#storage-backend) (5 vars)
- [Cache & rule lifecycle](#cache--rule-lifecycle) (4 vars)
- [Timeouts](#timeouts) (5 vars)
- [Circuit breakers](#circuit-breakers) (8 vars)
- [Thread pools](#thread-pools) (8 vars)
- [Validation](#validation) (5 vars)
- [CORS](#cors) (5 vars)
- [Rate limiting](#rate-limiting) (6 vars)
- [Admin authentication](#admin-authentication) (1 var)
- [Redis](#redis) (3 vars)
- [AWS](#aws) (6 vars)
- [Logging](#logging) (1 var)
- [Metrics](#metrics) (2 vars)
- [Drools system properties](#drools-system-properties) (3, JVM-args only)

**Total: 66 distinct env vars catalogued below.**

---

## Server & Spring

| Variable | Default | Type | What it controls |
|---|---|---|---|
| `SERVER_PORT` | `8080` | int | Main API + custom admin endpoints (`/execute-rule`, `/admin/*`). |
| `ADMIN_PORT` | `8081` | int | Spring Boot Actuator endpoints (`/actuator/*`). Note: this is the **management** port, not the custom admin port. |
| `SPRING_PROFILES_ACTIVE` | `local` | string | Comma-separated profile names. See [05-environments-and-profiles.md](05-environments-and-profiles.md). Common values: `local`, `dev`, `prod`, `docker`. |
| `MAX_HTTP_REQUEST_SIZE` | `10MB` | size | Tomcat `max-http-request-size`. Hard cap on inbound request size at the HTTP layer. |
| `MAX_HTTP_POST_SIZE` | `10MB` | size | Tomcat `max-http-post-size` AND `tomcat.max-http-post-size`. Set both via this var. |
| `MAX_SWALLOW_SIZE` | `2MB` | size | Tomcat `max-swallow-size`. Bytes Tomcat will read off a connection after a request fails before closing. Lower = faster failure on oversized payloads. |
| `MAX_FILE_SIZE` | `5MB` | size | Spring Boot `multipart.max-file-size`. Per-file cap for multipart uploads. |
| `MAX_REQUEST_SIZE` | `10MB` | size | Spring Boot `multipart.max-request-size`. Total cap across all parts of a multipart request. |

> **Note**: this service does not use multipart in normal operation; the `MAX_FILE_SIZE` / `MAX_REQUEST_SIZE` settings exist for defense-in-depth.

---

## Storage backend

| Variable | Default | Valid values | What it controls |
|---|---|---|---|
| `RULE_SOURCE` | **`local`** | `local`, `file`, `s3` | Storage backend selected by [`StorageFactory`](../src/main/java/com/company/drools/storage/StorageFactory.java). `local` → `InMemoryRuleStorageAdapter` (built-in sample rules). `file` → `LocalFileStorage` (filesystem). `s3` → `S3RuleStorage`. Unrecognized values fall through to in-memory with a warning logged. |
| `RULE_BUCKET_NAME` | `local-rules` | string | S3 bucket name. Only used when `RULE_SOURCE=s3`. |
| `LOCAL_RULES_DIRECTORY` | `src/main/resources/rules` | path | Filesystem directory for `RULE_SOURCE=file`. Path is `normalize()`d and traversal-checked. |
| `AWS_ENDPOINT` | *(empty)* | URL | S3 endpoint override. Set to `http://localhost:4566` for LocalStack. Empty = real AWS. Validated against scheme + host allowlist in [`S3Config`](../src/main/java/com/company/drools/config/S3Config.java) — SSRF protection. |
| `AWS_REGION` | `us-east-1` | AWS region | AWS region for the S3 client. |

---

## Cache & rule lifecycle

| Variable | Default | Type | What it controls |
|---|---|---|---|
| `LRU_CACHE_MAX_SIZE` | `100` | int | Maximum entries in [`LocalLRUCache`](../src/main/java/com/company/drools/cache/LocalLRUCache.java). LRU eviction beyond this. |
| `RULE_EXECUTION_TIMEOUT_SECONDS` | `30` | int (seconds) | Per-execution cap. `RuleExecutor` uses this to time out rule firing via `CompletableFuture.get(...)`; on timeout it calls `future.cancel(true)`. |
| `AUTO_REFRESH_ENABLED` | `false` | bool | If true, scheduled background reload from S3 at the interval below. Default `true` only in `prod` profile. |
| `AUTO_REFRESH_INTERVAL_MINUTES` | `5` | int (minutes) | Refresh cadence when auto-refresh is enabled. |

---

## Timeouts

All values are seconds.

| Variable | Default | What it controls |
|---|---|---|
| `DROOLS_HTTP_CONNECTION_TIMEOUT` | `10` | HTTP client connection timeout for outgoing calls. |
| `DROOLS_HTTP_READ_TIMEOUT` | `30` | HTTP client read timeout. |
| `DROOLS_RULE_EXECUTION_TIMEOUT` | `30` | Same as `RULE_EXECUTION_TIMEOUT_SECONDS` — both bind to `drools.timeout.rule-execution`. **Use only one to avoid confusion.** |
| `DROOLS_STORAGE_OPERATION_TIMEOUT` | `60` | Time the storage layer (S3 / file / memory) has to complete a fetch. |
| `DROOLS_CACHE_OPERATION_TIMEOUT` | `5` | Time a cache (LRU / Redis) operation has to complete. |

> **Profile-specific overrides** (see [05-environments-and-profiles.md](05-environments-and-profiles.md)):
> - `dev`: HTTP=5/15, rule-exec=15, storage=30, cache=3 — tighter; fail fast in development.
> - `prod`: HTTP=10/30, rule-exec=30, storage=60, cache=5 — base defaults.
> - `docker`: HTTP=8/25, rule-exec=25, storage=45, cache=3 — between dev and prod.

---

## Circuit breakers

Resilience4j circuit-breaker config. Two breakers: `s3CircuitBreaker` and `redisCircuitBreaker`. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

### S3 circuit breaker

| Variable | Default | What it means |
|---|---|---|
| `DROOLS_CB_S3_FAILURE_RATE` | `50` | % of failed calls (in the sliding window) needed to trip the breaker open. |
| `DROOLS_CB_S3_WAIT_DURATION` | `60000` (60s) | Milliseconds the breaker stays OPEN before transitioning to HALF_OPEN. |
| `DROOLS_CB_S3_SLIDING_WINDOW` | `100` | Number of recent calls in the sliding window. |
| `DROOLS_CB_S3_MIN_CALLS` | `10` | Minimum calls before the breaker can compute a failure rate. |

**Profile overrides**: `prod` is much stricter (40% / 120s / 200 / 20) — production needs to circuit-break aggressively to protect downstream. `dev` is looser (60% / 30s / 50 / 5).

### Redis circuit breaker

| Variable | Default | What it means |
|---|---|---|
| `DROOLS_CB_REDIS_FAILURE_RATE` | `60` | % failure threshold. |
| `DROOLS_CB_REDIS_WAIT_DURATION` | `30000` (30s) | OPEN→HALF_OPEN duration in ms. |
| `DROOLS_CB_REDIS_SLIDING_WINDOW` | `50` | Sliding window size. |
| `DROOLS_CB_REDIS_MIN_CALLS` | `5` | Minimum calls before evaluation. |

**Profile overrides**: `prod` is 50% / 60s / 100 / 10. `dev` is 70% / 15s / 25 / 3.

---

## Thread pools

Two separate executors: `ruleExecutionExecutor` (rule firing) and `storageExecutor` (S3 / file ops). See [`ThreadPoolConfig`](../src/main/java/com/company/drools/config/ThreadPoolConfig.java).

### Rule execution thread pool

| Variable | Default | What it means |
|---|---|---|
| `DROOLS_THREAD_POOL_CORE_SIZE` | `10` | Always-alive threads. |
| `DROOLS_THREAD_POOL_MAX_SIZE` | `50` | Burst capacity ceiling. |
| `DROOLS_THREAD_POOL_QUEUE_CAPACITY` | `100` | Queue when pool is full. After queue fills, rejection policy kicks in (`CallerRunsPolicy` — see ADR for the trade-off). |
| `DROOLS_THREAD_POOL_KEEP_ALIVE` | `60` | Seconds before idle non-core threads are reaped. |

**Profile overrides**: `prod`: 20/100/200/300. `docker`: 8/20/50/120.

### Storage thread pool

| Variable | Default | What it means |
|---|---|---|
| `DROOLS_STORAGE_THREAD_POOL_CORE_SIZE` | `5` | Always-alive threads. |
| `DROOLS_STORAGE_THREAD_POOL_MAX_SIZE` | `20` | Burst ceiling. |
| `DROOLS_STORAGE_THREAD_POOL_QUEUE_CAPACITY` | `50` | Queue size. |
| `DROOLS_STORAGE_THREAD_POOL_KEEP_ALIVE` | `60` | Idle thread keep-alive. |

**Profile overrides**: `prod`: 10/50/100/300. `docker`: 4/10/25/120.

---

## Validation

Limits enforced by `@ValidRuleId`, `@ValidRuleData`, and `RequestSizeValidationFilter`. See [16-drl-sandboxing.md](16-drl-sandboxing.md) for what's enforced before rule compilation.

| Variable | Default | What it means |
|---|---|---|
| `DROOLS_VALIDATION_RULE_ID_MAX_LENGTH` | `255` | Max characters in a `rule_id`. The validator also enforces `^[a-zA-Z0-9._-]+$` and rejects path-traversal characters (`..`, `/`, `\`). |
| `DROOLS_VALIDATION_DATA_MAX_FIELDS` | `100` | Max number of top-level keys in the request `data` map. |
| `DROOLS_VALIDATION_DATA_MAX_STRING_LENGTH` | `10000` | Max length of any string value in `data`. |
| `DROOLS_VALIDATION_DATA_MAX_NUMBER_VALUE` | `1000000000` (1B) | Max absolute value for any numeric value in `data`. **Note**: the `@Value` default in code reads `:1000000` (1M) but `application.yml` overrides this to `1000000000`. The yaml wins. (See `CODE_FINDINGS.md` F-031.) |
| `DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES` | `1048576` (1 MiB) | Max body size enforced by [`RequestSizeValidationFilter`](../src/main/java/com/company/drools/api/filter/RequestSizeValidationFilter.java). Validates Content-Length AND wraps chunked-transfer streams with a `SizeLimitedInputStream`. **Lower than `MAX_HTTP_REQUEST_SIZE` (10MB) by design** — the Tomcat limit is a backstop; this is the enforced one. |

---

## CORS

| Variable | Default | What it controls |
|---|---|---|
| `DROOLS_CORS_ALLOWED_ORIGINS` | *(empty)* | Comma-separated origin list. Empty = no CORS headers added. Wildcard `*` only used in `local`/`dev`/`docker` profiles automatically. **In `prod`, this defaults to empty and must be set explicitly to your real origins** (e.g., `https://app.example.com`). |
| `DROOLS_CORS_ALLOWED_METHODS` | `GET,POST,PUT,DELETE,OPTIONS` | Allowed HTTP methods. |
| `DROOLS_CORS_ALLOWED_HEADERS` | `*` | Allowed request headers. |
| `DROOLS_CORS_ALLOW_CREDENTIALS` | `false` | Send `Access-Control-Allow-Credentials: true`. **Cannot be true if origins is `*`** (browser security rule). |
| `DROOLS_CORS_MAX_AGE` | `3600` (1 hour) | `Access-Control-Max-Age` header — preflight cache duration. |

---

## Rate limiting

See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) for the full multi-tier client identification flow.

| Variable | Default | What it means |
|---|---|---|
| `DROOLS_RATE_LIMITING_ENABLED` | `true` | Master switch. Set `false` to bypass the filter entirely (NOT recommended in prod). |
| `DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE` | `1000` | Per-client minute limit. |
| `DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR` | `10000` | Per-client hour limit. |
| `DROOLS_RATE_LIMITING_BURST_SIZE` | `100` | Allowed burst above the rate (token-bucket style). |
| `DROOLS_RATE_LIMITING_CLEANUP_INTERVAL` | `5` | Minutes between background cleanup of expired client buckets. |
| `DROOLS_RATE_LIMITING_MAX_CLIENTS` | `10000` | Max distinct clients tracked. Beyond this, new clients share a fallback bucket — protects against memory exhaustion via spoofed `X-Client-Id`. |

> **Admin endpoints (`/admin/*`) are exempt** from rate limiting entirely. Verified by [`RateLimitingFilterTest:120-127`](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java#L120-L127).

---

## Admin authentication

| Variable | Default | What it does |
|---|---|---|
| `ADMIN_API_KEY` | *(empty)* | When **set to a non-empty value**, [`AdminAuthFilter`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) requires the `X-Admin-API-Key: <value>` header on every `/admin/*` request. When **empty or unset**, admin endpoints are **open** (a warning is logged at startup). See [15-admin-authentication.md](15-admin-authentication.md) for full operational guidance. |

> **Production deployment must set this.** A long random string (32+ chars) is recommended. Rotate by deploying a new instance with the new key behind a load balancer, draining old, terminating old.

---

## Redis

| Variable | Default | What it does |
|---|---|---|
| `REDIS_ENABLED` | `false` | If true, the `RedisRuleCache` bean is wired (it's `@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`). However, `LocalLRUCache` is `@Primary`, so Redis is dormant by default even when the bean exists. See [ADR-005](36-architecture-decision-records.md). |
| `REDIS_URL` | `redis://localhost:6379` | Lettuce-format URL. Examples: `redis://user:pass@host:6379`, `rediss://host:6379` (TLS). |
| `REDIS_TTL_MINUTES` | `60` | TTL for cache entries when Redis is in use. |

---

## AWS

| Variable | Default | What it does |
|---|---|---|
| `AWS_ACCESS_KEY_ID` | *(empty)* | Standard AWS credential. Empty in production = use IAM role from EC2/ECS. Set explicitly (with `AWS_SECRET_ACCESS_KEY`) for local dev pointing at LocalStack. |
| `AWS_SECRET_ACCESS_KEY` | *(empty)* | Pair with above. |
| `AWS_S3_MAX_CONNECTIONS` | `50` | Apache HTTP client max connection pool size for the S3 client. Profile overrides: `prod`=100, `dev`/`docker`=25. |
| `AWS_S3_MAX_IDLE_TIME` | `60` | Max idle seconds before a connection is closed. |
| `AWS_S3_CONNECTION_TIMEOUT` | `10` | Connection-establish timeout (seconds). |
| `AWS_S3_SOCKET_TIMEOUT` | `60` | Socket read timeout (seconds). |

> The `dev` profile also reads `AWS_ACCESS_KEY_ID_DEV` / `AWS_SECRET_ACCESS_KEY_DEV` (defaulting to `test`/`test`) — see [application.yml:220-221](../src/main/resources/application.yml#L220-L221).

---

## Logging

| Variable | Default | What it does |
|---|---|---|
| `LOG_LEVEL` | `INFO` | Log level for the `com.company.drools` package only. Other levels: `root: INFO`, `org.drools: WARN`, `org.kie: WARN`. |

To set wider log levels, override the underlying Spring properties:
```bash
LOGGING_LEVEL_ROOT=DEBUG                          # everything (verbose)
LOGGING_LEVEL_COM_COMPANY_DROOLS=DEBUG            # just our code
LOGGING_LEVEL_ORG_DROOLS=DEBUG                    # Drools internals (very verbose)
```

The `local` profile sets `com.company.drools=DEBUG` automatically.

---

## Metrics

| Variable | Default | What it does |
|---|---|---|
| `CLOUDWATCH_METRICS_ENABLED` | `false` | If true, Micrometer's CloudWatch registry exports metrics. **Default true in `prod` profile only.** |
| (`management.metrics.export.cloudwatch.namespace`) | `DroolsEngine` | CloudWatch namespace for the metrics. Override via `MANAGEMENT_METRICS_EXPORT_CLOUDWATCH_NAMESPACE`. |

To enable Prometheus instead (or in addition):
1. Add `micrometer-registry-prometheus` to `pom.xml`.
2. Spring Boot auto-registers `/actuator/prometheus`.

---

## Drools system properties

These are **JVM `-D` properties**, not env vars. Set in [`Dockerfile`](../Dockerfile#L42-L44) under `JAVA_OPTS`:

| Property | Default in Dockerfile | What it controls |
|---|---|---|
| `drools.dateformat` | `yyyy-MM-dd` | Date parsing format inside DRL rules. |
| `drools.timezone` | `UTC` | Time zone for date math in rules. **Always UTC** — avoids DST surprises. |
| `drools.multithreadEvaluation` | `true` | Enables Drools's multi-threaded rule evaluation. |

To set externally, append to `JAVA_OPTS`:
```bash
JAVA_OPTS="$JAVA_OPTS -Ddrools.timezone=America/Los_Angeles"
```

---

## Common configurations by deployment scenario

### Local "just run it" (no Docker)

```bash
# Defaults work — local profile, in-memory rules, no Redis, no S3.
mvn spring-boot:run
```

### Local with Docker (recommended)

```bash
# docker-compose.yml sets everything. Override here only if needed.
docker compose up -d --build
```

### Production AWS

```bash
SPRING_PROFILES_ACTIVE=prod
RULE_SOURCE=s3
RULE_BUCKET_NAME=production-rules
AWS_REGION=us-east-1
# AWS credentials via IAM role on EC2/ECS — leave AWS_ACCESS_KEY_ID/SECRET empty
ADMIN_API_KEY=<32+ char random string>
DROOLS_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
REDIS_ENABLED=true
REDIS_URL=redis://prod-redis.example.com:6379
CLOUDWATCH_METRICS_ENABLED=true
LOG_LEVEL=INFO
```

### Production with stricter limits

Add on top of the above:

```bash
DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=5000
DROOLS_VALIDATION_DATA_MAX_FIELDS=200
DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES=2097152   # 2 MiB
DROOLS_THREAD_POOL_MAX_SIZE=200
AWS_S3_MAX_CONNECTIONS=200
```

### Local testing with memory storage only

```bash
RULE_SOURCE=local                  # uses InMemoryRuleStorage
REDIS_ENABLED=false
# No need for AWS_ENDPOINT, RULE_BUCKET_NAME, etc.
```

---

## Verification

To verify the running service has the configuration you expect:

```bash
# Active profiles, Java version, application metadata
curl http://localhost:8080/admin/info | jq

# Component health (cache size, rules loaded, S3/Redis/breaker state)
curl http://localhost:8080/admin/health | jq

# Heap and GC settings actually applied
curl http://localhost:8080/admin/memory/info | jq

# Spring Boot's view of properties (Actuator, port 8081)
curl http://localhost:8081/actuator/configprops | jq '.contexts.application.beans | keys'
```

The Actuator `configprops` endpoint is the most authoritative for "what is Spring actually using?" — it reports resolved values after profile/env-var resolution.

---

## When the docs and code disagree

If you find a discrepancy between this doc and observed behavior, **trust the code**. The order of precedence for ground truth:

1. `application.yml` (the YAML literal)
2. `@Value` annotations in `src/main/java/.../config/`
3. This document

Then file the discrepancy in [`.ai-workspace/documentations/CODE_FINDINGS.md`](../.ai-workspace/documentations/CODE_FINDINGS.md) for triage.
