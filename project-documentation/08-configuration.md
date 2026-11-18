# ⚙️ Drools Rule Engine - Configuration Guide

## 📋 Table of Contents

- [Overview](#overview)
- [Environment Variables](#environment-variables)
- [Application Properties](#application-properties)
- [Configuration Profiles](#configuration-profiles)
- [Advanced Configuration](#advanced-configuration)
- [Security Configuration](#security-configuration)
- [Performance Tuning](#performance-tuning)
- [Monitoring Configuration](#monitoring-configuration)
- [Validation Examples](#validation-examples)

---

## 🎯 Overview

This guide provides comprehensive documentation for configuring the Drools Rule Engine Microservice across different environments.

### Configuration Hierarchy
1. **Environment Variables** (Highest Priority)
2. **Application Properties Files** (`application-{profile}.yml`)
3. **Default Application Properties** (`application.yml`)
4. **Java System Properties** (`-D` flags)

### Key Configuration Areas
- **Rule Storage**: S3, Local, In-Memory backends
- **Caching**: Optional `RedisCachedRuleStorage` decorator over base storage + Redis pub/sub fan-out for cross-instance refresh convergence. No in-process LRU layer (dead `RuleCache` deleted 2026-05-20 — see [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20)).
- **Security**: Admin authentication, input validation, rate limiting, CORS, DRL sandboxing, security headers
- **Performance**: Thread pools, timeouts, circuit breakers
- **Monitoring**: Health checks, metrics, logging

---

## 🌍 Environment Variables

### Required Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `RULE_SOURCE` | Rule storage backend | `local` | `s3`, `local`, `memory` |
| `RULE_BUCKET_NAME` | S3 bucket name | `local-rules` | `prod-drools-rules`, `dev-rules` |

### AWS Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `AWS_REGION` | AWS region | `us-east-1` | `us-west-2`, `eu-west-1` |
| `AWS_ACCESS_KEY_ID` | AWS access key | *(auto-detect)* | `AKIAIOSFODNN7EXAMPLE` |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key | *(auto-detect)* | `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE` |
| `AWS_ENDPOINT` | Custom S3 endpoint | *(default)* | `http://localhost:4566` (LocalStack) |
| `AWS_S3_MAX_CONNECTIONS` | S3 connection pool size | `50` | `25`, `100` |
| `AWS_S3_MAX_IDLE_TIME` | S3 max connection idle (s) | `60` | `30`, `120` |
| `AWS_S3_CONNECTION_TIMEOUT` | S3 connection timeout (s) | `10` | `5`, `30` |
| `AWS_S3_SOCKET_TIMEOUT` | S3 socket/read timeout (s) | `60` | `30` |

### Redis Configuration (cache + pub/sub)

When `REDIS_ENABLED=true`, `RedisCachedRuleStorage` wraps the base `RuleStorage` (S3 / file / memory) as a read-through + write-through decorator. When `REDIS_PUBSUB_ENABLED=true` (default when Redis is enabled), `RuleRefreshPublisher`/`Subscriber` fan refresh events across instances on `drools:rule:events`. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20).

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `REDIS_ENABLED` | Enable `RedisCachedRuleStorage` decorator | `false` | `true`, `false` |
| `REDIS_URL` | Redis connection URL (`spring.data.redis.url`). **Credentials and TLS travel in the URL** — there is no separate `REDIS_PASSWORD` var. In the `prod` profile this must be `rediss://user:pass@host` or startup fails (`RedisSecurityValidator`). | `redis://localhost:6379` | `rediss://drools:secret@prod-redis:6379` |
| `REDIS_DRL_RULES_TTL_MINUTES` | Cache TTL for DRL JSON (minutes) | `15` | `5`, `30`, `60` |
| `REDIS_DRL_RULES_KEY_PREFIX` | Key prefix for SCAN+MGET bulk path | `drools:rule:` | `myapp:rules:` |
| `REDIS_PUBSUB_ENABLED` | Enable cross-instance refresh fan-out | `true` (when Redis enabled) | `true`, `false` |
| `REDIS_REFRESH_CHANNEL` | Pub/sub channel for refresh events | `drools:rule:events` | `myapp:rule:events` |
| `REDIS_TIMEOUT` | Lettuce command timeout (Spring data.redis.timeout) | `500ms` | `1s`, `1500ms` |

### Performance Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `RULE_EXECUTION_TIMEOUT_SECONDS` | Rule execution timeout | `30` | `10`, `60`, `120` |
| `DROOLS_THREAD_POOL_CORE_SIZE` | Rule execution core threads | `10` | `5`, `20` |
| `DROOLS_THREAD_POOL_MAX_SIZE` | Rule execution max threads | `50` | `20`, `100` |
| `DROOLS_THREAD_POOL_QUEUE_CAPACITY` | Rule execution queue capacity | `100` | `50`, `200` |
| `DROOLS_THREAD_POOL_KEEP_ALIVE` | Rule execution keep-alive (s) | `60` | `120`, `300` |
| `DROOLS_STORAGE_THREAD_POOL_CORE_SIZE` | Storage operation core threads | `5` | `4`, `10` |
| `DROOLS_STORAGE_THREAD_POOL_MAX_SIZE` | Storage operation max threads | `20` | `10`, `50` |
| `DROOLS_STORAGE_THREAD_POOL_QUEUE_CAPACITY` | Storage queue capacity | `50` | `25`, `100` |
| `DROOLS_STORAGE_THREAD_POOL_KEEP_ALIVE` | Storage keep-alive (s) | `60` | `120`, `300` |

### Security Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `DROOLS_VALIDATION_RULE_ID_MAX_LENGTH` | Max rule ID length | `255` | `100`, `500` |
| `DROOLS_VALIDATION_DATA_MAX_FIELDS` | Max data fields per request | `100` | `50`, `200`, `500` |
| `DROOLS_VALIDATION_DATA_MAX_STRING_LENGTH` | Max string field length | `10000` | `1000`, `50000` |
| `DROOLS_VALIDATION_DATA_MAX_NUMBER_VALUE` | Max numeric value magnitude | `1000000000` | `1000000`, `10000000000` |
| `DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES` | Max request body (bytes) | `1048576` | `2097152` |
| `DROOLS_CORS_ALLOWED_ORIGINS` | CORS allowed origins | *(empty)* | `https://app.company.com` |
| `DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE` | Requests per minute limit (per source IP) | `1000` | `100`, `5000`, `10000` |
| `DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR` | Requests per hour limit (per source IP) | `10000` | `5000`, `100000` |
| `DROOLS_RATE_LIMITING_BURST_SIZE` | Token-bucket burst size | `100` | `50`, `200` |
| `DROOLS_RATE_LIMITING_MAX_CLIENTS` | Max tracked source IPs (LRU-evicted at capacity) | `10000` | `5000`, `50000` |
| `DROOLS_RATE_LIMITING_TRUST_PROXY` | Key on left-most `X-Forwarded-For` (behind a trusted proxy) | `false` | `true` |
| `ADMIN_API_KEY` | API key for admin endpoint auth. Blank = open in `local`/`dev`, **hard startup failure** in `prod`/`docker`. | *(empty)* | `your-secure-api-key` |

### Circuit Breaker Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `DROOLS_CB_S3_FAILURE_RATE` | S3 failure rate threshold (%) | `50` | `40`, `70` |
| `DROOLS_CB_S3_WAIT_DURATION` | S3 wait duration when open (ms) | `60000` | `45000`, `120000` |
| `DROOLS_CB_S3_SLIDING_WINDOW` | S3 sliding-window size | `100` | `50`, `200` |
| `DROOLS_CB_S3_MIN_CALLS` | S3 minimum calls before evaluating | `10` | `5`, `20` |
| `DROOLS_CB_REDIS_FAILURE_RATE` | Redis failure rate threshold (%) | `60` | `50`, `70` |
| `DROOLS_CB_REDIS_WAIT_DURATION` | Redis wait duration when open (ms) | `30000` | `15000`, `60000` |
| `DROOLS_CB_REDIS_SLIDING_WINDOW` | Redis sliding-window size | `50` | `25`, `100` |
| `DROOLS_CB_REDIS_MIN_CALLS` | Redis minimum calls before evaluating | `5` | `3`, `10` |

### Logging Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `LOG_LEVEL` | `com.company.drools` logging level | `INFO` | `DEBUG`, `WARN` |
| `CLOUDWATCH_METRICS_ENABLED` | Export metrics to CloudWatch | `false` | `true` |

> Note: `root`, `org.drools`, and `org.kie` log levels are fixed in `application.yml`; only `com.company.drools` is externalized via `LOG_LEVEL`. The JSON vs console pattern is selected by the logging `pattern` config, not an env var.

---

## 📄 Application Properties

### Default Configuration (`application.yml`)

> The block below mirrors the real [`src/main/resources/application.yml`](../src/main/resources/application.yml) (single file with profile documents separated by `---`). Circuit-breaker, thread-pool, and timeout values are under the `drools:` tree — this project does **not** use the `resilience4j.circuitbreaker.instances.*` namespace.

```yaml
server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful
  max-http-request-size: ${MAX_HTTP_REQUEST_SIZE:10MB}

management:
  server:
    port: ${ADMIN_PORT:8081}          # Actuator only; /admin/* is on 8080
  endpoints:
    web:
      exposure:
        include: health,metrics,info
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized
  health:
    redis:
      enabled: ${REDIS_ENABLED:false} # only aggregate Redis health when Redis is on

spring:
  application:
    name: drools-rule-engine
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
      timeout: ${REDIS_TIMEOUT:500ms}  # Lettuce command timeout (below Redis CB 2s slow-call)
      lettuce:
        pool: { max-active: 10, max-idle: 5, min-idle: 1 }

# Drools Configuration
drools:
  admin:
    api-key: ${ADMIN_API_KEY:}         # blank => open (local/dev) or fail-start (prod/docker)
  rule-source: ${RULE_SOURCE:local}
  s3:
    bucket-name: ${RULE_BUCKET_NAME:local-rules}
    region: ${AWS_REGION:us-east-1}
    endpoint: ${AWS_ENDPOINT:}
  local:
    rules-directory: ${LOCAL_RULES_DIRECTORY:src/main/resources/rules}
  cache:
    execution-timeout-seconds: ${RULE_EXECUTION_TIMEOUT_SECONDS:30}
  refresh:
    auto-enabled: ${AUTO_REFRESH_ENABLED:false}
    interval-minutes: ${AUTO_REFRESH_INTERVAL_MINUTES:5}
  timeout:
    http: { connection: ${DROOLS_HTTP_CONNECTION_TIMEOUT:10}, read: ${DROOLS_HTTP_READ_TIMEOUT:30} }
    rule-execution: ${DROOLS_RULE_EXECUTION_TIMEOUT:30}
    storage: { operation: ${DROOLS_STORAGE_OPERATION_TIMEOUT:60} }
    cache: { operation: ${DROOLS_CACHE_OPERATION_TIMEOUT:5} }
  circuit-breaker:
    s3:
      failure-rate-threshold: ${DROOLS_CB_S3_FAILURE_RATE:50}
      wait-duration-in-open-state: ${DROOLS_CB_S3_WAIT_DURATION:60000}
      sliding-window-size: ${DROOLS_CB_S3_SLIDING_WINDOW:100}
      minimum-number-of-calls: ${DROOLS_CB_S3_MIN_CALLS:10}
    redis:
      failure-rate-threshold: ${DROOLS_CB_REDIS_FAILURE_RATE:60}
      wait-duration-in-open-state: ${DROOLS_CB_REDIS_WAIT_DURATION:30000}
      sliding-window-size: ${DROOLS_CB_REDIS_SLIDING_WINDOW:50}
      minimum-number-of-calls: ${DROOLS_CB_REDIS_MIN_CALLS:5}
  thread-pool:
    rule-execution:
      core-size: ${DROOLS_THREAD_POOL_CORE_SIZE:10}
      max-size: ${DROOLS_THREAD_POOL_MAX_SIZE:50}
      queue-capacity: ${DROOLS_THREAD_POOL_QUEUE_CAPACITY:100}
      keep-alive: ${DROOLS_THREAD_POOL_KEEP_ALIVE:60}
    storage:
      core-size: ${DROOLS_STORAGE_THREAD_POOL_CORE_SIZE:5}
      max-size: ${DROOLS_STORAGE_THREAD_POOL_MAX_SIZE:20}
      queue-capacity: ${DROOLS_STORAGE_THREAD_POOL_QUEUE_CAPACITY:50}
      keep-alive: ${DROOLS_STORAGE_THREAD_POOL_KEEP_ALIVE:60}
  validation:
    rule-id: { max-length: ${DROOLS_VALIDATION_RULE_ID_MAX_LENGTH:255} }
    data:
      max-fields: ${DROOLS_VALIDATION_DATA_MAX_FIELDS:100}
      max-string-length: ${DROOLS_VALIDATION_DATA_MAX_STRING_LENGTH:10000}
      max-number-value: ${DROOLS_VALIDATION_DATA_MAX_NUMBER_VALUE:1000000000}
    request: { max-size-bytes: ${DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES:1048576} }
  cors:
    allowed-origins: ${DROOLS_CORS_ALLOWED_ORIGINS:}      # empty default; wildcard only in local/dev/docker
    allowed-methods: ${DROOLS_CORS_ALLOWED_METHODS:GET,POST,PUT,DELETE,OPTIONS}
    allowed-headers: ${DROOLS_CORS_ALLOWED_HEADERS:*}
    allow-credentials: ${DROOLS_CORS_ALLOW_CREDENTIALS:false}
    max-age: ${DROOLS_CORS_MAX_AGE:3600}
  rate-limiting:
    enabled: ${DROOLS_RATE_LIMITING_ENABLED:true}
    requests-per-minute: ${DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE:1000}
    requests-per-hour: ${DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR:10000}
    burst-size: ${DROOLS_RATE_LIMITING_BURST_SIZE:100}
    cleanup-interval-minutes: ${DROOLS_RATE_LIMITING_CLEANUP_INTERVAL:5}
    max-clients: ${DROOLS_RATE_LIMITING_MAX_CLIENTS:10000}
    trust-proxy: ${DROOLS_RATE_LIMITING_TRUST_PROXY:false}  # key on X-Forwarded-For only behind a trusted proxy

# Redis cache + pub/sub (opt-in)
redis:
  enabled: ${REDIS_ENABLED:false}
  drl-rules:
    ttl-minutes: ${REDIS_DRL_RULES_TTL_MINUTES:15}
    key-prefix: ${REDIS_DRL_RULES_KEY_PREFIX:drools:rule:}
  pubsub:
    enabled: ${REDIS_PUBSUB_ENABLED:true}
    channel: ${REDIS_REFRESH_CHANNEL:drools:rule:events}

# AWS S3 connection pool
aws:
  region: ${AWS_REGION:us-east-1}
  endpoint: ${AWS_ENDPOINT:}
  s3:
    connection-pool:
      max-connections: ${AWS_S3_MAX_CONNECTIONS:50}
      max-idle-time: ${AWS_S3_MAX_IDLE_TIME:60}
      connection-timeout: ${AWS_S3_CONNECTION_TIMEOUT:10}
      socket-timeout: ${AWS_S3_SOCKET_TIMEOUT:60}

# Logging (only com.company.drools is externalized via LOG_LEVEL)
logging:
  level:
    root: INFO
    com.company.drools: ${LOG_LEVEL:INFO}
    org.drools: WARN
    org.kie: WARN

management.metrics:
  export:
    cloudwatch:
      namespace: DroolsEngine
      enabled: ${CLOUDWATCH_METRICS_ENABLED:false}
```

---

## 🔧 Configuration Profiles

### Local Development (`application-local.yml`)

```yaml
spring:
  profiles:
    active: local

drools:
  rule-source: s3
  bucket-name: local-rules

aws:
  region: us-east-1
  s3:
    endpoint: http://localhost:4566  # LocalStack

redis:
  enabled: true
  url: redis://localhost:6379

logging:
  level:
    com.company.drools: DEBUG
    org.springframework.web: DEBUG
```

### Development (`application-dev.yml`)

```yaml
spring:
  profiles:
    active: dev

drools:
  rule-source: s3
  bucket-name: dev-drools-rules
  rate-limiting:
    requests-per-minute: 5000

redis:
  enabled: true
  url: redis://dev-redis.company.com:6379

logging:
  level:
    com.company.drools: INFO
```

### Production (`application-prod.yml`)

```yaml
spring:
  profiles:
    active: prod

server:
  tomcat:
    max-threads: 400
    accept-count: 200

drools:
  rule-source: s3
  bucket-name: prod-drools-rules
  rate-limiting:
    requests-per-minute: 10000
    requests-per-hour: 500000
  cors:
    allowed-origins: https://app.company.com,https://admin.company.com

spring:
  data:
    redis:
      timeout: ${REDIS_TIMEOUT:500ms}
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 2

# prod REQUIRES a TLS + authenticated Redis URL (rediss://user:pass@host) via REDIS_URL,
# or RedisSecurityValidator fails startup. Set it through the environment, not in YAML.
redis:
  enabled: true
  drl-rules:
    ttl-minutes: 15
  pubsub:
    enabled: true

drools:
  thread-pool:
    rule-execution:
      core-size: 20
      max-size: 100
    storage:
      core-size: 10
      max-size: 50

logging:
  level:
    root: WARN
    com.company.drools: INFO
```

> No `cache.lru.*` block exists anymore — the dead `RuleCache` (LocalLRUCache + old RedisRuleCache) was deleted on 2026-05-20. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20).

---

## 🔒 Security Configuration

### Input Validation Configuration

```yaml
# Detailed validation configuration
drools:
  validation:
    rule-id:
      max-length: 255
      pattern: "^[a-zA-Z0-9._-]+$"
      dangerous-patterns:
        - "\\.\\."  # Path traversal
        - "/"       # Directory separator
        - "\\\\"    # Windows separator
    data:
      max-fields: 100
      max-depth: 10  # Nested object depth
      string-max-length: 10000
      number-max-value: 1000000000
      number-min-value: -1000000000
      dangerous-patterns:
        - "(?i)script"
        - "(?i)javascript"
        - "(?i)eval\\s*\\("
        - "<script"
        - "javascript:"
    request:
      max-size-mb: 10
```

### CORS Configuration

```yaml
# Flexible CORS configuration
drools:
  cors:
    enabled: true
    allowed-origins: ${DROOLS_CORS_ALLOWED_ORIGINS:}
    allowed-methods: GET,POST,PUT,DELETE,OPTIONS
    allowed-headers: "*"
    exposed-headers: X-RateLimit-Limit,X-RateLimit-Remaining,X-RateLimit-Reset
    allow-credentials: true
    max-age: 3600
```

### Rate Limiting Configuration

```yaml
# Per-source-IP rate limiting (NOT multi-tier — application headers are never used for bucketing)
drools:
  rate-limiting:
    enabled: ${DROOLS_RATE_LIMITING_ENABLED:true}
    requests-per-minute: ${DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE:1000}
    requests-per-hour: ${DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR:10000}
    burst-size: ${DROOLS_RATE_LIMITING_BURST_SIZE:100}
    max-clients: ${DROOLS_RATE_LIMITING_MAX_CLIENTS:10000}   # LRU-evicts oldest bucket at capacity
    cleanup-interval-minutes: ${DROOLS_RATE_LIMITING_CLEANUP_INTERVAL:5}
    trust-proxy: ${DROOLS_RATE_LIMITING_TRUST_PROXY:false}
    # Client identity = request.getRemoteAddr() by default. X-API-Key / Authorization / X-Client-Id
    # are NOT read. X-Forwarded-For is used (left-most entry) ONLY when trust-proxy=true and the
    # service sits behind a trusted proxy that overwrites inbound XFF.
```

### Admin Authentication Configuration

Admin endpoints (`/admin/*`) can be protected with API key authentication:

```yaml
drools:
  admin:
    api-key: ${ADMIN_API_KEY:}  # blank => open+WARN in local/dev; FAIL-START in prod/docker
```

**Usage**: Clients must send the `X-Admin-API-Key` header with every admin request (the key is compared in constant time). All `/admin/*` paths require it, including `/admin/health`:
```bash
curl -H "X-Admin-API-Key: your-secret-key" http://localhost:8080/admin/health
```

When `ADMIN_API_KEY` is empty or not set:
- **`local` / `dev` profiles**: admin authentication is disabled (development mode), with a WARN logged at startup.
- **`prod` / `docker` profiles**: the application **fails to start** (`AdminAuthFilter` throws) — a deployable profile must never serve `/admin/*` unprotected. Always set a strong `ADMIN_API_KEY`.

### DRL Sandboxing Configuration

All DRL rule files are scanned before compilation by `DrlSanitizer`. The following restrictions are enforced:

- **Blocked imports**: `java.lang.Runtime`, `java.lang.ProcessBuilder`, `java.io.*`, `java.net.*`, `java.lang.reflect.*`, `javax.script.*`, `org.drools.core.spi.*`
- **Blocked classes**: `Runtime`, `ProcessBuilder`, `Thread`, `ClassLoader`, `SecurityManager`, `System.exit`
- **Blocked methods**: `exec()`, `getRuntime()`, `loadClass()`, `forName()`, `invoke()`
- **`eval()` blocked**: Drools `eval()` expressions are not allowed (use pattern matching instead)
- **Import allowlist**: Only `java.util.*`, `java.math.*`, `java.time.*`, and `com.company.*` are permitted

Rules that violate these restrictions will fail compilation with a descriptive error message.

### Security Headers Configuration

All HTTP responses include security headers added by `SecurityHeadersFilter` (`@Order(-1)`):

| Header | Value |
|--------|-------|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `X-XSS-Protection` | `1; mode=block` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Cache-Control` | `no-cache, no-store, must-revalidate` |
| `Content-Security-Policy` | `default-src 'self'` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |

These headers are always applied and are not configurable.

---

## ⚡ Performance Tuning

### JVM Configuration

```bash
# Production JVM settings
JAVA_OPTS="-Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -server \
  -Djava.awt.headless=true"

# Development JVM settings
JAVA_OPTS="-Xms512m -Xmx2g \
  -XX:+UseG1GC \
  -XX:+PrintGC \
  -XX:+PrintGCDetails"
```

### Thread Pool Optimization

```yaml
# High-throughput configuration (real keys under drools.thread-pool)
drools:
  thread-pool:
    rule-execution:
      core-size: 20        # 2x CPU cores
      max-size: 100        # 5x core size
      queue-capacity: 200  # 2x max size
      keep-alive: 300
    storage:
      core-size: 10        # I/O bound operations
      max-size: 50
      queue-capacity: 100
      keep-alive: 300

# Memory-optimized configuration
drools:
  thread-pool:
    rule-execution:
      core-size: 5         # Lower memory footprint
      max-size: 20
      queue-capacity: 50
      keep-alive: 60
```

> The rule-execution pool's rejection policy is **`AbortPolicy`** (not configurable): when the queue is full and all threads are busy, a submission is rejected and the request returns **HTTP 503** (see [12-error-code-catalog.md](12-error-code-catalog.md)). The storage pool uses `CallerRunsPolicy`.

### Cache Optimization

The `RedisCachedRuleStorage` decorator wraps the base storage when `REDIS_ENABLED=true` (see [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20)). The legacy `cache.lru.*` config block was removed on 2026-05-20 with the deletion of `LocalLRUCache` — there is no in-process LRU layer anymore. Tunable keys today:

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
      timeout: ${REDIS_TIMEOUT:500ms}   # Lettuce command timeout (added 2026-05-24)
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 1

redis:
  enabled: ${REDIS_ENABLED:false}
  drl-rules:
    ttl-minutes: ${REDIS_DRL_RULES_TTL_MINUTES:15}        # was REDIS_TTL_MINUTES pre-2026-05-20
    key-prefix: ${REDIS_DRL_RULES_KEY_PREFIX:drools:rule:}
  pubsub:
    enabled: ${REDIS_PUBSUB_ENABLED:true}                 # cross-instance refresh fan-out
    channel: ${REDIS_REFRESH_CHANNEL:drools:rule:events}
```

Per-environment guidance:
- **Distant Redis or high p99 latency**: raise `REDIS_TIMEOUT` to 1000–1500ms (keep below the Redis CB's `slowCallDurationThreshold=2s`, see [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md))
- **Many small refresh events**: leave TTL at default 15 min (acts as eventual-consistency ceiling for missed pub/sub events)
- **No cross-instance setup (single replica)**: set `REDIS_PUBSUB_ENABLED=false` to skip the listener container

### Connection Pool Tuning

```yaml
# S3 connection optimization (real keys under aws.s3.connection-pool)
aws:
  s3:
    connection-pool:
      max-connections: ${AWS_S3_MAX_CONNECTIONS:50}     # raise for high throughput (prod=100)
      max-idle-time: ${AWS_S3_MAX_IDLE_TIME:60}
      connection-timeout: ${AWS_S3_CONNECTION_TIMEOUT:10}
      socket-timeout: ${AWS_S3_SOCKET_TIMEOUT:60}

# Redis connection optimization (Spring data.redis namespace; Lettuce client)
spring:
  data:
    redis:
      timeout: ${REDIS_TIMEOUT:500ms}        # Lettuce command timeout — sits below Redis CB slowCallDurationThreshold=2s
      lettuce:
        pool:
          max-active: 20
          max-idle: 5
          min-idle: 1
```

---

## 📊 Monitoring Configuration

### Metrics Configuration

```yaml
# Comprehensive metrics setup
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,thread-pools
  endpoint:
    health:
      show-details: when-authorized
      show-components: always
    metrics:
      enabled: true
  metrics:
    tags:
      application: drools-rule-engine
      environment: ${ENVIRONMENT:local}
    export:
      prometheus:
        enabled: true
        step: 15s
      # Uncomment for other exporters
      # datadog:
      #   enabled: false
      # cloudwatch:
      #   enabled: false
```

### Health Check Configuration

The project exposes **two** health endpoints with different shapes:

- **`GET /actuator/health`** (port 8081, Spring Boot Actuator) — auto-discovered Spring `HealthIndicator` beans (`diskSpace`, `ping`, `redis` when Redis is enabled). Configured via standard `management.health.*` keys.
- **`GET /admin/health`** (port 8080, our custom enriched endpoint in `AdminController`) — returns `{drools, s3, redis, cache, circuit-breakers, memory}` components with details. **Not configurable via `management.health.*`** — the component list is hardcoded in `AdminController`. See [10-api-reference.md](10-api-reference.md) for the response shape.

Standard Spring tunables for the actuator endpoint:

```yaml
management:
  endpoint:
    health:
      show-details: when-authorized   # or 'always' / 'never'
      show-components: always
    status:
      order: FATAL,DOWN,OUT_OF_SERVICE,UNKNOWN,UP
```

### Structured Logging

```xml
<!-- logback-spring.xml -->
<configuration>
  <springProfile name="!local">
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
        <providers>
          <timestamp>
            <timeZone>UTC</timeZone>
          </timestamp>
          <logLevel/>
          <loggerName/>
          <message/>
          <mdc/>
          <arguments/>
          <stackTrace/>
        </providers>
      </encoder>
    </appender>
  </springProfile>
  
  <springProfile name="local">
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
      </encoder>
    </appender>
  </springProfile>
  
  <root level="${LOGGING_LEVEL_ROOT:INFO}">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

---

## ✅ Validation Examples

### Environment Variable Validation

```bash
#!/bin/bash
# validate-config.sh

# Required variables
required_vars=("RULE_SOURCE" "RULE_BUCKET_NAME")

for var in "${required_vars[@]}"; do
  if [ -z "${!var}" ]; then
    echo "ERROR: $var is not set"
    exit 1
  fi
done

# Validate RULE_SOURCE
if [[ ! "$RULE_SOURCE" =~ ^(s3|local|memory)$ ]]; then
  echo "ERROR: RULE_SOURCE must be 's3', 'local', or 'memory'"
  exit 1
fi

# Validate numeric values
if [ -n "$REDIS_DRL_RULES_TTL_MINUTES" ] && (! [[ "$REDIS_DRL_RULES_TTL_MINUTES" =~ ^[0-9]+$ ]] || [ "$REDIS_DRL_RULES_TTL_MINUTES" -lt 1 ]); then
  echo "ERROR: REDIS_DRL_RULES_TTL_MINUTES must be a positive integer"
  exit 1
fi

echo "Configuration validation passed"
```

### Application Startup Validation

```java
@Component
public class ConfigurationValidator implements ApplicationRunner {

    @Value("${drools.rule-source}")
    private String ruleSource;

    @Value("${redis.drl-rules.ttl-minutes:15}")
    private int redisTtlMinutes;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateRuleSource();
        validateRedisConfiguration();
        validateThreadPoolConfiguration();
        log.info("Configuration validation completed successfully");
    }

    private void validateRuleSource() {
        if (!"s3".equals(ruleSource) && !"local".equals(ruleSource) && !"memory".equals(ruleSource)) {
            throw new IllegalStateException("Invalid rule source: " + ruleSource);
        }
    }

    // Additional validation methods...
}
```

---

## 🔗 Configuration References

### Quick Reference Cards

#### Development Environment
```bash
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=local-rules
export AWS_ENDPOINT=http://localhost:4566
export REDIS_ENABLED=true
export LOG_LEVEL=DEBUG
```

#### Production Environment
```bash
export SPRING_PROFILES_ACTIVE=prod
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=prod-drools-rules
export REDIS_ENABLED=true
export REDIS_URL=rediss://drools:your-secret@prod-redis.company.com:6379  # prod requires rediss:// + creds
export REDIS_DRL_RULES_TTL_MINUTES=15
export REDIS_PUBSUB_ENABLED=true
export DROOLS_THREAD_POOL_CORE_SIZE=20
export DROOLS_THREAD_POOL_MAX_SIZE=100
export DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=10000
export DROOLS_CORS_ALLOWED_ORIGINS=https://app.company.com,https://admin.company.com
export ADMIN_API_KEY=your-secure-api-key   # prod fails to start if unset
export LOG_LEVEL=INFO
```

#### Testing Environment
```bash
export RULE_SOURCE=memory
export REDIS_ENABLED=false
export LOG_LEVEL=DEBUG
```

---

**Last Updated**: 2026-08-20
**Version**: 1.2.0