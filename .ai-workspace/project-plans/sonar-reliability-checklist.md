# Checklist: Sonar Reliability Fixes

**Branch:** `upgrade-java-sonar`  
**Starting issues:** 5 (1 BLOCKER, 4 MAJOR)  
**Target:** 0

---

## Pre-flight

- [ ] Confirm branch is `upgrade-java-sonar`
- [ ] Confirm 597 tests pass (last run clean)
- [ ] Confirm Quality Gate OK

---

## Step 1 — `RuleExecutor.java` (2 issues)

### Fix 1a — S2095 — try-with-resources for KieSession
- [ ] In `executeRuleInternal()`, wrap `KieSession` in `try (KieSession kieSession = kieContainer.newKieSession())`
- [ ] Remove `finally { kieSession.dispose(); }` block (try-with-resources handles it)
- [ ] Confirm `KieSession` import still present (still needed for the type declaration)

### Fix 1b — S2142 — catch InterruptedException
- [ ] Add `catch (InterruptedException ie)` block BEFORE the generic `catch (Exception e)` in `executeRule()`
- [ ] Body: `Thread.currentThread().interrupt(); future.cancel(true); return ExecutionResult.failure(...)`
- [ ] Confirm generic `catch (Exception e)` remains unchanged below it

---

## Step 2 — `GlobalExceptionHandler.java` (1 issue)

### Fix 2 — S2583 — always-true condition
- [ ] Remove the `Throwable cause =` local variable and the ternary null check
- [ ] Replace with: `String causeMessage = ex.getMostSpecificCause().getMessage();`
- [ ] Verify `LogSanitizer.sanitizeMessage(null)` handles null input (grep the method)

---

## Step 3 — `RuleDataValidator.java` + test (1 issue)

### Fix 3a — Source
- [ ] Remove `@Autowired private ValidationConfig validationConfig;` field declaration
- [ ] Add `private final ValidationConfig validationConfig;`
- [ ] Add constructor: `public RuleDataValidator(ValidationConfig validationConfig) { this.validationConfig = validationConfig; }`
- [ ] Remove `import org.springframework.beans.factory.annotation.Autowired;` (if no longer used)

### Fix 3b — Test (`RuleDataValidatorTest.java`)
- [ ] Change `validator = new RuleDataValidator();` → `validator = new RuleDataValidator(config);`
- [ ] Remove the three reflection-injection lines (getDeclaredField, setAccessible, set)
- [ ] Confirm `config` variable is available before the constructor call
- [ ] Remove `import java.lang.reflect.Field;` if no longer used

---

## Step 4 — `RuleIdValidator.java` + test (1 issue)

### Fix 4a — Source
- [ ] Remove `@Autowired private ValidationConfig validationConfig;` field declaration
- [ ] Add `private final ValidationConfig validationConfig;`
- [ ] Add constructor: `public RuleIdValidator(ValidationConfig validationConfig) { this.validationConfig = validationConfig; }`
- [ ] Remove `import org.springframework.beans.factory.annotation.Autowired;`

### Fix 4b — Test (`RuleIdValidatorTest.java`)
- [ ] Change `validator = new RuleIdValidator();` → `validator = new RuleIdValidator(config);`
- [ ] Remove the three reflection-injection lines
- [ ] Confirm `config` variable is available before the constructor call
- [ ] Remove `import java.lang.reflect.Field;` if no longer used

---

## Step 5 — Verify: Tests

- [ ] `docker run ... mvn clean test -Dtest='!S3StorageIntegrationTest'`
- [ ] Confirm: `Tests run: 597, Failures: 0, Errors: 0`
- [ ] Confirm: `BUILD SUCCESS`

---

## Step 6 — Verify: SpotBugs

- [ ] `docker run ... mvn compile spotbugs:check`
- [ ] Confirm: `BUILD SUCCESS`

---

## Step 7 — Commit

- [ ] `git add` all changed files (6 files)
- [ ] Commit: `fix(sonar): reliability — S2095 try-with-resources, S2142 InterruptedException, S2583 always-true, S6813×2 constructor injection`

---

## Step 8 — Sonar Scan + Verify

- [ ] Run Sonar scan via Docker (`mvn clean verify sonar:sonar`)
- [ ] MCP: `get_project_quality_gate_status` → `"status": "OK"`
- [ ] MCP: `search_sonar_issues RELIABILITY` → `"total": 0`

---

## Notes

- **S2095**: `KieSession.close()` in Drools 10 calls `dispose()` — semantically identical to the current `finally { dispose(); }`.
- **S2142**: `InterruptedException` is a subtype of `Exception`. Must be caught specifically BEFORE the generic `catch (Exception e)` to take effect.
- **S2583**: `getMostSpecificCause()` returns `this` (non-null) when there's no inner cause. The null check was always-true dead code.
- **S6813**: Spring Boot's `SpringConstraintValidatorFactory` supports constructor injection for validators. Tests become simpler — no reflection needed.
