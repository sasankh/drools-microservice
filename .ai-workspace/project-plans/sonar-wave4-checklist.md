# Sonar Wave 4 — Execution Checklist

> **STATUS: ✅ COMPLETED 2026-05-11** — All 4 waves (4A, 4B, 4C, 4D) executed. Final result: **0 open maintainability issues** (178 → 0). Quality Gate OK. Commits: `86a9d63`, `e405102`, `d371a36`, `b58bd19`. Items below preserved as reference; all were completed.

**Branch**: `upgrade-java-sonar` | **Starting issues**: 178 | **Final result**: 0

---

## Wave 4A — Production code

### S1192 — String constants
- [ ] `MemoryController.java` — add KEY_USED_MB, KEY_MAX_MB, KEY_USAGE_PERCENT, KEY_COMMITTED_MB; replace all usages
- [ ] `LocalLRUCache.java` — add TAG_CACHE_TYPE, CACHE_TYPE_LOCAL; replace at lines 57, 80, 85
- [ ] `MetricsConfig.java` — add TAG_APPLICATION, TAG_ENVIRONMENT; replace all .tag("application",...) and .tag("environment",...)
- [ ] `LoggingConfig.java` — read line 89, identify literal, extract constant

### S6204 — Stream.toList()
- [ ] `MemoryController.java` lines 109, 124
- [ ] `AdminController.java` line 540
- [ ] `InMemoryRuleStorageAdapter.java` line 61
- [ ] `S3RuleStorage.java` line 323
- [ ] Remove unused `import java.util.stream.Collectors` where applicable

### S6201 — instanceof pattern matching
- [ ] `RuleDataValidator.java` line 90 → `instanceof String strValue`
- [ ] `RuleDataValidator.java` line 116 → `instanceof Number numValue`
- [ ] `LogSanitizer.java` line 123 → `instanceof String stringValue`
- [ ] `ThreadPoolConfig.java` line 129 → `instanceof ThreadPoolTaskExecutor taskExecutor`
- [ ] `ThreadPoolConfig.java` line 148 → same for getStoragePoolStats()

### S1602 — Remove useless lambda curly braces
- [ ] `CircuitBreakerConfig.java` line 89 (S3 onStateTransition)
- [ ] `CircuitBreakerConfig.java` line 99 (S3 onCallNotPermitted)
- [ ] `CircuitBreakerConfig.java` line 138 (Redis onStateTransition)
- [ ] `CircuitBreakerConfig.java` line 148 (Redis onCallNotPermitted)
- [ ] `RuleExecutor.java` line 57 (CompletableFuture supplier)

### S5411 — Null-safe Boolean while condition
- [ ] `S3RuleStorage.java` line 190 → `Boolean.TRUE.equals(response.isTruncated())`
- [ ] `S3RuleStorage.java` line 299 → same
- [ ] `S3RuleStorage.java` line 332 → same

### S7467 — Unnamed catch vars (production)
- [ ] `MemoryController.java` line 161: verify `e` unused → `catch (InterruptedException _)`
- [ ] `RedisRuleCache.java` line 145: verify `e` unused → `catch (CallNotPermittedException _)`
- [ ] `LogSanitizer.java` line 241: verify `e` unused → `catch (ClassCastException _)`
- [ ] `S3RuleStorage.java` line 258: read + verify → fix if unused
- [ ] **`mvn clean compile`** after these changes (IDE ECJ false-positives `_` syntax)
- [ ] `mvn test -Dtest=S3RuleStorageTest,RedisRuleCacheTest` → pass

### S2629 — Suppress security log calls
- [ ] `GlobalExceptionHandler.java` → `@SuppressWarnings("java:S2629")` at class level
- [ ] `RuleExecutionController.java` → same
- [ ] `AdminController.java` → same
- [ ] `MemoryController.java` → on `getMemoryInfo()` method

### S2139 — Remove log from log+rethrow
- [ ] `S3RuleStorage.java` line 110 (CallNotPermittedException in getRule) — remove log.error, keep throw
- [ ] `S3RuleStorage.java` line 123 (SdkException in getRule) — remove log.error, keep throw
- [ ] `S3RuleStorage.java` line 195 (in getAllRules) — remove, keep throw
- [ ] `S3RuleStorage.java` line 218 (in saveRule) — remove, keep throw
- [ ] `S3RuleStorage.java` line 242 (in deleteRule) — remove, keep throw
- [ ] `LocalFileStorage.java` line 91 — remove log, keep throw
- [ ] `LocalFileStorage.java` line 109 — remove log, keep throw
- [ ] `AdminController.java` line 373 — remove log.error, keep rethrow
- [ ] `RuleExecutionController.java` line 128 — remove log.error, GlobalExceptionHandler logs
- [ ] `RequestTimeoutConfig.java` line 69 — read context first, decide remove or suppress

### S112 — Create RuleStorageException
- [ ] Create `src/main/java/com/company/drools/api/exception/RuleStorageException.java`
- [ ] `S3RuleStorage.java` line 134 → `throw new RuleStorageException(...)`
- [ ] `S3RuleStorage.java` line 197 → same
- [ ] `S3RuleStorage.java` line 220 → same
- [ ] `S3RuleStorage.java` line 244 → same
- [ ] `LocalFileStorage.java` line 93 → same
- [ ] `LocalFileStorage.java` line 111 → same

### S1068 — Delete truly unused fields
- [ ] `RedisRuleCache.java` line 39 (CACHE_STATS_KEY): grep for usages → delete if unused
- [ ] `DroolsEngineService.java` line 38: read + grep → delete if unused
- [ ] `S3RuleStorage.java` line 42: read + grep → delete if unused

### Wave 4A gate
- [ ] `mvn clean compile` → BUILD SUCCESS
- [ ] `mvn compile spotbugs:check` → 0 bugs
- [ ] `mvn test -Dtest='!S3StorageIntegrationTest'` → 597 pass
- [ ] `git commit -m "fix(sonar): Wave 4A — production code quality (S1192/S6204/S6201/S1602/S5411/S7467/S2629/S2139/S112/S1068)"`

---

## Wave 4B — Test files

### S5838 (43) — AssertJ fluent assertion modernization
- [ ] `LocalLRUCacheTest.java` lines 65,207,255,308,360,432,441,445,606,618,625,632,649,656 (14)
- [ ] `DtoTest.java` lines 67,127,214,235,258,261,280,379 (8)
- [ ] `AdminAuthFilterTest.java` lines 140,141 (2)
- [ ] `RateLimitingFilterTest.java` lines 371,373 (2)
- [ ] `RequestSizeValidationFilterTest.java` lines 257,258 (2)
- [ ] `RedisRuleCacheTest.java` lines 442,452 (2)
- [ ] `MetricsConfigTest.java` lines 145,152 (2)
- [ ] `DrlSanitizerTest.java` line 459 (1)
- [ ] `RateLimitingConfigTest.java` line 222 (1)
- [ ] `StorageConfigTest.java` line 96 (1)
- [ ] Remaining 7 across misc files

### S5853 (22) — Chain consecutive assertions
- [ ] `ThreadPoolConfigTest.java` lines 34,48,64,76 (4)
- [ ] `DtoTest.java` lines 62,75,122,209 (4)
- [ ] `LogSanitizerTest.java` lines 83,95,218 (3)
- [ ] `MemoryControllerTest.java` lines 193,216 (2)
- [ ] `RequestSizeValidationFilterTest.java` lines 88,249 (2)
- [ ] `RuleMetadataTest.java` lines 339,356 (2)
- [ ] `CacheStatisticsTest.java` line 140 (1)
- [ ] `LocalLRUCacheTest.java` line 462 (1)
- [ ] `RedisRuleCacheTest.java` line 490 (1)
- [ ] `DroolsEngineServiceTest.java` line 748 (1)
- [ ] Remaining 1

### S5976 (8) — @ParameterizedTest conversions
- [ ] `DrlSanitizerTest.java` lines 53, 216, 313 — read each group before converting
- [ ] `RuleDataValidatorTest.java` lines 95, 236
- [ ] `RateLimitingFilterTest.java` line 294
- [ ] `RequestSizeValidationFilterTest.java` line 128
- [ ] `S3ConfigTest.java` line 89

### S6068 (4) — Remove Mockito eq()
- [ ] `RedisRuleCacheTest.java` lines 103, 197, 345, 356 — verify mixed-arg context first

### S7467 (9 test-side) — Unnamed catch vars
- [ ] `LocalLRUCacheTest.java` lines 192, 240, 293 — verify `e` unused
- [ ] `LoggingConfigTest.java` line 104
- [ ] `DroolsEngineServiceTest.java` lines 222, 267, 326, 340, 395
- [ ] `RuleExecutorTest.java` line 223
- [ ] `RuleExecutionIntegrationTest.java` line 579
- [ ] `S3StorageIntegrationTest.java` line 302
- [ ] `mvn clean compile` after all these changes

### S2925 (5) — Suppress Thread.sleep
- [ ] `DroolsEngineServiceTest.java` method at line 246 → `@SuppressWarnings("java:S2925")`
- [ ] `DroolsEngineServiceTest.java` method at line 298 → same
- [ ] `DroolsEngineServiceTest.java` method at line 338 → same
- [ ] `RuleExecutorTest.java` method at line 158 → same
- [ ] `RuleExecutorTest.java` method at line 204 → same

### Wave 4B gate
- [ ] `mvn clean verify -Dtest='!S3StorageIntegrationTest'` → **597 pass, BUILD SUCCESS**
- [ ] `git commit -m "fix(sonar): Wave 4B — test suite AssertJ/parameterized/concurrent modernization"`

---

## Final verification
- [ ] `mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dtest='!S3StorageIntegrationTest' -Dsonar.projectKey=drools-microservice -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token>`
- [ ] MCP `get_project_quality_gate_status` → **OK**
- [ ] MCP `search_sonar_issues_in_projects` MAINTAINABILITY total → **≤ 15**
