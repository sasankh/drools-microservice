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
- **Caching**: Local LRU + Redis distributed caching
- **Security**: Admin authentication, input validation, rate limiting, CORS, DRL sandboxing, security headers
- **Performance**: Thread pools, timeouts, circuit breakers
- **Monitoring**: Health checks, metrics, logging

---

## 🌍 Environment Variables

### Required Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `RULE_SOURCE` | Rule storage backend | `s3` | `s3`, `local`, `memory` |
| `RULE_BUCKET_NAME` | S3 bucket name | *(required)* | `prod-drools-rules`, `dev-rules` |

### AWS Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `AWS_REGION` | AWS region | `us-east-1` | `us-west-2`, `eu-west-1` |
| `AWS_ACCESS_KEY_ID` | AWS access key | *(auto-detect)* | `AKIAIOSFODNN7EXAMPLE` |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key | *(auto-detect)* | `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE` |
| `AWS_ENDPOINT` | Custom S3 endpoint | *(default)* | `http://localhost:4566` (LocalStack) |
| `S3_CONNECTION_POOL_MAX_SIZE` | S3 connection pool size | `50` | `20`, `100` |
| `S3_CONNECTION_TIMEOUT_SECONDS` | S3 connection timeout | `30` | `10`, `60` |

### Redis Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `REDIS_ENABLED` | Enable Redis caching | `false` | `true`, `false` |
| `REDIS_URL` | Redis connection URL | `redis://localhost:6379` | `redis://prod-redis:6379` |
| `REDIS_PASSWORD` | Redis password | *(none)* | `your-secure-password` |
| `REDIS_TTL_SECONDS` | Cache TTL in seconds | `3600` | `1800`, `7200` |
| `REDIS_CONNECTION_POOL_MAX_SIZE` | Redis connection pool | `10` | `5`, `20` |

### Cache Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `LRU_CACHE_MAX_SIZE` | Local LRU cache size | `100` | `50`, `500`, `1000` |
| `CACHE_WARMING_ENABLED` | Enable cache warming | `true` | `true`, `false` |
| `CACHE_STATISTICS_ENABLED` | Track cache statistics | `true` | `true`, `false` |

### Performance Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `RULE_EXECUTION_TIMEOUT_SECONDS` | Rule execution timeout | `30` | `10`, `60`, `120` |
| `THREAD_POOL_RULE_EXECUTION_CORE_SIZE` | Rule execution core threads | `10` | `5`, `20`, `50` |
| `THREAD_POOL_RULE_EXECUTION_MAX_SIZE` | Rule execution max threads | `50` | `20`, `100`, `200` |
| `THREAD_POOL_STORAGE_CORE_SIZE` | Storage operation core threads | `5` | `3`, `10`, `15` |
| `THREAD_POOL_STORAGE_MAX_SIZE` | Storage operation max threads | `20` | `10`, `30`, `50` |

### Security Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `DROOLS_VALIDATION_RULE_ID_MAX_LENGTH` | Max rule ID length | `255` | `100`, `500` |
| `DROOLS_VALIDATION_DATA_MAX_FIELDS` | Max data fields per request | `100` | `50`, `200`, `500` |
| `DROOLS_VALIDATION_STRING_MAX_LENGTH` | Max string field length | `10000` | `1000`, `50000` |
| `DROOLS_VALIDATION_NUMBER_MAX_VALUE` | Max numeric value | `1000000000` | `1000000`, `10000000000` |
| `DROOLS_VALIDATION_REQUEST_MAX_SIZE_MB` | Max request size (MB) | `10` | `5`, `20`, `50` |
| `DROOLS_CORS_ALLOWED_ORIGINS` | CORS allowed origins | *(empty)* | `https://app.company.com` |
| `DROOLS_RATE_LIMITING_PER_MINUTE_LIMIT` | Requests per minute limit | `1000` | `100`, `5000`, `10000` |
| `DROOLS_RATE_LIMITING_PER_HOUR_LIMIT` | Requests per hour limit | `50000` | `10000`, `100000` |
| `DROOLS_RATE_LIMITING_MAX_CLIENTS` | Max tracked rate-limit clients | `10000` | `5000`, `50000` |
| `ADMIN_API_KEY` | API key for admin endpoint auth | *(empty/disabled)* | `your-secure-api-key` |

### Circuit Breaker Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `RESILIENCE4J_CIRCUITBREAKER_S3_FAILURE_RATE_THRESHOLD` | S3 failure rate threshold (%) | `50` | `30`, `70` |
| `RESILIENCE4J_CIRCUITBREAKER_S3_WAIT_DURATION_SECONDS` | S3 wait duration when open | `30` | `10`, `60` |
| `RESILIENCE4J_CIRCUITBREAKER_REDIS_FAILURE_RATE_THRESHOLD` | Redis failure rate threshold (%) | `50` | `30`, `70` |
| `RESILIENCE4J_CIRCUITBREAKER_REDIS_WAIT_DURATION_SECONDS` | Redis wait duration when open | `10` | `5`, `30` |

### Logging Configuration

| Variable | Description | Default | Examples |
|----------|-------------|---------|----------|
| `LOGGING_LEVEL_ROOT` | Root logging level | `INFO` | `WARN`, `DEBUG`, `ERROR` |
| `LOGGING_LEVEL_COM_COMPANY_DROOLS` | Application logging level | `INFO` | `DEBUG`, `WARN` |
| `LOG_FORMAT` | Log format | `JSON` | `JSON`, `CONSOLE` |
| `LOG_CORRELATION_ENABLED` | Enable correlation IDs | `true` | `true`, `false` |

---

## 📄 Application Properties

### Default Configuration (`application.yml`)

```yaml
server:
  port: 8080
  shutdown: graceful
  tomcat:
    max-threads: 200
    accept-count: 100

management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,thread-pools
  endpoint:
    health:
      show-details: when-authorized
  metrics:
    export:
      prometheus:
        enabled: true

spring:
  application:
    name: drools-rule-engine
  profiles:
    active: local

# Rule Storage Configuration
drools:
  rule-source: ${RULE_SOURCE:s3}
  bucket-name: ${RULE_BUCKET_NAME:local-rules}
  
# AWS Configuration
aws:
  region: ${AWS_REGION:us-east-1}
  s3:
    endpoint: ${AWS_ENDPOINT:}
    connection-pool-max-size: ${S3_CONNECTION_POOL_MAX_SIZE:50}
    connection-timeout-seconds: ${S3_CONNECTION_TIMEOUT_SECONDS:30}

# Cache Configuration
cache:
  lru:
    max-size: ${LRU_CACHE_MAX_SIZE:100}
    statistics-enabled: ${CACHE_STATISTICS_ENABLED:true}
  redis:
    enabled: ${REDIS_ENABLED:false}
    url: ${REDIS_URL:redis://localhost:6379}
    password: ${REDIS_PASSWORD:}
    ttl-seconds: ${REDIS_TTL_SECONDS:3600}
    connection-pool-max-size: ${REDIS_CONNECTION_POOL_MAX_SIZE:10}
  warming:
    enabled: ${CACHE_WARMING_ENABLED:true}

# Thread Pool Configuration
thread-pools:
  rule-execution:
    core-size: ${THREAD_POOL_RULE_EXECUTION_CORE_SIZE:10}
    max-size: ${THREAD_POOL_RULE_EXECUTION_MAX_SIZE:50}
    queue-capacity: 100
    thread-name-prefix: "rule-exec-"
  storage:
    core-size: ${THREAD_POOL_STORAGE_CORE_SIZE:5}
    max-size: ${THREAD_POOL_STORAGE_MAX_SIZE:20}
    queue-capacity: 50
    thread-name-prefix: "storage-"

# Timeout Configuration
timeouts:
  rule-execution: ${RULE_EXECUTION_TIMEOUT_SECONDS:30}
  s3-operations: ${S3_CONNECTION_TIMEOUT_SECONDS:30}
  redis-operations: 5

# Security Configuration
drools:
  validation:
    rule-id:
      max-length: ${DROOLS_VALIDATION_RULE_ID_MAX_LENGTH:255}
      pattern: "^[a-zA-Z0-9._-]+$"
    data:
      max-fields: ${DROOLS_VALIDATION_DATA_MAX_FIELDS:100}
      string-max-length: ${DROOLS_VALIDATION_STRING_MAX_LENGTH:10000}
      number-max-value: ${DROOLS_VALIDATION_NUMBER_MAX_VALUE:1000000000}
    request:
      max-size-mb: ${DROOLS_VALIDATION_REQUEST_MAX_SIZE_MB:10}
  cors:
    allowed-origins: ${DROOLS_CORS_ALLOWED_ORIGINS:}
    allowed-methods: GET,POST,PUT,DELETE,OPTIONS
    allowed-headers: "*"
    allow-credentials: true
  rate-limiting:
    enabled: true
    per-minute-limit: ${DROOLS_RATE_LIMITING_PER_MINUTE_LIMIT:1000}
    per-hour-limit: ${DROOLS_RATE_LIMITING_PER_HOUR_LIMIT:50000}

# Circuit Breaker Configuration
resilience4j:
  circuitbreaker:
    instances:
      s3:
        failure-rate-threshold: ${RESILIENCE4J_CIRCUITBREAKER_S3_FAILURE_RATE_THRESHOLD:50}
        wait-duration-in-open-state: ${RESILIENCE4J_CIRCUITBREAKER_S3_WAIT_DURATION_SECONDS:30}s
        sliding-window-size: 100
        minimum-number-of-calls: 10
        permitted-number-of-calls-in-half-open-state: 5
      redis:
        failure-rate-threshold: ${RESILIENCE4J_CIRCUITBREAKER_REDIS_FAILURE_RATE_THRESHOLD:50}
        wait-duration-in-open-state: ${RESILIENCE4J_CIRCUITBREAKER_REDIS_WAIT_DURATION_SECONDS:10}s
        sliding-window-size: 50
        minimum-number-of-calls: 5
        permitted-number-of-calls-in-half-open-state: 3

# Logging Configuration
logging:
  level:
    root: ${LOGGING_LEVEL_ROOT:INFO}
    com.company.drools: ${LOGGING_LEVEL_COM_COMPANY_DROOLS:INFO}
    org.springframework: WARN
    com.amazonaws: WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
  config: classpath:logback-spring.xml
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

cache:
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

cache:
  redis:
    enabled: true
    url: redis://dev-redis.company.com:6379

drools:
  rate-limiting:
    per-minute-limit: 5000

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

cache:
  lru:
    max-size: 500
  redis:
    enabled: true
    url: redis://prod-redis.company.com:6379
    connection-pool-max-size: 20

thread-pools:
  rule-execution:
    core-size: 20
    max-size: 100
  storage:
    core-size: 10
    max-size: 30

drools:
  rate-limiting:
    per-minute-limit: 10000
    per-hour-limit: 500000
  cors:
    allowed-origins: https://app.company.com,https://admin.company.com

logging:
  level:
    root: WARN
    com.company.drools: INFO
```

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
# Multi-tier rate limiting
drools:
  rate-limiting:
    enabled: true
    per-minute-limit: ${DROOLS_RATE_LIMITING_PER_MINUTE_LIMIT:1000}
    per-hour-limit: ${DROOLS_RATE_LIMITING_PER_HOUR_LIMIT:50000}
    max-clients: ${DROOLS_RATE_LIMITING_MAX_CLIENTS:10000}
    client-identification: request.getRemoteAddr()  # Always uses remote IP (X-Forwarded-For ignored for security)
    cleanup:
      interval-minutes: 60  # Clean old entries every hour
```

### Admin Authentication Configuration

Admin endpoints (`/admin/*`) can be protected with API key authentication:

```yaml
drools:
  admin:
    api-key: ${ADMIN_API_KEY:}  # Empty = auth disabled (backward compatible)
```

**Usage**: Clients must send the `X-Admin-API-Key` header with every admin request:
```bash
curl -H "X-Admin-API-Key: your-secret-key" http://localhost:8080/admin/health
```

When `ADMIN_API_KEY` is empty or not set, admin authentication is disabled (development mode). In production, always set a strong API key via the `ADMIN_API_KEY` environment variable.

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
# High-throughput configuration
thread-pools:
  rule-execution:
    core-size: 20        # 2x CPU cores
    max-size: 100        # 5x core size
    queue-capacity: 200  # 2x max size
    keep-alive-time: 60s
    rejection-policy: CALLER_RUNS
  storage:
    core-size: 10        # I/O bound operations
    max-size: 40         # Higher ratio for I/O
    queue-capacity: 100
    keep-alive-time: 30s

# Memory-optimized configuration
thread-pools:
  rule-execution:
    core-size: 5         # Lower memory footprint
    max-size: 20
    queue-capacity: 50
    keep-alive-time: 30s
```

### Cache Optimization

```yaml
# High-performance caching
cache:
  lru:
    max-size: 1000       # Larger local cache
    statistics-enabled: true
    access-order: true   # LRU eviction
  redis:
    enabled: true
    ttl-seconds: 7200    # 2 hours
    connection-pool-max-size: 20
    serialization: json  # Fast serialization
  warming:
    enabled: true
    batch-size: 50       # Warm cache in batches
```

### Connection Pool Tuning

```yaml
# S3 connection optimization
aws:
  s3:
    connection-pool-max-size: 100    # High throughput
    connection-timeout-seconds: 10   # Fast failure
    socket-timeout-seconds: 30       # Read timeout
    retry-policy:
      max-attempts: 3
      backoff-multiplier: 2
      max-backoff-seconds: 30

# Redis connection optimization
cache:
  redis:
    connection-pool-max-size: 20
    connection-pool-min-idle: 5
    connection-timeout-seconds: 5
    command-timeout-seconds: 3
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

```yaml
# Health check components
management:
  health:
    components:
      drools:
        enabled: true
      storage:
        enabled: true
      cache:
        enabled: true
      redis:
        enabled: true
      circuit-breakers:
        enabled: true
    show-details: always
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
if ! [[ "$LRU_CACHE_MAX_SIZE" =~ ^[0-9]+$ ]] || [ "$LRU_CACHE_MAX_SIZE" -lt 1 ]; then
  echo "ERROR: LRU_CACHE_MAX_SIZE must be a positive integer"
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
    
    @Value("${cache.lru.max-size}")
    private int cacheMaxSize;
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateRuleSource();
        validateCacheConfiguration();
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
export LOGGING_LEVEL_COM_COMPANY_DROOLS=DEBUG
```

#### Production Environment
```bash
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=prod-drools-rules
export REDIS_ENABLED=true
export REDIS_URL=redis://prod-redis.company.com:6379
export LRU_CACHE_MAX_SIZE=500
export THREAD_POOL_RULE_EXECUTION_CORE_SIZE=20
export THREAD_POOL_RULE_EXECUTION_MAX_SIZE=100
export DROOLS_RATE_LIMITING_PER_MINUTE_LIMIT=10000
export DROOLS_CORS_ALLOWED_ORIGINS=https://app.company.com,https://admin.company.com
export ADMIN_API_KEY=your-secure-api-key
export LOGGING_LEVEL_ROOT=WARN
```

#### Testing Environment
```bash
export RULE_SOURCE=memory
export REDIS_ENABLED=false
export CACHE_STATISTICS_ENABLED=true
export LOGGING_LEVEL_COM_COMPANY_DROOLS=DEBUG
```

---

**Last Updated**: 2026-02-26
**Version**: 1.1.0