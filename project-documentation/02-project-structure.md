# 02 · Project Structure

| | |
|---|---|
| **Audience** | Developers, AI agents (the navigation map both reach for first) |
| **Purpose** | Annotated tree of every file and folder in the repo, so a reader can find any code, config, or doc by purpose |
| **Last verified against repo** | 2026-05-10 (post-modernization, post-load-test) |
| **Related docs** | [01-project-overview.md](01-project-overview.md), [03-tech-stack.md](03-tech-stack.md), [27-development-setup.md](27-development-setup.md) |

---

## Top-level layout

```
drools-microservice/
├── src/                              # Java source + tests + resources
├── sample-rules/                     # 17 production-ready DRL rules (uploaded to S3 by init script)
├── scripts/                          # Profile-specific run scripts + load-test orchestrator
├── project-documentation/            # ★ THIS DOCUMENTATION CORPUS (NotebookLM target)
├── ai-instructions/                  # AI workflow protocols (excluded from doc corpus)
├── .ai-workspace/                    # AI planning artifacts (excluded from doc corpus)
├── .claude/                          # Claude Code session config (excluded)
├── .vscode/                          # IDE settings (excluded)
├── gc-logs/                          # Mounted GC log dir for container (runtime data)
├── heap-dumps/                       # Mounted heap dump dir (runtime data)
├── target/                           # Maven build output (gitignored)
│
├── pom.xml                           # Maven build configuration (deps, plugins, enforcer)
├── Dockerfile                        # Multi-stage Docker build (Maven → Corretto Alpine)
├── docker-compose.yml                # 3-service dev stack: app + LocalStack + Redis
├── .dockerignore                     # Files excluded from Docker build context
│
├── README.md                         # User-facing repo intro
├── CLAUDE.md                         # Claude Code working instructions for this repo
├── docker-validation.md              # Docker validation checklist
├── full-docker-test-plan.md          # 30-step Docker integration test plan
│
├── set-java-env.sh                   # Source to set JAVA_HOME=Java 25 (macOS)
├── setup-dev-environment.sh          # One-command full dev stack bootstrap
├── init-localstack.sh                # Auto-runs in LocalStack container; uploads sample-rules to S3
├── scripts/test-localstack.sh        # Validates LocalStack S3 setup
├── scripts/docker-build-test.sh     # Build + isolated container health-check test (port 9080/9081)
│
├── .env / .env.example               # Local env var defaults (gitignored / committed template)
├── .gitignore
└── app.log / setup.log               # Local runtime logs (gitignored)
```

---

## `src/` — application source

### `src/main/java/com/company/drools/`

The Java root. 57 files organized into 5 functional packages plus the `Application.java` entrypoint.

```
com/company/drools/
├── Application.java                  # @SpringBootApplication entry point
│
├── api/                              # HTTP-facing layer (controllers, DTOs, filters, validation)
│   ├── controller/
│   │   ├── RuleExecutionController.java   # POST /execute-rule (the only public business endpoint)
│   │   ├── AdminController.java           # /admin/health, /admin/rules, /admin/refresh-rules,
│   │   │                                  # /admin/info, /admin/thread-pools
│   │   └── MemoryController.java          # /admin/memory/info, /admin/memory/gc, /admin/memory/snapshot
│   │
│   ├── dto/
│   │   ├── RuleExecutionRequest.java      # @JsonProperty("rule_id") → ruleId  (the snake_case mapping)
│   │   ├── RuleExecutionResponse.java
│   │   ├── ErrorResponse.java
│   │   ├── HealthCheckResponse.java
│   │   ├── RuleListResponse.java          # GET /admin/rules wrapper
│   │   ├── RefreshRulesResponse.java
│   │   └── RefreshRuleResponse.java
│   │
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java    # @ControllerAdvice — maps every exception to HTTP status
│   │   ├── RuleNotFoundException.java     # → 404
│   │   ├── RuleExecutionException.java    # → 400
│   │   ├── TimeoutException.java          # → 408
│   │   ├── CircuitBreakerException.java   # → 503
│   │   └── RuleStorageException.java      # wraps S3/filesystem storage failures → 500
│   │
│   ├── filter/                        # Spring servlet filters (@Order matters)
│   │   ├── SecurityHeadersFilter.java     # @Order(-1) — adds 7 response headers
│   │   ├── AdminAuthFilter.java           # @Order(0)  — X-Admin-API-Key on /admin/*
│   │   ├── RateLimitingFilter.java        # @Order(1)  — multi-tier client ID
│   │   └── RequestSizeValidationFilter.java # no @Order — runs last; chunked-stream limiter
│   │
│   └── validation/                    # Jakarta Validation custom annotations
│       ├── ValidRuleId.java               # annotation
│       ├── RuleIdValidator.java           # impl: regex + length + path-traversal
│       ├── ValidRuleData.java             # annotation
│       └── RuleDataValidator.java         # impl: field count + per-field type/size/pattern
│
├── core/                              # Business logic — the rule engine itself
│   ├── engine/
│   │   ├── DroolsEngineService.java       # Central rule-execution service. Holds a single long-lived
│   │   │                                  # KieContainer updated in place via Drools 10's
│   │   │                                  # KieContainer.updateToVersion(ReleaseId). ReentrantReadWriteLock
│   │   │                                  # + explicit KieRepository.removeKieModule cleanup. See ADR-003
│   │   │                                  # 2026-05-10 update.
│   │   ├── RuleCompiler.java              # Compiles .drl → returns versioned ReleaseId (KieModule auto-
│   │   │                                  # registered in KieRepository). Calls DrlSanitizer first.
│   │   ├── RuleExecutor.java              # Async fireAllRules(maxRuleFirings=10000) with timeout cancel.
│   │   └── DrlSanitizer.java              # ★ Sandbox: import allowlist + class/method blocklist + eval() block
│   │
│   └── model/
│       ├── Rule.java                      # Rule entity (id, content, package, status, version)
│       └── RuleMetadata.java              # Execution stats, last-modified, status; incremental averaging
│
├── storage/                           # Storage abstraction
│   ├── RuleStorage.java                   # Interface: getRule, getAllRules, etc.
│   ├── S3RuleStorage.java                 # AWS S3 backend; Resilience4j circuit breaker; SCAN-based listing
│   ├── LocalFileStorage.java              # Filesystem backend; normalize() + startsWith() path safety
│   ├── InMemoryRuleStorage.java           # Test/dev backend with hardcoded sample rules
│   ├── InMemoryRuleStorageAdapter.java    # Adapter exposing InMemoryRuleStorage via RuleStorage iface
│   ├── RedisCachedRuleStorage.java        # @ConditionalOnProperty(redis.enabled=true) — read-through + write-through decorator on RuleStorage (ADR-016)
│   └── StorageFactory.java                # Selects backend by RULE_SOURCE; wraps in RedisCachedRuleStorage when REDIS_ENABLED=true
│
├── cache/                             # Pub/sub fan-out for cross-instance refresh (ADR-016)
│   ├── RefreshEvent.java                  # Wire format: {event_type, rule_id, source_instance_id, timestamp}
│   ├── RuleRefreshPublisher.java          # @ConditionalOnExpression — emits to drools:rule:events
│   └── RuleRefreshSubscriber.java         # MessageListener — self-dedups via source_instance_id, dispatches to engine
│
├── common/                            # Shared utilities
│   └── LogSanitizer.java                  # Masks sensitive data in logs (CC, SSN, tokens, etc.)
│
└── config/                            # 16 Spring @Configuration classes
    ├── DroolsConfig.java                  # KieServices/KieContainer beans
    ├── S3Config.java                      # AWS S3 client + connection pool + endpoint validation
    ├── RedisConfig.java                   # Lettuce ConnectionFactory + RedisTemplate<String,Rule> + RedisMessageListenerContainer (when enabled)
    ├── InstanceIdConfig.java              # UUID-per-process bean used by RuleRefreshPublisher/Subscriber for self-dedup
    ├── CircuitBreakerConfig.java          # Resilience4j: s3CircuitBreaker, redisCircuitBreaker
    ├── ThreadPoolConfig.java              # Custom executors: ruleExecutionExecutor, storageExecutor
    ├── TimeoutConfig.java                 # Centralized timeout values
    ├── RequestTimeoutConfig.java          # HTTP request timeouts
    ├── RateLimitingConfig.java            # Limit values + InMemoryRateLimitingService bean
    ├── ValidationConfig.java              # Validation limit values (rule ID len, data fields, etc.)
    ├── CorsConfig.java                    # CORS rules (empty default, wildcard in dev profiles)
    ├── LoggingConfig.java                 # MDC correlation IDs, request URI, user agent
    ├── MetricsConfig.java                 # Micrometer registry + custom metrics
    ├── DotenvConfig.java                  # Loads .env file (addLast — env vars override)
    ├── RuleLoadingConfig.java             # Startup rule load + auto-refresh scheduler
    ├── RuleStorageConfig.java             # Storage bean wiring
    └── StorageConfig.java                 # Generic storage props (paths, etc.)
```

### `src/main/resources/`

```
src/main/resources/
├── application.yml                    # Spring config (default + 4 profile overrides: local/dev/prod/docker)
├── logback-spring.xml                 # Logback config; structured JSON + correlation IDs in non-local
└── META-INF/                          # Generated: spring.factories, etc.
```

The `application.yml` is the most-referenced config file in the codebase. See [09-environment-variables-reference.md](09-environment-variables-reference.md) for an exhaustive enumeration of every env var that overrides values in this file.

### `src/test/java/com/company/drools/`

45 test files, 597 tests passing (1 pre-existing testcontainers env error, unrelated). Coverage roughly preserved from the 96.2% / 89.7% pre-modernization baseline.

```
src/test/java/com/company/drools/
├── BaseUnitTest.java                  # Mockito + MeterRegistry mock baseline
├── BaseIntegrationTest.java           # Testcontainers: LocalStack + Redis fixtures
│
├── api/
│   ├── controller/                    # AdminControllerTest, RuleExecutionControllerTest, MemoryControllerTest, TestValidationConfig
│   ├── dto/                           # DtoTest
│   ├── exception/                     # GlobalExceptionHandlerTest, ExceptionTest
│   ├── filter/                        # AdminAuthFilterTest, RateLimitingFilterTest, RequestSizeValidationFilterTest, SecurityHeadersFilterTest
│   └── validation/                    # RuleIdValidatorTest, RuleDataValidatorTest
│
├── core/
│   ├── engine/                        # DroolsEngineServiceTest, RuleCompilerTest, RuleExecutorTest, DrlSanitizerTest (23 cases — proves the sandbox)
│   └── model/                         # RuleTest, RuleMetadataTest
│
├── storage/                           # S3RuleStorageTest, LocalFileStorageTest, InMemoryRuleStorageTest, StorageFactoryTest, RedisCachedRuleStorageTest
├── cache/                             # RefreshEventTest, RuleRefreshPublisherTest, RuleRefreshSubscriberTest
├── common/                            # LogSanitizerTest
├── config/                            # 11 config tests — one per @Configuration class
│
├── integration/                       # End-to-end with Testcontainers (all excluded from default mvn test — see pom.xml surefire <excludes>; require host-side Docker)
│   ├── RuleExecutionIntegrationTest.java
│   ├── RuleRefreshIntegrationTest.java
│   ├── S3StorageIntegrationTest.java
│   ├── RedisCachedStorageIntegrationTest.java
│   └── RedisPubSubIntegrationTest.java
│
└── testutil/                          # RuleTestUtils, ValidationConfigTestHelper
```

The tests are the **most accurate behavior spec** in the project. When docs disagree with tests, the tests are right. See [28-testing-guide.md](28-testing-guide.md) for the test catalog and how to add new tests.

---

## `sample-rules/` — production-ready example DRLs

17 working rules used in the dev stack (auto-uploaded to LocalStack S3 by `init-localstack.sh`). The original 10 were the seed cookbook; 7 more were added 2026-05-10 to demonstrate Drools patterns previously not in the cookbook (`accumulate`, `exists`, `not`, `salience`, compound `&&`/`||`, temporal date math, `forall`).

```
sample-rules/
├── README.md                          # Human-readable rule catalog
│
├── pricing/
│   ├── discount/
│   │   ├── simple.drl                 # 10% off orders ≥ $50
│   │   ├── vip.drl                    # 20% off for VIP customers
│   │   ├── bulk.drl                   # 15% off for quantity ≥ 10
│   │   └── first-time.drl             # 5% off first-time customers
│   │
│   ├── shipping/
│   │   ├── standard.drl               # $5.99/$9.99/$15.99 by weight
│   │   └── express.drl                # $12.99/$19.99/$29.99 by weight, free over $100
│   │
│   ├── bundle/
│   │   └── accumulate.drl             # ⚡ accumulate: 10% bundle discount when summed item prices > $100
│   │
│   └── loyalty/
│       └── salience.drl               # ⚡ salience 100: loyalty member 15% override
│
├── seasonal/
│   ├── holiday/
│   │   ├── discount.drl               # 12% off when isHolidaySeason=true
│   │   └── blackfriday.drl            # 25% off with promotionCode=BLACK2024
│   │
│   └── expiry/
│       └── temporal.drl               # ⚡ date comparison: promo ACTIVE/EXPIRED via java.time.LocalDate
│
├── inventory/
│   └── warning/
│       └── exists.drl                 # ⚡ exists: low-stock warning if any item has stockLevel < 5
│
└── validation/
    ├── customer/
    │   ├── age.drl                    # Reject under-18; tag age groups
    │   └── credit.drl                 # Tiered approval (Excellent/Good/Fair/Poor)
    │
    ├── cart/
    │   ├── notempty.drl               # ⚡ not-pattern: reject when items list missing or empty
    │   └── forall.drl                 # ⚡ forall: every cart item must have stockLevel > 0
    │
    └── email/
        └── compound.drl               # ⚡ compound &&/|| LHS with `matches` regex (substituted from
                                       #    eval() because DrlSanitizer blocks the eval keyword)
```

(⚡ = added in the 2026-05-10 cookbook expansion.)

Verified curl examples for all 17 rules: [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md).

The path → rule ID transformation: `pricing/discount/vip.drl` ↔ `pricing.discount.vip`. See [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).

---

## Build, deploy, and tooling files

| File | Purpose |
|---|---|
| `pom.xml` | Maven build. Spring Boot 3.5.3, Drools 10.2.0, Java 25 enforced. Plugins: enforcer, spotless (Google Java Format), JaCoCo, SpotBugs, surefire, spring-boot-maven-plugin. |
| `Dockerfile` | Multi-stage: `maven:3.9-eclipse-temurin-25` → `amazoncorretto:25-alpine-jdk`. Non-root `appuser`. JAVA_OPTS pre-set with G1GC + container support + Drools properties. |
| `docker-compose.yml` | 3-service dev stack: `app` (build from Dockerfile), `localstack` (2.3, S3 only), `redis` (7-alpine). Healthchecks on all three. Networks: `drools-network`. Volumes: `localstack-data`, `redis-data`, plus host mounts for heap dumps and GC logs. |
| `.dockerignore` | Excludes target/, .git, *.log, IDE files, etc. from Docker build context. |
| `.env` / `.env.example` | Local env var template. `.env` is gitignored (real secrets); `.env.example` is committed (defaults). |

---

## Shell scripts (run from repo root)

| Script | What it does | When to run |
|---|---|---|
| `set-java-env.sh` | `source` to set `JAVA_HOME` and `PATH` to Java 25 (macOS via `/usr/libexec/java_home -v 25`). | Before running `mvn` or local `java` commands. |
| `setup-dev-environment.sh` | Full bootstrap: Maven build → Docker build → `docker-compose up -d` → wait for healthy → run `scripts/test-localstack.sh`. Flags: `--skip-build`, `--skip-tests`, `--force-rebuild`. | First time on a clean clone. |
| `init-localstack.sh` | Runs **inside** the LocalStack container as a ready-hook (mounted to `/etc/localstack/init/ready.d/init-aws.sh`). Creates `local-rules` bucket, syncs `sample-rules/` into it, applies a permissive Principal:`*` policy (LocalStack-guarded). | Automatic — fires when LocalStack starts. |

| Script under `scripts/` (validation helpers) | What it does | When to run |
|---|---|---|
| `scripts/test-localstack.sh` | 5 sequential checks against LocalStack: connectivity, bucket exists, rules uploaded, rule content valid, optional rule execution. | After LocalStack is up; manually or via setup script. |
| `scripts/docker-build-test.sh` | Isolated build + container test on ports 9080/9081 (avoids conflict with the compose stack). Uses `RULE_SOURCE=memory` so no S3/Redis needed. | CI-style validation that the Docker image works. |

| Script under `scripts/` | What it does |
|---|---|
| `scripts/start-development.sh` | Starts the app with the `dev` Spring profile. |
| `scripts/start-production.sh` | Starts the app with the `prod` Spring profile. Tighter timeouts, tighter circuit breakers, larger thread pools. |
| `scripts/run-load-test.sh` | One-command load + stress + soak test orchestrator (Phases 0–8). See [39-load-test-findings.md](39-load-test-findings.md) and `scripts/README.md`. |
| `scripts/lib/*.sh` | Reusable helpers (preflight checks, stack lifecycle, corpus generator, memory poller, refresh loops, heap-dump capture). |
| `scripts/jmeter/execute-only.jmx` | Single parameterized JMeter plan reused across all load-test phases (rps_per_min/duration_s/threads/rule_ids_csv/jtl_path via `-J`). |
| `scripts/docker-compose.loadtest.yml` | Compose override that disables the rate limiter when running the load harness from a single client IP. |

---

## Documentation files (this corpus)

`project-documentation/` contains the 39-doc corpus (40 files including this folder's own `README.md`). The numbering is a NotebookLM ordering signal.

```
project-documentation/
├── 00-system-overview.md              # Visual entry; written last
├── 01-project-overview.md             # ← you are here (one before)
├── 02-project-structure.md            # ← you are here
├── 03-tech-stack.md
├── 04-architecture.md
├── 05-environments-and-profiles.md
├── 06-deployment.md
├── 07-docker-and-compose.md
├── 08-configuration.md
├── 09-environment-variables-reference.md
├── 10-api-reference.md
├── 11-integration-guide.md
├── 12-error-code-catalog.md
├── 13-rate-limiting-and-throttling.md
├── 14-security-architecture.md
├── 15-admin-authentication.md
├── 16-drl-sandboxing.md
├── 17-rule-development.md
├── 18-rule-id-and-storage-layout.md
├── 19-sample-rules-cookbook.md
├── 20-rule-generation-prompt.md
├── 21-rule-generation-prompt-enhanced.md
├── 22-rule-generation-prompt-concise.md
├── 23-rule-language-reference.md
├── 24-jvm-optimization.md
├── 25-memory-monitoring-guide.md
├── 26-performance-tuning-runbook.md
├── 27-development-setup.md
├── 28-testing-guide.md
├── 29-circuit-breakers-and-resilience.md
├── 30-runbooks-and-monitoring.md
├── 31-troubleshooting.md
├── 32-getting-started.md
├── 33-simple-start.md
├── 34-java-setup-guide.md
├── 35-faq.md
├── 36-architecture-decision-records.md
├── 37-glossary.md
├── 38-for-ai-agents.md                # Verification rules + pitfalls for AI sessions
├── 39-load-test-findings.md           # 2026-05-10 load test verdict + production-planning guidance
├── README.md                          # Index of this corpus
└── api-reference/
    └── openapi.yml                    # Moved from repo root; canonical API spec
```

The numbering implies category groupings — see [01-project-overview.md](01-project-overview.md#how-to-use-this-corpus) for the audience-specific reading paths.

---

## Excluded from the documentation corpus

The following directories exist in the repo but are **intentionally excluded** from this documentation corpus (they are tooling, debugging, or AI-workflow artifacts, not part of the project's runtime functionality):

| Path | What it is | Why excluded |
|---|---|---|
| `ai-instructions/` | AI workflow protocols (`snap-memory`, `ai-context-update`, etc.) | Meta-tooling for AI agents working on the repo, not part of project functionality |
| `.ai-workspace/` | AI planning artifacts (this overhaul's plan/checklist/findings) | Workspace, not deliverable docs |
| `.claude/` | Claude Code session config (commands, settings) | IDE-equivalent config |
| `.vscode/` | VS Code editor settings | IDE config |
| `gc-logs/` | Mounted runtime GC log directory | Runtime data |
| `heap-dumps/` | Mounted heap dump directory (populated on OOM) | Runtime data |
| `target/` | Maven build output | Generated artifacts |

If you are an AI agent considering whether to read those folders: don't, unless the user explicitly asks. They are not source-of-truth for project behavior.

---

## Where to find what (clickable index)

| If you want... | Click here |
|---|---|
| Spring Boot entry point | [Application.java](../src/main/java/com/company/drools/Application.java) |
| Public business endpoint | [RuleExecutionController.java](../src/main/java/com/company/drools/api/controller/RuleExecutionController.java) |
| Admin endpoints | [AdminController.java](../src/main/java/com/company/drools/api/controller/AdminController.java) |
| Memory diagnostics endpoints | [MemoryController.java](../src/main/java/com/company/drools/api/controller/MemoryController.java) |
| Request DTO with `@JsonProperty("rule_id")` | [RuleExecutionRequest.java](../src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java) |
| All exception handlers (HTTP status mapping) | [GlobalExceptionHandler.java](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) |
| Security headers filter | [SecurityHeadersFilter.java](../src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java) |
| Admin API key filter | [AdminAuthFilter.java](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) |
| Rate limiting filter (multi-tier client ID) | [RateLimitingFilter.java](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) |
| Request size validation filter | [RequestSizeValidationFilter.java](../src/main/java/com/company/drools/api/filter/RequestSizeValidationFilter.java) |
| Custom rule-ID validator | [RuleIdValidator.java](../src/main/java/com/company/drools/api/validation/RuleIdValidator.java) |
| Custom rule-data validator | [RuleDataValidator.java](../src/main/java/com/company/drools/api/validation/RuleDataValidator.java) |
| Central rule-execution service | [DroolsEngineService.java](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) |
| DRL compiler | [RuleCompiler.java](../src/main/java/com/company/drools/core/engine/RuleCompiler.java) |
| Rule executor (timeout + cancel) | [RuleExecutor.java](../src/main/java/com/company/drools/core/engine/RuleExecutor.java) |
| **DRL sandbox** (allowlist + blocklist) | [DrlSanitizer.java](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) |
| Storage interface | [RuleStorage.java](../src/main/java/com/company/drools/storage/RuleStorage.java) |
| S3 backend (with circuit breaker) | [S3RuleStorage.java](../src/main/java/com/company/drools/storage/S3RuleStorage.java) |
| Local file backend | [LocalFileStorage.java](../src/main/java/com/company/drools/storage/LocalFileStorage.java) |
| In-memory backend | [InMemoryRuleStorage.java](../src/main/java/com/company/drools/storage/InMemoryRuleStorage.java) |
| Storage backend selector | [StorageFactory.java](../src/main/java/com/company/drools/storage/StorageFactory.java) |
| Redis cache decorator (read-through + write-through) | [RedisCachedRuleStorage.java](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) |
| Refresh pub/sub publisher | [RuleRefreshPublisher.java](../src/main/java/com/company/drools/cache/RuleRefreshPublisher.java) |
| Refresh pub/sub subscriber | [RuleRefreshSubscriber.java](../src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java) |
| Refresh event wire format | [RefreshEvent.java](../src/main/java/com/company/drools/cache/RefreshEvent.java) |
| Log sanitizer | [LogSanitizer.java](../src/main/java/com/company/drools/common/LogSanitizer.java) |
| Drools KIE beans | [DroolsConfig.java](../src/main/java/com/company/drools/config/DroolsConfig.java) |
| S3 client + endpoint validation | [S3Config.java](../src/main/java/com/company/drools/config/S3Config.java) |
| Circuit breakers | [CircuitBreakerConfig.java](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java) |
| Thread pools | [ThreadPoolConfig.java](../src/main/java/com/company/drools/config/ThreadPoolConfig.java) |
| Rate limit config | [RateLimitingConfig.java](../src/main/java/com/company/drools/config/RateLimitingConfig.java) |
| Validation limits | [ValidationConfig.java](../src/main/java/com/company/drools/config/ValidationConfig.java) |
| CORS config | [CorsConfig.java](../src/main/java/com/company/drools/config/CorsConfig.java) |
| Correlation ID + MDC | [LoggingConfig.java](../src/main/java/com/company/drools/config/LoggingConfig.java) |
| Default config | [application.yml](../src/main/resources/application.yml) |
| Profile overrides | [application.yml lines 165–350](../src/main/resources/application.yml#L165-L350) |
| Build config | [pom.xml](../pom.xml) |
| Container build | [Dockerfile](../Dockerfile) |
| Dev compose stack | [docker-compose.yml](../docker-compose.yml) |
| Sample rules (17) | [`sample-rules/`](../sample-rules/) |
| Unit test base | [BaseUnitTest.java](../src/test/java/com/company/drools/BaseUnitTest.java) |
| Integration test base (Testcontainers) | [BaseIntegrationTest.java](../src/test/java/com/company/drools/BaseIntegrationTest.java) |
| DRL sandbox tests (23 cases) | [DrlSanitizerTest.java](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) |
| Rate limiter tests | [RateLimitingFilterTest.java](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java) |
| API spec (OpenAPI 3.0) | [api-reference/openapi.yml](api-reference/openapi.yml) |
