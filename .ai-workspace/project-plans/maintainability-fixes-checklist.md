# Maintainability Fixes — Execution Checklist

> **STATUS: ✅ SUPERSEDED 2026-05-11** — Waves 1–3 in this checklist brought issues from 253 → 178. The remaining work was completed via [`sonar-wave4-checklist.md`](sonar-wave4-checklist.md) (Waves 4A–4D, 178 → 0). Final result: **0 open maintainability issues**. Items below are historical reference; all were completed.

**Branch**: `upgrade-java-sonar` | **SonarQube project**: `drools-microservice`
**Starting issues**: 253 | **Final result**: 0 (via Wave 4 continuation)

---

## Wave 1 — Production clean-up (S1192 + S1186 + S2699)

### String constants (S1192)
- [ ] `DroolsEngineService.java` — add 5 constants + replace all usages
  - `METRIC_RULE_EXECUTION_TIME`, `METRIC_RULE_EXECUTION_ERROR`, `TAG_RULE_ID`, `TAG_STATUS`, `STATUS_ERROR`
- [ ] `S3RuleStorage.java` — add 6 constants + replace all usages
  - `METRIC_STORAGE_OPERATION_TIME`, `TAG_OPERATION`, `TAG_STORAGE_TYPE`, `STORAGE_TYPE_S3`, `TAG_STATUS`, `FILE_EXT_DRL`
- [ ] `GlobalExceptionHandler.java` — add `ERROR_CODE_INVALID_INPUT` + replace 3 usages
- [ ] `RedisRuleCache.java` — add `TAG_CACHE_TYPE`, `CACHE_TYPE_REDIS` + replace usages; verify `CACHE_KEY_PREFIX` already referenced at lines 177, 209
- [ ] `RuleExecutionController.java` — add `METRIC_API_RESPONSE_TIME`, `METRIC_CACHE_MISSES`, `TAG_ENDPOINT`, `TAG_STATUS` + replace usages
- [ ] `AdminController.java` — add `TAG_ENDPOINT`, `TAG_STATUS`, `STATUS_UP`, `STATUS_DOWN` + replace usages

### Empty stub methods (S1186)
- [ ] `RuleRefreshIntegrationTest.java` lines 80, 83, 91, 94 — add `// intentionally empty — test-only RuleStorage stub`
- [ ] `RuleExecutionIntegrationTest.java` line 91 — add `// intentionally empty — test-only RuleStorage stub`

### Tests with no assertions — BLOCKERs (S2699)
- [ ] `S3ConfigTest.java` line 60 — wrap `testSetAccessKeyId` in `assertDoesNotThrow`
- [ ] `S3ConfigTest.java` line 67 — wrap `testSetSecretAccessKey` in `assertDoesNotThrow`
- [ ] `InMemoryRuleStorageTest.java` line 153 — wrap `testRefreshCache` in `assertDoesNotThrow`
- [ ] `InMemoryRuleStorageTest.java` line 159 — wrap `testRefreshRule` in `assertDoesNotThrow`

### Wave 1 gate
- [ ] `mvn compile spotbugs:check` → BUILD SUCCESS
- [ ] `mvn test -Dtest='DroolsEngineServiceTest,S3RuleStorageTest,AdminControllerTest,GlobalExceptionHandlerTest,RedisRuleCacheTest,RuleExecutionControllerTest,S3ConfigTest,InMemoryRuleStorageTest,RuleRefreshIntegrationTest'` → all pass
- [ ] `git commit -m "fix(sonar): Wave 1 — extract string constants + fix empty/assertion-free tests"`

---

## Wave 2 — Test-suite AssertJ modernization (S5838 + S5853 + S5976)

### S5838 — `map.get(k).isEqualTo(v)` → `containsEntry(k, v)`
- [ ] `LogSanitizerTest.java` — ~15 instances
- [ ] `DtoTest.java` — ~12 instances
- [ ] `LocalLRUCacheTest.java` — ~8 instances
- [ ] `RedisRuleCacheTest.java` — ~7 instances
- [ ] `MemoryControllerTest.java` — ~6 instances
- [ ] `DrlSanitizerTest.java` — ~5 instances
- [ ] `DroolsEngineServiceTest.java` — ~3 instances

### S5853 — Chain consecutive assertThat blocks on same subject
- [ ] `LogSanitizerTest.java` — ~6 blocks
- [ ] `LocalLRUCacheTest.java` — ~8 blocks
- [ ] `DroolsEngineServiceTest.java` — ~5 blocks
- [ ] `DtoTest.java` — ~6 blocks
- [ ] `RedisRuleCacheTest.java` — ~4 blocks

### S5976 — Convert N similar tests to `@ParameterizedTest`
- [ ] `DrlSanitizerTest.java` — 7 blocked-import tests → `@ParameterizedTest @MethodSource("blockedImports")`
- [ ] `MemoryControllerTest.java` — 8 threshold tests → `@ParameterizedTest @CsvSource({...})`
- [ ] `LocalLRUCacheTest.java` — disabled-cache behavior cluster → `@ParameterizedTest`

### Wave 2 gate
- [ ] `mvn test -Dtest='LogSanitizerTest,LocalLRUCacheTest,DroolsEngineServiceTest,DtoTest,RedisRuleCacheTest,MemoryControllerTest,DrlSanitizerTest'` → all pass
- [ ] `git commit -m "fix(sonar): Wave 2 — AssertJ S5838/S5853/S5976 test modernization"`

---

## Wave 3 — Modernization + Spring hygiene (S7467 + S6813 + S1874)

### S7467 — Replace unused `e` with `_` (3 confirmed only; rest are false positives)
- [ ] `DotenvConfig.java` line 31 — `catch (Exception e)` → `catch (Exception _)`
- [ ] `S3RuleStorage.java` line 84 — `catch (NoSuchKeyException e)` → `catch (NoSuchKeyException _)`
- [ ] `S3RuleStorage.java` line 139 — `catch (CallNotPermittedException e)` → `catch (CallNotPermittedException _)`

### S6813 — Constructor injection (skip RuleIdValidator + RuleDataValidator — constraint validators)
- [ ] `AdminController.java` — expand constructor to 9 params; add `@Nullable` for 4 optional deps
- [ ] `AdminController.java` — remove `@Autowired` from the 4 field declarations
- [ ] `AdminController.java` — add `import org.springframework.lang.Nullable`
- [ ] Verify `AdminControllerTest.java` compiles (if `@InjectMocks` → auto-handled; if manual construction → update)

### S1874 — Deprecated AWS SDK v2 `retryPolicy()` → `retryStrategy()`
- [ ] `S3Config.java` — replace `.retryPolicy(createRetryPolicy())` with `.retryStrategy(RetryStrategy.standardRetryStrategy())`
- [ ] `S3Config.java` — delete unused `createRetryPolicy()` method

### Wave 3 gate
- [ ] `mvn compile` → BUILD SUCCESS
- [ ] `mvn test -Dtest='AdminControllerTest,S3RuleStorageTest,S3ConfigTest'` → all pass
- [ ] `git commit -m "fix(sonar): Wave 3 — unnamed catch vars, constructor injection, retryStrategy migration"`

---

## Final verification

- [ ] `mvn clean verify -Dtest='!S3StorageIntegrationTest'` → **all 598 tests pass**
- [ ] Re-run SonarQube scan:
  ```bash
  mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
    -Dtest='!S3StorageIntegrationTest' \
    -Dsonar.projectKey=drools-microservice \
    -Dsonar.projectName='drools-microservice' \
    -Dsonar.host.url=http://localhost:9000 \
    -Dsonar.token=<token>
  ```
- [ ] MCP: `get_project_quality_gate_status` → **OK**
- [ ] MCP: `search_sonar_issues_in_projects` BLOCKER → **0**
- [ ] MCP: total open maintainability issues → **≤ 120**
