# Drools Rule Engine Microservice - Full AI Context Document
**Last Updated**: 2026-02-20
**Purpose**: Read ONLY this file to get full project context.

## PROJECT OVERVIEW

A high-performance Drools Rule Engine Microservice for business rule execution (100-1000 RPS). Rules are stored as `.drl` files in AWS S3, cached locally (LRU) and optionally in Redis, and executed via REST API. The project is feature-complete and production-ready.

## PROJECT STATUS

**Health Score**: 8.5/10
**Branch**: `restart-3`
**Git State**: Clean (all changes committed, up to date with origin)

| Phase | Status |
|-------|--------|
| Phase 1: Core Infrastructure | COMPLETED |
| Phase 2: Storage & Caching | COMPLETED |
| Phase 3: Production Readiness | COMPLETED |
| Phase 4: Testing & Documentation (4.3 JMeter deferred) | COMPLETED |
| Phase 5: Deployment & Infrastructure | COMPLETED |
| Phase 6: Critical Fixes & Hardening | COMPLETED |

**Test Coverage**: 96.2% instruction / 89.7% branch (550 tests, 100% pass rate)

## TECHNOLOGY STACK

- **Java**: 17 (enforced by Maven Enforcer Plugin, range `[17,18)`) — user wants to upgrade to 21 later
- **Spring Boot**: 3.x
- **Drools**: 8.44.0.Final
- **AWS S3**: Rule storage (LocalStack for dev)
- **Redis**: Optional caching layer
- **Micrometer**: Metrics (vendor-agnostic — CloudWatch, Grafana, Datadog)
- **Resilience4j**: Circuit breakers for S3 and Redis
- **Docker**: Multi-stage build, Amazon Corretto 17 Alpine, ~347MB image
- **Testcontainers**: LocalStack for integration tests

## REPOSITORY STRUCTURE

```
drools-microservice/
├── src/main/java/com/company/drools/
│   ├── api/                    # REST endpoints, DTOs, validation, filters, exceptions
│   │   ├── controller/         # RuleExecutionController, AdminController, MemoryController
│   │   ├── dto/               # RuleExecutionRequest/Response, ErrorResponse, HealthCheckResponse, etc.
│   │   ├── exception/         # GlobalExceptionHandler, custom exceptions
│   │   ├── validation/        # @ValidRuleId, @ValidRuleData custom annotations
│   │   └── filter/            # RateLimitingFilter, RequestSizeValidationFilter
│   ├── core/                   # Business logic and rule engine
│   │   ├── engine/            # DroolsEngineService, RuleExecutor, RuleCompiler
│   │   └── model/             # Rule, RuleMetadata
│   ├── storage/                # S3RuleStorage, LocalFileStorage, InMemoryRuleStorage, StorageFactory
│   ├── cache/                  # LocalLRUCache, RedisRuleCache, RuleCache interface
│   ├── common/                 # LogSanitizer
│   └── config/                 # 12+ config classes (S3, Redis, CORS, Metrics, ThreadPool, etc.)
├── src/test/java/              # 40+ test files, 550 tests
├── sample-rules/               # 10 .drl files (single source of truth)
│   ├── pricing/discount/       # simple.drl, vip.drl, bulk.drl, first-time.drl
│   ├── pricing/shipping/       # standard.drl, express.drl
│   ├── seasonal/holiday/       # discount.drl, blackfriday.drl
│   └── validation/customer/    # age.drl, credit.drl
├── documentations/             # architecture, deployment, troubleshooting, jvm-optimization, etc.
├── project-plan/               # project.checklist.md, project.progress.md
├── ai-workspace/               # AI session context, summaries, snap-memory
│   ├── ai-initial-context/     # This file + archives
│   ├── ai-summary/             # Session changelogs (FIXES-SUMMARY, MEMORY-LEAK-ANALYSIS, etc.)
│   ├── snap-memory/            # Session-by-session implementation history
│   └── compact-logs/           # Context compression logs
├── scripts/                    # start-production.sh, start-development.sh
├── CLAUDE.md                   # Primary development guide
├── README.md                   # Project README
├── docker-compose.yml          # LocalStack + Redis + App
├── Dockerfile                  # Multi-stage build
├── init-localstack.sh          # S3 bucket + rule upload (reads from sample-rules/)
├── test-localstack.sh          # LocalStack validation
├── setup-dev-environment.sh    # One-command dev setup
└── set-java-env.sh             # Java 17 environment setup
```

## PHASE 1: CORE INFRASTRUCTURE (COMPLETED)

- Spring Boot 3.x + Drools 8.44.0 project structure
- DroolsEngineService with thread-safe rule execution (ReentrantReadWriteLock)
- RuleExecutor with CompletableFuture-based execution and timeout handling
- RuleCompiler for DRL compilation to KieBase
- REST API: POST /execute-rule with proper JSON handling
- GlobalExceptionHandler with custom exceptions
- Multi-profile configuration (local/dev/prod/docker)

## PHASE 2: STORAGE & CACHING (COMPLETED)

- RuleStorage interface with 4 implementations (S3, LocalFile, InMemory, Adapter)
- StorageFactory for dynamic storage selection based on config
- Rule ID transformation: `pricing.discount.black-friday` → `pricing/discount/black-friday.drl`
- S3RuleStorage with AWS SDK v2, retry logic, pagination
- LocalLRUCache (thread-safe LinkedHashMap) with eviction tracking
- RedisRuleCache with serialization and circuit breaker
- Admin endpoints: POST /admin/refresh-rules, GET /admin/rules, GET /admin/health

## PHASE 3: PRODUCTION READINESS (COMPLETED)

- **Health & Monitoring**: Component-level health checks (Drools, S3, Redis, Circuit Breakers)
- **Metrics**: Micrometer integration — rule execution timing, cache hit/miss, error tracking
- **Structured Logging**: JSON logging with correlation IDs, MDC context
- **Performance**: Connection pooling (S3), custom thread pools, G1GC optimization
- **Timeout Handling**: Centralized TimeoutConfig, HTTP filter monitoring
- **Circuit Breakers**: Resilience4j for S3 and Redis fault tolerance
- **Security**: Input validation (custom annotations), rate limiting, request size limits, CORS, log sanitization
- **Thread Pool Monitoring**: GET /admin/thread-pools endpoint

## PHASE 4: TESTING & DOCUMENTATION (COMPLETED — 4.3 JMeter deferred)

### Testing (550 tests, 96.2% / 89.7%)
- Unit tests: 40+ test files covering all packages
- Integration tests: Testcontainers LocalStack for S3, full-stack rule execution
- Test infrastructure: BaseUnitTest, BaseIntegrationTest, RuleTestUtils, ValidationConfigTestHelper

### Coverage by Package
| Package | Instruction | Branch |
|---------|------------|--------|
| api/validation | 100% | 94.6% |
| core/model | 100% | 100% |
| common | 98.6% | 90.5% |
| api/filter | 98.6% | 94.1% |
| cache | 98.3% | 90.0% |
| api/controller | 97.4% | 91.2% |
| api/dto | 96.0% | 86.8% |
| core/engine | 95.7% | 83.3% |
| storage | 94.1% | 89.5% |
| config | 93.1% | 83.3% |

### Documentation
- OpenAPI 3.0 spec (api-documentation.yml, 983 lines)
- Architecture guide (documentations/architecture.md, ~1,350 lines)
- Deployment guide, configuration reference, rule development guide, troubleshooting guide
- JVM optimization guide, memory monitoring guide, Java setup guide

## PHASE 5: DEPLOYMENT & INFRASTRUCTURE (COMPLETED)

- Multi-stage Dockerfile: Maven build → Amazon Corretto 17 Alpine runtime (~347MB)
- docker-compose.yml: LocalStack S3 + Redis + App with volume mounts
- init-localstack.sh: 126 lines, reads from sample-rules/ (no hardcoded DRL)
- 10 sample business rules across pricing, shipping, seasonal, validation
- One-command setup: ./setup-dev-environment.sh
- Validation scripts: docker-build-test.sh, test-localstack.sh

## PHASE 6: CRITICAL FIXES & HARDENING (COMPLETED)

- **Java 17 Enforcement**: Maven Enforcer Plugin, range `[17,18)`
- **Memory Leak Fix**: KieContainer disposal in DroolsEngineService (lines 164-178) — fixed OOM exit code 137
- **Memory Monitoring**: GET /admin/memory/info endpoint with heap, GC stats, warnings
- **Test Coverage Push**: 0% → 96.2% instruction / 89.7% branch (550 tests)
- **init-localstack.sh Refactor**: 267 → 126 lines, removed hardcoded DRL
- **Admin Port Fix**: All /admin/* URLs fixed to port 8080 across 30+ files
- **API Field Naming Fix**: `ruleId` → `rule_id` (snake_case) in all docs

## ARCHITECTURE DECISIONS

1. **Ports**: Main API + Admin on port 8080, Actuator on port 8081
   - `/execute-rule`, `/admin/*` → port 8080
   - `/actuator/*` → port 8081 (management.server.port)
2. **API DTO**: Uses snake_case (`rule_id`) via `@JsonProperty("rule_id")`
3. **Caching**: S3 → Redis (optional) → Local LRU → Rule Execution
4. **Thread Safety**: Each rule execution uses a new KieSession (stateless)
5. **Rule ID Transformation**: `pricing.discount.vip` → `pricing/discount/vip.drl`
6. **Storage Factory**: Dynamic selection based on `RULE_SOURCE` env var (s3/local/memory)

## CORE CONCEPTS

- **Rule Execution Flow**: API request → validate → check cache → compile if needed → execute in KieSession → return results
- **Rule Refresh**: POST /admin/refresh-rules reloads all rules from S3, disposes old KieContainers
- **Memory Safety**: Old KieContainers disposed on refresh to prevent OOM
- **Circuit Breakers**: S3 and Redis protected — fast-fail during external service outages

## PERFORMANCE METRICS

- **Startup Time**: 1.3s (target: <60s)
- **Rule Execution**: 1ms (target: <100ms)
- **API Response**: <10ms (target: <500ms)
- **Docker Image**: 347MB (target: <350MB)

## SAMPLE RULES & TESTING

10 .drl files in `sample-rules/`:
- pricing/discount/simple.drl — Basic 10% discount
- pricing/discount/vip.drl — VIP 20% discount
- pricing/discount/bulk.drl — Bulk order discount
- pricing/discount/first-time.drl — First-time customer discount
- pricing/shipping/standard.drl — Standard shipping calc
- pricing/shipping/express.drl — Express shipping calc
- seasonal/holiday/discount.drl — Holiday season discount
- seasonal/holiday/blackfriday.drl — Black Friday promotion
- validation/customer/age.drl — Age validation
- validation/customer/credit.drl — Credit score validation

Test example:
```bash
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100}}'
```

## DOCKER & DEPLOYMENT

```bash
# One-command setup
./setup-dev-environment.sh

# Or manual
docker-compose up -d
# Wait for LocalStack init, then:
curl -X POST http://localhost:8080/admin/refresh-rules

# Build image
docker build -t drools-rule-engine .
```

**Known issue**: Race condition — app may start before LocalStack init creates S3 bucket. Use `POST /admin/refresh-rules` after startup.

## DEFERRED FEATURES

- **Phase 4.3**: JMeter performance benchmarks
- **Java 21 upgrade**: User plans to return to this (pom.xml, enforcer, Dockerfile, set-java-env.sh)
- **Coverage gaps**: config 83.3% branch, core/engine 83.3% branch
- **Docker race condition**: App starts before LocalStack init completes

## GIT STATE

- **Branch**: `restart-3` (up to date with origin)
- **Working tree**: Clean
- **Recent commits**:
  - `3e0b7a0` organized files
  - `5d2fac4` added doc and script
  - `099ae30` docs
  - `43a25f9` added snap
  - `b4e9966` updated localstake to be dynamic

## DEVELOPMENT WORKFLOW COMMANDS

```bash
source ./set-java-env.sh        # Setup Java 17
mvn test                        # Run all 550 tests
mvn test jacoco:report          # Generate coverage report
mvn spotless:apply              # Format code (required before commit)
docker-compose up -d            # Start full stack
curl http://localhost:8080/admin/health          # Health check
curl http://localhost:8080/admin/memory/info     # Memory diagnostics
curl http://localhost:8081/actuator/health       # Actuator health (management port)
```

## FILE ORGANIZATION

- **AI workspace files** moved to organized directories:
  - `ai-workspace/ai-summary/` — Session changelogs (FIXES-SUMMARY, MEMORY-LEAK-ANALYSIS, test-coverage-checklist, etc.)
  - `ai-workspace/ai-initial-context/` — This context file + archives
  - `ai-workspace/snap-memory/` — Session implementation history
- **Documentation** in `documentations/` — architecture, deployment, troubleshooting, jvm-optimization, etc.
- **Project planning** in `project-plan/` — checklist, progress tracker

## NEXT STEPS

1. **Java 21 upgrade** (user plans to return)
2. **JMeter performance tests** (Phase 4.3 deferred)
3. **Docker race condition fix** (add retry/dependency logic)
4. **Project is production-ready** — all core features complete
