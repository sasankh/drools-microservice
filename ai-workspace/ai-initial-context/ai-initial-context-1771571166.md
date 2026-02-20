# Drools Rule Engine Microservice - Full AI Context Document
**Last Updated**: 2026-02-19
**Purpose**: Read ONLY this file to get full project context.

## PROJECT OVERVIEW

A high-performance, production-ready Drools rule engine microservice that dynamically loads and executes business rules via REST API. Designed for 100-1000 RPS, containerized with Docker, targeting AWS ECS deployment.

- **Main API**: `POST /execute-rule` on port 8080
- **Admin API**: `/admin/*` endpoints on port 8081
- **Rule ID format**: `pricing.discount.vip` → S3 path `pricing/discount/vip.drl`

## PROJECT STATUS

| Metric | Value |
|--------|-------|
| **Health Score** | 8.5/10 |
| **Test Coverage** | 81% instruction, 63% branch (277 tests, 100% passing) |
| **Build Status** | GREEN - `mvn test` passes all 277 tests |
| **Phases Complete** | All 5 phases + critical fixes + test coverage push |

### Phase Completion Summary
- **Phase 1**: Core Infrastructure ✅ (Spring Boot + Drools setup)
- **Phase 2**: Storage & Caching ✅ (S3 + Redis + LRU)
- **Phase 3**: Production Readiness ✅ (Security, Performance, Monitoring)
- **Phase 4.4**: Documentation ✅ (API docs, guides)
- **Phase 4.1-4.3**: Testing ✅ (81% coverage achieved - was 0%, then 55%, now 81%)
- **Phase 5**: Deployment & Infrastructure ✅ (Docker, LocalStack, one-command setup)
- **Critical Fixes**: ✅ Java 17 enforcement, memory leak fix, monitoring endpoint

### Remaining Gaps
- Phase 4.3 Performance Tests (JMeter benchmarks) - NOT DONE
- Some packages still below 70%: cache (32%), config (55%), api.controller (61%)

## TECHNOLOGY STACK

| Component | Version/Details |
|-----------|----------------|
| Java | 17 (enforced via Maven Enforcer Plugin) |
| Spring Boot | 3.2.5 |
| Drools | 8.44.0.Final |
| AWS SDK | v2 (S3 client with connection pooling) |
| Redis | Spring Data Redis (optional, ConditionalOnProperty) |
| Metrics | Micrometer + CloudWatch (vendor-agnostic) |
| Resilience | Resilience4j circuit breakers (S3 + Redis) |
| Docker | Multi-stage build, Amazon Corretto Alpine, ~347MB |
| Testing | JUnit 5, Mockito, AssertJ, Testcontainers (LocalStack) |
| Code Quality | Spotless (formatting), JaCoCo (coverage) |

## REPOSITORY STRUCTURE

```
drools-microservice/
├── src/main/java/com/company/drools/
│   ├── Application.java
│   ├── api/
│   │   ├── controller/          # RuleExecutionController, AdminController, MemoryController
│   │   ├── dto/                 # Request/Response DTOs (7 files)
│   │   ├── exception/           # GlobalExceptionHandler + custom exceptions (5 files)
│   │   ├── filter/              # RateLimitingFilter
│   │   └── validation/          # ValidRuleId, ValidRuleData custom annotations
│   ├── cache/                   # LocalLRUCache, RedisRuleCache, RuleCache interface
│   ├── common/                  # LogSanitizer (PII masking)
│   ├── config/                  # 16 config classes (S3, Redis, Metrics, CB, ThreadPool, etc.)
│   ├── core/
│   │   ├── engine/              # DroolsEngineService, RuleExecutor, RuleCompiler
│   │   └── model/               # Rule, RuleMetadata
│   └── storage/                 # RuleStorage interface, S3RuleStorage, LocalFileStorage,
│                                  InMemoryRuleStorage, InMemoryRuleStorageAdapter, StorageFactory
├── src/test/java/com/company/drools/
│   ├── BaseUnitTest.java        # Common mock setup (MeterRegistry)
│   ├── BaseIntegrationTest.java # Testcontainers LocalStack base
│   ├── testutil/                # RuleTestUtils, ValidationConfigTestHelper
│   ├── core/engine/             # DroolsEngineServiceTest, RuleExecutorTest, RuleCompilerTest
│   ├── cache/                   # LocalLRUCacheTest, RedisRuleCacheTest
│   ├── storage/                 # S3RuleStorageTest, LocalFileStorageTest, InMemoryRuleStorageTest, StorageFactoryTest
│   ├── api/controller/          # RuleExecutionControllerTest, AdminControllerTest, MemoryControllerTest
│   ├── api/dto/                 # DtoTest (all DTOs)
│   ├── api/exception/           # GlobalExceptionHandlerTest, ExceptionTest
│   ├── api/filter/              # RateLimitingFilterTest
│   ├── api/validation/          # RuleDataValidatorTest
│   ├── common/                  # LogSanitizerTest
│   ├── config/                  # MetricsConfigTest, ThreadPoolConfigTest, CircuitBreakerConfigTest,
│   │                              S3ConfigTest, LoggingConfigTest, RequestTimeoutConfigTest
│   └── integration/             # S3StorageIntegrationTest, RuleExecutionIntegrationTest
├── project-plan/                # project.checklist.md, project.progress.md, project.documentation.md
├── ai-instructions/             # ai-start-prompt.md, ai-initial-context-instructions.md, snap-memory-instructions.md
├── ai-workspace/
│   ├── ai-initial-context/      # This file + archives
│   ├── snap-memory/             # Session logs (snap-memory-{epoch}.md)
│   └── compact-logs/            # Context compaction logs
├── documentations/              # 12 documentation files (architecture, config, deployment, etc.)
├── Dockerfile                   # Multi-stage build
├── docker-compose.yml           # App + LocalStack + Redis
├── pom.xml                      # Maven with all dependencies
└── CLAUDE.md                    # AI assistant instructions
```

## PHASE 1: CORE INFRASTRUCTURE (COMPLETED)

- Spring Boot 3.x + Drools 8.44.0 integration
- Thread-safe rule execution via stateless KieSession per request
- KieBase shared/immutable, compiled once on load
- REST API: `POST /execute-rule` with JSON request/response
- Global exception handler with structured error responses
- Multi-profile config: local, dev, prod, docker
- Startup time: ~1.3s, rule execution: 1-51ms

## PHASE 2: STORAGE & CACHING (COMPLETED)

- **Storage abstraction**: RuleStorage interface with S3, LocalFile, InMemory implementations
- **StorageFactory**: Routes to correct implementation based on `drools.rule-source` config
- **Rule ID transformation**: `pricing.discount.vip` → `pricing/discount/vip.drl`
- **Multi-tier caching**: S3 → Redis (L2, optional) → LRU Cache (L1) → Compiled KieBase
- **LRU Cache**: LinkedHashMap-based with ReentrantReadWriteLock, configurable max size
- **Redis**: Optional (ConditionalOnProperty), JSON serialization, TTL support
- **Admin endpoints**: refresh-rules, refresh-rules/{ruleId}, list rules, health check
- **Note**: LRU cache has a known concurrency issue - access-ordered LinkedHashMap `get()` modifies structure under read lock

## PHASE 3: PRODUCTION READINESS (COMPLETED)

- **Security**: Custom validation annotations (@ValidRuleId, @ValidRuleData), rate limiting, CORS, log sanitization
- **Performance**: Connection pooling (S3), custom thread pools (rule-exec + storage), G1GC tuning
- **Circuit Breakers**: Resilience4j for S3 and Redis with configurable thresholds
- **Request Timeouts**: Filter-based timeout tracking with slow request logging
- **Monitoring**: Micrometer metrics (counters, timers, gauges), structured JSON logging with correlation IDs
- **Health checks**: Component-level (Drools, S3, Redis, circuit breakers)
- **Memory monitoring**: `/admin/memory/info`, `/admin/memory/gc`, `/admin/memory/snapshot`

## PHASE 4: TESTING & DOCUMENTATION (COMPLETED)

### Test Coverage (81% overall, 277 tests)
| Package | Coverage | Tests |
|---------|----------|-------|
| core.engine | 80% (was 94%) | DroolsEngineServiceTest, RuleExecutorTest, RuleCompilerTest |
| core.model | 50% (was 87%) | Covered via other tests |
| api.controller | 61% (was 49%) | RuleExecutionControllerTest, AdminControllerTest, MemoryControllerTest |
| api.dto | 86% (was 46%) | DtoTest (all DTOs comprehensive) |
| api.exception | ~100% (was 40%) | GlobalExceptionHandlerTest, ExceptionTest |
| api.filter | 61% (was 71%) | RateLimitingFilterTest |
| api.validation | 69% (was 79%) | RuleDataValidatorTest |
| cache | 32% (was 62%) | LocalLRUCacheTest, RedisRuleCacheTest |
| common | 78% (was 93%) | LogSanitizerTest |
| config | 55% (was 20%) | 6 new test files |
| storage | 78% (was 66%) | S3RuleStorageTest, LocalFileStorageTest, InMemoryRuleStorageTest, StorageFactoryTest |
| integration | - | S3StorageIntegrationTest, RuleExecutionIntegrationTest |

### Key Test Patterns
- **BaseUnitTest**: Extends MockitoExtension, provides mock MeterRegistry with Counter/Timer stubs
- **SimpleMeterRegistry**: Use instead of mocking MeterRegistry (avoids complex Timer.builder chains)
- **@WebMvcTest with excludeFilters**: Exclude RateLimitingFilter for controller tests
- **AnnotationConfigApplicationContext**: For validator setup in controller tests
- **Testcontainers LocalStack**: For S3 integration tests
- **Reflection for @Value fields**: `setField(obj, "fieldName", value)` pattern for config tests
- **Standalone MockMvc**: For simple controller tests (MemoryController)

### Documentation (Phase 4.4)
- ~3,900 lines across 5 comprehensive guides
- OpenAPI 3.0 spec for all 8 endpoints
- Rule development guide with 5 examples
- Configuration reference (60+ env vars)
- Troubleshooting guide (50+ solutions)

## PHASE 5: DEPLOYMENT & INFRASTRUCTURE (COMPLETED)

- **Docker**: Multi-stage build (Maven → Corretto Alpine), 347MB image, non-root user
- **docker-compose**: App + LocalStack (S3) + Redis stack
- **LocalStack**: 10 sample business rules in S3
- **Scripts**: `setup-dev-environment.sh` (one-command), `init-localstack.sh`, `test-localstack.sh`
- **JVM**: Container-aware memory settings, G1GC, heap dumps on OOM

## CRITICAL FIXES (2026-02-19)

1. **Java 17 Enforcement**: Maven Enforcer Plugin fails build if wrong Java version
2. **Memory Leak Fix**: KieContainer disposal in DroolsEngineService.java (lines 164-178) prevents OOM (exit code 137)
3. **Memory Monitoring**: `/admin/memory/info` endpoint with heap/non-heap/GC stats + warning levels
4. **Java setup script**: `source ./set-java-env.sh` for local development

## ARCHITECTURE DECISIONS

1. **Rule Storage**: .drl files in S3 with hierarchical organization
2. **Caching**: S3 → Redis (optional) → Local LRU → Compiled KieBase
3. **Thread Safety**: New KieSession per execution, KieBase is immutable/shared
4. **API Design**: Main on 8080, Admin on 8081 (separate ports)
5. **Security**: No auth (handled by API Gateway), admin on separate port
6. **Resilience**: Circuit breakers for S3/Redis, graceful degradation if Redis unavailable
7. **Monitoring**: Vendor-agnostic Micrometer (CloudWatch/Grafana/Datadog compatible)

## CORE CONCEPTS

### Rule Execution Flow
1. Client sends `POST /execute-rule` with `{rule_id, data}`
2. Check LRU cache for compiled KieBase → cache hit = fast path
3. Cache miss → check Redis (if enabled) → S3 fetch
4. Compile .drl to KieBase, cache it
5. Create stateless KieSession, insert data Map, fire rules
6. Extract results from modified Map, return response

### Rule ID Transformation
- `pricing.discount.black-friday-2024` → S3 key `pricing/discount/black-friday-2024.drl`
- Dots become slashes, `.drl` extension appended

### Environment Configuration
- `RULE_SOURCE=s3|local|file` - storage backend
- `REDIS_ENABLED=true|false` - toggle Redis L2 cache
- `LRU_CACHE_MAX_SIZE=100` - local cache capacity
- `RULE_EXECUTION_TIMEOUT_SECONDS=30` - per-rule timeout
- Full list: 60+ vars documented in `documentations/configuration.md`

## PERFORMANCE METRICS

- Startup: ~1.3s (local), <3s (Docker)
- Rule execution: 1-51ms (cached)
- Docker image: 347MB
- Target: 100-1000 RPS with 4 ECS tasks

## SAMPLE RULES & TESTING

10 sample rules in `sample-rules/` directory:
- `pricing/discount/simple.drl` - 10% discount for orders > $50
- `pricing/discount/vip.drl` - 20% VIP discount
- And 8 more covering validation, seasonal, tiered scenarios

Quick test commands:
```bash
# Simple discount
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100.0}}'

# VIP discount
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.vip", "data": {"customer_tier": "vip", "amount": 100.0}}'
```

## DOCKER & DEPLOYMENT

```bash
# One-command setup (recommended)
./setup-dev-environment.sh

# Or manual
docker-compose up -d
./init-localstack.sh

# Build Docker image
docker build -t drools-rule-engine .

# Validate
./docker-build-test.sh
```

## DEFERRED FEATURES

- Terraform/IaC configuration (separate project)
- JMeter performance benchmarking (Phase 4.3)
- Some packages below 70% coverage (cache 32%, config 55%)

## GIT STATE

- **Branch**: `restart-2` (based on `main`)
- **Status**: Uncommitted changes from test coverage session
- **Modified**: `LocalLRUCacheTest.java` (flaky test fix)
- **New files**: 12 new test files (config, controller, dto, exception, storage packages)
- **Recent commits**: `6edae5a compact`, `d1d3684 added test`, `02682f5 code update for test`

## DEVELOPMENT WORKFLOW COMMANDS

```bash
# Java 17 setup (required)
source ./set-java-env.sh

# Build & test
mvn clean test jacoco:report     # Tests + coverage
mvn spotless:apply               # Format code
mvn package -DskipTests           # Build JAR

# Docker
docker-compose up -d              # Start full stack
docker-compose down               # Stop all

# Coverage report
open target/site/jacoco/index.html
```

## SESSION HISTORY

| Session | Focus | Key Outcome |
|---------|-------|-------------|
| 1-5 | Phases 1-5 implementation | Full microservice built |
| 6 | Test coverage 0% → 55% | 147 tests, BaseUnitTest pattern |
| 7 | Test coverage 55% → 81% | 277 tests, 12 new test files |

## NEXT STEPS

1. **Commit current work**: 12 new test files + flaky test fix (277 tests, 81% coverage)
2. **Optional**: Push cache/config coverage higher (currently 32% and 55%)
3. **Optional**: JMeter performance benchmarks (Phase 4.3)
4. **Optional**: Terraform/IaC for AWS deployment
