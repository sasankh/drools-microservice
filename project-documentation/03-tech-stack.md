# 03 · Technology Stack

| | |
|---|---|
| **Audience** | Developers, architects |
| **Purpose** | Every technology used, with version, purpose, and rationale. The single source of truth for "what version of X are we on?" |
| **Last verified against** | [pom.xml](../pom.xml), [Dockerfile](../Dockerfile) on 2026-05-24 |
| **Related docs** | [02-project-structure.md](02-project-structure.md), [27-development-setup.md](27-development-setup.md), [36-architecture-decision-records.md](36-architecture-decision-records.md) |

---

## At a glance

| Layer | Choice | Version |
|---|---|---|
| Language | Java (LTS) | **25** *(enforced)* |
| Application framework | Spring Boot | **3.5.3** |
| Rule engine | Drools | **10.2.0** |
| HTTP server | Embedded Tomcat (via Spring Boot) | bundled with 3.5.3 |
| Build tool | Apache Maven | **3.9+** |
| Container base (build) | `maven:3.9-eclipse-temurin-25` | — |
| Container base (runtime) | `amazoncorretto:25-alpine-jdk` | — |
| AWS SDK | `software.amazon.awssdk` v2 | **2.34.0** |
| Cache library | Spring Data Redis (Lettuce) | bundled with Spring Boot |
| Resilience | Resilience4j | **2.3.0** |
| Metrics | Micrometer (CloudWatch registry) | **1.14.7** |
| Logging | Logback + logstash-logback-encoder | encoder **7.4** |
| Validation | Jakarta Bean Validation (Hibernate Validator) | bundled with Spring Boot |
| Env loading | dotenv-java | **3.0.0** |
| Code style | Spotless + Google Java Format | spotless **2.44.5**, GJF **1.27.0** |
| Coverage | JaCoCo | **0.8.13** |
| Static analysis | SpotBugs | **4.9.3.0** |
| Build enforcement | Maven Enforcer Plugin | **3.6.2** |
| Test framework | JUnit 5 (Jupiter) | bundled with `spring-boot-starter-test` |
| Mocking | Mockito | bundled |
| Integration tests | Testcontainers + LocalStack | **1.21.3** |
| Async polling | Awaitility | bundled (test scope) |

---

## Runtime

### Java 25 (LTS)

**Why this version, why enforced:**
- Java 25 is the current latest LTS (released September 2025); Premier support runs through September 2030.
- Spring Boot 3.5.3 baselines on Java 17, but runs cleanly on Java 25 (forward-compatible). Drools 10 baselines on Java 17 and likewise runs on 25.
- Bumping to 25 (vs staying on the older 17 LTS) was done as part of the 2026-05-09 stack modernization. See [ADR-013](36-architecture-decision-records.md#adr-013-java-17--25--spring-boot-modernization-2026-05-09).

**Enforcement** (in [pom.xml](../pom.xml) — Maven Enforcer Plugin):
```xml
<requireJavaVersion>
  <version>[25,26)</version>
  <message>❌ Java 25 is required!</message>
</requireJavaVersion>
```
Builds with the wrong Java version fail at the `validate` phase with a clear error. There is no fallback path — this is by design.

Local setup helper: [`set-java-env.sh`](../set-java-env.sh) (macOS); detailed guide in [34-java-setup-guide.md](34-java-setup-guide.md).

### Container runtime: Amazon Corretto 25 Alpine

**Why this image:**
- Alpine base = ~180 MB; the multi-stage build produces a final image of ~347 MB.
- Amazon Corretto is AWS's hardened JDK distribution; receives security patches in lockstep with OpenJDK and is well-tested in AWS environments where this service is deployed.
- Alpine's musl libc is acceptable here because we have no native dependencies that conflict with musl. (The JDK bundles its own libraries.)

The Maven build stage uses `maven:3.9-eclipse-temurin-25` (separate JDK distribution) — the build-time JDK doesn't need to match the runtime JDK as long as both are 25. See [Dockerfile](../Dockerfile) for the multi-stage layout.

---

## Application framework

### Spring Boot 3.5.3

**Why this version:**
- Spring Boot 3.5.x is the current latest 3.x line (Jakarta EE namespace, Java 17 baseline).
- 3.5.3 is the latest stable patch release as of 2026-05-09. Spring Boot 4.x is on the horizon but not yet GA.
- The bump from 3.2.5 → 3.5.3 was part of the 2026-05-09 stack modernization; see [ADR-013](36-architecture-decision-records.md#adr-013-java-17--25--spring-boot-modernization-2026-05-09).

**Starters used** (transitively pulls dependencies; see `pom.xml` lines 56–70):
- `spring-boot-starter-web` — Spring MVC, embedded Tomcat, Jackson
- `spring-boot-starter-actuator` — `/actuator/*` endpoints (port 8081)
- `spring-boot-starter-validation` — Jakarta Bean Validation, Hibernate Validator
- `spring-boot-starter-data-redis` — Lettuce client, Spring Data Redis. Active when `REDIS_ENABLED=true` via `RedisCachedRuleStorage` decorator + `RuleRefreshPublisher`/`Subscriber` pub/sub fan-out; see [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20). (ADR-005's "dormant Redis bean" pattern was superseded 2026-05-20.)

### Embedded Tomcat (via Spring Boot)

Tomcat is bundled by Spring Boot. No separate Tomcat install is needed. Configured via `application.yml`:
- `server.port: 8080` (main API)
- `management.server.port: 8081` (Actuator)
- `server.shutdown: graceful`
- Multipart limits, max swallow size, etc.

We did **not** swap to Undertow or Jetty — Tomcat is well-tested with our load profile and security filters.

---

## Rule engine

### Drools 10.2.0

**Why this version, why traditional syntax:**
- Drools 10.x is the current latest major. Drools 10 baselines on JDK 17+, deprecates `drools-engine-classic` and `drools-mvel` in favor of the single `drools-engine` aggregator (executable model by default).
- Per the [Drools 10 migration guide](https://kie.apache.org/docs/10.0.x/drools/drools/migration-guide/index.html): *"All APIs and DRL syntax are compatible"* between Drools 8 and 10. Traditional KieServices/KieContainer/KieBase/KieSession is "still supported but discouraged" — meaning it works without code rewrites.
- This project uses **only the traditional subset** — see [ADR-001](36-architecture-decision-records.md). Traditional DRL has a smaller learning curve, integrates cleanly with JSON→Map conversion, and the modern Rule Units / OOPath features don't add value for synchronous request/response rule execution.
- The bump from 8.44.0.Final → 10.2.0 was part of the 2026-05-09 stack modernization; see [ADR-014](36-architecture-decision-records.md#adr-014-drools-8--10-migration-2026-05-09).

**Modules pulled** (`pom.xml`):
- `drools-engine` — the new aggregator that replaces `drools-core` + `drools-compiler` + `drools-engine-classic` (the executable model is the new default)
- `drools-mvel` — MVEL dialect runtime; still required because traditional DRL `then` blocks default to MVEL semantics. Officially deprecated by the Drools team but still published in 10.2.0.

> Note on MVEL: rule files in this project use the default dialect, which is MVEL semantics inside `then` blocks. The `drools-mvel` artifact provides the runtime support; without it, rule compilation fails with `MissingDependencyException`.

---

## Storage layer

### AWS SDK v2 (2.34.0)

**Why v2 over v1:**
- AWS SDK v2 is the current generation: smaller dependency footprint, async-first, modular (only pull `s3`, not the whole `aws-sdk-bundle`).
- v1 is in maintenance mode.

**Modules used** (`pom.xml` lines 91–105):
- `aws-core` — base SDK
- `s3` — S3 client
- `apache-client` — Apache HTTP client backend (`Apache5HttpClient`)

We chose Apache HTTP over Netty (the v2 default) because:
- Apache HTTP integrates well with the connection-pool tuning the rest of the stack uses
- It's blocking — fits cleanly in our synchronous filter chain
- Lower memory overhead than Netty for our connection volumes

**S3 client tuning** (`application.yml` `aws.s3.connection-pool.*`):
- `max-connections=50` (default), `100` in prod profile
- `connection-timeout=10s`
- `socket-timeout=60s` (30s in dev/docker, 60s in prod)

---

## Cache layer

### Spring Data Redis + Lettuce — decorator + pub/sub (since 2026-05-20)

**Why Redis exists in this codebase**: Multi-instance deployments need a shared rule cache and cross-instance refresh coherence. `RedisCachedRuleStorage` is a read-through + write-through decorator on `RuleStorage`. `RuleRefreshPublisher`/`Subscriber` provide refresh fan-out on `drools:rule:events`. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20).

**Why Lettuce over Jedis**:
- Spring Boot 3 default
- Async API, connection-multiplexing, supports Redis Cluster cleanly
- Healthier maintenance posture than Jedis

When Redis is enabled (`REDIS_ENABLED=true`):
- `RedisCachedRuleStorage` wraps the base storage in `StorageFactory`; all `getRule` / `getAllRules` / `saveRule` / `deleteRule` calls go through it
- All Redis ops — including `SCAN` (wrapped 2026-05-24 after a Phase 9.4 audit caught it bypassing the breaker) — are wrapped in `redisCircuitBreaker`; failures fall through to base storage with no user-visible error
- Bulk path uses `SCAN(prefix*)` + `MGET` (cursor-based, non-blocking) — never `KEYS`
- DRL JSON is serialized via Jackson `GenericJackson2JsonRedisSerializer` with `BasicPolymorphicTypeValidator` (strict allow-list)
- Lettuce command timeout is `REDIS_TIMEOUT` (default `500ms`); sits cleanly below the Redis CB's `slowCallDurationThreshold=2s` so command timeouts unambiguously count as CB failures (not slow calls)
- `RedisMessageListenerContainer` (pub/sub) uses an explicit `FixedBackOff(2s, ∞)` recovery policy so a dropped subscription deterministically re-subscribes within ≤2s of Redis becoming reachable

The legacy `LocalLRUCache` + `RedisRuleCache` layer was deleted on 2026-05-20 — it was dead code (`RuleCache.get()` was never called from production paths).

---

## Resilience and observability

### Resilience4j 2.3.0

**Why Resilience4j over Hystrix**:
- Hystrix is in maintenance mode at Netflix.
- Resilience4j is functional, lightweight, integrates with Spring Boot 3 and Micrometer.

**Modules** (`pom.xml` lines 152–169):
- `resilience4j-spring-boot3` — Spring Boot integration (`@CircuitBreaker` etc., though we use the programmatic API instead)
- `resilience4j-circuitbreaker` — core
- `resilience4j-micrometer` — emits CB state as Micrometer metrics

Configured in `CircuitBreakerConfig.java`. Two breakers: `s3CircuitBreaker`, `redisCircuitBreaker`. Stricter thresholds in prod (40%/50% failure) than dev (60%/70%). Full details in [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

### Micrometer 1.14.7

**Why Micrometer:**
- Vendor-agnostic: same metric calls support CloudWatch, Prometheus, Datadog, Grafana.
- Spring Boot Actuator integration is built in.

**Registries pulled**:
- `micrometer-core` — base
- `micrometer-registry-cloudwatch2` — for AWS deployment

Prometheus registry is **not** explicitly pulled; if needed, add `micrometer-registry-prometheus` and Spring Boot will expose `/actuator/prometheus` automatically.

### Logback + logstash-logback-encoder 7.4

**Why this combo:**
- Logback is Spring Boot's default logging backend; no swap needed.
- `logstash-logback-encoder` produces structured JSON logs — required for log aggregation in CloudWatch, ELK, Datadog.

In `local` profile, plain text format. In all other profiles, JSON. See `logback-spring.xml` and the profile blocks in `application.yml`.

MDC (Mapped Diagnostic Context) carries correlation IDs through the request lifecycle. See `LoggingConfig.java`.

---

## Validation

### Jakarta Bean Validation (Hibernate Validator)

Bundled with `spring-boot-starter-validation`. Used through:
- Standard annotations: `@NotNull`, `@Size`, `@Pattern`
- Custom annotations: `@ValidRuleId`, `@ValidRuleData` — see [04-architecture.md](04-architecture.md) Input Validation Architecture section, and [16-drl-sandboxing.md](16-drl-sandboxing.md) for what content the validators reject.

Triggered via `@Valid` in controller signatures. Validation failures bubble up as `MethodArgumentNotValidException` and are handled by `GlobalExceptionHandler` → HTTP 400.

---

## Environment loading

### dotenv-java 3.0.0

**Why**: Lets developers keep secrets in a `.env` file (gitignored) without polluting their shell environment. Spring Boot does not natively read `.env`.

**Important**: We use `addLast` (not `addFirst`) when wiring dotenv into Spring's PropertySource list. This means **real environment variables override `.env`** — important for production where the `.env` file shouldn't accidentally suppress a real env var. See `DotenvConfig.java`.

---

## Build tooling (Maven plugins)

| Plugin | Version | What it enforces |
|---|---:|---|
| **maven-enforcer-plugin** | 3.6.2 | Java 25 (`[25,26)`), Maven 3.8+. Hard-fails the build on violation. |
| **maven-compiler-plugin** | 3.15.0 | Source/target = 25, `parameters: true` (preserves method param names). |
| **spring-boot-maven-plugin** | 3.5.3 | `repackage` goal — produces fat jar with embedded Tomcat. Excludes Lombok from the runtime jar. |
| **spotless-maven-plugin** | 2.44.5 | Code formatting via Google Java Format 1.27.0. Run `mvn spotless:apply` before commit; `mvn spotless:check` is recommended in CI. Also: removes unused imports, trims trailing whitespace. |
| **jacoco-maven-plugin** | 0.8.13 | Coverage instrumentation (`prepare-agent`) and report (`report` in test phase). Output: `target/site/jacoco/`. **No threshold configured** (would be a useful addition — see [`CODE_FINDINGS.md`](../.ai-workspace/documentations/CODE_FINDINGS.md) F-029). |
| **spotbugs-maven-plugin** | 4.9.3.0 | Static analysis at "Max" effort, "High" threshold. Run with `mvn spotbugs:check`. Recommended in CI. |
| **maven-surefire-plugin** | 3.0.0 | Runs unit tests in the `test` phase. Default include patterns: `**/*Test.java`, `**/*Tests.java`. |

---

## Testing stack

### JUnit 5 (Jupiter)

Pulled via `spring-boot-starter-test`. We use:
- `@Test`, `@ParameterizedTest`, `@Nested`
- `@BeforeEach`, `@AfterEach`
- AssertJ assertions (`assertThat()`)
- Mockito mocks (`@Mock`, `@InjectMocks`)

Base test classes: `BaseUnitTest` (Mockito + mocked `MeterRegistry`) and `BaseIntegrationTest` (Testcontainers fixtures).

### Testcontainers 1.21.3 + LocalStack

For integration tests that need real AWS S3 behavior. The `localstack` Testcontainers module spins up a fresh LocalStack container per test class.

Used in (5 integration test classes total — all excluded from default `mvn test` via `pom.xml` surefire `excludes`; run on Linux CI):
- `S3StorageIntegrationTest` — verifies `S3RuleStorage` against real S3 API (LocalStack)
- `RuleExecutionIntegrationTest` — full request → S3 → KieBase → response flow
- `RuleRefreshIntegrationTest` — refresh path with Redis populate
- `RedisCachedStorageIntegrationTest` — read-through cache + circuit-breaker fallback against real Redis
- `RedisPubSubIntegrationTest` — pub/sub fan-out across two simulated instances

### Awaitility

Fluent async assertions (`await().atMost(...).until(...)`). Used sparingly — most tests are synchronous.

---

## What's NOT in the stack (intentional omissions)

| Tech we don't use | Why |
|---|---|
| Spring Security | Lightweight `AdminAuthFilter` instead — see [ADR-006](36-architecture-decision-records.md). API gateway is primary auth; this is defense in depth. |
| Hibernate / JPA | No relational database. Rules in S3, cache in memory/Redis. |
| GraphQL | REST is sufficient. The API is small. |
| Kotlin / Scala | Java 25 only. Single-language codebase. |
| Reactive (WebFlux, Reactor) | Synchronous request/response is sufficient. Async only inside the rule executor (`CompletableFuture` for timeout cancellation). |
| Spring Cloud | Single-service, not microservices-ecosystem deployment. No service registry / config server. |
| Terraform / Pulumi / CDK | No IaC in repo. AWS deployment is documented as a reference architecture in [06-deployment.md](06-deployment.md) but provisioning is owned by the deploying team. |
| Caffeine cache | No per-instance cache layer in scope — shared cache lives in Redis via `RedisCachedRuleStorage`. Execution reads from the compiled `kieContainer`, not a key-value cache. |
| Hystrix | Resilience4j replaces it. |
| Lombok in production code | Pulled as `provided` scope only; runtime annotations like `@Slf4j` use Lombok at compile time. The runtime jar excludes Lombok. |
| Apache HttpClient v5 (raw) | Used transitively via AWS SDK v2 `apache-client`. Not used directly. |

---

## Upgrade considerations

If you're considering version bumps:

| Component | Risk | Notes |
|---|---|---|
| Java 25 → 26 | TBD | Java 26 expected ~Sept 2026. Verify Drools 10.x + Spring Boot 3.5.x compatibility before bumping. |
| Spring Boot 3.5.3 → 4.x | Medium | Spring Boot 4.x is approaching GA. Major version with possible API changes. Wait for ecosystem to stabilize. |
| Drools 10.2.0 → 10.x latest | Low | Patch releases within 10.x are backward compatible. |
| Drools 10 → 11 | High | Hypothetical future major. Different runtime model expected. Out of scope. |
| AWS SDK 2.34.0 → 2.x latest | Low | v2 patch releases are backward compatible. |
| Resilience4j 2.3.0 → 2.x latest | Low | Verify `@CircuitBreaker` annotation usage if we adopt it later. |
| Lettuce / Jedis swap | Medium | Only if Redis becomes hot path. Stay with Lettuce. |
| Adopt Drools Rule Units / OOPath | High | Would change `DroolsEngineService` orchestration. Currently rejected by ADR-001. |

---

## Where this is set / verified

- **Versions**: [`pom.xml`](../pom.xml) lines 16–28 (`<properties>`), then per-dependency overrides
- **Java 25 enforcement**: [`pom.xml`](../pom.xml) Maven Enforcer Plugin section
- **Container Java**: [`Dockerfile`](../Dockerfile) `FROM amazoncorretto:25-alpine-jdk`
- **Build Java**: [`Dockerfile`](../Dockerfile) `FROM maven:3.9-eclipse-temurin-25 AS build`
- **JVM tuning**: `Dockerfile` `ENV JAVA_OPTS=...` and `docker-compose.yml` env override
