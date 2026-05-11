# Plan: Sonar Reliability — 5 Open Issues → 0

## Context

After completing Maintainability Wave 4 (178 → 4 issues), we now address **Reliability**.
Current state: **5 open reliability issues** (1 BLOCKER + 4 MAJOR).
Branch: `upgrade-java-sonar`. All 597 tests pass. Quality Gate OK.

---

## Issue Inventory

| # | Severity | Rule | File | Line | Description |
|---|---|---|---|---|---|
| 1 | BLOCKER | S2095 | `RuleExecutor.java` | 80 | KieSession not closed with try-with-resources |
| 2 | MAJOR | S2142 | `RuleExecutor.java` | 70 | InterruptedException swallowed in generic catch |
| 3 | MAJOR | S2583 | `GlobalExceptionHandler.java` | 125 | Condition always evaluates to true |
| 4 | MAJOR | S6813 | `RuleDataValidator.java` | 24 | Field injection → use constructor injection |
| 5 | MAJOR | S6813 | `RuleIdValidator.java` | 15 | Field injection → use constructor injection |

---

## Fix Details

### Fix 1 — BLOCKER · S2095 · `RuleExecutor.java:80`

**File:** `src/main/java/com/company/drools/core/engine/RuleExecutor.java`

**Current code (lines 77–98):**
```java
private Map<String, Object> executeRuleInternal(
    KieContainer kieContainer, String ruleId, Map<String, Object> inputData) {
  KieSession kieSession = kieContainer.newKieSession();

  try {
    kieSession.insert(inputData);
    int rulesFired = kieSession.fireAllRules(maxRuleFirings);
    log.debug("Fired {} rules for rule ID {} (limit: {})", rulesFired, ruleId, maxRuleFirings);
    return inputData;
  } finally {
    kieSession.dispose();
  }
}
```

**Problem:** Sonar S2095 requires `try-with-resources` when an object implements `AutoCloseable`. Even though the finally block correctly calls `dispose()`, Sonar flags the pattern. In Drools 10, `KieSession` implements `AutoCloseable` and `close()` delegates to `dispose()`.

**Fix — convert to try-with-resources:**
```java
private Map<String, Object> executeRuleInternal(
    KieContainer kieContainer, String ruleId, Map<String, Object> inputData) {
  try (KieSession kieSession = kieContainer.newKieSession()) {
    kieSession.insert(inputData);
    int rulesFired = kieSession.fireAllRules(maxRuleFirings);
    log.debug("Fired {} rules for rule ID {} (limit: {})", rulesFired, ruleId, maxRuleFirings);
    return inputData;
  }
}
```

**Safety:** `KieSession.close()` in Drools 10 calls `dispose()` internally. Functionally identical. Remove the explicit `kieSession.dispose()` since try-with-resources handles it.

Also remove the `import org.kie.api.runtime.KieSession;` only if no longer referenced elsewhere in the file. (It's still referenced in the method signature area, so keep it.)

---

### Fix 2 — MAJOR · S2142 · `RuleExecutor.java:70`

**File:** `src/main/java/com/company/drools/core/engine/RuleExecutor.java`

**Current code (lines 65–74):**
```java
} catch (java.util.concurrent.TimeoutException e) {
  future.cancel(true);
  log.error("Rule {} execution timed out after {}s", ruleId, timeoutSeconds);
  throw new TimeoutException("Rule execution: " + ruleId, timeoutSeconds, e);

} catch (Exception e) {    // ← S2142 fires here — InterruptedException is swallowed
  long executionTime = System.currentTimeMillis() - startTime;
  log.error("Rule {} execution failed after {}ms", ruleId, executionTime, e);
  return ExecutionResult.failure("Rule execution failed: " + e.getMessage());
}
```

**Problem:** `future.get(timeoutSeconds, TimeUnit.SECONDS)` can throw `InterruptedException` if the waiting thread is interrupted. The generic `catch (Exception e)` silently swallows it without restoring the interrupt status, breaking the thread interrupt contract. Sonar S2142 flags any catch of `InterruptedException` that doesn't call `Thread.currentThread().interrupt()` or rethrow.

**Fix — add specific catch before the generic one:**
```java
} catch (java.util.concurrent.TimeoutException e) {
  future.cancel(true);
  log.error("Rule {} execution timed out after {}s", ruleId, timeoutSeconds);
  throw new TimeoutException("Rule execution: " + ruleId, timeoutSeconds, e);

} catch (InterruptedException ie) {
  Thread.currentThread().interrupt();
  future.cancel(true);
  log.warn("Rule {} execution interrupted", ruleId);
  return ExecutionResult.failure("Rule execution interrupted: " + ruleId);

} catch (Exception e) {
  long executionTime = System.currentTimeMillis() - startTime;
  log.error("Rule {} execution failed after {}ms", ruleId, executionTime, e);
  return ExecutionResult.failure("Rule execution failed: " + e.getMessage());
}
```

**Note:** `InterruptedException` is a checked exception that IS a subtype of `Exception`. By adding the specific handler before the generic one, Sonar S2142 is resolved. The thread's interrupt flag is restored via `Thread.currentThread().interrupt()`.

---

### Fix 3 — MAJOR · S2583 · `GlobalExceptionHandler.java:125`

**File:** `src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java`

**Current code (lines 123–126):**
```java
Throwable cause = ex.getMostSpecificCause();
String causeMessage = cause != null ? cause.getMessage() : ex.getMessage();
log.warn("Malformed JSON in request body: {}", LogSanitizer.sanitizeMessage(causeMessage));
```

**Problem:** Spring's `NestedRuntimeException.getMostSpecificCause()` is documented to never return null — it returns `this` if there is no deeper cause. Therefore `cause != null` is always `true` and the `else` branch (`ex.getMessage()`) is dead code. Sonar S2583 flags this as a reliability issue because it indicates the developer may have incorrect assumptions about the API.

**Fix — remove the always-true condition:**
```java
String causeMessage = ex.getMostSpecificCause().getMessage();
log.warn("Malformed JSON in request body: {}", LogSanitizer.sanitizeMessage(causeMessage));
```

Also remove the now-unused `Throwable cause` local variable. `getMessage()` may return null but SLF4J handles null gracefully in log arguments, and `LogSanitizer.sanitizeMessage(null)` should be safe (returns null or empty string).

**Verify:** Check `LogSanitizer.sanitizeMessage(null)` handles null input. If not, use:
```java
String causeMessage = ex.getMostSpecificCause().getMessage();
// causeMessage may be null — SLF4J handles null in log args
```

---

### Fix 4 — MAJOR · S6813 · `RuleDataValidator.java:24`

**File:** `src/main/java/com/company/drools/api/validation/RuleDataValidator.java`

**Current code:**
```java
@Autowired private ValidationConfig validationConfig;
```

**Problem:** Field injection via `@Autowired` is considered an anti-pattern because:
1. Hides the dependency (not visible in constructor)
2. Makes the class harder to test without Spring context
3. Prevents the field from being `final`

**Fix — constructor injection:**
```java
private final ValidationConfig validationConfig;

public RuleDataValidator(ValidationConfig validationConfig) {
    this.validationConfig = validationConfig;
}
```

Remove `import org.springframework.beans.factory.annotation.Autowired;` if no longer used.

**Spring compatibility:** Spring Boot's `SpringConstraintValidatorFactory` (auto-configured via `LocalValidatorFactoryBean`) creates `ConstraintValidator` instances through Spring's `AutowireCapableBeanFactory`, which fully supports constructor injection. This is the recommended pattern.

**Test update (`RuleDataValidatorTest.java`):**

Current setUp:
```java
validator = new RuleDataValidator();
Field configField = RuleDataValidator.class.getDeclaredField("validationConfig");
configField.setAccessible(true);
configField.set(validator, config);
```

Updated setUp (simpler and cleaner):
```java
ValidationConfig config = ValidationConfigTestHelper.createTestValidationConfig();
validator = new RuleDataValidator(config);
```

Remove the reflection-based field injection and the `Field` import if it's no longer needed.

---

### Fix 5 — MAJOR · S6813 · `RuleIdValidator.java:15`

**File:** `src/main/java/com/company/drools/api/validation/RuleIdValidator.java`

Identical pattern to Fix 4.

**Fix:**
```java
private final ValidationConfig validationConfig;

public RuleIdValidator(ValidationConfig validationConfig) {
    this.validationConfig = validationConfig;
}
```

Remove `import org.springframework.beans.factory.annotation.Autowired;`.

**Test update (`RuleIdValidatorTest.java`):**

Current setUp:
```java
validator = new RuleIdValidator();
Field configField = RuleIdValidator.class.getDeclaredField("validationConfig");
configField.setAccessible(true);
configField.set(validator, config);
```

Updated setUp:
```java
ValidationConfig config = ValidationConfigTestHelper.createTestValidationConfig();
validator = new RuleIdValidator(config);
```

---

## Execution Order

```
Step 1: Fix RuleExecutor.java
  - Fix 1 (S2095): try-with-resources for KieSession
  - Fix 2 (S2142): add InterruptedException catch block

Step 2: Fix GlobalExceptionHandler.java
  - Fix 3 (S2583): remove always-true null check

Step 3: Fix RuleDataValidator.java + test
  - Fix 4a: constructor injection in source
  - Fix 4b: update RuleDataValidatorTest setUp

Step 4: Fix RuleIdValidator.java + test
  - Fix 5a: constructor injection in source
  - Fix 5b: update RuleIdValidatorTest setUp

Step 5: mvn clean test -Dtest='!S3StorageIntegrationTest' (Docker)
  - Expect: 597 pass, 0 failures

Step 6: mvn compile spotbugs:check (Docker)
  - Expect: 0 bugs, BUILD SUCCESS

Step 7: git commit -m "fix(sonar): reliability fixes — S2095/S2142/S2583/S6813×2"

Step 8: Sonar scan + MCP verify
  - get_project_quality_gate_status → OK
  - search_sonar_issues RELIABILITY → 0 open
```

---

## Critical Files

**Production (modified):**
- `src/main/java/com/company/drools/core/engine/RuleExecutor.java`
- `src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java`
- `src/main/java/com/company/drools/api/validation/RuleDataValidator.java`
- `src/main/java/com/company/drools/api/validation/RuleIdValidator.java`

**Test (modified):**
- `src/test/java/com/company/drools/api/validation/RuleDataValidatorTest.java`
- `src/test/java/com/company/drools/api/validation/RuleIdValidatorTest.java`

**No new files created.**
