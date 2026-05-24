# 27 · Development Setup

| | |
|---|---|
| **Audience** | New contributors, engineers extending the service |
| **Purpose** | Clone-to-running-tests in under 30 minutes. Plus the conventions and patterns this codebase follows so contributions are consistent. |
| **Last verified against** | [`pom.xml`](../pom.xml), [`Dockerfile`](../Dockerfile), [`setup-dev-environment.sh`](../setup-dev-environment.sh) on 2026-05-10 |
| **Related docs** | [03-tech-stack.md](03-tech-stack.md), [28-testing-guide.md](28-testing-guide.md), [32-getting-started.md](32-getting-started.md), [34-java-setup-guide.md](34-java-setup-guide.md) |

---

## Prerequisites

| Tool | Required version | Why | How to verify |
|---|---|---|---|
| **Java** | 25 (exactly — enforced) | Spring Boot 3.5 + Drools 10 baseline | `java -version` should show `25.x.x` |
| **Maven** | 3.8+ | Build, dependency resolution, plugin enforcement | `mvn -version` |
| **Docker** | 20+ | Local stack via docker-compose | `docker info` |
| **Git** | any recent | Source control | — |
| **Optional**: AWS CLI | any | Manually inspect LocalStack S3 bucket during dev | `aws --version` |
| **Optional**: `jq` | any | Parse JSON in shell pipes | `jq --version` |

The build will **fail** with a clear error if Java 25 isn't active. See [34-java-setup-guide.md](34-java-setup-guide.md) for the install guide.

---

## Two paths: Docker-only or local Java

**Path A — Docker only** (recommended for first-time):
- All builds and runs happen inside containers.
- No local Java/Maven needed.
- Slow first build (Maven downloads dependencies inside the container, ~5 min).

**Path B — local Java + Docker for dependencies**:
- You run `mvn` and `java` locally.
- LocalStack and Redis run in containers.
- Faster iteration once set up. Recommended for active development.

The rest of this doc covers Path B. For Path A, see [32-getting-started.md](32-getting-started.md).

---

## Setup (Path B)

### 1. Clone

```bash
git clone <repo-url>
cd drools-microservice
```

### 2. Java environment

```bash
# Set JAVA_HOME to Java 25 for the current shell:
source ./set-java-env.sh

# Verify:
java -version           # → openjdk 25.x.x
mvn -version | grep Java # → Java version: 25.x.x
```

The `set-java-env.sh` is macOS-specific (uses `/usr/libexec/java_home`). Linux users: install OpenJDK 25 via your package manager and set `JAVA_HOME` directly. See [34-java-setup-guide.md](34-java-setup-guide.md).

### 3. Build the project

```bash
mvn clean compile         # compile only
# OR
mvn clean package         # compile + run all 548 unit tests + package jar (14 Testcontainers integration tests surefire-excluded; run on Linux CI)
# OR
mvn clean package -DskipTests   # if you want to skip tests
```

First build takes ~3-5 minutes (Maven downloads ~250 MB of dependencies). Subsequent builds are ~30 seconds.

### 4. Start dependencies (LocalStack + Redis)

```bash
docker compose up -d localstack redis
```

Wait ~10-15 seconds for LocalStack to initialize. Verify:
```bash
docker compose ps
# Both should show (healthy)

# LocalStack S3 should have rules from sample-rules/ uploaded
docker compose exec localstack awslocal s3 ls s3://local-rules/ --recursive
# Should show 10 .drl files
```

### 5. Run the application locally

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

App starts in ~10-15 seconds. Verify:
```bash
curl http://localhost:8080/admin/health | jq '.status'
# → "UP"
```

### 6. Test a rule

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq
# → result.amount: 90.0, result.discount: 10.0
```

You're set up.

---

## Build & test commands cheat sheet

```bash
# Compile
mvn compile

# Compile + tests
mvn test

# Compile + tests + package fat jar
mvn package

# Run a single test class
mvn test -Dtest=DroolsEngineServiceTest

# Run a single test method
mvn test -Dtest=DroolsEngineServiceTest#testRuleExecution

# Coverage report
mvn test jacoco:report
# Output: target/site/jacoco/index.html

# Format code (required before committing)
mvn spotless:apply

# Check formatting (CI gate)
mvn spotless:check

# Static analysis
mvn spotbugs:check

# Skip tests (for fast iteration)
mvn package -DskipTests

# Just the dependency tree
mvn dependency:tree

# Just integration tests (Testcontainers — slower)
mvn test -Dtest=*IntegrationTest

# Run as packaged jar (no Maven runtime)
mvn package -DskipTests
java -jar target/drools-rule-engine-1.0.0.jar --spring.profiles.active=dev
```

---

## Maven plugins enforced

These plugins run as part of the build pipeline. Awareness of what they enforce avoids surprises.

| Plugin | Phase | What it does | Failure mode |
|---|---|---|---|
| **maven-enforcer-plugin** 3.6.2 | `validate` | Requires Java 25 (`[25,26)`) and Maven 3.8+ | Build aborts with clear error if Java mismatch |
| **maven-compiler-plugin** 3.11.0 | `compile` | Source/target = 17, `parameters: true` (preserves param names) | Standard compile errors |
| **spring-boot-maven-plugin** 3.5.3 | `package` | Repackages the jar as a Spring Boot fat jar; excludes Lombok | If executable jar isn't produced, this is the cause |
| **spotless-maven-plugin** 2.36.0 | `verify` (when `spotless:check`) | Code formatting via Google Java Format 1.17.0; removes unused imports; trims trailing whitespace | `mvn spotless:check` fails if any file is unformatted |
| **jacoco-maven-plugin** 0.8.8 | `test` | Records coverage; outputs `target/site/jacoco/` | None (no threshold gate currently — see CODE_FINDINGS F-029) |
| **spotbugs-maven-plugin** 4.7.3.0 | `verify` (when `spotbugs:check`) | Static analysis at "Max" effort, "High" threshold | Reports bugs but doesn't fail unless you run `:check` |
| **maven-surefire-plugin** 3.0.0 | `test` | Runs `**/*Test.java`, `**/*Tests.java` | Test failure aborts build |

> **Pre-commit habit**: run `mvn spotless:apply` before every commit. Otherwise CI fails on `spotless:check`. Wire it into a git pre-commit hook if you want.

---

## Coding conventions

The repo follows a consistent set of patterns. New code should match.

### 1. Package layout follows the [02-project-structure.md](02-project-structure.md) tree

Don't add classes outside the existing top-level packages (`api/`, `core/`, `storage/`, `cache/`, `common/`, `config/`). New top-level packages need a justification.

### 2. Lock disciplines

| Where | Lock | Why |
|---|---|---|
| [`DroolsEngineService`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) | `ReentrantReadWriteLock` | Many concurrent reads (rule execution); rare writes (refresh) |
| [`RedisCachedRuleStorage`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) | Lock-free | All mutation goes through Redis + delegate; circuit breaker handles failure |
| [`RateLimitingConfig.InMemoryRateLimitingService`](../src/main/java/com/company/drools/config/RateLimitingConfig.java) | `ConcurrentHashMap` + `AtomicLong` | Lock-free hot path |

If you add a new shared mutable structure, document its lock discipline in javadoc.

### 3. Filter ordering

Filters use `@Order(N)`:
- `-1` = SecurityHeadersFilter (runs first)
- `0` = AdminAuthFilter
- `1` = RateLimitingFilter
- (no @Order) = RequestSizeValidationFilter (runs last)

When adding a new filter, set `@Order` explicitly if the position matters.

### 4. DTO conventions

- JSON field names use **snake_case** via `@JsonProperty("rule_id")` on the camelCase Java field (`ruleId`).
- Jakarta validation annotations (`@NotNull`, `@Size`) are applied to fields. Custom validators (`@ValidRuleId`, `@ValidRuleData`) handle domain-specific rules.

### 5. Exception conventions

- Throw a custom exception (`RuleNotFoundException`, `TimeoutException`, `CircuitBreakerException`) for known failure modes.
- `GlobalExceptionHandler` maps each exception type to a specific HTTP status + error code.
- Don't `throw new RuntimeException(...)` from controllers — use a typed exception.

See [12-error-code-catalog.md](12-error-code-catalog.md) for the full mapping.

### 6. Configuration via `@Value` + env vars

```java
@Value("${drools.foo.bar:defaultValue}")
private String fooBar;
```

Always provide a default. Defaults in code should match the `application.yml` defaults. (Mismatches caught: see CODE_FINDINGS F-031 for the lesson.)

### 7. Logging

- Use `org.slf4j.LoggerFactory.getLogger(MyClass.class)` — not `System.out.println`.
- Always sanitize user-provided data before logging via [`LogSanitizer`](../src/main/java/com/company/drools/common/LogSanitizer.java).
- Use MDC for correlation IDs — see [`LoggingConfig`](../src/main/java/com/company/drools/config/LoggingConfig.java).
- Levels: `INFO` for state changes, `WARN` for unexpected but recoverable, `ERROR` for unrecoverable.

### 8. Testing patterns

See [28-testing-guide.md](28-testing-guide.md) for the full guide. Quick rules:
- Unit tests extend `BaseUnitTest` (Mockito + mocked `MeterRegistry`).
- Integration tests extend `BaseIntegrationTest` (Testcontainers + `@SpringBootTest`).
- Test class name: `<ClassUnderTest>Test.java`.
- Place tests in the matching package under `src/test/java/`.

---

## Common dev tasks

### Add a new config property

1. Add to [`application.yml`](../src/main/resources/application.yml) with a default value:
   ```yaml
   drools:
     myfeature:
       enabled: ${DROOLS_MYFEATURE_ENABLED:false}
   ```
2. Read in your config class:
   ```java
   @Value("${drools.myfeature.enabled:false}")
   private boolean myFeatureEnabled;
   ```
3. Document in [09-environment-variables-reference.md](09-environment-variables-reference.md).
4. Add to relevant Spring profile blocks if it should override per-environment.

### Add a new controller endpoint

1. Create handler method in [`AdminController`](../src/main/java/com/company/drools/api/controller/AdminController.java) or [`RuleExecutionController`](../src/main/java/com/company/drools/api/controller/RuleExecutionController.java) (don't add new controllers without good reason).
2. Use `@GetMapping` / `@PostMapping`.
3. Apply `@Valid` if there's a request body.
4. Throw typed exceptions for failure paths; don't return raw `ResponseEntity` for errors.
5. Add to [10-api-reference.md](10-api-reference.md).
6. Add a test in `api/controller/` mirroring the controller name.

### Add a new metric

1. Inject `MeterRegistry`:
   ```java
   private final MeterRegistry meterRegistry;
   ```
2. Use `Counter`, `Timer`, or `Gauge`. Tag with `endpoint`, `status`, `rule_id` (using `"unknown"` for missing — see ADR or [04-architecture.md](04-architecture.md) for cardinality rationale).
3. The `TaggedCircuitBreakerMetrics` already wires breaker state into Micrometer.
4. Verify via `GET /actuator/metrics/{your.metric.name}` (port 8081).

### Add a new storage backend

1. Implement [`RuleStorage`](../src/main/java/com/company/drools/storage/RuleStorage.java) interface.
2. Annotate the bean with a Spring name (`@Component("myBackend")`).
3. Add a `case` in [`StorageFactory`](../src/main/java/com/company/drools/storage/StorageFactory.java).
4. Document the `RULE_SOURCE` value in [09-environment-variables-reference.md](09-environment-variables-reference.md) and [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).
5. Implement path-traversal protection in your backend (defense in depth).

### Add a custom validation annotation

1. Create the annotation: `@Target({FIELD,PARAMETER}) @Retention(RUNTIME) @Constraint(validatedBy = MyValidator.class)`.
2. Implement `ConstraintValidator<MyAnnotation, TargetType>`.
3. Apply to the DTO field.
4. Test the violation path.

---

## Branch and commit conventions

Conventional Commits:
```
feat(api): add /admin/memory/snapshot endpoint
fix(storage): handle S3 NoSuchKeyException in getAllRules
docs(architecture): correct security header values
chore(deps): bump testcontainers to 1.19.7
test(filter): add rate limiter X-Forwarded-For test
refactor(cache): extract RedisCachedRuleStorage decorator
```

PR checklist before merging:
- [ ] `mvn spotless:apply` ran
- [ ] `mvn test` passes (all 597+ tests)
- [ ] `mvn spotbugs:check` clean (or new warnings explained)
- [ ] If env var added: documented in [09-environment-variables-reference.md](09-environment-variables-reference.md)
- [ ] If endpoint added: documented in [10-api-reference.md](10-api-reference.md)
- [ ] If error code added: documented in [12-error-code-catalog.md](12-error-code-catalog.md)
- [ ] If behavior changed materially: tests reflect the change
- [ ] If a doc claim is now wrong: fix the doc

---

## Run the full pre-commit pipeline locally

```bash
# What CI will check:
mvn clean spotless:check verify spotbugs:check
```

If any step fails locally, fix and re-run before pushing.

---

## Common gotchas

| Gotcha | Symptom | Fix |
|---|---|---|
| Java version wrong | `mvn` fails on enforcer plugin | `source ./set-java-env.sh` |
| `mvn spotless:apply` not run | CI fails on `spotless:check` | Run it before commit |
| LocalStack not started | `RuleNotFoundException` for sample rules | `docker compose up -d localstack`; wait 10s |
| Stale Maven cache | Weird transitive dependency errors | `rm -rf ~/.m2/repository/com/company` |
| Port 8080/8081/4566/6379 in use | Boot fails with `Port already in use` | `lsof -i :8080` to find culprit, kill it |
| Tests pass locally, fail in CI | Probably timezone or locale | Set `-Duser.timezone=UTC` in test config |
| Spring profile not active | Wrong env vars used | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` |

---

## IDE setup

### IntelliJ IDEA (recommended)

1. Open project → "Open as Maven Project"
2. **Project SDK**: 17 (set in File → Project Structure)
3. **Language Level**: 17
4. Install the **Lombok** plugin (project uses Lombok extensively for `@Slf4j`, `@Data`, `@Builder`)
5. Enable annotation processing: Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable
6. Install the **google-java-format** plugin and configure:
   - Settings → google-java-format → "Enable" + "Google style" — wait, the project uses google-java-format 1.17.0 via Spotless. Match the version.
7. Run config: "Spring Boot" template, profile = `local` or `dev`.

### VS Code

1. Install the **Extension Pack for Java**
2. Install the **Spring Boot Extension Pack**
3. Install the **Lombok Annotations Support** extension
4. Open the repo folder. The Java extension auto-detects Maven.

---

## Done when

You can:
- Build: `mvn package`
- Run tests: `mvn test` (all 597 pass)
- Run service: `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- Hit `/execute-rule` and get a result
- Format code: `mvn spotless:apply`

If any of these fail with the documented setup, see [31-troubleshooting.md](31-troubleshooting.md) or check this doc's "Common gotchas" section.
