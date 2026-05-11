# Maintainability Fixes — Execution Checklist

> **STATUS: ✅ COMPLETED 2026-05-11** — Waves 1–3 brought issues 253 → 178. Wave 4 (tracked in [`sonar-wave4-checklist.md`](sonar-wave4-checklist.md)) completed the remainder 178 → **0**. Final result: **0 open maintainability issues**, Quality Gate OK.

**Branch**: `upgrade-java-sonar` | **SonarQube project**: `drools-microservice`
**Starting issues**: 253 | **Final result**: 0

---

## Wave 1 — Production clean-up (S1192 + S1186 + S2699)

### String constants (S1192)
- [x] `DroolsEngineService.java` — add 5 constants + replace all usages
  - `METRIC_RULE_EXECUTION_TIME`, `METRIC_RULE_EXECUTION_ERROR`, `TAG_RULE_ID`, `TAG_STATUS`, `STATUS_ERROR`
- [x] `S3RuleStorage.java` — add 6 constants + replace all usages
  - `METRIC_STORAGE_OPERATION_TIME`, `TAG_OPERATION`, `TAG_STORAGE_TYPE`, `STORAGE_TYPE_S3`, `TAG_STATUS`, `FILE_EXT_DRL`
- [x] `GlobalExceptionHandler.java` — add `ERROR_CODE_INVALID_INPUT` + replace 3 usages
- [x] `RedisRuleCache.java` — add `TAG_CACHE_TYPE`, `CACHE_TYPE_REDIS` + replace usages; verify `CACHE_KEY_PREFIX` already referenced at lines 177, 209
- [x] `RuleExecutionController.java` — add `METRIC_API_RESPONSE_TIME`, `METRIC_CACHE_MISSES`, `TAG_ENDPOINT`, `TAG_STATUS` + replace usages
- [x] `AdminController.java` — add `TAG_ENDPOINT`, `TAG_STATUS`, `STATUS_UP`, `STATUS_DOWN` + replace usages

### Empty stub methods (S1186)
- [x] `RuleRefreshIntegrationTest.java` lines 80, 83, 91, 94 — add `// intentionally empty — test-only RuleStorage stub`
- [x] `RuleExecutionIntegrationTest.java` line 91 — add `// intentionally empty — test-only RuleStorage stub`

### Tests with no assertions — BLOCKERs (S2699)
- [x] `S3ConfigTest.java` line 60 — wrap `testSetAccessKeyId` in `assertDoesNotThrow`
- [x] `S3ConfigTest.java` line 67 — wrap `testSetSecretAccessKey` in `assertDoesNotThrow`
- [x] `InMemoryRuleStorageTest.java` line 153 — wrap `testRefreshCache` in `assertDoesNotThrow`
- [x] `InMemoryRuleStorageTest.java` line 159 — wrap `testRefreshRule` in `assertDoesNotThrow`

### Wave 1 gate
- [x] `mvn compile spotbugs:check` → BUILD SUCCESS
- [x] All affected tests pass
- [x] `git commit` — Wave 1 string constants + empty/assertion-free tests

---

## Wave 2 — Test-suite AssertJ modernization (S5838 + S5853 + S5976)

### S5838 — `map.get(k).isEqualTo(v)` → `containsEntry(k, v)`
- [x] `LogSanitizerTest.java`
- [x] `DtoTest.java`
- [x] `LocalLRUCacheTest.java`
- [x] `RedisRuleCacheTest.java`
- [x] `MemoryControllerTest.java`
- [x] `DrlSanitizerTest.java`
- [x] `DroolsEngineServiceTest.java`

### S5853 — Chain consecutive assertThat blocks on same subject
- [x] `LogSanitizerTest.java`
- [x] `LocalLRUCacheTest.java`
- [x] `DroolsEngineServiceTest.java`
- [x] `DtoTest.java`
- [x] `RedisRuleCacheTest.java`

### S5976 — Convert N similar tests to `@ParameterizedTest`
- [x] `DrlSanitizerTest.java` — blocked-import tests → `@ParameterizedTest @MethodSource`
- [x] `MemoryControllerTest.java` — threshold tests → parameterized
- [x] `LocalLRUCacheTest.java` — disabled-cache behavior cluster → parameterized

### Wave 2 gate
- [x] All affected tests pass
- [x] `git commit` — Wave 2 AssertJ S5838/S5853/S5976 test modernization

---

## Wave 3 — Modernization + Spring hygiene (S7467 + S6813 + S1874)

### S7467 — Replace unused `e` with `_`
- [x] `DotenvConfig.java` — `catch (Exception e)` → `catch (Exception _)`
- [x] `S3RuleStorage.java` — `catch (NoSuchKeyException e)` → `catch (NoSuchKeyException _)`
- [x] `S3RuleStorage.java` — `catch (CallNotPermittedException e)` → `catch (CallNotPermittedException _)`
- [x] Additional instances found and fixed across production + test files in Wave 4

### S6813 — Constructor injection
- [x] `AdminController.java` — constructor injection for optional deps
- [x] `RuleDataValidator.java` — constructor injection (done in Reliability fixes, 2026-05-11)
- [x] `RuleIdValidator.java` — constructor injection (done in Reliability fixes, 2026-05-11)

### S1874 — Deprecated AWS SDK v2 `retryPolicy()` → `retryStrategy()`
- [x] `S3Config.java` — replaced `.retryPolicy()` with `.retryStrategy(RetryStrategy.standardRetryStrategy())`
- [x] `S3Config.java` — deleted unused `createRetryPolicy()` method

### Wave 3 gate
- [x] `mvn compile` → BUILD SUCCESS
- [x] All affected tests pass
- [x] `git commit` — Wave 3 unnamed catch vars, constructor injection, retryStrategy migration

---

## Wave 4 continuation — see [`sonar-wave4-checklist.md`](sonar-wave4-checklist.md)

After Wave 3, 178 issues remained. Waves 4A–4D completed the remainder:
- **4A** — production code (S1192, S6204, S6201, S1602, S5411, S7467, S2629, S2139, S112, S1068, S135, S1126)
- **4B** — test suite (S5838×43, S5853×22, S5976×8, S6068×4, S7467, S2925, S1612, etc.)
- **4C** — remaining production (S1068, S1118, S1488, S1215, S1172, S106, S7467, S2925)
- **4D** — final fixes (S1874, S1141×2, S7467)

---

## Final verification ✅

- [x] `mvn clean verify -Dtest='!S3StorageIntegrationTest'` → **597 tests pass**
- [x] SonarQube scan complete
- [x] MCP: `get_project_quality_gate_status` → **OK**
- [x] MCP: `search_sonar_issues_in_projects` BLOCKER → **0**
- [x] MCP: total open maintainability issues → **0** (target was ≤120; achieved 0)
- [x] MCP: total open reliability issues → **0**
- [x] MCP: security hotspots → **0**
