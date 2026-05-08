# 03 · Technology Stack

| | |
|---|---|
| **Audience** | Developers, architects |
| **Purpose** | Every technology used, with version, purpose, and rationale. The single source of truth for "what version of X are we on?" |
| **Last verified against** | [pom.xml](../pom.xml), [Dockerfile](../Dockerfile) on 2026-05-08 |
| **Related docs** | [02-project-structure.md](02-project-structure.md), [27-development-setup.md](27-development-setup.md), [36-architecture-decision-records.md](36-architecture-decision-records.md) |

---

## At a glance

| Layer | Choice | Version |
|---|---|---|
| Language | Java (LTS) | **17** *(enforced)* |
| Application framework | Spring Boot | **3.2.5** |
| Rule engine | Drools | **8.44.0.Final** |
| HTTP server | Embedded Tomcat (via Spring Boot) | bundled with 3.2.5 |
| Build tool | Apache Maven | **3.9+** |
| Container base (build) | `maven:3.9-eclipse-temurin-17` | — |
| Container base (runtime) | `amazoncorretto:17-alpine-jdk` | — |
| AWS SDK | `software.amazon.awssdk` v2 | **2.20.56** |
| Cache library | Spring Data Redis (Lettuce) | bundled with Spring Boot |
| Resilience | Resilience4j | **2.2.0** |
| Metrics | Micrometer (CloudWatch registry) | **1.12.4** |
| Logging | Logback + logstash-logback-encoder | encoder **7.4** |
| Validation | Jakarta Bean Validation (Hibernate Validator) | bundled with Spring Boot |
| Env loading | dotenv-java | **3.0.0** |
| Code style | Spotless + Google Java Format | spotless **2.36.0**, GJF **1.17.0** |
| Coverage | JaCoCo | **0.8.8** |
| Static analysis | SpotBugs | **4.7.3.0** |
| Build enforcement | Maven Enforcer Plugin | **3.3.0** |
| Test framework | JUnit 5 (Jupiter) | bundled with `spring-boot-starter-test` |
| Mocking | Mockito | bundled |
| Integration tests | Testcontainers + LocalStack | **1.19.7** |
| Async polling | Awaitility | bundled (test scope) |

---

## Runtime

### Java 17 (LTS)

**Why this version, why enforced:**
- Spring Boot 3.x requires Java 17 minimum. Drools 8.44.0 is tested against Java 17.
- Java 17 is the current LTS with broad JDK vendor support and a security patch lifeline through 2029.
- Java 21 is intentionally not adopted yet — the ecosystem (notably Drools, some Resilience4j internals) hasn't been verified for compatibility, and the user has noted this as a future task.

**Enforcement** (in [pom.xml](../pom.xml) — Maven Enforcer Plugin):
```xml
<requireJavaVersion>
  <version>[17,18)</version>
  <message>❌ Java 17 is required!</message>
</requireJavaVersion>
```
Builds with the wrong Java version fail at the `validate` phase with a clear error. There is no fallback path — this is by design.

Local setup helper: [`set-java-env.sh`](../set-java-env.sh) (macOS); detailed guide in [34-java-setup-guide.md](34-java-setup-guide.md).

### Container runtime: Amazon Corretto 17 Alpine

**Why this image:**
- Alpine base = ~180 MB; the multi-stage build produces a final image of ~347 MB.
- Amazon Corretto is AWS's hardened JDK distribution; receives security patches in lockstep with OpenJDK and is well-tested in AWS environments where this service is deployed.
- Alpine's musl libc is acceptable here because we have no native dependencies that conflict with musl. (The JDK bundles its own libraries.)

The Maven build stage uses `maven:3.9-eclipse-temurin-17` (separate JDK distribution) — the build-time JDK doesn't need to match the runtime JDK as long as both are 17. See [Dockerfile](../Dockerfile) for the multi-stage layout.

---

## Application framework

### Spring Boot 3.2.5

**Why this version:**
- Spring Boot 3.x is the current major line (Jakarta EE namespace, Java 17 baseline).
- 3.2.5 is a stable patch release used in production. Newer 3.2.x patches are compatible upgrades.
- 3.3.x and 3.4.x updates are deferred pending compatibility check with Resilience4j 2.2.0 and Drools 8.44.0.

**Starters used** (transitively pulls dependencies; see `pom.xml` lines 56–70):
- `spring-boot-starter-web` — Spring MVC, embedded Tomcat, Jackson
- `spring-boot-starter-actuator` — `/actuator/*` endpoints (port 8081)
- `spring-boot-starter-validation` — Jakarta Bean Validation, Hibernate Validator
- `spring-boot-starter-data-redis` — Lettuce client, Spring Data Redis (currently dormant; see ADR-005)

### Embedded Tomcat (via Spring Boot)

Tomcat is bundled by Spring Boot. No separate Tomcat install is needed. Configured via `application.yml`:
- `server.port: 8080` (main API)
- `management.server.port: 8081` (Actuator)
- `server.shutdown: graceful`
- Multipart limits, max swallow size, etc.

We did **not** swap to Undertow or Jetty — Tomcat is well-tested with our load profile and security filters.

---

## Rule engine

### Drools 8.44.0.Final

**Why this version, why traditional syntax:**
- 8.44.0 is the latest 8.x release in the Drools 8 line at time of project start. The 8.x line continues to support traditional DRL syntax (Map-based pattern matching) alongside the modern rule-unit/OOPath syntax.
- This project uses **only the traditional subset** — see [ADR-001](36-architecture-decision-records.md) for the reasoning. Short version: traditional DRL has a smaller learning curve, integrates cleanly with JSON→Map conversion, and the modern features (rule units, DataStream) don't add value for synchronous request/response rule execution.
- Drools 9.x is intentionally not adopted — different release cadence, breaking changes possible.

**Modules pulled** (`pom.xml` lines 72–89):
- `drools-core` — engine runtime
- `drools-compiler` — `.drl` parser and compiler
- `drools-mvel` — MVEL expression language support (some Drools internals require it)

> Note on MVEL: rule files in this project do not use MVEL dialect (everything is Java dialect). MVEL is pulled in because Drools internally uses it for certain expression evaluation paths.

---

## Storage layer

### AWS SDK v2 (2.20.56)

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

### Spring Data Redis + Lettuce

**Why Redis exists in this codebase**: The architecture has slots for distributed caching. The Redis bean is wired (`RedisRuleCache`), but `LocalLRUCache` is `@Primary`. **Redis is currently dormant** — see [ADR-005](36-architecture-decision-records.md). It's there so we can flip it on without restructuring code.

**Why Lettuce over Jedis**:
- Spring Boot 3 default
- Async API, connection-multiplexing, supports Redis Cluster cleanly
- Healthier maintenance posture than Jedis

When Redis is enabled (`REDIS_ENABLED=true`):
- `RedisRuleCache` registers; reads/writes wrapped in Resilience4j circuit breaker
- Lookups use SCAN (cursor-based) rather than KEYS (blocking) — see [ADR-???](36-architecture-decision-records.md) (forthcoming)

### LocalLRUCache (in-memory)

**Why custom (not Caffeine, not Guava):**
- Simple LinkedHashMap with `accessOrder=true` and `removeEldestEntry` override is enough for this scale (100–1000 entries).
- We need only get/put/clear/stats — Caffeine's full feature set is overkill.
- Custom code lets us see exactly the lock semantics ([ADR-004](36-architecture-decision-records.md): write-lock on get).

---

## Resilience and observability

### Resilience4j 2.2.0

**Why Resilience4j over Hystrix**:
- Hystrix is in maintenance mode at Netflix.
- Resilience4j is functional, lightweight, integrates with Spring Boot 3 and Micrometer.

**Modules** (`pom.xml` lines 152–169):
- `resilience4j-spring-boot3` — Spring Boot integration (`@CircuitBreaker` etc., though we use the programmatic API instead)
- `resilience4j-circuitbreaker` — core
- `resilience4j-micrometer` — emits CB state as Micrometer metrics

Configured in `CircuitBreakerConfig.java`. Two breakers: `s3CircuitBreaker`, `redisCircuitBreaker`. Stricter thresholds in prod (40%/50% failure) than dev (60%/70%). Full details in [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

### Micrometer 1.12.4

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
| **maven-enforcer-plugin** | 3.3.0 | Java 17 (`[17,18)`), Maven 3.8+. Hard-fails the build on violation. |
| **maven-compiler-plugin** | 3.11.0 | Source/target = 17, `parameters: true` (preserves method param names). |
| **spring-boot-maven-plugin** | 3.2.5 | `repackage` goal — produces fat jar with embedded Tomcat. Excludes Lombok from the runtime jar. |
| **spotless-maven-plugin** | 2.36.0 | Code formatting via Google Java Format 1.17.0. Run `mvn spotless:apply` before commit; `mvn spotless:check` is recommended in CI. Also: removes unused imports, trims trailing whitespace. |
| **jacoco-maven-plugin** | 0.8.8 | Coverage instrumentation (`prepare-agent`) and report (`report` in test phase). Output: `target/site/jacoco/`. **No threshold configured** (would be a useful addition — see [`CODE_FINDINGS.md`](../.ai-workspace/CODE_FINDINGS.md) F-029). |
| **spotbugs-maven-plugin** | 4.7.3.0 | Static analysis at "Max" effort, "High" threshold. Run with `mvn spotbugs:check`. Recommended in CI. |
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

### Testcontainers 1.19.7 + LocalStack

For integration tests that need real AWS S3 behavior. The `localstack` Testcontainers module spins up a fresh LocalStack container per test class.

Used in:
- `S3StorageIntegrationTest` — verifies `S3RuleStorage` against real S3 API
- `RuleExecutionIntegrationTest` — full request → S3 → KieBase → response flow

### Awaitility

Fluent async assertions (`await().atMost(...).until(...)`). Used sparingly — most tests are synchronous.

---

## What's NOT in the stack (intentional omissions)

| Tech we don't use | Why |
|---|---|
| Spring Security | Lightweight `AdminAuthFilter` instead — see [ADR-006](36-architecture-decision-records.md). API gateway is primary auth; this is defense in depth. |
| Hibernate / JPA | No relational database. Rules in S3, cache in memory/Redis. |
| GraphQL | REST is sufficient. The API is small. |
| Kotlin / Scala | Java 17 only. Single-language codebase. |
| Reactive (WebFlux, Reactor) | Synchronous request/response is sufficient. Async only inside the rule executor (`CompletableFuture` for timeout cancellation). |
| Spring Cloud | Single-service, not microservices-ecosystem deployment. No service registry / config server. |
| Terraform / Pulumi / CDK | No IaC in repo. AWS deployment is documented as a reference architecture in [06-deployment.md](06-deployment.md) but provisioning is owned by the deploying team. |
| Caffeine cache | Custom LocalLRUCache is sufficient — see ADR-004. |
| Hystrix | Resilience4j replaces it. |
| Lombok in production code | Pulled as `provided` scope only; runtime annotations like `@Slf4j` use Lombok at compile time. The runtime jar excludes Lombok. |
| Apache HttpClient v5 (raw) | Used transitively via AWS SDK v2 `apache-client`. Not used directly. |

---

## Upgrade considerations

If you're considering version bumps:

| Component | Risk | Notes |
|---|---|---|
| Java 17 → 21 | Medium | User-deferred. Verify Drools 8.44.0 + Resilience4j 2.2.0 against 21 first. Update `Dockerfile` base image, `set-java-env.sh`, `pom.xml` enforcer rule. |
| Spring Boot 3.2.5 → 3.3/3.4 | Low | Patch releases generally drop in cleanly. Verify Actuator endpoint structure if external monitoring depends on it. |
| Drools 8.44.0 → 8.x latest | Low–Medium | Drools 8.x line is stable. Test sample rules + DrlSanitizer against the new version. |
| Drools 8 → 9 | High | Major upgrade. Different runtime model. Out of scope for this project. |
| AWS SDK 2.20.56 → 2.x latest | Low | v2 patch releases are backward compatible. |
| Resilience4j 2.2.0 → 2.x latest | Low | Verify `@CircuitBreaker` annotation usage if we adopt it later. |
| Lettuce / Jedis swap | Medium | Only if Redis becomes hot path. Stay with Lettuce. |

---

## Where this is set / verified

- **Versions**: [`pom.xml`](../pom.xml) lines 16–27 (`<properties>`), then per-dependency overrides
- **Java 17 enforcement**: [`pom.xml`](../pom.xml) Maven Enforcer Plugin section
- **Container Java**: [`Dockerfile`](../Dockerfile) `FROM amazoncorretto:17-alpine-jdk`
- **Build Java**: [`Dockerfile`](../Dockerfile) `FROM maven:3.9-eclipse-temurin-17 AS build`
- **JVM tuning**: `Dockerfile` `ENV JAVA_OPTS=...` and `docker-compose.yml` env override
