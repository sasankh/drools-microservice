# Documentation Plan — Per-Doc Specifications

**34 documents total**: 8 keep, 5 revise, 21 new.

Audience tags: **DEV** = developer, **ARCH** = architect, **OPS** = operator, **PARTNER** = external integrator, **AI** = AI agent (NotebookLM/Claude Code/Codex).

Status: **K** = Keep existing, **R** = Revise existing, **N** = New.

Length estimate: rough markdown line count.

---

## CATEGORY 0 — Visual Overview

### 00-system-overview.md  ·  N  ·  ~150 lines
**Audience**: ALL (entry point)
**Scope**: The single starting point for the entire corpus. ASCII system diagram, audience-specific reading paths, navigation index to all 33 other docs.
**Sections**:
1. One-paragraph project description
2. ASCII system diagram (client → filters → controller → service → cache → storage)
3. "Where to start" by audience: quickstart for devs / architecture path for architects / runbooks for operators / API+integration for partners / glossary for AI
4. Full doc index grouped by category, with one-line description per doc
5. External resources (root README, OpenAPI spec, sample-rules/)
**Source files cited**: All 33 other docs as cross-links.
**Dependencies**: Written **last**, after all other docs are stable.
**Done when**: A reader unfamiliar with the project can identify which 3 docs to read for their role within 30 seconds.

---

## CATEGORY 1 — Foundation & Architecture

### 01-project-overview.md  ·  N  ·  ~250 lines
**Audience**: ALL
**Scope**: What the service is, why it exists, business value, key capabilities, performance targets, current status.
**Sections**:
1. Project identity (name, version 1.0.0, license MIT)
2. Business problem solved (externalize business logic from app code; non-engineers can edit rules)
3. Key capabilities (rule execution via REST, S3 rule storage, multi-tier caching, hot reload, security hardening)
4. Performance targets vs achieved (100–1000 RPS target; 1.3s startup; 1–40ms cached execution)
5. Production readiness statement (39/42 security findings closed, 589 tests, 96.2% coverage)
6. Non-goals (what this service is NOT — not a workflow engine, not an event broker)
7. Out-of-scope features (rule versioning, A/B testing — deferred)
**Source files cited**: `pom.xml`, `README.md`, `CLAUDE.md`, `application.yml`.
**Dependencies**: None.
**Done when**: A non-technical reader understands what the project is in under 5 minutes.

### 02-project-structure.md  ·  N  ·  ~300 lines
**Audience**: DEV, AI
**Scope**: Annotated repo directory tree. The map AI agents and new devs reach for first to navigate the codebase.
**Sections**:
1. Top-level layout (one line per top-level entry)
2. `src/main/java/com/company/drools/` package tree with one-line descriptions per package and key classes
3. `src/main/resources/` (application.yml, logback-spring.xml)
4. `src/test/java/` test layout (44 files, package-by-package)
5. `sample-rules/` rule files
6. Shell scripts catalog (`set-java-env.sh`, `setup-dev-environment.sh`, `init-localstack.sh`, `test-localstack.sh`, `docker-build-test.sh`)
7. Docker artifacts (`Dockerfile`, `docker-compose.yml`)
8. Excluded directories (`ai-instructions/`, `.ai-workspace/`, etc.) with one-line "what this is"
**Source files cited**: Every top-level path; key files within each package.
**Dependencies**: None.
**Done when**: A new dev opens this and finds any file in the repo within 60 seconds.

### 03-tech-stack.md  ·  N  ·  ~250 lines
**Audience**: DEV, ARCH
**Scope**: Every technology used, with version, purpose, and rationale.
**Sections**:
1. Runtime: Java 17 LTS (enforced by Maven Enforcer)
2. Framework: Spring Boot 3.2.5 — why this version
3. Rule engine: Drools 8.44.0.Final + drools-core/compiler/mvel — why traditional syntax
4. Storage: AWS SDK v2 (2.20.56) + Apache HTTP client — why v2 vs v1
5. Cache: Spring Data Redis + Lettuce
6. Resilience: Resilience4j 2.2.0 (circuit-breaker + spring-boot3 + micrometer)
7. Metrics: Micrometer 1.12.4 + CloudWatch registry
8. Logging: Logback + logstash-logback-encoder 7.4 (structured JSON)
9. Validation: Jakarta Validation
10. Container: Amazon Corretto 17 Alpine + multi-stage Maven build
11. Dev tooling: Spotless (Google Java Format), JaCoCo, SpotBugs, Maven Enforcer
12. Test: JUnit 5, Mockito, AssertJ, Testcontainers (LocalStack), Awaitility
13. Env loading: dotenv-java 3.0.0
**Source files cited**: `pom.xml` (every dependency block), `Dockerfile`.
**Dependencies**: None.
**Done when**: A reader can answer "what version of X are we on?" for every component in under 10 seconds.

### 04-architecture.md  ·  R  ·  current 1540 lines → target ~1700 lines
**Audience**: ARCH, DEV, AI
**Scope**: Comprehensive system architecture: layered design, request flows, threading model, security architecture, performance architecture.
**Drift fixes (must-fix)**:
- Replace every `ruleId` (camelCase) with `rule_id` (snake_case) in JSON examples — actual is `@JsonProperty("rule_id")` per [RuleExecutionRequest.java:12](src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java#L12).
- Update security header values to actual code: X-XSS-Protection: 0, Cache-Control: no-store, CSP: `default-src 'none'; frame-ancestors 'none'` per [SecurityHeadersFilter.java:21-27](src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java#L21-L27).
- Clarify port architecture: `/admin/*` runs on port 8080 (NOT a separate port). Only Spring Actuator is on 8081.
- Add: filter chain order is `-1, 0, 1, [unordered]` — `RequestSizeValidationFilter` has no `@Order`.
- Add: rate limiting client ID is multi-tier, not just IP.
**New sections to add**:
- Embedded "Component dependency map" — what depends on what; failure mode if X fails (originally planned as separate `38-dependency-map.md`, merged here per category trim)
- Refresh-rules atomic-swap pattern (compile outside lock, swap inside)
- TOCTOU-safe lookup pattern in DroolsEngineService
**Sections retained from current**:
1. System overview
2. Layered architecture
3. Component architecture (with citation upgrades)
4. Request flow diagrams
5. Thread model & concurrency
6. Caching architecture
7. Security architecture (8 layers — already accurate count)
8. Performance architecture
9. Monitoring architecture
10. Deployment architecture
11. Tech stack reference
12. Design patterns & principles
**Source files cited**: All 60+ Java files, application.yml, Dockerfile, docker-compose.yml.
**Dependencies**: 03-tech-stack.md (cross-reference target).
**Done when**: Spot-check 10 random architectural claims — all map to verified code.

---

## CATEGORY 2 — Infrastructure & Deployment

### 05-environments-and-profiles.md  ·  N  ·  ~400 lines
**Audience**: DEV, OPS
**Scope**: Spring profile system: 4 profiles (`local`/`dev`/`prod`/`docker`), what each overrides, when to use each, full diff table.
**Sections**:
1. Default config baseline (no profile)
2. `local` profile — file-based rules, debug logging, no Redis (dev with embedded data)
3. `dev` profile — S3 via LocalStack, debug timeouts (shorter), thread pool 8/20
4. `prod` profile — S3 production, auto-refresh enabled, strict CB thresholds, thread pool 20/100, CloudWatch metrics
5. `docker` profile — S3 via LocalStack-in-compose, thread pool 8/20, S3 pool 25
6. Full property override matrix (one row per overridden property × 4 profile columns)
7. How to set: `SPRING_PROFILES_ACTIVE=prod` env var or JVM `-Dspring.profiles.active=prod`
**Source files cited**: `application.yml` lines 165–350.
**Dependencies**: 09-environment-variables-reference.md.
**Done when**: Reader can answer "what changes if I switch from `dev` to `prod`?" by reading one table.

### 06-deployment.md  ·  R  ·  current 866 lines → target ~1100 lines
**Audience**: OPS, DEV
**Scope**: Full deployment guide: local, Docker Compose, AWS ECS reference. Post-revision is the single source of truth for "how do I run this?".
**Drift fixes (must-fix)**:
- Replace example Dockerfile (currently `openjdk:17-jre-slim`) with the actual one (Amazon Corretto 17 Alpine multi-stage; cite [Dockerfile:1-57](Dockerfile)).
- Fix architecture diagram at lines 29-38: `/admin/*` is on the same port as the main API (8080), not a separate "Admin Portal" service.
- Update healthcheck command to match actual: `wget --spider http://localhost:8080/admin/health` per [Dockerfile:46-48](Dockerfile#L46-L48).
**New sections to add**:
- Live verified curl tests (every example tested against running stack)
- Production AWS ECS Fargate reference architecture (since `12-aws-deployment-reference.md` is being merged here per scope trim — actually no, see decision below)
**Sections retained**:
1. Prerequisites (Java 17, Maven 3.8+, Docker, AWS CLI)
2. Environment configuration template
3. Local development setup
4. Production deployment (systemd service, Nginx LB)
5. AWS deployment (S3, IAM, ElastiCache, EC2)
6. Docker deployment
7. Monitoring setup
**Source files cited**: `Dockerfile`, `docker-compose.yml`, `setup-dev-environment.sh`, `application.yml`.
**Dependencies**: 07-docker-and-compose.md (cross-link), 14-security-architecture.md (auth setup).
**Done when**: Every command in the doc has been run against the live stack and produces documented output.

### 07-docker-and-compose.md  ·  N  ·  ~600 lines
**Audience**: DEV, OPS
**Scope**: Deep dive on the container layer. Dockerfile multi-stage walkthrough, docker-compose.yml service-by-service, JAVA_OPTS line-by-line.
**Sections**:
1. Why multi-stage (Maven build stage → Corretto runtime; image size ~347MB)
2. Build stage: `maven:3.9-eclipse-temurin-17` — dependency caching layer, `mvn package -DskipTests`
3. Runtime stage: `amazoncorretto:17-alpine-jdk` — non-root `appuser` (uid 1000)
4. JAVA_OPTS line-by-line: every `-XX:` flag explained (UseContainerSupport, MaxRAMPercentage 75%, G1GC pause 100ms, region size 16m, IHOP 30%, string dedup, compressed OOPs, ExitOnOOMError) — cite [Dockerfile:27-44](Dockerfile#L27-L44)
5. Drools system properties (`drools.dateformat`, `drools.timezone=UTC`, `drools.multithreadEvaluation=true`)
6. Healthcheck (interval 30s, timeout 10s, start-period 60s, retries 3)
7. docker-compose service: `app` — resource limits, env vars, healthcheck, volume mounts
8. docker-compose service: `localstack` — image 2.3, init hook for sample rules, healthcheck via awslocal
9. docker-compose service: `redis` — image 7-alpine, AOF persistence, no auth in dev
10. Networking: bridge network `drools-network`
11. Volumes: `localstack-data`, `redis-data`, `./heap-dumps`, `./gc-logs` mounts
12. Override patterns: docker-compose.override.yml usage
**Source files cited**: `Dockerfile:1-57`, `docker-compose.yml:1-129`.
**Dependencies**: None.
**Done when**: Every JAVA_OPTS flag is explained, every compose service block has a corresponding section.

---

## CATEGORY 3 — Configuration

### 08-configuration.md  ·  K  ·  current 748 lines
**Audience**: ALL
**Scope**: High-level configuration primer. Already accurate per Agent 3 audit.
**Action**: Light reformatting only (renumber from `configuration.md` → `08-configuration.md`, update internal cross-links to numbered scheme).
**Done when**: File renamed; cross-links updated.

### 09-environment-variables-reference.md  ·  N  ·  ~700 lines
**Audience**: DEV, OPS, AI
**Scope**: Exhaustive reference of every env var the code actually reads. The single source of truth for configuration. AI coding agents reach for this first.
**Sections** (organized by config domain):
1. Server (`SERVER_PORT`, `ADMIN_PORT`, `MAX_HTTP_REQUEST_SIZE`, etc.) — 7 vars
2. Spring (`SPRING_PROFILES_ACTIVE`, `MAX_FILE_SIZE`, etc.) — 3 vars
3. Drools admin auth (`ADMIN_API_KEY`) — 1 var
4. Storage (`RULE_SOURCE`, `RULE_BUCKET_NAME`, `LOCAL_RULES_DIRECTORY`, `AWS_ENDPOINT`) — 4 vars
5. Cache (`LRU_CACHE_MAX_SIZE`, `RULE_EXECUTION_TIMEOUT_SECONDS`) — 2 vars
6. Auto-refresh (`AUTO_REFRESH_ENABLED`, `AUTO_REFRESH_INTERVAL_MINUTES`) — 2 vars
7. Timeouts (`DROOLS_HTTP_*`, `DROOLS_RULE_EXECUTION_TIMEOUT`, etc.) — 5 vars
8. Circuit breaker S3 (`DROOLS_CB_S3_*`) — 4 vars
9. Circuit breaker Redis (`DROOLS_CB_REDIS_*`) — 4 vars
10. Thread pool rule-execution (`DROOLS_THREAD_POOL_*`) — 4 vars
11. Thread pool storage (`DROOLS_STORAGE_THREAD_POOL_*`) — 4 vars
12. Validation (`DROOLS_VALIDATION_*`) — 5 vars
13. CORS (`DROOLS_CORS_*`) — 5 vars
14. Rate limiting (`DROOLS_RATE_LIMITING_*`) — 6 vars
15. Redis (`REDIS_ENABLED`, `REDIS_URL`, `REDIS_TTL_MINUTES`) — 3 vars
16. AWS (`AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_*`) — 6 vars
17. Logging (`LOG_LEVEL`)
18. Metrics (`CLOUDWATCH_METRICS_ENABLED`)
19. Drools system properties via Dockerfile (`drools.dateformat`, etc.)

For each var: name, type, default, valid range/values, what it controls (with code citation), profile-specific overrides if any, related vars.

**Source files cited**: `application.yml` exhaustively, every `@Value` annotation in `src/main/java/com/company/drools/config/`.
**Dependencies**: 05-environments-and-profiles.md (cross-link for profile overrides).
**Done when**: `grep -rn '@Value("\\${' src/main/java/` produces no env vars not in this doc.

---

## CATEGORY 4 — APIs & Integration

### 10-api-reference.md  ·  N  ·  ~600 lines (+ openapi.yml moved here)
**Audience**: PARTNER, DEV, AI
**Scope**: Complete API surface. Prose wrapper around the OpenAPI spec (which is moved from repo root to `project-documentation/api-reference/openapi.yml`).
**Sections**:
1. Overview & base URLs
2. Authentication (Admin API key flow, when required, when not)
3. Common request/response shapes (success envelope, error envelope, rate-limit headers)
4. Endpoint: `POST /execute-rule` — full request schema, response, status codes, examples
5. Endpoint: `GET /admin/health` — component breakdown, status enum
6. Endpoint: `GET /admin/info` — version, java_version
7. Endpoint: `GET /admin/rules` — list shape, RuleInfo fields
8. Endpoint: `POST /admin/refresh-rules` — full refresh flow
9. Endpoint: `POST /admin/refresh-rules/{ruleId}` — single rule refresh
10. Endpoint: `GET /admin/thread-pools` — pool stats shape
11. Endpoint: `GET /admin/memory/info` — heap/non-heap/pools/GC
12. Endpoint: `GET /admin/memory/snapshot` — lightweight version
13. Endpoint: `POST /admin/memory/gc` — diagnostic only
14. Actuator endpoints (port 8081): `/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`
15. Versioning policy
**Source files cited**: `api-documentation.yml` (moved to `project-documentation/api-reference/openapi.yml`), all controller files.
**Dependencies**: `api-documentation.yml` must be relocated before this doc is written.
**Done when**: Every endpoint has a curl example that produces documented output against running stack.

### 11-integration-guide.md  ·  N  ·  ~500 lines
**Audience**: PARTNER, DEV
**Scope**: How to integrate this service into a client application. Code examples in 4 languages.
**Sections**:
1. Quick integration (5-minute curl tutorial)
2. Authentication setup (when to send `X-Admin-API-Key`)
3. Code examples — same simple discount call in:
   - curl (with explanations)
   - Python (`requests` lib)
   - Java (Spring `RestTemplate`/`WebClient`)
   - Node.js (`fetch`/`axios`)
4. Error handling pattern (which codes to retry, which not)
5. Retry strategy (exponential backoff for 503/circuit breaker open; not for 4xx)
6. Idempotency (rule execution is idempotent; refresh-rules is not)
7. Batching (no batch endpoint; client-side patterns)
8. Connection pooling (recommended pool size for high-volume integrators)
9. Rate limit headers and back-off behavior
10. Webhooks/events (none currently — see future roadmap)
11. SDK availability (none currently)
**Source files cited**: 10-api-reference.md, 13-rate-limiting-and-throttling.md.
**Dependencies**: 10-api-reference.md, 12-error-code-catalog.md.
**Done when**: All 4 language examples tested against running stack and produce identical output.

### 12-error-code-catalog.md  ·  N  ·  ~400 lines
**Audience**: DEV, OPS, PARTNER, AI
**Scope**: Single-page catalog of every error code. AI agents and on-call engineers reach for this first.
**Sections** (one entry per error code):
For each: error code, HTTP status, JSON shape example, what triggers it, code reference, fix steps.

Codes (from `GlobalExceptionHandler.java`):
- `RULE_NOT_FOUND` (404) — RuleNotFoundException
- `RULE_EXECUTION_ERROR` (400) — RuleExecutionException
- `RULE_COMPILATION_ERROR` (400) — RuleCompilationException
- `VALIDATION_ERROR` (400) — MethodArgumentNotValidException
- `INVALID_INPUT` (400) — IllegalArgumentException
- `TIMEOUT_ERROR` (408) — TimeoutException
- `REQUEST_TOO_LARGE` (413) — MaxUploadSizeExceededException
- `RATE_LIMIT_EXCEEDED` (429) — from RateLimitingFilter
- `RESOURCE_NOT_FOUND` (404) — NoResourceFoundException
- `SERVICE_UNAVAILABLE` (503) — CircuitBreakerException
- `INTERNAL_ERROR` (500) — generic Exception fallback

Plus DRL-sandboxing rejection messages (cite `DrlSanitizer.java`).

**Source files cited**: [GlobalExceptionHandler.java](src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) (every handler method), [DrlSanitizer.java](src/main/java/com/company/drools/core/engine/DrlSanitizer.java).
**Dependencies**: None.
**Done when**: Every error code in the codebase has an entry; every entry is reproducible.

### 13-rate-limiting-and-throttling.md  ·  N  ·  ~300 lines
**Audience**: PARTNER, DEV, OPS
**Scope**: Complete rate limiting behavior. Surface the multi-tier client identification that's invisible in current docs.
**Sections**:
1. Default limits (1000 req/min, 10000 req/hour, burst 100)
2. Client identification priority (this is the surprise):
   - 1st: `X-API-Key` header → `api-key:{key}`
   - 2nd: `Authorization: Bearer {token}` → `bearer:{hash}`
   - 3rd: `X-Client-Id` header → `client-id:{id}`
   - 4th: `request.getRemoteAddr()` → `ip:{addr}` (fallback)
3. **X-Forwarded-For is explicitly ignored** (anti-spoofing) per [RateLimitingFilter.java:92](src/main/java/com/company/drools/api/filter/RateLimitingFilter.java#L92)
4. Admin endpoint exemption (`/admin/*` skips rate limiting per [RateLimitingFilterTest.java:120-127](src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java#L120-L127))
5. Response headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `X-RateLimit-Reset-After`)
6. 429 response body shape
7. Bucket refill strategy & cleanup interval
8. `max-clients=10000` cap (memory protection)
9. Burst behavior
10. How to disable (`DROOLS_RATE_LIMITING_ENABLED=false`)
11. Profile-specific overrides
**Source files cited**: [RateLimitingFilter.java](src/main/java/com/company/drools/api/filter/RateLimitingFilter.java), [RateLimitingConfig.java](src/main/java/com/company/drools/config/RateLimitingConfig.java), [RateLimitingFilterTest.java](src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java).
**Dependencies**: None.
**Done when**: A partner can predict their rate-limit identity and behavior under any of the 4 identification tiers.

---

## CATEGORY 5 — Security

### 14-security-architecture.md  ·  N  ·  ~600 lines
**Audience**: ARCH, OPS, DEV
**Scope**: 8-layer security model with file:line citations. The threat-model document.
**Sections**:
1. Layer 0 — Security Headers (`SecurityHeadersFilter` @Order(-1)): exact 7 header values cited from code
2. Layer 1 — Network (CORS empty default, HSTS, TLS at LB)
3. Layer 2 — Admin Authentication (`AdminAuthFilter` @Order(0)): API key flow, dev-mode bypass
4. Layer 3 — Rate Limiting (`RateLimitingFilter` @Order(1)): client ID tiers, max-clients cap
5. Layer 4 — Request Validation (`RequestSizeValidationFilter`, no @Order): chunked stream wrapping, size limits
6. Layer 5 — Input Sanitization (`@ValidRuleId`, `@ValidRuleData`): regex, field count, depth, dangerous patterns
7. Layer 6 — DRL Sandboxing (`DrlSanitizer`, integrated in `RuleCompiler`): see 16-drl-sandboxing.md
8. Layer 7 — Log Sanitization (`LogSanitizer`): sensitive data masking, nested map recursion
9. Cross-cutting: Path traversal protection (S3RuleStorage + LocalFileStorage)
10. Cross-cutting: SSRF protection (S3 endpoint allowlist)
11. Cross-cutting: Generic error messages (no internal leaks)
12. Threat model summary
13. Pre-production security checklist
**Source files cited**: All 4 filters in [api/filter/](src/main/java/com/company/drools/api/filter/), [DrlSanitizer.java](src/main/java/com/company/drools/core/engine/DrlSanitizer.java), [LogSanitizer.java](src/main/java/com/company/drools/common/LogSanitizer.java), [S3Config.java](src/main/java/com/company/drools/config/S3Config.java).
**Dependencies**: 15-admin-authentication.md, 16-drl-sandboxing.md (cross-references).
**Done when**: Every claim is backed by a `path:line` citation; "How is X secured?" answerable in 2 minutes for X in {input, output, storage, transport, auth, audit}.

### 15-admin-authentication.md  ·  N  ·  ~250 lines
**Audience**: OPS, DEV
**Scope**: The Admin API key flow end-to-end.
**Sections**:
1. How `AdminAuthFilter` works (path matching: `uri.startsWith("/admin/")`)
2. The dev-mode bypass: when `ADMIN_API_KEY` is empty/null, auth is **skipped** (warning logged at startup) — **CRITICAL operational note**
3. How to enable: `ADMIN_API_KEY` env var (recommend long random string)
4. How to authenticate: `X-Admin-API-Key` header
5. 401 response shape
6. Key rotation procedure (graceful: deploy new instance with new key, drain old)
7. Threat model: API gateway is primary, this is defense in depth
8. Why not Spring Security (rationale, easy to swap later)
**Source files cited**: [AdminAuthFilter.java](src/main/java/com/company/drools/api/filter/AdminAuthFilter.java), [AdminAuthFilterTest.java](src/test/java/com/company/drools/api/filter/AdminAuthFilterTest.java).
**Dependencies**: 14-security-architecture.md.
**Done when**: An operator can rotate the admin key with zero downtime using the documented procedure.

### 16-drl-sandboxing.md  ·  N  ·  ~500 lines
**Audience**: DEV (rule authors), AI (rule generation)
**Scope**: Definitive reference for what the DRL sandbox blocks. The doc AI rule-generation tools must consume to produce sandbox-passing rules.
**Sections**:
1. Why sandboxing exists (DRL is Turing-complete; rules are user-supplied content)
2. **Allowed imports** (full list from [DrlSanitizer.java:17-38](src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L17-L38)):
   - `java.util.*`, `java.math.*`, `java.time.*`
   - `java.lang.Math`, `java.lang.String`, `java.lang.Number`, primitives, `Comparable`, `Object`, `Enum`
   - `java.text.DecimalFormat`, `NumberFormat`, `SimpleDateFormat`
3. **Blocked import prefixes** (from [DrlSanitizer.java:77-97](src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L77-L97)) — full list: `java.io.*`, `java.nio.*`, `java.net.*`, `java.lang.reflect.*`, `java.lang.invoke.*`, `java.lang.{Process,Runtime,ClassLoader,Thread,SecurityManager}`, `javax.{script,naming,management,net}.*`, `sun.*`, `com.sun.*`, `jdk.*`, `org.kie.api.internal`, `org.drools.core`
4. **Blocked class references** (13 classes): Runtime, ProcessBuilder, ClassLoader, URLClassLoader, Thread, ThreadGroup, SecurityManager, ScriptEngine, ScriptEngineManager, MethodHandle, Lookup, Unsafe
5. **Blocked method calls** (15 methods): `Runtime.getRuntime`, `System.exit`, `System.getenv`, `System.setProperty`, `Class.forName`, `Class.getMethod`, `Class.getDeclaredField`, `Class.newInstance`, `.getClass().getMethod`, etc.
6. **`eval()` blocked** — pattern `\beval\s*\(` per [DrlSanitizer.java:102](src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L102), test at [DrlSanitizerTest.java:530-551](src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java#L530-L551)
7. Static imports rejected
8. Validation flow (sanitize → reject if any violation, with error message)
9. **Sandbox-passing rule template** (the canonical pattern — Map() with eval()-free conditions, plain Java in then-block)
10. Common pitfalls and how to fix
11. How to test a rule against the sandbox locally (DrlSanitizer is invoked during `RuleCompiler.compileRules()`)
**Source files cited**: [DrlSanitizer.java](src/main/java/com/company/drools/core/engine/DrlSanitizer.java) full, [DrlSanitizerTest.java](src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) full, sample rules.
**Dependencies**: 17-rule-development.md, 19-sample-rules-cookbook.md.
**Done when**: A rule author can produce a sandbox-passing rule on first try using only this doc.

---

## CATEGORY 6 — Rule Authoring

### 17-rule-development.md  ·  R  ·  current 933 lines → target ~1100 lines
**Audience**: DEV (rule authors), AI
**Scope**: Comprehensive rule-authoring guide. Already strong; needs targeted fixes.
**Drift fixes (must-fix)**:
- Add prominent header note: "This project uses **traditional DRL syntax** (Map-based pattern matching), NOT Drools 8 rule units or OOPath. See 23-rule-language-reference.md for upstream Drools 8 features."
- Replace Drools 7.74.1 doc links at line 917 with Drools 8.x equivalents.
- Cross-link to 16-drl-sandboxing.md for what's allowed/blocked.
- Verify all DRL examples pass DrlSanitizer (no `eval()`, allowed imports only).
**New sections to add**:
- "Project conventions" subsection: package naming `com.company.rules.{domain}.{category}`, rule ID format, output field naming (camelCase, "Applied" suffix, "Reason" fields)
- "Sandbox-aware rule-author checklist" (cross-link to 16)
**Sections retained**:
1. Rule basics (DRL, execution flow)
2. Rule structure
3. Development workflow
4. Examples (5 categories)
5. Best practices
6. Testing rules
7. Troubleshooting
8. Advanced topics
9. Security restrictions (cross-link 16)
**Source files cited**: All sample rules, `DroolsEngineService.java`, `RuleExecutor.java`, `DrlSanitizer.java`.
**Dependencies**: 16-drl-sandboxing.md, 19-sample-rules-cookbook.md, 23-rule-language-reference.md.
**Done when**: Every code example compiles and passes DrlSanitizer.

### 18-rule-id-and-storage-layout.md  ·  N  ·  ~250 lines
**Audience**: DEV (rule authors), OPS
**Scope**: How rule IDs map to file paths, packages, and S3 keys.
**Sections**:
1. Rule ID format: `^[a-zA-Z0-9._-]+$`, max 255 chars
2. Transformation: `pricing.discount.vip` → `pricing/discount/vip.drl` (S3 key) → `com.company.rules.pricing.discount` (package)
3. Why dots in ID, slashes in path (hierarchical organization, S3-friendly)
4. Storage backend selection via `RULE_SOURCE`:
   - `local` (default) → `InMemoryRuleStorage`
   - `file` → `LocalFileStorage` (uses `LOCAL_RULES_DIRECTORY`)
   - `s3` → `S3RuleStorage` (uses `RULE_BUCKET_NAME`)
5. Path traversal protection in both backends (cite [S3RuleStorage.java:339-341](src/main/java/com/company/drools/storage/S3RuleStorage.java#L339-L341), [LocalFileStorage.java:161](src/main/java/com/company/drools/storage/LocalFileStorage.java#L161))
6. Reverse mapping: S3 key → rule ID
7. How to upload to S3: `aws s3 cp` examples for prod and LocalStack
8. Folder structure conventions (`pricing/discount/`, `validation/customer/`, `seasonal/holiday/`)
**Source files cited**: [S3RuleStorage.java](src/main/java/com/company/drools/storage/S3RuleStorage.java), [LocalFileStorage.java](src/main/java/com/company/drools/storage/LocalFileStorage.java), [StorageFactory.java](src/main/java/com/company/drools/storage/StorageFactory.java).
**Dependencies**: 17-rule-development.md, 16-drl-sandboxing.md.
**Done when**: Reader can predict the file path for any rule ID and vice versa.

### 19-sample-rules-cookbook.md  ·  N  ·  ~600 lines
**Audience**: DEV (rule authors), AI, PARTNER
**Scope**: Live-tested catalog of all 10 sample rules, with reproducible curl examples.
**Sections** (one per rule, 10 total):
1. `pricing.discount.simple` — 10% discount over $50; example: `{"rule_id":"pricing.discount.simple","data":{"amount":100}}` → `{"discount":10.0,"amount":90.0,"discountPercent":10,"discountReason":"..."}`
2. `pricing.discount.vip` — 20% VIP discount; **note: stacks with simple discount because both fire** → final `amount=72` for $100 VIP order. Document the stacking explicitly.
3. `pricing.discount.bulk` — 15% on quantity ≥ 10
4. `pricing.discount.first-time` — 5% for first-time customers
5. `pricing.shipping.standard` — weight-based: $5.99/$9.99/$15.99
6. `pricing.shipping.express` — weight-based + free over $100
7. `validation.customer.age` — age ≥ 18 + age group + senior discount flag
8. `validation.customer.credit` — score-based tiered approval (Excellent/Good/Fair/Poor)
9. `seasonal.holiday.discount` — 12% if `isHolidaySeason==true`
10. `seasonal.holiday.blackfriday` — 25% if promo code `BLACK2024` and amount ≥ $100

For each: rule purpose, expected input fields, expected output fields, full curl request, full curl response, common variations.

**Source files cited**: Every `.drl` file in `sample-rules/`.
**Dependencies**: 10-api-reference.md (request shape), running Docker stack (for verification).
**Done when**: Every curl example produces documented output against the running stack.

### 20-rule-generation-prompt.md  ·  K  ·  current 361 lines
**Action**: Renumber from `rule-generation-prompt.md`. No content changes.

### 21-rule-generation-prompt-enhanced.md  ·  K  ·  current 384 lines
**Action**: Renumber from `rule-generation-prompt-enhanced.md`. No content changes.

### 22-rule-generation-prompt-concise.md  ·  K  ·  current 179 lines
**Action**: Renumber from `rule-generation-prompt-concise.md`. No content changes.

### 23-rule-language-reference.md  ·  R  ·  current 3167 lines → target ~3200 lines
**Audience**: DEV (advanced), AI
**Scope**: Upstream Drools 8 reference. Already accurate as upstream docs.
**Drift fixes (must-fix)**:
- Add prominent header disclaimer at the top:
  > **NOTE**: This is the upstream Drools 8 reference. **This project uses traditional DRL syntax only** (Map-based pattern matching). Features like rule units, OOPath, DataStream, and reactive workflows described here are **NOT used** in this project. For project-specific patterns, see [17-rule-development.md](17-rule-development.md) and [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md).
- Tag sections that describe unused features (rule units, OOPath, DataStream) with a "[Not used in this project]" callout.
**Done when**: Reader cannot mistake unused features for project patterns.

---

## CATEGORY 7 — Performance & Memory

### 24-jvm-optimization.md  ·  R  ·  current 239 lines → target ~280 lines
**Audience**: OPS, DEV
**Scope**: JVM tuning guide. Already mostly accurate.
**Drift fixes (must-fix)**:
- **Remove deprecated flag**: line 41's `-XX:+UseCGroupMemoryLimitForHeap` is deprecated in Java 9 and removed in Java 11+. Replace with explanation: "Java 17 automatically respects cgroup limits via `-XX:+UseContainerSupport` (already in Dockerfile)."
- Update production targets to align with measured performance.
- Cross-link to 25-memory-monitoring-guide.md and 26-performance-tuning-runbook.md.
**Done when**: No deprecated flags documented.

### 25-memory-monitoring-guide.md  ·  K  ·  current 730 lines
**Action**: Renumber. Already accurate per Agent 3 audit.

### 26-performance-tuning-runbook.md  ·  N  ·  ~500 lines
**Audience**: OPS, DEV (performance engineer)
**Scope**: Decision-tree runbook for diagnosing performance issues.
**Sections**:
1. Symptom triage table (latency↑, throughput↓, GC pauses↑, OOM, etc.)
2. **Branch: rule compilation slow** — check rule count, rule complexity, restart frequency; tune by reducing rule churn or pre-warming
3. **Branch: rule execution slow** — check `maxRuleFirings` (10000 cap), check rule complexity, check thread pool saturation
4. **Branch: S3 slow** — check circuit breaker state, connection pool, AWS region latency
5. **Branch: Redis slow** — check connection pool, network, circuit breaker
6. **Branch: GC pauses** — check heap sizing, G1 region size, tenured-gen growth
7. **Branch: OOM (exit 137)** — check heap dump, KieContainer disposal (already fixed), metaspace
8. **Branch: thread pool exhaustion** — check `/admin/thread-pools`, increase max-size or queue
9. **Branch: rate limit false positives** — check client identification tier, max-clients cap
10. Tools reference (jstack, jcmd, jmap, JFR)
**Source files cited**: All performance-related code paths.
**Dependencies**: 25-memory-monitoring-guide.md, 24-jvm-optimization.md, 29-circuit-breakers-and-resilience.md.
**Done when**: Each branch leads to a concrete tuning action.

---

## CATEGORY 8 — Development

### 27-development-setup.md  ·  N  ·  ~600 lines
**Audience**: DEV (new contributor)
**Scope**: Complete onboarding for a new code contributor: clone → run → test → contribute.
**Sections**:
1. Prerequisites (Java 17, Maven 3.8+, Docker, git, IDE)
2. Clone and bootstrap: `source ./set-java-env.sh`, `mvn clean compile`
3. Run locally (Docker compose path) and (local Java path)
4. Run the test suite (`mvn test`, JaCoCo report)
5. Code style (Spotless / Google Java Format) — `mvn spotless:apply` before commit
6. Static analysis (SpotBugs)
7. **Coding patterns and conventions** (this is the merged "patterns" content):
   - Lock disciplines: ReentrantReadWriteLock for KieContainer; LocalLRUCache write-on-get
   - Storage abstraction (Strategy pattern via `RuleStorage` interface + `StorageFactory`)
   - Cache abstraction (`RuleCache` interface, `@Primary` LocalLRUCache)
   - Filter ordering (`@Order(-1, 0, 1)`)
   - DTO conventions: `@JsonProperty` for snake_case
   - Validation conventions: custom annotations + Jakarta Validation
   - Exception conventions: custom exception types + `GlobalExceptionHandler` mapping
   - Test patterns: `BaseUnitTest`, `BaseIntegrationTest` with Testcontainers
8. **Build and CI** (merged):
   - Maven plugin map: enforcer (Java 17), spotless, jacoco, spotbugs, surefire
   - Build commands cheat sheet
   - Spring Boot Maven plugin (fat jar, repackage)
   - Multi-module readiness (currently single module)
9. Branching and commit conventions (Conventional Commits)
10. PR checklist
11. Common dev tasks: add a config property, add a metric, add a controller endpoint, add a storage backend
**Source files cited**: `pom.xml`, `Dockerfile`, `setup-dev-environment.sh`, base test classes.
**Dependencies**: 28-testing-guide.md, 34-java-setup-guide.md.
**Done when**: A new contributor reaches "first passing test" in under 30 minutes.

### 28-testing-guide.md  ·  N  ·  ~500 lines
**Audience**: DEV
**Scope**: Test suite map and how-to. The "implicit spec" of project behavior lives in tests.
**Sections**:
1. Test inventory (44 files, ~1,181 tests, 96.2% instruction / 89.7% branch coverage)
2. Test categories: unit (most), integration (Testcontainers + LocalStack), filter, validation
3. How to run: `mvn test`, single class, with coverage, integration only
4. Test layout per package (mirrors `src/main/java/`)
5. Base classes: `BaseUnitTest` (Mockito), `BaseIntegrationTest` (Testcontainers fixtures)
6. Test utilities: `RuleTestUtils.createSimpleRule()`, `ValidationConfigTestHelper`
7. **Behavior verified by tests** (the gold list — proves docs are accurate):
   - Rate limiting per-IP independence
   - Rate limiting admin exemption
   - DrlSanitizer eval() rejection
   - DrlSanitizer 18 import allowlist + 19 blocklist
   - X-Forwarded-For ignored
   - Path traversal rejection
   - KieContainer disposal
   - Atomic-swap rule loading
   - 7 security headers
8. How to add a unit test (template + example)
9. How to add an integration test (template + Testcontainers gotchas)
10. Coverage threshold (none configured — recommended action item)
**Source files cited**: Every test file referenced in section 7.
**Dependencies**: 27-development-setup.md.
**Done when**: Every "implicit spec" claim in docs has a corresponding test cited.

### 29-circuit-breakers-and-resilience.md  ·  N  ·  ~400 lines
**Audience**: DEV, OPS, ARCH
**Scope**: Resilience4j circuit-breaker behavior end-to-end.
**Sections**:
1. Why circuit breakers (cascading failure prevention)
2. Configured breakers: `s3CircuitBreaker`, `redisCircuitBreaker`
3. Per-profile thresholds:
   - `dev`: S3 60% fail / Redis 70% fail
   - `prod`: S3 40% fail / Redis 50% fail (stricter)
4. State machine: CLOSED → OPEN → HALF_OPEN → CLOSED
5. Sliding window, minimum-number-of-calls, wait-duration semantics
6. **S3 wiring** in `S3RuleStorage`:
   - `getRule()` decorated ([S3RuleStorage.java:62-90](src/main/java/com/company/drools/storage/S3RuleStorage.java#L62-L90))
   - `getAllRules()` decorated ([S3RuleStorage.java:135-142](src/main/java/com/company/drools/storage/S3RuleStorage.java#L135-L142))
7. **Redis wiring** in `RedisRuleCache` (get and put both decorated)
8. Behavior on OPEN: `CallNotPermittedException` → mapped to `CircuitBreakerException` → HTTP 503
9. Recovery: HALF_OPEN test calls
10. Metrics emitted (`resilience4j_circuitbreaker_state`)
11. Manual reset (restart service)
12. Testing failure scenarios (chaos: kill LocalStack mid-request)
**Source files cited**: [CircuitBreakerConfig.java](src/main/java/com/company/drools/config/CircuitBreakerConfig.java), [S3RuleStorage.java](src/main/java/com/company/drools/storage/S3RuleStorage.java), [RedisRuleCache.java](src/main/java/com/company/drools/cache/RedisRuleCache.java).
**Dependencies**: None.
**Done when**: An on-call engineer can predict service behavior under any combination of S3/Redis failure states.

---

## CATEGORY 9 — Operations

### 30-runbooks-and-monitoring.md  ·  N  ·  ~700 lines
**Audience**: OPS
**Scope**: Combined operational runbooks + monitoring setup. The operator's primary doc.
**Sections** (Runbooks half):
1. Start the service (Docker / systemd / kubectl)
2. Stop the service (graceful shutdown via SIGTERM)
3. Check health (`/admin/health`, expected component states)
4. Refresh all rules (`POST /admin/refresh-rules` with admin key)
5. Refresh single rule (`POST /admin/refresh-rules/{ruleId}`)
6. Scale horizontally (load-balancer config, no shared state)
7. Diagnose 503 errors (circuit breaker check, Redis/S3 health)
8. Diagnose 429 errors (rate limiter state, client identification)
9. Memory diagnostics (`/admin/memory/info`, heap dump procedure)
10. Roll out a config change (env var update + restart)
11. Roll back a bad rule (S3 versioning + refresh)
12. Emergency: drop traffic via LB; restart in clean state
**Sections** (Monitoring half):
13. Metrics architecture (Micrometer → Prometheus/CloudWatch)
14. Key metric names (`drools.rule.execution.time`, `drools.cache.hits`, `drools.s3.requests`, `resilience4j_circuitbreaker_state`, `jvm.memory.used`, `http.server.requests`)
15. Recommended dashboards (rule throughput, latency P50/P95/P99, cache hit rate, circuit breaker states, GC pauses, heap usage)
16. Recommended alerts (rule errors > 1%, P99 > 200ms, cache hit rate < 80%, breaker open, 5xx > threshold, heap > 80%)
17. Log shipping (structured JSON via logstash encoder)
18. Correlation IDs (`X-Correlation-ID` header, MDC propagation)
**Source files cited**: All controllers, [MetricsConfig.java](src/main/java/com/company/drools/config/MetricsConfig.java), [LoggingConfig.java](src/main/java/com/company/drools/config/LoggingConfig.java).
**Dependencies**: 25-memory-monitoring-guide.md, 29-circuit-breakers-and-resilience.md.
**Done when**: An operator can handle any P1 incident using only this doc.

### 31-troubleshooting.md  ·  K  ·  current 934 lines
**Action**: Renumber. Already comprehensive per Agent 3 audit.

---

## CATEGORY 10 — Onboarding & Reference

### 32-getting-started.md  ·  N  ·  ~300 lines
**Audience**: DEV (new user, evaluating the project)
**Scope**: Fastest path to "first successful curl". <30 minutes target.
**Sections**:
1. What you'll accomplish (in <30 min: running stack + 3 successful API calls)
2. Prerequisites checklist (Docker installed, ports 8080/8081/4566/6379 free)
3. Clone & start: `git clone` → `docker compose up -d --build` → wait for healthy
4. First curl: list rules
5. Second curl: execute simple discount
6. Third curl: execute VIP discount (note the discount stacking)
7. View health and metrics
8. What to read next (audience-specific links)
**Source files cited**: 33-simple-start.md (cross-link).
**Dependencies**: Stack must run.
**Done when**: A reader who has never seen this repo reaches "third successful curl" in under 30 minutes.

### 33-simple-start.md  ·  K  ·  current 240 lines
**Action**: Renumber. Already accurate.

### 34-java-setup-guide.md  ·  K  ·  current 659 lines
**Action**: Renumber. Already accurate.

### 35-faq.md  ·  N  ·  ~400 lines
**Audience**: ALL
**Scope**: 30+ Q&A drawn from the gaps Agent 3 surfaced.
**Sample questions**:
- How do I add a new rule? (→ 19, 17, 18)
- Why is my rule being rejected? (→ 16)
- How do I authenticate admin requests? (→ 15)
- What happens when ADMIN_API_KEY is empty? (→ 15)
- How does rate limiting identify my client? (→ 13)
- Why does VIP discount give $72 instead of $80? (→ 19)
- Can I use `eval()` in rules? (→ 16)
- Does this support Drools 8 rule units? (→ 17, 23)
- Why is RULE_SOURCE defaulting to `local`? (→ 09)
- How do I scale horizontally? (→ 30)
- How do I monitor production? (→ 30)
- What if Redis goes down? (→ 29)
- What if S3 goes down? (→ 29)
- How big can a request be? (→ 09, 14)
- Are rules sandboxed? (→ 16)
- How do I set up CloudWatch metrics? (→ 30)
- What's the rule ID format? (→ 18)
- How do I refresh a single rule? (→ 10)
- How do I add a custom storage backend? (→ 36)
- ... (~30 total)
**Source files cited**: Cross-references to other docs.
**Dependencies**: All other docs (this is written near the end).
**Done when**: 30+ questions; each answer cites the authoritative doc.

---

## CATEGORY 11 — Advanced Deep Dives

### 36-architecture-decision-records.md  ·  N  ·  ~700 lines
**Audience**: ARCH, DEV (senior)
**Scope**: ADRs for load-bearing design choices. Includes "extension points" appendix (how to extend the system).
**ADRs to write** (~12):
1. **ADR-001**: Use traditional DRL syntax (not Drools 8 rule units / OOPath) — backward compatibility, smaller learning curve
2. **ADR-002**: Map-based facts vs typed POJOs — JSON-friendly, no class loading per rule
3. **ADR-003**: KieContainer atomic-swap with disposal — prevents OOM on repeated reloads
4. **ADR-004**: LocalLRUCache uses write-lock on get() — accessOrder LinkedHashMap mutates on get
5. **ADR-005**: Redis bean exists but dormant by default — infrastructure-ready, not in execution path
6. **ADR-006**: AdminAuthFilter (lightweight) instead of Spring Security — defense in depth, easy to swap
7. **ADR-007**: No Terraform — reference architecture documented but IaC out of scope for this repo
8. **ADR-008**: snake_case JSON via `@JsonProperty` — API ergonomics
9. **ADR-009**: `eval()` ban in DRL — eliminates code injection vector
10. **ADR-010**: Rate limiting in-memory (not Redis) — simpler, sufficient for current scale
11. **ADR-011**: Custom validation annotations (not just Jakarta) — domain-specific limits
12. **ADR-012**: Drools 8.44.0 (not 8.x latest) — tested compatibility baseline

**Extension points appendix**:
- How to add a storage backend (implement `RuleStorage`, register in `StorageFactory`)
- How to add a custom validation annotation
- How to add a custom Micrometer metric
- How to add a custom filter (set `@Order`, mind the chain)
- How to swap rate limiting to Redis-backed

**Source files cited**: Throughout, with links to commits/PRs where decisions landed.
**Dependencies**: Many. Written late in the cycle.
**Done when**: For each load-bearing choice, the rationale is recorded.

### 37-glossary.md  ·  N  ·  ~250 lines
**Audience**: ALL, AI (reach-for reference)
**Scope**: Drools terms + project terms. Single-page glossary.
**Categories**:
- Drools core: KieBase, KieSession, KieContainer, KieBuilder, working memory, fact, agenda, salience, no-loop, lock-on-active, agenda-group, ruleflow-group, DRL, OOPath (not used here), rule unit (not used here)
- Project-specific: rule ID, rule source, LRU cache, RedisRuleCache, DrlSanitizer, sandbox, admin API key, sample rules, LocalStack
- Infrastructure: cgroup, G1GC, Resilience4j, circuit breaker, Testcontainers, Spotless, JaCoCo
- Concepts: TOCTOU, atomic swap, reentrant read-write lock, fan-out/fan-in
**Done when**: Every acronym/jargon term used elsewhere in the corpus is defined here.

### 38-dependency-map.md  ·  ABSORBED into 04-architecture.md
**Note**: Per category trim, the dependency map content lives inside `04-architecture.md` as a section. This frees a slot for content elsewhere; 34-doc total preserved.

---

## Summary table

| # | Title | Status | Lines (target) | Audience |
|---:|---|:---:|---:|---|
| 00 | system-overview | N | 150 | ALL |
| 01 | project-overview | N | 250 | ALL |
| 02 | project-structure | N | 300 | DEV, AI |
| 03 | tech-stack | N | 250 | DEV, ARCH |
| 04 | architecture | R | 1700 | ARCH, DEV, AI |
| 05 | environments-and-profiles | N | 400 | DEV, OPS |
| 06 | deployment | R | 1100 | OPS, DEV |
| 07 | docker-and-compose | N | 600 | DEV, OPS |
| 08 | configuration | K | 748 | ALL |
| 09 | environment-variables-reference | N | 700 | DEV, OPS, AI |
| 10 | api-reference | N | 600 | PARTNER, DEV, AI |
| 11 | integration-guide | N | 500 | PARTNER, DEV |
| 12 | error-code-catalog | N | 400 | DEV, OPS, PARTNER, AI |
| 13 | rate-limiting-and-throttling | N | 300 | PARTNER, DEV, OPS |
| 14 | security-architecture | N | 600 | ARCH, OPS, DEV |
| 15 | admin-authentication | N | 250 | OPS, DEV |
| 16 | drl-sandboxing | N | 500 | DEV, AI |
| 17 | rule-development | R | 1100 | DEV, AI |
| 18 | rule-id-and-storage-layout | N | 250 | DEV, OPS |
| 19 | sample-rules-cookbook | N | 600 | DEV, AI, PARTNER |
| 20 | rule-generation-prompt | K | 361 | AI |
| 21 | rule-generation-prompt-enhanced | K | 384 | AI |
| 22 | rule-generation-prompt-concise | K | 179 | AI |
| 23 | rule-language-reference | R | 3200 | DEV, AI |
| 24 | jvm-optimization | R | 280 | OPS, DEV |
| 25 | memory-monitoring-guide | K | 730 | OPS |
| 26 | performance-tuning-runbook | N | 500 | OPS, DEV |
| 27 | development-setup | N | 600 | DEV |
| 28 | testing-guide | N | 500 | DEV |
| 29 | circuit-breakers-and-resilience | N | 400 | DEV, OPS, ARCH |
| 30 | runbooks-and-monitoring | N | 700 | OPS |
| 31 | troubleshooting | K | 934 | OPS, DEV |
| 32 | getting-started | N | 300 | DEV |
| 33 | simple-start | K | 240 | DEV (rule author) |
| 34 | java-setup-guide | K | 659 | DEV |
| 35 | faq | N | 400 | ALL |
| 36 | architecture-decision-records | N | 700 | ARCH, DEV |
| 37 | glossary | N | 250 | ALL, AI |

**Total markdown lines (target)**: ~22,400 (~700K chars). **NotebookLM-friendly**: well within the 50-source / 500K-char-per-source limits.

---

## Cross-cutting requirements (apply to every doc)

1. **Citations**: Every code claim cited as `[path/file.ext:line](path/file.ext#Lline)` markdown link.
2. **Frontmatter** (every doc): "Audience", "Last verified against code", "Related docs" (cross-link list).
3. **NotebookLM-friendly**: Clear H2/H3 hierarchy, no >120-line sections without subheadings, every acronym defined or linked to glossary.
4. **AI-coding-agent-friendly**: Every config example shows actual env var name (not paraphrased), every code snippet is copy-paste-ready, every rule example passes `DrlSanitizer`.
5. **Idempotency**: Each doc readable standalone — repeat key context (don't assume prior reading).
6. **Discoverability**: First H2 of every doc is "What this doc covers" + "Who it's for".
