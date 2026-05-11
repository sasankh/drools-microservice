# Checklist: Sonar Reliability Fixes

**Branch:** `upgrade-java-sonar`  
**Starting issues:** 5 (1 BLOCKER, 4 MAJOR)  
**Final result:** 0 ✅ — Quality Gate OK  
**Commits:** `bc9bb74`, `a2f9e74`

---

## Pre-flight

- [x] Confirm branch is `upgrade-java-sonar`
- [x] Confirm 597 tests pass (last run clean)
- [x] Confirm Quality Gate OK

---

## Step 1 — `RuleExecutor.java` (2 issues)

### Fix 1a — S2095 — try-with-resources for KieSession
- [x] In `executeRuleInternal()`, wrap `KieSession` in `try (KieSession kieSession = kieContainer.newKieSession())`
- [x] Remove `finally { kieSession.dispose(); }` block (try-with-resources handles it)
- [x] Confirm `KieSession` import still present (still needed for the type declaration)

### Fix 1b — S2142 — catch InterruptedException
- [x] Add `catch (InterruptedException _)` block BEFORE the generic `catch (Exception e)` in `executeRule()`
- [x] Body: `Thread.currentThread().interrupt(); future.cancel(true); return ExecutionResult.failure(...)`
- [x] Confirm generic `catch (Exception e)` remains unchanged below it
- [x] Used `_` (unnamed pattern) to avoid S7467 on the new catch variable

**Extra:** `RuleExecutorTest` updated to verify `mockSession.close()` instead of `mockSession.dispose()` (try-with-resources calls `close()`).

---

## Step 2 — `GlobalExceptionHandler.java` (1 issue)

### Fix 2 — S2583 — always-true condition
- [x] Remove the `Throwable cause =` local variable and the ternary null check
- [x] Replace with: `String causeMessage = ex.getMostSpecificCause().getMessage();`
- [x] Verified `LogSanitizer.sanitizeMessage(null)` handles null input (returns null safely)

---

## Step 3 — `RuleDataValidator.java` + test (1 issue)

### Fix 3a — Source
- [x] Remove `@Autowired private ValidationConfig validationConfig;` field declaration
- [x] Add `private final ValidationConfig validationConfig;`
- [x] Add constructor: `public RuleDataValidator(ValidationConfig validationConfig) { this.validationConfig = validationConfig; }`
- [x] Remove `import org.springframework.beans.factory.annotation.Autowired;`

### Fix 3b — Test (`RuleDataValidatorTest.java`)
- [x] Changed to `validator = new RuleDataValidator(ValidationConfigTestHelper.createTestValidationConfig());`
- [x] Removed reflection-injection lines
- [x] Removed `import java.lang.reflect.Field;`
- [x] Removed unused `import com.company.drools.config.ValidationConfig;`

---

## Step 4 — `RuleIdValidator.java` + test (1 issue)

### Fix 4a — Source
- [x] Remove `@Autowired private ValidationConfig validationConfig;` field declaration
- [x] Add `private final ValidationConfig validationConfig;`
- [x] Add constructor: `public RuleIdValidator(ValidationConfig validationConfig) { this.validationConfig = validationConfig; }`
- [x] Remove `import org.springframework.beans.factory.annotation.Autowired;`

### Fix 4b — Test (`RuleIdValidatorTest.java`)
- [x] Changed to `validator = new RuleIdValidator(ValidationConfigTestHelper.createTestValidationConfig());`
- [x] Removed reflection-injection lines
- [x] Removed `import java.lang.reflect.Field;` and unused `ValidationConfig` import

---

## Step 5 — Verify: Tests

- [x] `docker run ... mvn clean test -Dtest='!S3StorageIntegrationTest'`
- [x] `Tests run: 597, Failures: 0, Errors: 0`
- [x] `BUILD SUCCESS`

---

## Step 6 — Verify: SpotBugs

- [x] `docker run ... mvn compile spotbugs:check`
- [x] `BUILD SUCCESS`

---

## Step 7 — Commit

- [x] Committed `bc9bb74` — all 6 files (+ plan/checklist)
- [x] Committed `a2f9e74` — S7467 fix on new `catch (InterruptedException _)`

---

## Step 8 — Sonar Scan + Verify

- [x] Sonar scan complete
- [x] MCP: `get_project_quality_gate_status` → `"status": "OK"`
- [x] MCP: `search_sonar_issues RELIABILITY` → `"total": 0`
