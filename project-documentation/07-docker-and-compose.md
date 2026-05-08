# 07 · Docker & Docker Compose Deep Dive

| | |
|---|---|
| **Audience** | Developers, operators |
| **Purpose** | Line-by-line walkthrough of `Dockerfile` and `docker-compose.yml` so a reader understands every flag, env var, healthcheck, and volume |
| **Last verified against** | [`Dockerfile`](../Dockerfile), [`docker-compose.yml`](../docker-compose.yml) on 2026-05-08 |
| **Related docs** | [03-tech-stack.md](03-tech-stack.md), [05-environments-and-profiles.md](05-environments-and-profiles.md), [06-deployment.md](06-deployment.md), [24-jvm-optimization.md](24-jvm-optimization.md) |

---

## Why this doc exists

The Dockerfile and compose file together encode dozens of decisions about JVM tuning, security hardening, networking, and resource limits. Most of those decisions are non-obvious. This doc explains *every* line so an operator can reason about the runtime, and so an AI agent can answer "why is this flag set?" without speculation.

---

## Part 1: Dockerfile

[`Dockerfile`](../Dockerfile) is **57 lines, two stages**:

### Stage 1: Build (lines 1–13)

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies by copying pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build application
COPY src ./src
RUN mvn clean package -DskipTests
```

**Why `maven:3.9-eclipse-temurin-17` for the build stage**:
- Maven 3.9+ matches the project's enforcer rule.
- Eclipse Temurin 17 is a mainstream OpenJDK 17 distribution. Build-time JDK doesn't have to match runtime JDK as long as both are Java 17.
- Multi-stage allows the build artifacts (Maven cache, source) to be excluded from the final image.

**Why pom.xml is copied separately first** (line 8 before line 12):
- Docker layer caching. If only `src/` changes (the common case), the dependency-download layer (`mvn dependency:go-offline`) is reused. Otherwise, every build re-downloads the entire dependency graph (~5+ minutes).
- `dependency:go-offline -B` (`-B` = batch mode, no interactive prompts) primes the local Maven repo with everything needed for the offline build.

**Why `-DskipTests`**:
- Tests run in CI, not in the Docker build. Docker build is for image construction; testing is a separate concern.
- Skipping tests cuts build time from ~5 min to ~30s in the build stage.

### Stage 2: Runtime (lines 16–57)

```dockerfile
FROM amazoncorretto:17-alpine-jdk
WORKDIR /app

# Add non-root user for security
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -s /bin/sh -D appuser

# Copy the built JAR from build stage
COPY --from=build /app/target/drools-rule-engine-*.jar app.jar
```

**Why `amazoncorretto:17-alpine-jdk`**:
- Amazon Corretto = AWS's hardened OpenJDK 17 build, kept in lockstep with security patches. Project deploys to AWS so vendor alignment matters.
- Alpine base = ~180 MB before Java; final image is ~347 MB.
- `-jdk` (not `-jre`): Drools `KieBuilder` invokes `javac` at runtime to compile generated rule classes. A JRE-only image breaks rule compilation. **Do not switch to `-jre`.**

**Why uid/gid 1000 and a dedicated `appuser`**:
- Non-root execution is mandatory in many container security policies (PSP, OPA Gatekeeper, ECS task definitions with `requiresCompatibilities: FARGATE`).
- uid 1000 is a common convention for first non-system user — matches host filesystem conventions when bind-mounting volumes.
- `-D` flag = no password (login disabled). Container users should never log in interactively.
- `-s /bin/sh` gives a shell for `docker exec` debugging. (Could be `/sbin/nologin` for stricter setups, but loses debug ergonomics.)

**Why `app.jar` (not the versioned name)**:
- The wildcard `drools-rule-engine-*.jar` matches whatever version Maven produced; renaming to `app.jar` decouples the entrypoint from the version. Bumping the version in `pom.xml` doesn't require a Dockerfile edit.

### JAVA_OPTS (lines 27–44) — every flag explained

```dockerfile
ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:InitialRAMPercentage=50.0 \
  -XX:MaxRAMPercentage=75.0 \
  -XX:MinRAMPercentage=50.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=30 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -XX:+UseCompressedOops \
  -XX:+UseCompressedClassPointers \
  -XX:ThreadStackSize=1024 \
  -XX:TieredStopAtLevel=4 \
  -XX:+ExitOnOutOfMemoryError \
  -Ddrools.dateformat=yyyy-MM-dd \
  -Ddrools.timezone=UTC \
  -Ddrools.multithreadEvaluation=true"
```

| Flag | What it does | Why this value |
|---|---|---|
| `-XX:+UseContainerSupport` | JVM reads cgroup limits to size heap | Required for Java 17 to respect container memory caps. Replaces the deprecated `-XX:+UseCGroupMemoryLimitForHeap`. |
| `-XX:InitialRAMPercentage=50.0` | Initial heap = 50% of cgroup limit | Smooth ramp; avoids JVM starting too small and immediately resizing. |
| `-XX:MaxRAMPercentage=75.0` | Max heap = 75% of cgroup limit | Leaves 25% headroom for non-heap (Metaspace, code cache, native, thread stacks). |
| `-XX:MinRAMPercentage=50.0` | Floor when small containers | For containers < 200MB, ensures heap doesn't shrink absurdly. |
| `-XX:+UseG1GC` | Use G1 garbage collector | Low-pause concurrent GC; standard for high-throughput services. |
| `-XX:MaxGCPauseMillis=100` | Target max pause = 100ms | Aligns with API P99 latency target. Soft target — G1 may exceed under stress. |
| `-XX:G1HeapRegionSize=16m` | Heap region size | 16m suits multi-GB heaps. Auto-sizing would pick 4m for our typical 2GB heap, which over-fragments. |
| `-XX:InitiatingHeapOccupancyPercent=30` | Start concurrent GC at 30% old-gen full | Aggressive — starts GC early to avoid full pauses. Trades CPU for predictable latency. |
| `-XX:+UseStringDeduplication` | G1 deduplicates equal `String` instances | Drools generates many duplicate rule strings; saves ~5-15% heap. |
| `-XX:+OptimizeStringConcat` | C2 intrinsic for `+` on Strings | Marginal win; cheap to enable. |
| `-XX:+UseCompressedOops` | 32-bit object pointers (heap < 32GB) | Default true in Java 17, but explicit for clarity. Saves ~50% on object headers. |
| `-XX:+UseCompressedClassPointers` | 32-bit class metadata pointers | Same family as above. |
| `-XX:ThreadStackSize=1024` | Per-thread stack = 1MB | Default is 1MB on most platforms; this pins it. With ~50–100 threads under load, that's 50–100MB native memory. |
| `-XX:TieredStopAtLevel=4` | Use C2 (full optimizing) compiler | Default. Mentioned explicitly because some "fast startup" profiles set this to 1. |
| `-XX:+ExitOnOutOfMemoryError` | Crash hard on OOM | A Java service that's OOMing is broken — the orchestrator should restart it. Hung-with-stale-state services are worse than dead ones. |
| `-Ddrools.dateformat=yyyy-MM-dd` | Drools date parsing format | ISO-8601 date format; predictable across locales. |
| `-Ddrools.timezone=UTC` | Drools time zone | UTC always. No surprise DST or local-zone math in rules. |
| `-Ddrools.multithreadEvaluation=true` | Enable Drools multi-threaded rule evaluation | Drools can parallelize rule eval across cores. Worth turning on for our throughput target. |

> **Heads-up**: `docker-compose.yml` line 25 *overrides* this `JAVA_OPTS` with a different set when running in compose (see Part 2 below). The Dockerfile's `JAVA_OPTS` is what applies in raw `docker run` invocations or in production ECS deployments.

### Healthcheck (lines 47–48)

```dockerfile
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/admin/health || exit 1
```

| Field | Value | Why |
|---|---|---|
| `interval` | 30s | Reasonable polling cadence; doesn't load the service. |
| `timeout` | 10s | Allows for occasional GC pause. |
| `start-period` | 60s | Spring Boot + Drools rule compilation takes ~30–45s on a cold start. Don't fail the container during boot. |
| `retries` | 3 | Three consecutive failures = container marked unhealthy. Avoids flap on a single transient blip. |
| Command | `wget --spider` to `/admin/health` | Alpine has `wget` built in but not `curl`. `--spider` does HEAD/GET without saving. |

**Why `/admin/health` (port 8080) and not `/actuator/health` (port 8081)**:
- `/admin/health` is the project's enriched health endpoint with component-level breakdown (Drools, S3, Redis, circuit breakers, cache).
- `/actuator/health` exists too but is on the management port, which may not be exposed in all deployments.
- Healthcheck running on 8080 = same port the LB is checking = same code path = consistent UP/DOWN signal.

### USER, EXPOSE, ENTRYPOINT (lines 51–57)

```dockerfile
USER appuser
EXPOSE 8080 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

- `USER appuser` is **after** the COPY (line 24) so the JAR is copied as root and made readable. Switching to a non-root user before copy can break read permissions.
- `EXPOSE` is documentation-only (Docker doesn't actually publish ports — `docker run -p` does). Listing both 8080 (API+admin) and 8081 (Actuator) makes the contract explicit.
- The entrypoint uses `sh -c "..."` so `$JAVA_OPTS` is shell-expanded. The exec form `["java", "$JAVA_OPTS", ...]` would NOT expand the variable. (Side effect: signals like SIGTERM go to `sh`, not the Java process; in practice Spring Boot's `server.shutdown: graceful` handles JVM-side shutdown anyway.)

---

## Part 2: docker-compose.yml

[`docker-compose.yml`](../docker-compose.yml) defines a 3-service dev stack: `app`, `localstack`, `redis`. Compose v3.8 syntax. Total file: 129 lines.

### Top-level shape

```
services:
  app:          # The Drools service itself (built from Dockerfile)
  localstack:   # AWS S3 emulator
  redis:        # Optional cache (when REDIS_ENABLED=true)
networks:
  drools-network:
volumes:
  localstack-data:
  redis-data:
```

### Service: `app`

```yaml
app:
  build:
    context: .
    dockerfile: Dockerfile
  ports:
    - "8080:8080"  # Main API + admin
    - "8081:8081"  # Actuator
  deploy:
    resources:
      limits:    { memory: 4G, cpus: '2.0' }
      reservations: { memory: 2G, cpus: '1.0' }
  volumes:
    - ./heap-dumps:/tmp/heap-dumps
    - ./gc-logs:/tmp/gc-logs
  environment:
    # JVM Memory & GC Configuration  ← OVERRIDES Dockerfile JAVA_OPTS
    - JAVA_OPTS=-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
                -XX:+UseStringDeduplication
                -XX:+HeapDumpOnOutOfMemoryError
                -XX:HeapDumpPath=/tmp/heap-dumps/heapdump.hprof
                -Xlog:gc*:file=/tmp/gc-logs/gc.log:time,uptime,level,tags

    # Rule source configuration
    - RULE_SOURCE=s3
    - RULE_BUCKET_NAME=local-rules

    # AWS/LocalStack configuration
    - AWS_REGION=us-east-1
    - AWS_ENDPOINT=http://localstack:4566   # ← compose-network DNS
    - AWS_ACCESS_KEY_ID=test
    - AWS_SECRET_ACCESS_KEY=test

    # Redis configuration
    - REDIS_ENABLED=true
    - REDIS_URL=redis://redis:6379          # ← compose-network DNS

    # Cache configuration
    - LRU_CACHE_MAX_SIZE=100
    - RULE_EXECUTION_TIMEOUT_SECONDS=30

    # Thread pool configuration for containerized environment
    - DROOLS_THREAD_POOL_CORE_SIZE=8
    - DROOLS_THREAD_POOL_MAX_SIZE=20
    - DROOLS_THREAD_POOL_QUEUE_CAPACITY=50
    - DROOLS_STORAGE_THREAD_POOL_CORE_SIZE=4
    - DROOLS_STORAGE_THREAD_POOL_MAX_SIZE=10

    # S3 connection pool optimization
    - AWS_S3_MAX_CONNECTIONS=25
    - AWS_S3_CONNECTION_TIMEOUT=5
    - AWS_S3_SOCKET_TIMEOUT=30

    # Auto refresh configuration
    - AUTO_REFRESH_ENABLED=false
    - AUTO_REFRESH_INTERVAL_MINUTES=5

    # Logging
    - LOG_LEVEL=INFO

    # Spring profile for containerized environment
    - SPRING_PROFILES_ACTIVE=docker         # ← activates Docker profile in application.yml
  depends_on:
    localstack:  { condition: service_healthy }
    redis:       { condition: service_healthy }
  networks: [drools-network]
  restart: unless-stopped
  healthcheck:
    test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/admin/health"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 60s
```

#### Why these specific values

**Resource limits (4G mem, 2 vCPU)**: matches a typical "medium" dev workstation. The `reservations` (2G/1.0 cpu) tell Docker the *minimum* the container needs — important for fair scheduling when multiple containers compete.

**JAVA_OPTS override**: the Dockerfile sets percentage-based heap (50%/75% of cgroup limit). The compose override switches to **explicit `-Xms512m -Xmx2048m`** — predictable for dev. It also adds:
- `-XX:+HeapDumpOnOutOfMemoryError` + `HeapDumpPath` → on OOM, a `.hprof` is written to the host-mounted `./heap-dumps/` dir for post-mortem analysis.
- `-Xlog:gc*` → GC log written to the host-mounted `./gc-logs/`. Persists across container restarts.

**Volume mounts**: `./heap-dumps` and `./gc-logs` are bind-mounted from the host. After a crash, the developer can inspect them on the host filesystem without `docker cp`. (Both directories must exist on the host before compose starts; they're committed to the repo as empty dirs — see `.gitkeep` if needed.)

**`AWS_ENDPOINT=http://localstack:4566`**: this URL works because `localstack` is a service name on `drools-network`. Docker's built-in DNS resolves it to the LocalStack container's IP. The same URL would NOT work outside the compose network — outside, you'd use `http://localhost:4566`.

**`SPRING_PROFILES_ACTIVE=docker`**: activates the `docker` profile in [application.yml](../src/main/resources/application.yml). See [05-environments-and-profiles.md](05-environments-and-profiles.md) for what that profile changes.

**`depends_on` with `condition: service_healthy`**: app waits until both localstack and redis report healthy before starting. Without this, the app would race-start before LocalStack has uploaded sample rules and crash on first rule load. Compose doesn't restart `app` on dep failure — it just delays start.

**`restart: unless-stopped`**: container is restarted on crash or daemon restart, but NOT if you manually `docker compose stop`. This is what you want for a dev stack.

**healthcheck**: same logic as the Dockerfile healthcheck, just declared at the compose layer too. (The compose healthcheck *replaces* the Dockerfile one.)

### Service: `localstack`

```yaml
localstack:
  image: localstack/localstack:2.3
  ports:
    - "127.0.0.1:4566:4566"      # bound to localhost ONLY
  environment:
    - SERVICES=s3
    - DEBUG=1
    - DATA_DIR=/tmp/localstack/data
    - PERSISTENCE=1              # data survives container restart
  volumes:
    - "./init-localstack.sh:/etc/localstack/init/ready.d/init-aws.sh"   # auto-init hook
    - "./sample-rules:/tmp/sample-rules"                                 # mounted into container
    - "localstack-data:/tmp/localstack"                                  # named volume for persistence
  networks: [drools-network]
  restart: unless-stopped
  healthcheck:
    test: ["CMD", "bash", "-c", "awslocal s3 ls s3://local-rules/ --recursive 2>/dev/null | grep -q .drl"]
    interval: 5s
    timeout: 5s
    retries: 30
    start_period: 10s
```

#### Why these specific values

**`localstack/localstack:2.3`**: pinned major version. LocalStack 2.x is the current line; 3.x has API differences. Don't auto-upgrade in production stacks.

**`127.0.0.1:4566:4566` (not just `4566:4566`)**: binds the published port to the loopback interface only. Anyone on your dev LAN can't hit your LocalStack S3. Defense against accidental exposure on hotel/coffee-shop networks.

**`SERVICES=s3`**: LocalStack supports many AWS services; we only spin up S3 to keep startup fast (~5s vs ~30s for the full stack).

**`PERSISTENCE=1` + `DATA_DIR`**: LocalStack persists S3 data across container restarts. Combined with the named volume `localstack-data:/tmp/localstack`, sample rules survive `docker compose restart`. They DON'T survive `docker compose down -v` (the `-v` wipes volumes).

**The init script mount**: `./init-localstack.sh:/etc/localstack/init/ready.d/init-aws.sh` is the magic. LocalStack runs anything in `/etc/localstack/init/ready.d/` once it's ready. So when LocalStack starts, our `init-localstack.sh` automatically:
1. Creates the `local-rules` S3 bucket
2. Syncs all `.drl` files from `/tmp/sample-rules/` (mounted from `./sample-rules/` on the host) into S3
3. Applies a permissive bucket policy

**Healthcheck command**: `awslocal s3 ls s3://local-rules/ --recursive | grep -q .drl`. Returns 0 only when at least one `.drl` file is in the bucket — i.e., **after the init script has run**. This is the gate that the `app` service waits on. Until rules are uploaded, the app stays in `Created` state.

**`retries: 30, interval: 5s`**: gives the init script up to ~150 seconds to complete. In practice it's done in ~10s.

### Service: `redis`

```yaml
redis:
  image: redis:7-alpine
  ports:
    - "127.0.0.1:6379:6379"      # localhost only
  command: redis-server --appendonly yes --requirepass ""
  volumes:
    - redis-data:/data
  networks: [drools-network]
  restart: unless-stopped
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 3s
    retries: 3
```

**`redis:7-alpine`**: Redis 7.x, Alpine base. Smaller than the standard image.

**`--appendonly yes`**: Append-Only File persistence. Each write is logged. Redis recovers on restart. Volume `redis-data:/data` holds the AOF and RDB snapshots.

**`--requirepass ""`**: empty password = no auth. Acceptable in a localhost-only dev stack with the network bound to `127.0.0.1`. **Production must set a password and use TLS** — see CODE_FINDINGS F-???: Redis auth/TLS skipped per user.

**Healthcheck `redis-cli ping`**: simplest possible — Redis responds with `PONG`.

### Networks and volumes

```yaml
networks:
  drools-network:
    driver: bridge

volumes:
  localstack-data:
    driver: local
  redis-data:
    driver: local
```

- `drools-network` is a bridge network created by compose. All 3 services join it. Container-to-container DNS works (`http://localstack:4566`, `redis://redis:6379`).
- Named volumes (`localstack-data`, `redis-data`) live in Docker's volume directory; they're not bind-mounted to the host. Use `docker volume inspect` to find their on-disk path.
- The host bind-mounts (`./heap-dumps`, `./gc-logs`, `./init-localstack.sh`, `./sample-rules`) are **not** named volumes — they map directly to host paths.

---

## How they work together

A new dev clones the repo and runs:
```bash
docker compose up -d --build
```

What happens:

1. **`docker compose up`** parses the compose file, sees three services with healthchecks and `depends_on`.
2. **`localstack`** starts first (no deps). LocalStack boots in ~5–10s. Its readiness hook runs `init-localstack.sh` which creates the bucket and uploads sample rules. Healthcheck transitions to "healthy" once rules exist.
3. **`redis`** starts in parallel with localstack (also no deps). Healthy in ~5s.
4. **`app`** waits until both `localstack` and `redis` are healthy (`condition: service_healthy`). Then it starts the build phase (~2 min on first run; cached after).
5. Built `app` container starts. Connects to `localstack` and `redis` via Docker DNS. Boots the Spring Boot app (~30–45s — the `start-period` of the healthcheck).
6. `app` healthcheck on `/admin/health` flips to "healthy". The stack is ready.

Total cold-start time: ~3–5 minutes (mostly Maven build inside the container).
Warm restart time: ~30–60 seconds.

---

## Common operations

| Task | Command |
|---|---|
| Cold-start the stack | `docker compose up -d --build` |
| Warm restart (no rebuild) | `docker compose restart` |
| Tail app logs | `docker compose logs -f app` |
| Tail all logs | `docker compose logs -f` |
| Stop everything (keep data) | `docker compose down` |
| Stop everything (wipe volumes) | `docker compose down -v` |
| Check service health | `docker compose ps` (look for `(healthy)` next to each) |
| Shell into the app container | `docker compose exec app sh` |
| Re-run rule init manually | `docker compose exec localstack bash /etc/localstack/init/ready.d/init-aws.sh` |
| Force rebuild only the app | `docker compose up -d --build app` |
| View resource usage | `docker stats` |

---

## Customizing for your environment

### Run the app with a different profile

The compose file pins `SPRING_PROFILES_ACTIVE=docker`. To use `dev` instead (e.g., to talk to a host-installed LocalStack rather than the compose one), override:

```bash
SPRING_PROFILES_ACTIVE=dev docker compose up -d
```

But note: the `dev` profile expects LocalStack at `http://localhost:4566` from the *app's* perspective. From inside the `app` container, `localhost` is the container itself, not the host. You'd also have to override `AWS_ENDPOINT` to point at the right place. Easier: use `docker` profile + the default compose stack.

### Enable Redis cache as primary

Currently `LocalLRUCache` is `@Primary`. If you want to test the Redis path:

```bash
# In .env or via -e on docker compose:
REDIS_ENABLED=true   # already true in compose default
```

But this only initializes the Redis bean. To make Redis the primary cache, you need code changes (move `@Primary` from `LocalLRUCache` to `RedisRuleCache`) — see [36-architecture-decision-records.md](36-architecture-decision-records.md) ADR-005.

### Adjust resource limits

Edit `docker-compose.yml` `app.deploy.resources.limits`. After save:

```bash
docker compose up -d --force-recreate app
```

### Mount your own rules instead of `sample-rules/`

```yaml
localstack:
  volumes:
    - "./my-rules:/tmp/sample-rules"   # replace ./sample-rules with your dir
```

Make sure the directory structure follows the rule-ID-to-path convention; see [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).

---

## Things to know

### `JAVA_OPTS` is overridden — Dockerfile values don't apply in compose

The Dockerfile sets one `JAVA_OPTS` (percentage-based heap, lots of optimizer flags). The compose file replaces it entirely with simpler values (explicit `-Xms`/`-Xmx`, plus heap dump and GC logging). If you `docker run` the image directly (not via compose), you get the Dockerfile's flags. Production deployments that don't use compose pick which set to use.

### Restart of `app` does NOT restart LocalStack

Sample rules persist in the named volume across `docker compose restart`. Good for development. But: if you change a `.drl` file on the host, you need to either:
- `docker compose restart app` + call `POST /admin/refresh-rules`, OR
- `docker compose down -v && docker compose up -d` (heavy — wipes everything)

Better workflow: edit the `.drl` on the host, re-run `init-localstack.sh` from outside compose to sync changes, then call `/admin/refresh-rules`.

### The compose healthcheck timing matters

If you add a slow new step to startup (a slow rule, a connection-heavy init), bump `start_period` for the `app` service. Otherwise the orchestrator marks the container unhealthy mid-boot and may try to restart it.

### `setup-dev-environment.sh` does the right things

If you forget any of this, `./setup-dev-environment.sh` runs the complete cold-start with validation. See [02-project-structure.md](02-project-structure.md) Shell Scripts section.

---

## What's not in the compose file (intentional)

- **No Prometheus / Grafana**: out of scope for the dev stack. You can add them locally if you want to visualize metrics from `/actuator/prometheus`.
- **No Nginx/load balancer**: dev only needs one app instance. Production deploys behind ALB.
- **No databases**: this service has no relational DB.
- **No Kibana / ELK**: structured JSON logs go to stdout; pipe through `jq` if you want.
- **No volumes for the app's `./logs/`**: logs go to stdout, captured by Docker's logging driver. Use `docker compose logs` to read them.
