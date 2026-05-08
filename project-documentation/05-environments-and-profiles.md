# 05 · Environments and Spring Profiles

| | |
|---|---|
| **Audience** | Developers, operators |
| **Purpose** | The 4 Spring profiles (`local`, `dev`, `prod`, `docker`): what each one overrides, when to use which, and the full diff table |
| **Last verified against** | [`application.yml`](../src/main/resources/application.yml) lines 165–350 on 2026-05-08 |
| **Related docs** | [06-deployment.md](06-deployment.md), [09-environment-variables-reference.md](09-environment-variables-reference.md), [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) |

---

## How profiles work

Spring profiles let one `application.yml` describe four runtime configurations. The **base configuration** (lines 1–163 of `application.yml`) defines defaults; each profile block (lines 165–350) overrides specific values.

You activate a profile by setting:
```bash
SPRING_PROFILES_ACTIVE=dev          # env var (preferred for containers)
# or
java -Dspring.profiles.active=dev -jar app.jar     # JVM arg
```

Default if unset: `local` (per `application.yml:31` — `${SPRING_PROFILES_ACTIVE:local}`).

You can stack profiles (`SPRING_PROFILES_ACTIVE=dev,debug`) but this project does not currently use stacked profiles.

---

## The 4 profiles at a glance

| Profile | When to use | Storage | Redis | CORS | Logging |
|---|---|---|---|---|---|
| **`local`** | Pure local dev with no Docker. Filesystem rules, no Redis. | `local` (in-memory adapter) | Disabled | Wildcard `*` | `DEBUG` for app, `INFO` root |
| **`dev`** | Dev with Docker stack (LocalStack S3 + Redis). Used by `setup-dev-environment.sh`. | `s3` via LocalStack | Enabled | Wildcard `*` | App `INFO`, root `INFO` |
| **`prod`** | Production deployment. Real AWS S3, real Redis, strict resilience, large pools. | `s3` (real AWS) | Enabled | **Empty** (set explicitly) | App `INFO`, root `WARN` |
| **`docker`** | When the app runs inside the docker-compose container (alongside LocalStack + Redis containers). | `s3` via LocalStack-in-compose | Enabled | Wildcard `*` | App `INFO`, root `INFO` |

The compose stack ([docker-compose.yml:65](../docker-compose.yml#L65)) sets `SPRING_PROFILES_ACTIVE=docker` for the `app` service.

---

## Full override matrix

This table lists every value that **changes** between profiles. Values marked `—` use the base default.

| Property | Base default | local | dev | prod | docker |
|---|---|---|---|---|---|
| `drools.rule-source` | `local` | — | `s3` | `s3` | `s3` |
| `drools.cors.allowed-origins` | *(empty)* | `*` | `*` | *(empty — set explicitly)* | `*` |
| `drools.s3.endpoint` | *(empty)* | — | `http://localhost:4566` | — *(real AWS)* | — *(uses `aws.endpoint` override)* |
| `drools.refresh.auto-enabled` | `false` | — | — | **`true`** | — |
| `drools.timeout.http.connection` | `10` | — | `5` | `10` | `8` |
| `drools.timeout.http.read` | `30` | — | `15` | `30` | `25` |
| `drools.timeout.rule-execution` | `30` | — | `15` | `30` | `25` |
| `drools.timeout.storage.operation` | `60` | — | `30` | `60` | `45` |
| `drools.timeout.cache.operation` | `5` | — | `3` | `5` | `3` |
| `drools.circuit-breaker.s3.failure-rate-threshold` | `50` | — | `60` | **`40`** | `50` |
| `drools.circuit-breaker.s3.wait-duration-in-open-state` | `60000` (60s) | — | `30000` (30s) | `120000` (2min) | `45000` (45s) |
| `drools.circuit-breaker.s3.sliding-window-size` | `100` | — | `50` | `200` | `75` |
| `drools.circuit-breaker.s3.minimum-number-of-calls` | `10` | — | `5` | `20` | `8` |
| `drools.circuit-breaker.redis.failure-rate-threshold` | `60` | — | `70` | **`50`** | `65` |
| `drools.circuit-breaker.redis.wait-duration-in-open-state` | `30000` (30s) | — | `15000` (15s) | `60000` (60s) | `30000` (30s) |
| `drools.circuit-breaker.redis.sliding-window-size` | `50` | — | `25` | `100` | `40` |
| `drools.circuit-breaker.redis.minimum-number-of-calls` | `5` | — | `3` | `10` | `5` |
| `drools.thread-pool.rule-execution.core-size` | `10` | — | — | **`20`** | `8` |
| `drools.thread-pool.rule-execution.max-size` | `50` | — | — | **`100`** | `20` |
| `drools.thread-pool.rule-execution.queue-capacity` | `100` | — | — | `200` | `50` |
| `drools.thread-pool.rule-execution.keep-alive` | `60` | — | — | `300` | `120` |
| `drools.thread-pool.storage.core-size` | `5` | — | — | **`10`** | `4` |
| `drools.thread-pool.storage.max-size` | `20` | — | — | **`50`** | `10` |
| `drools.thread-pool.storage.queue-capacity` | `50` | — | — | `100` | `25` |
| `drools.thread-pool.storage.keep-alive` | `60` | — | — | `300` | `120` |
| `redis.enabled` | `false` | `false` | **`true`** | **`true`** | **`true`** |
| `aws.endpoint` | *(empty)* | — | `http://localhost:4566` | — *(real AWS)* | — |
| `aws.access-key-id` | *(env)* | — | `${AWS_ACCESS_KEY_ID_DEV:test}` | *(env)* | — |
| `aws.secret-access-key` | *(env)* | — | `${AWS_SECRET_ACCESS_KEY_DEV:test}` | *(env)* | — |
| `aws.s3.connection-pool.max-connections` | `50` | — | `25` | **`100`** | `25` |
| `aws.s3.connection-pool.max-idle-time` | `60` | — | `30` | `120` | `60` |
| `management.metrics.export.cloudwatch.enabled` | `false` | — | — | **`true`** | — |
| `logging.level.com.company.drools` | `INFO` | `DEBUG` | — | — | — |
| `logging.level.root` | `INFO` | — | — | `WARN` | — |

---

## Profile-by-profile details

### `local` — pure local dev, no Docker

```yaml
drools:
  rule-source: local        # Uses InMemoryRuleStorageAdapter — built-in sample rules
  cors:
    allowed-origins: "*"    # Wildcard — easy local browser testing
redis:
  enabled: false            # No Redis dependency
logging:
  level:
    com.company.drools: DEBUG
```

**When to use**: You want to run the app on bare metal without Docker, just to play with code. No external services needed.

**Run it**:
```bash
source ./set-java-env.sh
mvn spring-boot:run                      # default profile is `local`
# or explicitly:
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**What's in the cache**: 3 sample rules hardcoded in `InMemoryRuleStorage.java`. Real rule files in `sample-rules/` are NOT loaded in this mode.

---

### `dev` — local Docker stack via LocalStack

```yaml
drools:
  rule-source: s3
  cors:
    allowed-origins: "*"
  s3:
    endpoint: http://localhost:4566       # LocalStack
  timeout:
    http: { connection: 5, read: 15 }     # Tighter than prod — fail fast in dev
    rule-execution: 15                    # Don't wait 30s for a slow rule in dev
    storage: { operation: 30 }
    cache: { operation: 3 }
  circuit-breaker:
    s3:    { failure-rate-threshold: 60, wait-duration-in-open-state: 30000, sliding-window-size: 50, minimum-number-of-calls: 5 }
    redis: { failure-rate-threshold: 70, wait-duration-in-open-state: 15000, sliding-window-size: 25, minimum-number-of-calls: 3 }
aws:
  endpoint: http://localhost:4566
  access-key-id: ${AWS_ACCESS_KEY_ID_DEV:test}
  secret-access-key: ${AWS_SECRET_ACCESS_KEY_DEV:test}
  s3:
    connection-pool: { max-connections: 25, max-idle-time: 30, connection-timeout: 5, socket-timeout: 30 }
redis:
  enabled: true
```

**When to use**: You're developing locally, running the app on bare metal but want real S3 (LocalStack) and Redis containers for testing.

**Setup** (assumes LocalStack + Redis containers already running):
```bash
docker-compose up -d localstack redis        # start the deps
./init-localstack.sh                         # upload sample rules to LocalStack S3
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Why looser circuit breakers (60% S3, 70% Redis)**: in dev, transient failures are common (containers restarting, LocalStack resetting). We don't want the circuit to open easily — better to let a few errors through and see the underlying cause in logs.

**Why credentials use `${..._DEV:test}`**: the dev profile defaults to LocalStack's `test`/`test` creds but lets you override per-developer if you ever point dev at a real AWS account (which you usually shouldn't).

---

### `prod` — production AWS deployment

```yaml
drools:
  rule-source: s3                              # Real AWS S3
  refresh:
    auto-enabled: true                         # Auto-refresh rules on schedule
  timeout:
    http: { connection: 10, read: 30 }
    rule-execution: 30
    storage: { operation: 60 }                 # Allow longer for cold S3 reads
    cache: { operation: 5 }
  circuit-breaker:
    s3:    { failure-rate-threshold: 40, wait-duration-in-open-state: 120000, sliding-window-size: 200, minimum-number-of-calls: 20 }
    redis: { failure-rate-threshold: 50, wait-duration-in-open-state: 60000,  sliding-window-size: 100, minimum-number-of-calls: 10 }
  thread-pool:
    rule-execution: { core-size: 20, max-size: 100, queue-capacity: 200, keep-alive: 300 }
    storage:        { core-size: 10, max-size: 50,  queue-capacity: 100, keep-alive: 300 }
redis:
  enabled: true
aws:
  s3:
    connection-pool: { max-connections: 100, max-idle-time: 120, connection-timeout: 5, socket-timeout: 30 }
management.metrics.export.cloudwatch.enabled: true
logging:
  level:
    com.company.drools: INFO
    root: WARN
```

**When to use**: production deployment to AWS ECS Fargate (or similar).

**Why stricter circuit breakers (40% S3, 50% Redis)**: in prod, *real* failures need to circuit-break quickly to protect user latency and downstream systems. Wait time is also longer (2 min for S3) so the breaker doesn't flap.

**Why bigger thread pools (20/100 vs 10/50)**: prod handles real RPS. Sliding-window sizes are also doubled (200/100 vs 100/50) for more stable failure-rate calculation.

**Why CORS empty**: production must whitelist explicit origins. Set `DROOLS_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com` via env var.

**Why CloudWatch enabled**: production observability. Metrics are exported to CloudWatch. Adjust namespace via `management.metrics.export.cloudwatch.namespace=DroolsEngine` (defaults are sane).

**Auto-refresh enabled**: in prod, `AUTO_REFRESH_ENABLED=true` lets the service periodically reload rules from S3 without manual `/admin/refresh-rules` calls. Interval default 5 min (`AUTO_REFRESH_INTERVAL_MINUTES`).

---

### `docker` — running inside docker-compose

```yaml
drools:
  rule-source: s3
  cors:
    allowed-origins: "*"
  timeout:
    http: { connection: 8, read: 25 }            # Slightly tighter than prod
    rule-execution: 25
    storage: { operation: 45 }
    cache: { operation: 3 }
  circuit-breaker:
    s3:    { failure-rate-threshold: 50, wait-duration-in-open-state: 45000, sliding-window-size: 75, minimum-number-of-calls: 8 }
    redis: { failure-rate-threshold: 65, wait-duration-in-open-state: 30000, sliding-window-size: 40, minimum-number-of-calls: 5 }
  thread-pool:
    rule-execution: { core-size: 8, max-size: 20, queue-capacity: 50, keep-alive: 120 }
    storage:        { core-size: 4, max-size: 10, queue-capacity: 25, keep-alive: 120 }
redis:
  enabled: true
aws:
  s3:
    connection-pool: { max-connections: 25, max-idle-time: 60, connection-timeout: 5, socket-timeout: 30 }
```

**When to use**: automatic — set by `docker-compose.yml`. The app container runs with this profile when you `docker compose up`.

**Why thread pools 8/20 (not prod's 20/100)**: the app container has `cpus: '2.0'` and `memory: 4G` limits in compose. Prod-sized thread pools would oversubscribe a 2-CPU container. Sized for typical dev hardware.

**Why connection-pool: 25 (not prod's 100)**: same reasoning — proportional to expected concurrency in a single dev container, and LocalStack typically can't sustain 100 concurrent S3 connections anyway.

**`aws.endpoint` is set per-service in [docker-compose.yml:33](../docker-compose.yml#L33)**: `AWS_ENDPOINT=http://localstack:4566`. The `localstack` hostname resolves via the `drools-network` bridge.

---

## Selecting the right profile

| Scenario | Profile | Notes |
|---|---|---|
| "Just let me run mvn spring-boot:run and see something work" | `local` | Built-in sample rules, no S3, no Redis. |
| "I want to develop with the full stack but my IDE running the app" | `dev` | Run `docker-compose up -d localstack redis` first; then `mvn spring-boot:run -Dspring-boot.run.profiles=dev`. |
| "I want the entire stack containerized" | `docker` (auto) | `docker compose up -d --build`. The compose file sets the profile. |
| "I'm deploying to production" | `prod` | Set `SPRING_PROFILES_ACTIVE=prod` in your ECS task definition (or equivalent). Provide real AWS creds via IAM role. |
| "I'm running JUnit tests" | (none) / `test` | Tests typically don't activate a profile; they configure beans via test-only config classes. |

---

## Profile differences summary (the highlights)

| Question | Answer |
|---|---|
| **Which profiles use real AWS?** | Only `prod`. `dev`/`docker`/`local` use LocalStack or in-memory. |
| **Which profile has the strictest circuit breakers?** | `prod` — S3 opens at 40% failure rate; sliding window 200 calls. |
| **Which profile has the largest thread pools?** | `prod` — 20 core / 100 max for rule execution. |
| **Which profile has the most permissive CORS?** | `local`/`dev`/`docker` (wildcard `*`). `prod` is empty by default — must be set explicitly. |
| **Which profile auto-refreshes rules from S3?** | Only `prod` (`drools.refresh.auto-enabled: true`). |
| **Which profile exports metrics to CloudWatch?** | Only `prod`. |
| **Which profile uses `DEBUG` logging?** | Only `local`. |
| **Which profile reduces `root` logging to `WARN`?** | Only `prod`. |

---

## How to override a profile value

You don't have to edit `application.yml`. Spring Boot's property override order (highest priority first):

1. Command-line args: `--drools.rule-source=memory`
2. JVM args: `-Ddrools.rule-source=memory`
3. OS env vars: `DROOLS_RULE_SOURCE=memory` *(this is what production uses)*
4. `.env` file (loaded via `DotenvConfig` with `addLast` — env vars take priority)
5. Profile-specific YAML (`application-prod.yml` if you split, or in-line `on-profile: prod` block)
6. Default YAML (`application.yml` base)

For the full env var catalog, see [09-environment-variables-reference.md](09-environment-variables-reference.md).

## Troubleshooting profiles

| Symptom | Likely cause | Fix |
|---|---|---|
| App says "Profile 'local' active" but I want dev | `SPRING_PROFILES_ACTIVE` not set | `export SPRING_PROFILES_ACTIVE=dev` or pass `-Dspring.profiles.active=dev` |
| App can't reach LocalStack at `localhost:4566` | Wrong profile (host network vs container network) | If running in compose: profile should be `docker` (uses `localstack` hostname). If running on host: profile `dev` (uses `localhost`). |
| Rate limits, timeouts feel wrong | Profile loaded different overrides than expected | `curl /admin/info` and inspect; or set `LOGGING_LEVEL_ROOT=DEBUG` at startup to see profile activation |
| `RULE_SOURCE` not respected | A profile is overriding it | Profiles are *higher priority* than the base default. If you set `RULE_SOURCE=memory` but the active profile sets `rule-source: s3`, the env var still wins (env > profile). |
| Two profiles' values are merging unexpectedly | Stacked profiles (`SPRING_PROFILES_ACTIVE=dev,prod`) | This project does not test stacked profiles — pick one. |
