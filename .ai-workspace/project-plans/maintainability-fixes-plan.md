# Plan: Maintainability — All 3 Waves (253 issues → ~120)

## Context

SonarQube scan of branch `upgrade-java-sonar` returned 253 open maintainability issues after the Java 25 / Spring Boot 3.5.3 / Drools 10.2.0 upgrade. Quality Gate is **OK** today, so none of these are blockers by SonarQube's metric — but fixing them before merging to main is good practice and avoids carrying known tech-debt forward. Issues are split into 3 waves by type and risk level.

**Goal**: Drop open maintainability issues from 253 → ≤120, eliminate all 4 BLOCKERs and the top CRITICAL clusters, and leave each wave's changes committed separately so they are independently reviewable and revertable.

**Branch**: `upgrade-java-sonar`
**SonarQube project key**: `drools-microservice`
**Date planned**: 2026-05-10

---

## Wave 1 — Production clean-up (~30 min, ~60 lines changed)
**Target: eliminate 4 BLOCKERs + 36 CRITICAL S1192 + 8 S1186**

### 1a. S1192 — Extract duplicated string literals into constants (36 issues)

Add `private static final String` constants at the top of each class (existing fields block). Per-file — no shared constants class (over-engineering for this scale). Only constants that appear **≥ 3 times** need extracting per the rule.

#### `src/main/java/com/company/drools/core/engine/DroolsEngineService.java`
```java
private static final String METRIC_RULE_EXECUTION_TIME  = "drools.rule.execution.time";
private static final String METRIC_RULE_EXECUTION_ERROR = "drools.rule.execution.error";
private static final String TAG_RULE_ID                 = "rule_id";
private static final String TAG_STATUS                  = "status";
private static final String STATUS_ERROR                = "error";
```

#### `src/main/java/com/company/drools/storage/S3RuleStorage.java` (23 production issues)
```java
private static final String METRIC_STORAGE_OPERATION_TIME = "drools.storage.operation.time";
private static final String TAG_OPERATION    = "operation";
private static final String TAG_STORAGE_TYPE = "storage_type";
private static final String STORAGE_TYPE_S3  = "s3";
private static final String TAG_STATUS       = "status";
private static final String FILE_EXT_DRL     = ".drl";
```

#### `src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java`
```java
private static final String ERROR_CODE_INVALID_INPUT = "INVALID_INPUT";
```

#### `src/main/java/com/company/drools/cache/RedisRuleCache.java`
```java
private static final String TAG_CACHE_TYPE    = "cache_type";
private static final String CACHE_TYPE_REDIS  = "redis";
```
Note: `CACHE_KEY_PREFIX = "drools:rule:"` already exists — verify lines 177, 209 reference it correctly.

#### `src/main/java/com/company/drools/api/controller/RuleExecutionController.java`
```java
private static final String METRIC_API_RESPONSE_TIME = "drools.api.response.time";
private static final String METRIC_CACHE_MISSES       = "drools.cache.misses";
private static final String TAG_ENDPOINT              = "endpoint";
private static final String TAG_STATUS                = "status";
```

#### `src/main/java/com/company/drools/api/controller/AdminController.java`
```java
private static final String TAG_ENDPOINT  = "endpoint";
private static final String TAG_STATUS    = "status";
private static final String STATUS_UP     = "UP";
private static final String STATUS_DOWN   = "DOWN";
```

---

### 1b. S1186 — Add comment to empty test stub methods (8 issues)

These are all stub implementations of a `RuleStorage` interface inside test classes. They are intentionally empty. Fix: add a single-line comment so the intent is documented.

| File | Lines | Method names | Comment to add |
|---|---|---|---|
| `src/test/java/com/company/drools/integration/RuleRefreshIntegrationTest.java` | 80, 83, 91, 94 | `saveRule`, `deleteRule`, `refreshCache`, `refreshRule` | `// intentionally empty — test-only RuleStorage stub` |
| `src/test/java/com/company/drools/integration/RuleExecutionIntegrationTest.java` | 91 | `refreshRule` | `// intentionally empty — test-only RuleStorage stub` |

---

### 1c. S2699 — BLOCKERs: Tests with no assertions (4 issues)

#### `src/test/java/com/company/drools/config/S3ConfigTest.java` — lines 60, 67
```java
@Test void testSetAccessKeyId() {
    assertDoesNotThrow(() -> s3Config.setAccessKeyId("new-key"));
}
@Test void testSetSecretAccessKey() {
    assertDoesNotThrow(() -> s3Config.setSecretAccessKey("new-secret"));
}
```

#### `src/test/java/com/company/drools/storage/InMemoryRuleStorageTest.java` — lines 153, 159
```java
@Test void testRefreshCache() {
    assertDoesNotThrow(() -> adapter.refreshCache());
}
@Test void testRefreshRule() {
    assertDoesNotThrow(() -> adapter.refreshRule("pricing.discount.simple"));
}
```

---

### Wave 1 verification
```bash
mvn compile spotbugs:check
mvn test -Dtest='DroolsEngineServiceTest,S3RuleStorageTest,AdminControllerTest,\
  GlobalExceptionHandlerTest,RedisRuleCacheTest,RuleExecutionControllerTest,\
  S3ConfigTest,InMemoryRuleStorageTest,RuleRefreshIntegrationTest'
git commit -m "fix(sonar): Wave 1 — extract string constants + fix empty/assertion-free tests"
```

---

## Wave 2 — Test-suite AssertJ modernization (~60 min, ~150 lines changed)
**Target: eliminate 63 S5838 + 26 S5853 + 9 S5976 = ~98 issues**

### 2a. S5838 — `assertThat(map.get(k)).isEqualTo(v)` → `assertThat(map).containsEntry(k, v)` (63 issues)

Mechanical find-and-replace in 7 test files.

```java
// BEFORE:
assertThat(result.get("payment")).isEqualTo("****-****-****-1111");
// AFTER:
assertThat(result).containsEntry("payment", "****-****-****-1111");
```

Files (instances):
- `LogSanitizerTest.java` (~15)
- `DtoTest.java` (~12)
- `LocalLRUCacheTest.java` (~8)
- `RedisRuleCacheTest.java` (~7)
- `MemoryControllerTest.java` (~6)
- `DrlSanitizerTest.java` (~5)
- `DroolsEngineServiceTest.java` (~3)

---

### 2b. S5853 — Chain consecutive assertions on same subject (26 issues)

```java
// BEFORE:
assertThat(response.getStatus()).isEqualTo("completed");
assertThat(response.getErrorCode()).isNull();

// AFTER:
assertThat(response).satisfies(r -> {
    assertThat(r.getStatus()).isEqualTo("completed");
    assertThat(r.getErrorCode()).isNull();
});
```

Highest yield: `LogSanitizerTest` (~6), `LocalLRUCacheTest` (~8), `DroolsEngineServiceTest` (~5), `DtoTest` (~6).

---

### 2c. S5976 — Replace N similar tests with `@ParameterizedTest` (9 issues)

**Group A — `DrlSanitizerTest.java`**: 7 blocked-import tests → `@ParameterizedTest @MethodSource`
```java
@ParameterizedTest
@MethodSource("blockedImports")
void testRejectsBlockedImport(String blockedImport) { ... }

static Stream<String> blockedImports() {
    return Stream.of("java.io.File", "java.net.URL", "java.lang.reflect.Method",
                     "java.lang.Runtime", "java.lang.ClassLoader",
                     "java.lang.Thread", "groovy.lang.GroovyShell");
}
```

**Group B — `MemoryControllerTest.java`**: 8 threshold tests → `@ParameterizedTest @CsvSource`
```java
@ParameterizedTest
@CsvSource({"95.0,CRITICAL", "85.0,WARNING", "75.0,CAUTION", "65.0,NORMAL",
            "55.0,NORMAL", "45.0,NORMAL", "35.0,NORMAL", "25.0,NORMAL"})
void testMemoryWarningLevel(double usedPercent, String expectedLevel) { ... }
```

**Group C — `LocalLRUCacheTest.java`**: disabled-cache behavior cluster → `@ParameterizedTest`

---

### Wave 2 verification
```bash
mvn test -Dtest='LogSanitizerTest,LocalLRUCacheTest,DroolsEngineServiceTest,\
  DtoTest,RedisRuleCacheTest,MemoryControllerTest,DrlSanitizerTest'
git commit -m "fix(sonar): Wave 2 — AssertJ S5838/S5853/S5976 test modernization"
```

---

## Wave 3 — Modernization + Spring hygiene (~30 min, ~40 lines changed)
**Target: 3 × S7467 + 4 × S6813 + 1 × S1874**

### 3a. S7467 — Replace unused `e` with unnamed `_` (3 confirmed instances only)

Most of the 20 SonarQube hits are false positives (`e` IS used in log statements). Only 3 are genuinely unused:

| File | Line | Fix |
|---|---|---|
| `src/main/java/com/company/drools/config/DotenvConfig.java` | 31 | `catch (Exception _)` |
| `src/main/java/com/company/drools/storage/S3RuleStorage.java` | 84 | `catch (NoSuchKeyException _)` |
| `src/main/java/com/company/drools/storage/S3RuleStorage.java` | 139 | `catch (CallNotPermittedException _)` |

---

### 3b. S6813 — Field injection → constructor injection (4 real, 2 skipped)

**Skip**: `RuleIdValidator` and `RuleDataValidator` — Spring `ConstraintValidator` implementations require field injection. Leave unchanged.

**Fix**: `src/main/java/com/company/drools/api/controller/AdminController.java`

Expand the existing 5-param constructor to 9 params (add 4 `@Nullable` optional deps):
```java
@Autowired
public AdminController(
    DroolsEngineService droolsEngineService,
    StorageFactory storageFactory,
    RuleCache ruleCache,
    MeterRegistry meterRegistry,
    ThreadPoolConfig threadPoolConfig,
    @Nullable RedisConnectionFactory redisConnectionFactory,
    @Nullable S3Client s3Client,
    @Nullable @Qualifier("s3CircuitBreaker") CircuitBreaker s3CircuitBreaker,
    @Nullable @Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker) { ... }
```
Remove the 4 `@Autowired` annotations from the field declarations (keep the fields). Add `import org.springframework.lang.Nullable`.

Check `AdminControllerTest.java` — if it uses `@InjectMocks`, Mockito handles the new constructor automatically.

---

### 3c. S1874 — Deprecated AWS SDK v2 `retryPolicy()` → `retryStrategy()` (1 instance)

**File**: `src/main/java/com/company/drools/config/S3Config.java`

```java
// BEFORE:
.overrideConfiguration(builder -> builder.retryPolicy(createRetryPolicy()))

// AFTER (simple drop-in):
.overrideConfiguration(builder -> builder.retryStrategy(
    RetryStrategy.standardRetryStrategy()))
```

Or preserve the 3-retry config:
```java
.overrideConfiguration(builder -> builder.retryStrategy(
    RetryStrategy.builder().maxAttempts(4).build()))
```

Delete the unused `createRetryPolicy()` method after migration.
⚠️ Run integration test with Docker: `mvn test -Dtest=S3StorageIntegrationTest`

---

### Wave 3 verification
```bash
mvn compile
mvn test -Dtest='AdminControllerTest,S3RuleStorageTest,S3ConfigTest'
git commit -m "fix(sonar): Wave 3 — unnamed catch vars, constructor injection, retryStrategy migration"
```

---

## Final full-test + SonarQube re-scan

```bash
# Full test suite (skip Docker-dependent test)
mvn clean verify -Dtest='!S3StorageIntegrationTest'

# SonarQube re-scan
mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dtest='!S3StorageIntegrationTest' \
  -Dsonar.projectKey=drools-microservice \
  -Dsonar.projectName='drools-microservice' \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=<token>
```

**Expected outcome**:
- Open maintainability issues: 253 → ≤120
- BLOCKERs: 4 → 0
- CRITICAL: 47 → ~15
- Quality Gate: OK (maintained or improved)

---

## Files modified

| Wave | File | Change |
|---|---|---|
| 1 | `src/main/java/.../core/engine/DroolsEngineService.java` | +5 constants |
| 1 | `src/main/java/.../storage/S3RuleStorage.java` | +6 constants |
| 1 | `src/main/java/.../api/exception/GlobalExceptionHandler.java` | +1 constant |
| 1 | `src/main/java/.../cache/RedisRuleCache.java` | +2 constants |
| 1 | `src/main/java/.../api/controller/RuleExecutionController.java` | +4 constants |
| 1 | `src/main/java/.../api/controller/AdminController.java` | +4 constants |
| 1 | `src/test/java/.../integration/RuleRefreshIntegrationTest.java` | +4 comments |
| 1 | `src/test/java/.../integration/RuleExecutionIntegrationTest.java` | +1 comment |
| 1 | `src/test/java/.../config/S3ConfigTest.java` | assertDoesNotThrow (2 tests) |
| 1 | `src/test/java/.../storage/InMemoryRuleStorageTest.java` | assertDoesNotThrow (2 tests) |
| 2 | `src/test/java/.../common/LogSanitizerTest.java` | S5838 + S5853 |
| 2 | `src/test/java/.../cache/LocalLRUCacheTest.java` | S5838 + S5853 + S5976 |
| 2 | `src/test/java/.../core/engine/DroolsEngineServiceTest.java` | S5838 + S5853 |
| 2 | `src/test/java/.../api/dto/DtoTest.java` | S5838 + S5853 + S5976 |
| 2 | `src/test/java/.../cache/RedisRuleCacheTest.java` | S5853 |
| 2 | `src/test/java/.../api/controller/MemoryControllerTest.java` | S5976 |
| 2 | `src/test/java/.../core/engine/DrlSanitizerTest.java` | S5838 + S5976 |
| 3 | `src/main/java/.../config/DotenvConfig.java` | catch(_) |
| 3 | `src/main/java/.../storage/S3RuleStorage.java` | 2× catch(_) |
| 3 | `src/main/java/.../api/controller/AdminController.java` | Constructor injection (9-param) |
| 3 | `src/main/java/.../config/S3Config.java` | retryStrategy migration |
