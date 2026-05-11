# Plan: Sonar Maintainability — Wave 4 (178 → ≤15)

## Context

After Waves 1–3 (committed af7cc49, f8c6c64, 56e03e6, f9193a0), Quality Gate is OK and BLOCKERs = 0. 178 open maintainability issues remain. Goal: reduce to lowest possible while only making changes that make sense.

**Branch**: `upgrade-java-sonar` | **Date**: 2026-05-11

---

## Issue decisions

| Rule | Count | Decision | Rationale |
|---|---|---|---|
| S5838 | 43 | FIX | AssertJ modernization — test files only |
| S5853 | 22 | FIX | Chain assertions — test files only |
| S7467 | 17 | FIX (verify) | Unnamed `_` catch vars — verify `e` unused first |
| S2629 | 11 | SUPPRESS | Security-motivated `LogSanitizer.sanitizeMessage()` calls |
| S2139 | 10 | FIX | Log+rethrow — remove log, keep throw with context message |
| S5976 | 8 | FIX | More @ParameterizedTest groups |
| S1192 | 8 | FIX | Duplicated literals → extract constants |
| S112 | 6 | FIX | Generic RuntimeException → RuleStorageException |
| S6204 | 5 | FIX | Collectors.toList() → Stream.toList() |
| S6201 | 5 | FIX | instanceof pattern matching (Java 16+) |
| S2925 | 5 | SUPPRESS | Thread.sleep in concurrency tests — intentional |
| S1602 | 5 | FIX | Useless lambda curly braces |
| S6068 | 4 | FIX | Mockito eq() → bare value |
| S5411 | 3 | FIX | Boolean.TRUE.equals(response.isTruncated()) |
| S1068 | 3 | VERIFY+FIX | Check each unused field then delete |

---

## Wave 4A — Production code (~40 issues)

### S1192 — Extract duplicated literals
- `MemoryController.java`: KEY_USED_MB, KEY_MAX_MB, KEY_USAGE_PERCENT, KEY_COMMITTED_MB
- `LocalLRUCache.java`: TAG_CACHE_TYPE="cache_type", CACHE_TYPE_LOCAL="local"
- `MetricsConfig.java`: TAG_APPLICATION="application", TAG_ENVIRONMENT="environment"
- `LoggingConfig.java`: read line 89, extract

### S6204 — Stream.toList()
- `MemoryController.java` lines 109, 124
- `AdminController.java` line 540
- `InMemoryRuleStorageAdapter.java` line 61
- `S3RuleStorage.java` line 323
- Remove unused Collectors import after each file

### S6201 — instanceof pattern matching
- `RuleDataValidator.java` lines 90, 116
- `LogSanitizer.java` line 123
- `ThreadPoolConfig.java` lines 129, 148

### S1602 — Remove useless lambda curly braces
- `CircuitBreakerConfig.java` lines 89, 99, 138, 148
- `RuleExecutor.java` line 57

### S5411 — Null-safe Boolean in while
- `S3RuleStorage.java` lines 190, 299, 332: `} while (Boolean.TRUE.equals(response.isTruncated()));`

### S7467 — Unnamed catch vars (production)
- `MemoryController.java` line 161: `catch (InterruptedException _)`
- `RedisRuleCache.java` line 145: verify then `catch (CallNotPermittedException _)`
- `LogSanitizer.java` line 241: `catch (ClassCastException _)`
- `S3RuleStorage.java` line 258: verify then fix
→ **Always `mvn clean compile` after these changes** (IDE ECJ bug with `_` syntax)

### S2629 — Suppress (security-motivated log sanitization)
- `GlobalExceptionHandler.java`: `@SuppressWarnings("java:S2629")` at class level
- `RuleExecutionController.java`: same
- `AdminController.java`: same
- `MemoryController.java`: on `getMemoryInfo()` method

### S2139 — Log+rethrow: remove log, keep throw
- `S3RuleStorage.java` lines 110, 123, 195, 218, 242
- `LocalFileStorage.java` lines 91, 109
- `AdminController.java` line 373
- `RuleExecutionController.java` line 128
- `RequestTimeoutConfig.java` line 69: read first

### S112 — Create RuleStorageException
New file: `src/main/java/com/company/drools/api/exception/RuleStorageException.java`
Replace `throw new RuntimeException(...)` in S3RuleStorage (lines 134, 197, 220, 244) and LocalFileStorage (lines 93, 111)

### S1068 — Verify unused fields before deleting
- `RedisRuleCache.java` line 39 (CACHE_STATS_KEY)
- `DroolsEngineService.java` line 38
- `S3RuleStorage.java` line 42

### Gate after 4A
```bash
mvn clean compile spotbugs:check
mvn test -Dtest='!S3StorageIntegrationTest'  # 597 pass
git commit -m "fix(sonar): Wave 4A — production code quality"
```

---

## Wave 4B — Test files (~77 issues)

### S5838 (43) — AssertJ assertion modernization
Patterns: `.size()).isEqualTo(n)` → `.hasSize(n)`, `.isPresent()).isTrue()` → `.isPresent()`, etc.
Files: LocalLRUCacheTest (14), DtoTest (8), AdminAuthFilterTest (2), RateLimitingFilterTest (2), RequestSizeValidationFilterTest (2), RedisRuleCacheTest (2), MetricsConfigTest (2), + 11 others

### S5853 (22) — Chain consecutive assertions
Files: ThreadPoolConfigTest (4), DtoTest (4), LogSanitizerTest (3), MemoryControllerTest (2), RequestSizeValidationFilterTest (2), RuleMetadataTest (2), + 5 others

### S5976 (8) — More @ParameterizedTest
Files: DrlSanitizerTest (3 groups), RuleDataValidatorTest (2), RateLimitingFilterTest (1), RequestSizeValidationFilterTest (1), S3ConfigTest (1)

### S6068 (4) — Remove Mockito eq()
File: RedisRuleCacheTest lines 103, 197, 345, 356

### S7467 (9 test-side) — Unnamed catch vars
Files: LocalLRUCacheTest (3), DroolsEngineServiceTest (5), + 4 others — verify before fixing

### S2925 (5) — Suppress Thread.sleep
Add `@SuppressWarnings("java:S2925")` to: DroolsEngineServiceTest (3 methods), RuleExecutorTest (2 methods)

### Gate after 4B
```bash
mvn clean verify -Dtest='!S3StorageIntegrationTest'  # 597 pass
git commit -m "fix(sonar): Wave 4B — test suite modernization"
```

---

## Final verification
```bash
mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dtest='!S3StorageIntegrationTest' \
  -Dsonar.projectKey=drools-microservice \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=<token>
```
MCP: `get_project_quality_gate_status` → OK; maintainability issues → ≤ 15
