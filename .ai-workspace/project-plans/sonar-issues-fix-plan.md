# Plan: Clear the 11 open SonarQube issues (1 reliability + 10 maintainability)

**Created:** 2026-08-19 · **Branch:** `re-review-fable-1`
**Checklist:** [`sonar-issues-fix-checklist.md`](sonar-issues-fix-checklist.md)

## Context
A SonarQube scan of `drools-microservice` (`http://localhost:9000`) reports **11 open issues:
1 BUG (reliability) + 10 CODE_SMELL (maintainability)**. The repo was at 0/0 before the 2026-08-19
production-readiness session, so **8 of the 11 were introduced by that session** (new code + new
tests); 3 are pre-existing that the fresh scan surfaced. All are small, mechanical, low-risk fixes.
Goal: return the Quality Gate to 0/0 without weakening any production-readiness fix.

(The SonarQube MCP configured in `.mcp.json` is not loaded in the current Claude Code session — MCP
servers load at startup and would need a restart. Issues were read via SonarQube's REST API:
`/api/issues/search?componentKeys=drools-microservice&resolved=false`.)

## The 11 issues and fixes

### Main code
1. **S3077 / BUG** — `RedisCachedRuleStorage.java:90` — `volatile RuleStorage delegate` (from the
   S10 fix). "volatile is not enough to make this field thread-safe." **Fix:** replace with
   `private final AtomicReference<RuleStorage> delegate = new AtomicReference<>();`; update
   `setDelegate` (`.set(...)`), `requireDelegate` (`.get() == null`), and the ~9 `delegate.` read
   sites to `delegate.get().`. Proper thread-safe type, no suppression, preserves set-once semantics.
2. **S7467 / MINOR** — `RuleExecutor.java:115` — `catch (RuntimeException ignored)` →
   `catch (RuntimeException _)` (unnamed pattern; matches existing `_` usage).
3. **S4276 / MINOR** — `RedisCachedRuleStorage.java:189` (`ruleExists`) — `Supplier<Boolean>`.
   `CircuitBreaker.decorateSupplier` requires `Supplier<Boolean>` (no `BooleanSupplier` overload), so
   this is a false positive. **Fix:** `@SuppressWarnings("java:S4276")` on `ruleExists` + comment.

### Tests
4-5. **S5778 / MAJOR** — `AdminAuthFilterTest.java:188,197` — hoist `envWithProfile(...)` out of the
   `assertThatThrownBy(...)` lambda so the constructor is the only throwing call inside it.
6-7. **S2699 / BLOCKER** — `AdminAuthFilterTest.java:204,211` (`testOpenInLocal`,
   `testConfiguredKeyProdStarts`) — add an assertion:
   `assertThatCode(() -> new AdminAuthFilter(...)).doesNotThrowAnyException();`.
8-9. **S5778 / MAJOR** — `RuleExecutorTest.java:250,276` — hoist `Map<String,Object> data =
   new HashMap<>();` out of the `assertThatThrownBy` lambda.
10. **S5853 / MINOR** — `RefreshEventTest.java:33` — chain the three `assertThat(json).contains(...)`
   into one `assertThat(json).contains(a).contains(b).contains(c)`.
11. **S1130 / MINOR** — `RedisCachedStorageIntegrationTest.java:233` — remove the unthrowable
   `throws Exception` from `redisKillCircuitBreakerFallback()`.

## Files
- `src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java` (S3077, S4276)
- `src/main/java/com/company/drools/core/engine/RuleExecutor.java` (S7467)
- `src/test/java/com/company/drools/api/filter/AdminAuthFilterTest.java` (S5778 ×2, S2699 ×2)
- `src/test/java/com/company/drools/core/engine/RuleExecutorTest.java` (S5778 ×2)
- `src/test/java/com/company/drools/cache/RefreshEventTest.java` (S5853)
- `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java` (S1130)

## Verification (Docker, Java 25)
1. `docker run --rm -v "$PWD":/app -w /app -v drools-m2:/root/.m2 maven:3.9-eclipse-temurin-25 mvn -B clean spotless:apply verify`
   → BUILD SUCCESS (tests green; spotless/spotbugs/jacoco gates pass).
2. Re-scan SonarQube (`mvn … sonar:sonar`, no `-Dtest`) and confirm `total: 0` open issues.
