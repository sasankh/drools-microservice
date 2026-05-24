# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 🛑 Read these files first (every session, before anything else)

### AI workflow protocols (required before responding to commands)
1. `ai-instructions/snap-memory-instructions.md` — how to handle the `snap-memory` command
2. `ai-instructions/ai-initial-context-instructions.md` — how to handle the `ai-context-update` command

### Project documentation (the canonical reference)
3. **`project-documentation/00-system-overview.md`** — entry point for all 40 docs. Has role-based reading paths.

The full documentation corpus is in `project-documentation/`. See [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md) for the index and reading paths by role.

The legacy consolidated context file at `.ai-workspace/ai-initial-context/ai-initial-context-latest.md` is no longer the primary reference — the structured `project-documentation/` corpus supersedes it.

## ⚠️ Important: Recent change log (most recent first)

### Redis CB + pub/sub hardening — Phase 9.4 follow-ups (2026-05-24)
- **SCAN now wrapped in `redisCircuitBreaker`** — closes coverage gap in [`RedisCachedRuleStorage.scanKeys()`](src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) so bulk-path failures (used by `invalidateAll`, `collectFromRedis`) contribute to the CB sliding window during a Redis outage. Previously SCAN bypassed the CB entirely — Phase 9.4 surfaced this by observing CB never opened during a 60s Redis kill.
- **Lettuce timeout 2000ms → 500ms** via new env var `REDIS_TIMEOUT` (default `500ms`). The prior 2s value was an exact match for the CB's `slowCallDurationThreshold=2s`, putting Lettuce timeouts in an ambiguous classification window. 500ms sits cleanly below the slow-call threshold so timeouts unambiguously count as failures. Externalized for per-environment tuning.
- **`RedisMessageListenerContainer.setRecoveryBackoff(FixedBackOff(2s, ∞))`** in [`RedisConfig.java`](src/main/java/com/company/drools/config/RedisConfig.java) — explicit pub/sub re-subscribe policy bounds worst-case latency to ≤2s after Redis becomes reachable post-restart. FixedBackOff over ExponentialBackOff for predictability (Phase 9.4 convergence deadline is fixed).
- **Test count**: 545 → 548 unit tests; 13 → 14 Testcontainers integration tests (the new `scanKeysCircuitBreakerFallback` inherits the existing macOS-DinD surefire exclusion).
- **Plan + checklist**: [`.ai-workspace/project-plans/redis-cb-hardening-plan.md`](.ai-workspace/project-plans/redis-cb-hardening-plan.md) and [`-checklist.md`](.ai-workspace/project-plans/redis-cb-hardening-checklist.md).

### Redis cache + pub/sub layer (2026-05-20)
- **Replaced dead `RuleCache` layer.** Forensic trace revealed both `LocalLRUCache` and `RedisRuleCache` were never read from at runtime — written to during refresh but `.get()` was never called. Deleted ~600 LOC of dead code.
- **New `RedisCachedRuleStorage` decorator** wraps the base `RuleStorage` when `REDIS_ENABLED=true`. Read-through Redis cache of DRL text with 15-min TTL default. Circuit-breaker fallback to base storage if Redis is down.
- **New pub/sub layer** (`RuleRefreshPublisher`, `RuleRefreshSubscriber`, `RefreshEvent`) — when one ECS task refreshes a rule, sibling tasks receive a JSON event on `drools:rule:events` and refresh their own `kieContainer` within ~1 sec. Solves the multi-instance compiled-state divergence problem.
- **Env var migration**: `REDIS_TTL_MINUTES` → `REDIS_DRL_RULES_TTL_MINUTES`; `LRU_CACHE_MAX_SIZE` dropped; added `REDIS_DRL_RULES_KEY_PREFIX`, `REDIS_PUBSUB_ENABLED`, `REDIS_REFRESH_CHANNEL`.
- **Test results**: 545 unit tests pass (down from 597 due to deleted dead-cache tests); +13 Testcontainers integration tests (excluded from default `mvn test` via pom.xml surefire config). `full-docker-test-plan.md` extended with Step 8 (Redis cache verification) and Step 9 (Redis-off regression); all 11 steps executed live and pass.
- **ADR-016** added; ADR-004 and ADR-005 marked Superseded.

### Sonar quality gates cleared (2026-05-11)
- **Maintainability**: 178 → 0 open issues (Waves 4A–4D: AssertJ modernization, parameterized tests, constructor injection, unused fields, cognitive complexity, ReDoS hotspots)
- **Reliability**: 5 → 0 (BLOCKER fixed: KieSession try-with-resources; S2142 InterruptedException handling; S2583 dead branch; S6813 constructor injection for validators)
- **Security hotspots**: 2 resolved — `CorsConfig` regex ReDoS (S5852) and `DrlSanitizer` IMPORT_PATTERN possessive quantifiers
- **Quality Gate**: OK on all three conditions (new_coverage 89.7%, new_violations 0, no duplications)
- **Test count**: 597 (down 1 from parameterized test consolidation in Wave 4B)

### Tooling: SonarQube MCP wired into Claude Code (2026-05-10)
This repo supports a SonarQube MCP server, but **`.mcp.json` is gitignored** because it holds a SonarQube token. Each developer creates their own. To set it up:

1. Get a SonarQube user token (SonarQube UI → My Account → Security → Generate Tokens).
2. From the repo root, run:
   ```bash
   claude mcp add sonarqube \
     --scope project \
     --env SONARQUBE_TOKEN=<your-token> \
     --env SONARQUBE_URL=http://host.docker.internal:9000 \
     -- docker run -i --rm \
          -e SONARQUBE_TOKEN \
          -e SONARQUBE_URL \
          mcp/sonarqube
   ```
   This creates a local `.mcp.json` (gitignored, never committed). On macOS use `host.docker.internal:9000` so the MCP container can reach SonarQube on your host; on Linux use `localhost:9000`.
3. Verify: `claude mcp list` should show `sonarqube` as `✓ Connected`.
4. Restart Claude Code so the new MCP is loaded.

Docker must be running; the MCP launches `mcp/sonarqube` per session. See [`.env.example`](.env.example) "SONARQUBE MCP" section for additional notes.

### Load test + sample-rules expansion + rule-loading rework (2026-05-10)
- **Drools 10 rule-loading rework**: `DroolsEngineService` now holds a **single long-lived `KieContainer`** updated in place via `KieContainer.updateToVersion(ReleaseId)`, with an explicit `KieRepository.removeKieModule(oldReleaseId)` after each swap (Drools 10 does **not** auto-clean). Replaces the earlier two-container atomic-swap-with-`dispose()` pattern. See [ADR-003 2026-05-10 update](project-documentation/36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal).
- **LOADING-marker bug fix**: latent at 10-rule scale (sub-ms compile); surfaced at 1000 rules (~46s compile) as a 1.5% error rate during refresh windows. Fixed in [`DroolsEngineService.loadOrReplaceRule`](src/main/java/com/company/drools/core/engine/DroolsEngineService.java).
- **Sample-rules cookbook 10 → 17**: added 7 rules covering `accumulate`, `exists`, `not`, `salience`, regex, temporal, and accumulate-with-collect patterns. All under [`sample-rules/`](sample-rules/); cookbook in [`19-sample-rules-cookbook.md`](project-documentation/19-sample-rules-cookbook.md).
- **Load test orchestrator**: `scripts/run-load-test.sh` runs the full Phase 0–8 suite end-to-end (1000 rules, JMeter, mixed-workload soak). Findings in [`project-documentation/39-load-test-findings.md`](project-documentation/39-load-test-findings.md).
- **Test count**: 589 → 598 (load test session) → 597 (Sonar Wave 4B parameterized consolidation); **test files**: 45.

### Stack modernization (2026-05-09)
- **Java 17 → 25 LTS** (Maven Enforcer Plugin range `[25,26)`).
- **Spring Boot 3.2.5 → 3.5.3**.
- **Drools 8.44.0 → 10.2.0** (forced a refactor of rule-loading because `CompilationResult.getKieContainer()` was removed; led to the 2026-05-10 work above).
- Closed deferred security finding #30 (outdated dependencies). See [ADR-013](project-documentation/36-architecture-decision-records.md#adr-013-java-17--25--spring-boot-modernization-2026-05-09) and [ADR-014](project-documentation/36-architecture-decision-records.md#adr-014-drools-844--102-as-part-of-stack-modernization).

### Security Hardening Complete (2026-02-26)

**39/42 security findings addressed** across 9 phases. Key changes:

1. **Admin Authentication** — `/admin/*` endpoints protected by `X-Admin-API-Key` header (env: `ADMIN_API_KEY`)
2. **DRL Sandboxing** — `DrlSanitizer` blocks dangerous imports/classes/methods before compilation
3. **Security Headers** — 7 headers on all responses (CSP, HSTS, X-Frame-Options, etc.)
4. **CORS Default Changed** — Default is now empty (no CORS); wildcard only in local/dev/docker profiles
5. **Rate Limiting Hardened** — X-Forwarded-For ignored, uses `request.getRemoteAddr()` only; maxClients cap
6. **Jackson RCE Fixed** — `activateDefaultTyping()` with strict `BasicPolymorphicTypeValidator`
7. **Path Traversal Protection** — Defense-in-depth in LocalFileStorage and S3RuleStorage
8. **Non-blocking Compilation** — Rule compilation outside write lock in DroolsEngineService

### Earlier Critical Fixes (2026-02-19)
- **Java 17 Enforcement** (later upgraded to Java 25 on 2026-05-09)
- **Memory Leak Fixed**: original atomic-swap KieContainer disposal prevented OOM (superseded 2026-05-10 by Drools 10 `updateToVersion` + `KieRepository.removeKieModule`)
- **Memory Monitoring**: `GET /admin/memory/info` endpoint

See [`project-documentation/14-security-architecture.md`](project-documentation/14-security-architecture.md) and [`project-documentation/30-runbooks-and-monitoring.md`](project-documentation/30-runbooks-and-monitoring.md) for full details.

## Project Overview

This is a Drools Rule Engine Microservice designed for high-performance business rule execution (100-1000 RPS). Rules are stored in AWS S3 and executed via REST API.

**Tech Stack**: Java 25 (enforced), Spring Boot 3.5.3, Drools 10.2.0, AWS S3, Redis (optional), Micrometer, Resilience4j, Docker & Docker Compose, AWS ECS

**Health Status**: 9/10 - 548 unit tests + 14 Testcontainers integration tests (CI-only, surefire-excluded on macOS-DinD), 96%/90% coverage (pre-modernization baseline), 39/42 security fixes complete, load-tested at 1000 rules (single-container 2026-05-10) + 3-replica multi-container + Redis-kill failure mode (Phase 9.4 2026-05-24), Sonar QG OK (0 maintainability / 0 reliability / 0 security issues)

## Common Commands

### Java 25 Setup (Required for Local Development)

**IMPORTANT**: Java 25 is required. Maven will enforce this automatically.

```bash
# Option 1: Use the setup script (temporary for current terminal)
source ./set-java-env.sh

# Option 2: Permanent setup (add to ~/.zshrc or ~/.bashrc)
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"

# Verify Java 25 is active
java -version   # Should show "openjdk version 25.x.x"
mvn -version    # Should show "Java version: 25.x.x"
```

### Build & Run (once project is initialized)

#### Docker Development (Recommended)
```bash
# Start complete development stack (LocalStack + Redis + Application)
docker-compose up -d

# Build optimized Docker image (347MB)
docker build -t drools-rule-engine .

# Test Docker build and validation
./scripts/docker-build-test.sh

# View application logs
docker-compose logs -f app

# Stop all services
docker-compose down
```

#### Local Java Development
```bash
# Build
mvn clean package

# Run locally with development profile
mvn spring-boot:run -Dspring.profiles.active=dev

# Run with specific profile
mvn spring-boot:run -Dspring.profiles.active=local
```

### Testing
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=DroolsEngineServiceTest

# Run with coverage
mvn test jacoco:report

# Integration tests only
mvn test -Dtest=*IntegrationTest

# Skip tests during build
mvn clean package -DskipTests
```

### Code Quality
```bash
# Format code
mvn spotless:apply

# Check formatting
mvn spotless:check

# Run static analysis
mvn compile spotbugs:check
```

## Architecture Overview

### Package Structure
```
com.company.drools/
├── api/               # REST endpoints, DTOs, validation, filters
│   ├── controller/    # REST endpoints
│   ├── dto/          # Data transfer objects
│   ├── exception/    # Exception handlers
│   ├── validation/   # Custom validation annotations
│   └── filter/       # Security and request filters
├── core/              # Business logic and rule engine
├── storage/           # S3 and file storage implementations  
├── cache/             # LRU and Redis caching
├── common/            # Shared utilities (log sanitization)
└── config/            # Spring configuration classes
```

### Key Architectural Decisions

1. **Rule Storage**: Rules are stored as .drl files in S3 with hierarchical organization (e.g., `pricing/discount/black-friday.drl`)

2. **Caching Strategy** (refactored 2026-05-20):
   - When `REDIS_ENABLED=true`: `RedisCachedRuleStorage` decorates the base `RuleStorage` — read-through Redis cache of DRL text with configurable TTL (default 15 min via `REDIS_DRL_RULES_TTL_MINUTES`)
   - When `REDIS_ENABLED=false`: direct base storage, no caching layer
   - **Cross-task convergence**: when `REDIS_PUBSUB_ENABLED=true` (default when Redis is on), `RuleRefreshPublisher` emits events on `drools:rule:events` channel after refresh; sibling ECS tasks' `RuleRefreshSubscriber` receives and refreshes their own `kieContainer` within ~1 sec (single-rule) or ~7 min (bulk). Self-emitted events filtered via per-instance UUID
   - Execution path: `DroolsEngineService.kieContainer` (all compiled rules, no eviction) — Redis stores raw DRL text only, compiled KieBases live exclusively in the long-lived `KieContainer`
   - **Replaces dead `RuleCache` layer** (LocalLRUCache + old RedisRuleCache) — those were written to but never read from; deleted in Phase 4+5

3. **API Design**:
   - Main API on port 8080 (`/execute-rule`)
   - Admin API on port 8080 (`/admin/*`) — same port as main API
   - Actuator API on port 8081 (`/actuator/*`) — Spring Boot management port

4. **Thread Safety**: Each rule execution uses a new KieSession (stateless)

5. **Rule ID Transformation**: `pricing.discount.black-friday` → `pricing/discount/black-friday.drl`

6. **Security Architecture**: 
   - Multi-layer input validation with custom annotations
   - Rate limiting with configurable per-client limits
   - Request size protection and CORS configuration
   - Log sanitization to prevent sensitive data exposure

7. **Performance Architecture**:
   - Connection pooling for S3 and HTTP clients
   - Custom thread pools for rule execution and storage operations
   - Circuit breakers for external service fault tolerance
   - Request timeout handling and monitoring

8. **Monitoring Architecture**:
   - Vendor-agnostic metrics with Micrometer
   - Structured JSON logging with correlation IDs
   - Component health checks (Drools, S3, Redis, Circuit Breakers)
   - Thread pool and performance monitoring endpoints

9. **Docker Architecture**:
   - Multi-stage Docker build (Maven build → Amazon Corretto Alpine runtime)
   - Optimized image size: ~347MB with security hardening
   - Non-root user execution, health checks, JVM container optimization
   - Complete docker-compose dev stack with LocalStack S3 and Redis

## Environment Variables

Critical environment variables for local development:
```bash
# Rule source
RULE_SOURCE=s3                    # or 'local' for development
RULE_BUCKET_NAME=local-rules      # S3 bucket name
AWS_ENDPOINT=http://localhost:4566 # LocalStack endpoint

# Redis (optional decorator over base storage + pub/sub fan-out)
REDIS_ENABLED=false
REDIS_URL=redis://localhost:6379
REDIS_DRL_RULES_TTL_MINUTES=15
REDIS_DRL_RULES_KEY_PREFIX=drools:rule:
REDIS_PUBSUB_ENABLED=true                # only active when REDIS_ENABLED=true
REDIS_REFRESH_CHANNEL=drools:rule:events

# Performance
RULE_EXECUTION_TIMEOUT_SECONDS=30
DROOLS_THREAD_POOL_MAX_SIZE=50
AWS_S3_MAX_CONNECTIONS=50

# Security
DROOLS_VALIDATION_DATA_MAX_FIELDS=100
DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=1000
DROOLS_CORS_ALLOWED_ORIGINS=*
MAX_HTTP_REQUEST_SIZE=10MB

# Monitoring
DROOLS_CB_S3_FAILURE_RATE=50
DROOLS_HTTP_CONNECTION_TIMEOUT=10

# Docker
SPRING_PROFILES_ACTIVE=docker        # Container profile
JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

## Development Workflow

1. **Current Status**: 39/42 security findings addressed (Phases 1–9 complete, 2026-02-26); stack modernized 2026-05-09 (Java 25, Spring Boot 3.5.3, Drools 10.2.0); Drools 10 rule-loading rework + sample-rules expansion + 1000-rule load test 2026-05-10; Sonar Wave 4 (maintainability 178→0, reliability 5→0, 2 security hotspots resolved) 2026-05-11; Redis cache + pub/sub layer + Phase 9 load-test harness + Phase 9.4 hardening (SCAN-CB-wrap, REDIS_TIMEOUT, pub/sub recovery backoff) 2026-05-20 → 2026-05-24; **548 unit tests + 14 integration**; 96% / 90% coverage (pre-modernization baseline); documentation rebuild 2026-05-08 with refreshes through 2026-05-24 (40 numbered docs in [`project-documentation/`](project-documentation/)). Canonical overview: [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md).

2. **One-Command Development Environment**: Complete automated setup with validation
   ```bash
   # Complete automated setup (recommended)
   ./setup-dev-environment.sh
   
   # Or manual setup
   docker-compose up -d
   
   # Initialize LocalStack S3 with 17 sample rules
   ./init-localstack.sh
   
   # Validate LocalStack setup
   ./scripts/test-localstack.sh
   
   # Check all services status
   docker-compose ps
   ```

3. **Rule Development**: Rules are Drools .drl files. Example structure:
   ```drools
   package com.company.rules.pricing.discount
   
   rule "Rule Name"
   when
       $data : Map(...)
   then
       // Business logic
   end
   ```

4. **Docker Validation**: Comprehensive testing and validation
   ```bash
   # Build and test Docker image
   ./scripts/docker-build-test.sh

   # Manual Docker validation steps
   docker build -t drools-rule-engine:latest .
   docker images drools-rule-engine:latest  # Should be ~347MB

   # Test container startup and health
   docker run -d --name test-container -p 9080:8080 -p 9081:8081 \
     -e RULE_SOURCE=memory drools-rule-engine:latest
   curl http://localhost:9081/admin/health
   docker stop test-container && docker rm test-container
   ```

5. **Memory Monitoring** (NEW): Real-time memory diagnostics
   ```bash
   # Check current memory status
   curl http://localhost:8080/admin/memory/info | jq

   # Monitor heap usage
   curl -s http://localhost:8080/admin/memory/info | jq '.heap'

   # Real-time monitoring (updates every 5 seconds)
   watch -n 5 'curl -s http://localhost:8080/admin/memory/info | jq ".heap.usagePercent"'

   # Test memory leak fix (should remain stable)
   for i in {1..10}; do
       echo "Refresh $i/10"
       curl -X POST http://localhost:8080/admin/refresh-rules
       sleep 3
       curl -s http://localhost:8080/admin/memory/info | jq '.heap.usedMB'
   done
   # Memory should NOT grow by 10-100MB each refresh

   # Check GC logs
   tail -f gc-logs/gc.log

   # Check heap dumps (if OOM occurred)
   ls -lh heap-dumps/
   ```

## Implementation Status

All phases shipped:
1. ✅ Core Infrastructure (Spring Boot + Drools)
2. ✅ Storage & Caching (S3 + Redis)
3. ✅ Production Readiness (Security, Performance, Monitoring)
4. ✅ Testing & Documentation
5. ✅ Deployment & Infrastructure
6. ✅ Critical Fixes — original Java 17 enforcement, memory-leak hardening, memory monitoring (2026-02-19)
7. ✅ Security Hardening — 39/42 findings, Phases 1–9 (2026-02-26)
8. ✅ Stack Modernization — Java 17→25, Spring Boot 3.2.5→3.5.3, Drools 8.44.0→10.2.0 (2026-05-09)
9. ✅ Drools 10 rule-loading rework + sample-rules expansion (10→17) + 1000-rule load test (2026-05-10)
10. ✅ Sonar quality gates — Maintainability 178→0, Reliability 5→0, Security hotspots 2→0 (2026-05-11)
11. ✅ Redis cache + pub/sub layer — `RedisCachedRuleStorage` decorator + `RuleRefreshPublisher`/`Subscriber`; dead `RuleCache`/`LocalLRUCache`/`RedisRuleCache` deleted (~600 LOC); env-var migration (ADR-016, 2026-05-20)
12. ✅ Phase 9 load-test harness (3-replica + nginx + Redis-kill failure mode) + Phase 9.4 production hardening (SCAN-CB-wrap, `REDIS_TIMEOUT` env var lowering Lettuce timeout 2000ms→500ms, `RedisMessageListenerContainer.setRecoveryBackoff(FixedBackOff(2s, ∞))`) (2026-05-24)

Current snapshot:
- **Health Score**: 9/10
- **Test Coverage**: 96.2% instruction / 89.7% branch (548 unit + 14 integration tests; coverage is the pre-modernization JaCoCo baseline — roughly preserved through the 2026-05-20 dead-cache deletion and 2026-05-24 Phase 9.4 hardening, not yet re-run)
- **Security**: 39/42 findings addressed
- **Performance**: 100–1000 RPS target, P99 < 100ms cached / < 500ms cache miss (load-tested at 1000 rules — see [`39-load-test-findings.md`](project-documentation/39-load-test-findings.md))

For the canonical narrative — phase history, ADRs, performance targets, testing strategy, runbooks — see [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md) and the 40 numbered docs it indexes.

## Important Project Files

- [`project-documentation/`](project-documentation/) — full 40-doc corpus, the canonical reference
- [`set-java-env.sh`](set-java-env.sh) — Java 25 environment setup script
- [`setup-dev-environment.sh`](setup-dev-environment.sh) — one-command local dev setup
- [`init-localstack.sh`](init-localstack.sh) — LocalStack bootstrap (reads from `sample-rules/`)
- [`sample-rules/`](sample-rules/) — 17 sample DRL files (single source of truth: 10 original + 7 added 2026-05-10 covering accumulate/exists/not/salience/regex/temporal/accumulate-with-collect patterns)
- [`scripts/test-localstack.sh`](scripts/test-localstack.sh) — validates LocalStack S3 bucket and uploaded rules
- [`scripts/docker-build-test.sh`](scripts/docker-build-test.sh) — automated Docker build and validation
- [`scripts/e2e-load-test.sh`](scripts/e2e-load-test.sh) — one-command E2E + 5-min load test with hot reload
- [`scripts/run-load-test.sh`](scripts/run-load-test.sh) — full load-test orchestrator (Phase 0–8, 1000 rules, JMeter)
- `gc-logs/`, `heap-dumps/` — runtime diagnostics output (gitignored content)
- [`.ai-workspace/snap-memory/`](.ai-workspace/snap-memory/) — session memory files for `snap-memory` AI workflow

## Quick development setup

```bash
# One-command setup (recommended)
./setup-dev-environment.sh

# View logs and test
docker-compose logs -f app
curl http://localhost:8080/admin/health
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100}}'

# Stop
docker-compose down
```

For full deployment scenarios, configuration reference, troubleshooting, and runbooks, see [`project-documentation/`](project-documentation/) — start at [`00-system-overview.md`](project-documentation/00-system-overview.md).