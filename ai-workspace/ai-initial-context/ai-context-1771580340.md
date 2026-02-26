# AI Context - Drools Rule Engine Microservice

**Last Updated**: 2026-02-20 (Session 10 — All phases complete)

## Project Status

**Health Score**: 8.5/10
- **Test Coverage**: 96.2% instruction / 89.7% branch (550 tests, 100% passing)
- **Build**: SUCCESS (Java 17 enforced by Maven Enforcer Plugin)
- **Memory**: Stable (KieContainer disposal fix)
- **Docker**: 347MB image, all 10 sample rules verified

## Coverage by Package

| Package | Instruction | Branch |
|---------|------------|--------|
| api/validation | 100% | 94.6% |
| api/controller | 97.4% | 91.2% |
| api/filter | 98.6% | 94.1% |
| cache | 98.3% | 90.0% |
| common | 98.6% | 90.5% |
| storage | 94.1% | 89.5% |
| config | 93.1% | 83.3% |
| core/engine | 95.7% | 83.3% |
| api/dto | 96.0% | 86.8% |
| core/model | 100% | 100% |
| **Overall** | **96.2%** | **89.7%** |

## Test Files (550 tests across 40+ files)

### Core Engine
- DroolsEngineServiceTest.java, RuleCompilerTest.java, RuleExecutorTest.java

### Controllers
- RuleExecutionControllerTest.java, AdminControllerTest.java (36), MemoryControllerTest.java (15)

### Storage & Cache
- S3RuleStorageTest.java, LocalFileStorageTest.java, InMemoryRuleStorageTest.java (17), StorageFactoryTest.java (5)
- LocalLRUCacheTest.java (40), RedisRuleCacheTest.java (28), CacheStatisticsTest.java (15)

### Config
- DroolsConfigTest.java (4), RateLimitingConfigTest.java (19), RedisConfigTest.java (9)
- RuleLoadingConfigTest.java (6), StorageConfigTest.java (17), S3ConfigTest.java (10)
- MetricsConfigTest.java (13), ThreadPoolConfigTest.java (4), CircuitBreakerConfigTest.java (5)
- LoggingConfigTest.java (9), RequestTimeoutConfigTest.java (4)

### API / Validation / Security
- DtoTest.java (37), GlobalExceptionHandlerTest.java (9), ExceptionTest.java (9)
- RuleDataValidatorTest.java (10), LogSanitizerTest.java (20+), RateLimitingFilterTest.java (7)

### Integration
- S3StorageIntegrationTest.java (6), RuleExecutionIntegrationTest.java (8)

## Key Facts

- API request DTO uses **snake_case** (`rule_id`, not `ruleId`) via `@JsonProperty("rule_id")`
- Admin endpoints are on **port 8080** (same as main API) in Docker — CLAUDE.md says 8081
- `init-localstack.sh` reads `.drl` files from `sample-rules/` directory (126 lines, no hardcoded rules)
- Maven requires `source ./set-java-env.sh` before builds (enforcer checks Java version)
- Spotless formatter runs on pre-commit — always run `mvn spotless:apply` after edits

## Test Patterns

- Unit tests extend `BaseUnitTest` (sets up Mockito) or use plain JUnit 5
- `@Value` fields injected via `ReflectionTestUtils.setField()` or manual `Field.setAccessible()`
- `ValidationConfigTestHelper` provides mock `ValidationConfig` for validator tests
- `RuleTestUtils` has helpers: `createSimpleRule()`, `createTestData()`
- Metrics verified with real `SimpleMeterRegistry` (not mocked)
- S3 tests mock `S3Client` with builders: `S3Object.builder()`, `ListObjectsV2Response.builder()`

## Critical Fixes Applied

1. **Java 17 Enforcement** — Maven Enforcer Plugin, range `[17,18)`
2. **Memory Leak Fixed** — KieContainer disposal (DroolsEngineService.java lines 164-178)
3. **Memory Monitoring** — GET /admin/memory/info endpoint
4. **init-localstack.sh** — Refactored from 267→126 lines, no hardcoded DRL

## Implementation Phases

| Phase | Status |
|-------|--------|
| Phase 1: Core Infrastructure | COMPLETED |
| Phase 2: Storage & Caching | COMPLETED |
| Phase 3: Production Readiness | COMPLETED |
| Phase 4: Testing & Documentation (4.3 JMeter deferred) | COMPLETED |
| Phase 5: Deployment & Infrastructure | COMPLETED |
| Phase 6: Critical Fixes & Hardening | COMPLETED |

## Remaining / Optional

- Phase 4.3: JMeter performance benchmarks (deferred)
- Java 21 upgrade (user plans to return to this)
- Coverage gaps: config (83.3% branch), core/engine (83.3% branch)
- Docker race condition: app may start before LocalStack init

## Common Commands

```bash
source ./set-java-env.sh        # Setup Java 17
mvn test                        # Run all 550 tests
mvn test jacoco:report          # Generate coverage report
mvn spotless:apply              # Format code
docker-compose up -d            # Start full stack
```

## Important Locations

- **Memory Leak Fix**: `src/main/java/com/company/drools/core/engine/DroolsEngineService.java:164-178`
- **Test Config**: `src/test/java/com/company/drools/api/controller/TestValidationConfig.java`
- **Validation Helper**: `src/test/java/com/company/drools/testutil/ValidationConfigTestHelper.java`
- **Sample Rules**: `sample-rules/` (10 .drl files — pricing, shipping, seasonal, validation)

## Documentation

- `CLAUDE.md` — Development guide (primary reference)
- `project-plan/project.progress.md` — Phase tracking
- `project-plan/project.checklist.md` — Task checklist
- `FIXES-SUMMARY.md` — Critical fixes summary
- `docs/` — API docs, deployment guide, configuration reference, troubleshooting
