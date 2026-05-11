# E2E Validation Findings — Fix Plan

**Source**: surfaced during the [e2e-validation-checklist.md](e2e-validation-checklist.md) run on 2026-05-09 / 2026-05-10
**Status**: ✅ **Both fixed on 2026-05-10**. See [`fix-findings-plan.md`](fix-findings-plan.md) + [`fix-findings-checklist.md`](fix-findings-checklist.md) for the implementation. Finding #1 was fixed via Option B (Drools 10 `KieContainer.updateToVersion(ReleaseId)`); Finding #2 was fixed by adding the `HttpMessageNotReadableException` handler. ADR-003 updated with the new architecture's supersession note.
**Both findings are pre-existing**, not Drools 10 / Spring Boot 3.5 / Java 25 regressions

---

## Finding #1 — Single-rule refresh replaces the entire KieContainer

### Severity

**Medium**. Caller-induced (not triggered by normal traffic), but silently degrades the engine until the next full refresh. No data loss because S3 remains the source of truth.

### Symptom

After calling `POST /admin/refresh-rules/{ruleId}` for a single rule, the engine **only** fires that rule. All other rules silently stop matching — they don't return errors, they just don't fire — until `POST /admin/refresh-rules` (full refresh) is called.

### Reproduction

Stack: app + LocalStack + Redis with 10 rules loaded from S3.

```bash
# 1. Confirm full set is loaded
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "validation.customer.age", "data": {"customerAge": 17}}'
# → validationResult: REJECTED (age rule fires correctly)

# 2. Trigger single-rule refresh
curl -X POST http://localhost:8080/admin/refresh-rules/pricing.discount.simple
# → {"rule_id":"pricing.discount.simple","status":"success", ...}

# 3. Re-execute the age rule
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "validation.customer.age", "data": {"customerAge": 17}}'
# → result: {} (age rule no longer fires — KieContainer was replaced)

# 4. Recovery: full refresh restores all 10 rules
curl -X POST http://localhost:8080/admin/refresh-rules
# → {"rules_loaded":10,"rules_failed":0,...}
```

### Root cause

Two interlocking pieces:

1. **`AdminController.refreshRule`** at [src/main/java/com/company/drools/api/controller/AdminController.java:446](../../src/main/java/com/company/drools/api/controller/AdminController.java#L446):
   ```java
   // Reload rule into engine
   boolean success = droolsEngineService.loadRules(List.of(rule));
   ```
   It passes a **list of one rule** to `loadRules`.

2. **`DroolsEngineService.loadRules`** at [src/main/java/com/company/drools/core/engine/DroolsEngineService.java:137-196](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java#L137-L196):
   ```java
   public boolean loadRules(List<Rule> rules) {
     // ...
     RuleCompiler.CompilationResult compilationResult = ruleCompiler.compileRules(rules);
     // ...
     rulesLock.writeLock().lock();
     try {
       KieContainer oldContainer = currentKieContainer;
       currentKieContainer = compilationResult.getKieContainer();   // atomic swap
       // ...
       oldContainer.dispose();   // dispose the old one
       // ...
     }
   }
   ```
   `loadRules` was designed for the **full-refresh** path. Its contract is "replace the entire engine with a KieContainer built from this list." It doesn't merge — it swaps. Combined with single-KieBase architecture (ADR-001), passing 1 rule produces a 1-rule KieContainer, and the swap discards the other 9.

The ADR-003 atomic-swap pattern is doing exactly what it promises; the misuse is on the caller side.

### Why nobody caught this earlier

- `RuleExecutionIntegrationTest$SampleRulesExecution` exercises rules in isolation and calls full-refresh between scenarios.
- The 2026-02-26 full-docker-test-plan.md run included a single-rule refresh check (Step 3) but didn't re-execute *other* rules afterward to confirm they still fired — so the bug was masked.
- During Phase 8 of the 2026-05-09 stack modernization end-to-end run, this surfaced because Phase C executes all 10 rules sequentially with a single-rule refresh somewhere in the middle.

### Suggested fix — three options

#### Option A (preferred): merge single rule into existing rule set, then swap

Add a `loadOrReplaceRule(Rule rule)` method to `DroolsEngineService` that:
1. Reads the current `loadedRules` map.
2. Either adds the new rule or replaces the existing entry for that ruleId.
3. Compiles the *full* combined list.
4. Performs the atomic swap with the new full-set KieContainer.

```java
// DroolsEngineService.java — new method
public boolean loadOrReplaceRule(Rule rule) {
  rulesLock.readLock().lock();
  List<Rule> combined;
  try {
    combined = new ArrayList<>(loadedRules.values());
  } finally {
    rulesLock.readLock().unlock();
  }
  combined.removeIf(r -> r.getRuleId().equals(rule.getRuleId()));
  combined.add(rule);
  return loadRules(combined);   // atomic swap with the full updated set
}
```

Then update `AdminController.refreshRule` line 446:
```java
// OLD
boolean success = droolsEngineService.loadRules(List.of(rule));

// NEW
boolean success = droolsEngineService.loadOrReplaceRule(rule);
```

**Tradeoff**: every single-rule refresh recompiles the full rule set. With 10 rules that's negligible (~hundreds of ms). With 10,000 rules this would be expensive — but at that scale a different cache strategy is needed anyway.

#### Option B: incremental KieContainer update (Drools-native)

Drools supports incremental KieBase updates via `kieContainer.updateToVersion(...)` and the KieBuilder incremental API. This is more efficient but considerably more complex; it requires per-rule KieModule isolation and changes how the project assembles its KieFileSystem. Out of scope for a 1-file-edit fix.

Reject for now — the Option A cost at production rule counts (project's documented target is ~100s of rules, not 10,000s) is acceptable.

#### Option C: deprecate the endpoint

Remove `POST /admin/refresh-rules/{ruleId}` entirely and document `POST /admin/refresh-rules` (full) as the only refresh path. Cheapest fix, but loses the per-rule observability of "did THIS rule recompile cleanly?"

Reject — Option A is straightforward and keeps the API surface.

### Files to change (Option A)

| File | Change |
|---|---|
| [`src/main/java/com/company/drools/core/engine/DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) | Add `loadOrReplaceRule(Rule)` method (~12 lines as shown above) |
| [`src/main/java/com/company/drools/api/controller/AdminController.java`](../../src/main/java/com/company/drools/api/controller/AdminController.java) | Line 446: `loadRules(List.of(rule))` → `loadOrReplaceRule(rule)` |
| `src/test/java/com/company/drools/core/engine/DroolsEngineServiceTest.java` | Add unit test: load 3 rules → loadOrReplaceRule on rule[1] → assert all 3 still fire |
| `src/test/java/com/company/drools/integration/AdminControllerIntegrationTest.java` (if it exists; otherwise use the existing admin integration test) | Add: load 10 sample rules → single-rule refresh on one → re-execute a *different* rule → assert it still fires |

### Test plan

```java
// DroolsEngineServiceTest.java
@Test
void loadOrReplaceRule_preservesOtherRules() {
  service.loadRules(List.of(ruleA, ruleB, ruleC));
  assertThat(service.getLoadedRulesCount()).isEqualTo(3);

  Rule updatedB = new Rule("ruleB", newContent, ruleB.getMetadata());
  service.loadOrReplaceRule(updatedB);

  // After single-rule replace, all 3 still loaded and fireable
  assertThat(service.getLoadedRulesCount()).isEqualTo(3);
  assertTrue(service.hasRule("ruleA"));
  assertTrue(service.hasRule("ruleB"));
  assertTrue(service.hasRule("ruleC"));
}
```

### Acceptance criteria

- After `POST /admin/refresh-rules/{ruleId}`, all *other* loaded rules still fire on `POST /execute-rule`.
- New unit test in `DroolsEngineServiceTest` covers the scenario.
- New integration test reproduces the Phase C reproduction sequence (above) and asserts non-degradation.
- No regression in `RuleExecutionIntegrationTest$SampleRulesExecution` (existing 10-rule cookbook test).

---

## Finding #2 — Malformed JSON returns 500 INTERNAL_ERROR instead of 400 INVALID_INPUT

### Severity

**Low**. The request fails closed (no rule executes, no data leaks), but the wrong status code makes monitoring noisier (500s page on-call when they shouldn't) and the error message is unhelpful for the client ("An unexpected error occurred / Please contact support if this persists" — for a syntax error in their own request body).

### Symptom

```bash
curl -s -w "\nHTTP_STATUS:%{http_code}\n" \
     -X POST http://localhost:8080/execute-rule \
     -H "Content-Type: application/json" \
     -d '{'
```

Returns:
```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "An unexpected error occurred",
    "details": "Please contact support if this persists"
  }
}
```
HTTP_STATUS: **500**

Logs show the real cause:
```
ERROR c.c.d.a.e.GlobalExceptionHandler - Unexpected error occurred
org.springframework.http.converter.HttpMessageNotReadableException:
JSON parse error: Unexpected end-of-input: expected close marker for Object ...
Caused by: com.fasterxml.jackson.core.io.JsonEOFException: ...
```

### Root cause

[`GlobalExceptionHandler.java`](../../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) defines specific handlers for:

- `RuleNotFoundException` → 404
- `RuleExecutionException` → 400
- `MethodArgumentNotValidException` → 400 (bean-validation failures)
- `TimeoutException` → 408
- `CircuitBreakerException` → 503
- `IllegalArgumentException` → 400
- `MaxUploadSizeExceededException` → 413
- `NoResourceFoundException` → 404

…and a catch-all at line 143:
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<RuleExecutionResponse> handleGenericException(Exception ex) {
  log.error("Unexpected error occurred", ex);
  // ... 500 INTERNAL_ERROR
}
```

There is **no handler for `HttpMessageNotReadableException`** (Spring's wrapper for Jackson parse failures), so it falls through to the catch-all.

### Suggested fix

Add a dedicated handler that maps `HttpMessageNotReadableException` to 400 INVALID_INPUT with a sanitized message. The handler should NOT echo the raw parser message back to the client (it can contain offsets / fragments of the input) — return a generic "Request body is not valid JSON" instead.

```java
// GlobalExceptionHandler.java — new handler
@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<RuleExecutionResponse> handleMalformedJson(
    HttpMessageNotReadableException ex) {
  log.warn("Malformed JSON in request body: {}",
           LogSanitizer.sanitizeMessage(ex.getMostSpecificCause().getMessage()));

  RuleExecutionResponse response =
      RuleExecutionResponse.failure(
          null,
          "INVALID_INPUT",
          "Request body is not valid JSON",
          "Verify the request body is well-formed JSON matching the documented schema");

  return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
}
```

`HttpMessageNotReadableException` already wraps `JsonParseException`, `JsonMappingException`, `MismatchedInputException`, etc., so a single handler covers all Jackson read failures.

### Files to change

| File | Change |
|---|---|
| [`src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java`](../../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) | Add `@ExceptionHandler(HttpMessageNotReadableException.class)` method (~10 lines) + matching import (`org.springframework.http.converter.HttpMessageNotReadableException`) |
| `src/test/java/com/company/drools/api/exception/GlobalExceptionHandlerTest.java` | Add unit test feeding malformed JSON and asserting 400 + INVALID_INPUT code |
| `src/test/java/com/company/drools/api/controller/RuleExecutionControllerIntegrationTest.java` (or equivalent) | Add MockMvc test: `POST /execute-rule` with body `"{"` → expect 400 |

### Test plan

```java
// GlobalExceptionHandlerTest.java
@Test
void malformedJson_returns400() throws Exception {
  mockMvc.perform(post("/execute-rule")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
      .andExpect(jsonPath("$.error.message").value("Request body is not valid JSON"));
}
```

### Acceptance criteria

- `POST /execute-rule` with malformed JSON returns 400 + INVALID_INPUT.
- Log entry is at WARN, not ERROR (no longer counts as an unexpected error).
- The catch-all at line 143 still handles genuinely unexpected exceptions (e.g., NPEs, infrastructure failures).
- `request.getRemoteAddr()` and other observability hooks still attached (verified by reviewing the logging filter chain hasn't been bypassed — it isn't, since the exception is thrown after the filters run).

### Optional follow-up: also verify the existing handlers ladder

While editing `GlobalExceptionHandler`, audit:
- `MissingServletRequestParameterException` → currently 500, should be 400
- `HttpMediaTypeNotSupportedException` → currently 500, should be 415
- `HttpRequestMethodNotSupportedException` → currently 500, should be 405

Out of scope for this finding's fix but worth flagging as a separate cleanup pass — same pattern, different exceptions.

---

## Combined estimate

- Finding #1 fix + tests: **~1 hour** (small code change, ~30 min for the test + integration check)
- Finding #2 fix + tests: **~30 min**
- Combined PR review + integration run: **~30 min**
- **Total**: half-day, ideally bundled into a single small PR titled "Fix two pre-existing API edge cases (single-rule refresh; malformed JSON)"

---

## Out of scope

- Phase D Plan-vs-reality gap on **empty `data: {}`** returning 200 with empty result (instead of 400). This is by design — the validator allows empty maps; the rule simply doesn't fire when no constraints match. The plan's expectation was wrong, not the code. No fix needed unless product decides "empty data must be rejected."
- The existing handlers ladder audit (415, 405, etc.) — flagged in Finding #2's optional follow-up but not file-as-a-finding here.
- Drools incremental KieBase update path (Option B in Finding #1) — premature optimization at the project's documented rule-count scale.

---

## References

- [`e2e-validation-checklist.md`](e2e-validation-checklist.md) — full Phase 8 e2e validation results where these surfaced
- [`e2e-validation-plan.md`](e2e-validation-plan.md) — the validation plan companion
- [`stack-modernization-checklist.md`](stack-modernization-checklist.md#phase-8--full-docker-compose-end-to-end-validation--2026-05-09--2026-05-10) — Phase 8 summary
- [ADR-001](../../project-documentation/36-architecture-decision-records.md#adr-001-traditional-drl-syntax-only-not-rule-units--oopath) — single-KieBase architecture (relevant to Finding #1)
- [ADR-003](../../project-documentation/36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal) — atomic-swap pattern (working as designed; misuse is on the caller side)
