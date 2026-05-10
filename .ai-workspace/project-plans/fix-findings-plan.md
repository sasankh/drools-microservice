# Fix Plan — Two Pre-Existing API Findings (single-rule refresh + malformed JSON)

**Companion checklist**: [`fix-findings-checklist.md`](fix-findings-checklist.md)
**Source of findings**: [`e2e-validation-findings.md`](e2e-validation-findings.md) (which itself came out of [`e2e-validation-checklist.md`](e2e-validation-checklist.md) Phase 8)
**Started**: 2026-05-10
**Estimated effort**: 3–4 focused hours (above the half-day "Option A" estimate; Option B requires a compiler/engine refactor)
**Approach**: bundle both fixes into one PR with unit + integration tests

---

## Context

The 2026-05-09 / 2026-05-10 docker-compose end-to-end validation surfaced two pre-existing bugs (neither is a Drools 10 / Spring Boot 3.5 / Java 25 regression). User direction (2026-05-10):

- Fix **both** in one PR.
- For Finding #1, use **Option B** (Drools 10 `KieContainer.updateToVersion(ReleaseId)` incremental KieBase pattern) over Option A (merge + full re-swap).
- Test coverage: **unit + integration**.
- Tracking: a **new plan + checklist pair** in `.ai-workspace/project-plans/` (this file + its companion).

---

## The two findings (recap)

### Finding #1 — Single-rule refresh wipes out all other rules
- **Where**: [`AdminController.java:446`](../../src/main/java/com/company/drools/api/controller/AdminController.java#L446) calls `droolsEngineService.loadRules(List.of(rule))`.
- **Why it breaks**: `loadRules` ([`DroolsEngineService.java:137-196`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java#L137-L196)) is the full-replace atomic-swap path. Pass it 1 rule, you get a 1-rule engine. ADR-003 atomic-swap is doing what it promises; the misuse is on the caller side.
- **Severity**: medium. Caller-induced, but silently degrades the engine while the response says "success".

### Finding #2 — Malformed JSON returns 500 INTERNAL_ERROR instead of 400 INVALID_INPUT
- **Where**: [`GlobalExceptionHandler.java`](../../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) has no handler for `HttpMessageNotReadableException`.
- **Why it breaks**: Jackson parse failures fall through to the line-143 catch-all `@ExceptionHandler(Exception.class)` and return 500.
- **Severity**: low. Misleading status code + unhelpful message + pollutes ERROR logs with what should be 4xx.

---

## Design — Finding #1 (Option B: incremental KieBase update)

### Why Option B over Option A

| Concern | Option A (merge + full re-swap) | Option B (`updateToVersion`) |
|---|---|---|
| Code change scope | Small (one new method) | Larger (compiler + engine refactor) |
| Memory churn | Two KieContainers during swap (current) | Single long-lived container; Drools handles internal swap |
| ADR-003 status | Preserved as-is | Becomes obsolete; update note added |
| Drools 10 alignment | Uses legacy pattern | Canonical Drools 10 idiom |
| Per-rule perf | Full recompile each time | Drools' incremental compiler diffs old-vs-new at RETE level |

User picked B. Note: there is **no public per-rule incremental API in Drools 10**. The "incremental" win comes from Drools' compiler diffing the new module against the old internally. We still submit the full rule set on each update; Drools just does less work to integrate the change. The architectural improvement is the single-container model.

### Scope: migrate BOTH refresh paths (recommended)

I'm proposing migrating both the single-rule path AND the full-refresh path to `updateToVersion`. They share `loadRules` already, and keeping two parallel architectures (atomic-swap for full-refresh, `updateToVersion` for single-rule) would be confusing. The full-refresh path also benefits from the no-double-container memory characteristics.

ADR-003 (atomic-swap with explicit dispose) gets a "superseded by" update note rather than being deleted — the historical context (memory leak, why dispose was needed) stays load-bearing for understanding the codebase.

### Implementation

#### 1. `RuleCompiler` — assign explicit ReleaseId

[`src/main/java/com/company/drools/core/engine/RuleCompiler.java`](../../src/main/java/com/company/drools/core/engine/RuleCompiler.java)

Current state (per Phase-1 exploration):
- Builds an anonymous `KieFileSystem` with all rules.
- Writes each rule to `src/main/resources/rules/{ruleId}.drl`.
- Calls `KieBuilder.buildAll()` and gets `getKieModule().getReleaseId()` — a default/snapshot id.
- Returns `CompilationResult { success, kieContainer, errorMessage }`.

Change:
- Add a private `AtomicLong version = new AtomicLong(0)` field.
- On each `compileRules` call, increment and use as the version component of a synthetic `ReleaseId`: `com.company.drools:rules-runtime:1.0.<n>`.
- Before writing DRLs to the KieFileSystem, call `kfs.generateAndWritePomXML(releaseId)`. This embeds the version in the synthetic POM so the resulting `KieModule` is keyed by `ReleaseId` in the `KieRepository`.
- Extend `CompilationResult` to carry the `ReleaseId`. Existing fields (`success`, `errorMessage`) preserved. The `kieContainer` field becomes nullable — populated on the **first** compile (so the engine service can establish the long-lived container), null thereafter (the compiler just registers a new versioned module in the `KieRepository` and the engine calls `updateToVersion(releaseId)`).

```java
// RuleCompiler.java — sketch
private final AtomicLong version = new AtomicLong(0);
private static final String GROUP_ID = "com.company.drools";
private static final String ARTIFACT_ID = "rules-runtime";

public CompilationResult compileRules(List<Rule> rules) {
  long v = version.incrementAndGet();
  ReleaseId releaseId = kieServices.newReleaseId(GROUP_ID, ARTIFACT_ID, "1.0." + v);

  KieFileSystem kfs = kieServices.newKieFileSystem();
  kfs.generateAndWritePomXML(releaseId);              // synthetic POM
  for (Rule rule : rules) {
    kfs.write("src/main/resources/rules/" + rule.getRuleId() + ".drl", rule.getContent());
  }
  KieBuilder kb = kieServices.newKieBuilder(kfs).buildAll();
  if (kb.getResults().hasMessages(Message.Level.ERROR)) {
    return CompilationResult.failure(kb.getResults().toString());
  }
  // kb.getKieModule() is auto-registered in KieRepository under releaseId.
  return CompilationResult.success(releaseId);
}
```

#### 2. `DroolsEngineService` — single long-lived container + `updateToVersion`

[`src/main/java/com/company/drools/core/engine/DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java)

Current state (per Phase-1 exploration):
- `volatile KieContainer currentKieContainer` (line 36), initialized at construction from injected bean.
- `loadRules(List<Rule>)` (lines 137-196): compiles outside lock, takes write lock, swaps `currentKieContainer`, disposes the old container, updates maps.

Changes:
- `currentKieContainer` becomes `private final KieContainer kieContainer`. Initialized once at construction (the first compilation produces the initial container; subsequent compilations only emit `ReleaseId`).
- Refactor `loadRules`:
  ```java
  public boolean loadRules(List<Rule> rules) {
    log.info("Loading {} rules", rules.size());

    // Mark all rules as loading
    for (Rule rule : rules) {
      ruleMetadata.put(rule.getRuleId(), RuleMetadata.createNew());
    }

    // Compile (outside lock) — registers new versioned KieModule in KieRepository
    CompilationResult result = ruleCompiler.compileRules(rules);
    if (!result.isSuccess()) {
      log.error("Failed to compile rules: {}", result.getErrorMessage());
      for (Rule rule : rules) {
        RuleMetadata current = ruleMetadata.get(rule.getRuleId());
        if (current != null) {
          ruleMetadata.put(rule.getRuleId(), current.withError(result.getErrorMessage()));
        }
      }
      return false;
    }

    // Hot-swap KieBase via Drools incremental update
    rulesLock.writeLock().lock();
    try {
      Results updateResults = kieContainer.updateToVersion(result.getReleaseId());
      if (updateResults.hasMessages(Message.Level.ERROR)) {
        log.error("Failed to apply rule update: {}", updateResults.getMessages());
        return false;  // KieBase unchanged on failure (Drools guarantees this)
      }
      // Update loaded-rule and metadata maps as today
      // (Note: must REPLACE, not append — the new rule set is authoritative for this release)
      loadedRules.clear();
      for (Rule rule : rules) {
        loadedRules.put(rule.getRuleId(), rule);
        ruleMetadata.put(rule.getRuleId(),
            ruleMetadata.get(rule.getRuleId()).withStatus(RuleMetadata.RuleStatus.ACTIVE));
      }
      log.info("Successfully loaded {} rules at version {}", rules.size(), result.getReleaseId().getVersion());
      return true;
    } finally {
      rulesLock.writeLock().unlock();
    }
  }
  ```
- **Remove** the old-container dispose block (lines 169-181). Add a one-line comment pointing at the updated ADR-003 explaining why it's no longer needed.
- **Add** a new method:
  ```java
  public boolean loadOrReplaceRule(Rule rule) {
    List<Rule> combined;
    rulesLock.readLock().lock();
    try {
      combined = new ArrayList<>(loadedRules.values());
    } finally {
      rulesLock.readLock().unlock();
    }
    combined.removeIf(r -> r.getRuleId().equals(rule.getRuleId()));
    combined.add(rule);
    return loadRules(combined);  // shares the updateToVersion path
  }
  ```

Initialization needs careful handling: at startup, the `kieContainer` must be created from the *first* `ReleaseId`. Two approaches:
- **(a)** Lazy: pass a no-rule list to `compileRules` at construction, get a versioned empty container.
- **(b)** Eager: in `compileRules`, when no prior container exists, return a fresh container alongside the ReleaseId; the engine service stores it.

Recommend (b) — explicit and avoids a special "empty rule set" path. The compiler signals "this is the first compilation" by calling `kieServices.newKieContainer(releaseId)` and returning the container; subsequent compiles return only `ReleaseId`.

#### 3. `AdminController` — switch one line

[`src/main/java/com/company/drools/api/controller/AdminController.java#L446`](../../src/main/java/com/company/drools/api/controller/AdminController.java#L446)

```diff
- boolean success = droolsEngineService.loadRules(List.of(rule));
+ boolean success = droolsEngineService.loadOrReplaceRule(rule);
```

No other controller changes. Cache invalidate (line 443) and cache add-back (line 450) preserved.

#### 4. ADR-003 — supersession update note

[`project-documentation/36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md) — ADR-003. Append (don't rewrite) a "2026-05-10 update" subsection:

> **2026-05-10 update**: Drools 10's `KieContainer.updateToVersion(ReleaseId)` is now the rule-update mechanism. The two-container atomic-swap with explicit `dispose()` documented above is replaced with a single long-lived `KieContainer`; Drools handles the internal KieBase swap. The memory-leak fix that motivated the original explicit-dispose pattern is preserved — verified by the e2e validation 10-refresh heap-stability check showing post-GC heap below pre-test baseline. See [`fix-findings-plan.md`](../../.ai-workspace/project-plans/fix-findings-plan.md).

---

## Design — Finding #2 (malformed JSON → 400)

[`src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java`](../../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) — add one handler method (~10 lines) and one import (`org.springframework.http.converter.HttpMessageNotReadableException`). The exception already wraps `JsonParseException`, `JsonMappingException`, `MismatchedInputException`, so a single handler covers all Jackson read failures.

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<RuleExecutionResponse> handleMalformedJson(
    HttpMessageNotReadableException ex) {
  log.warn("Malformed JSON in request body: {}",
           LogSanitizer.sanitizeMessage(ex.getMostSpecificCause().getMessage()));

  RuleExecutionResponse response = RuleExecutionResponse.failure(
      null, "INVALID_INPUT", "Request body is not valid JSON",
      "Verify the request body is well-formed JSON matching the documented schema");

  return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
}
```

The handler does NOT echo the raw parser message back to the client (it can contain offsets/fragments of the input); only logs it (sanitized).

---

## Tests

### Unit tests (new)

| File | Test | Asserts |
|---|---|---|
| [`RuleCompilerTest.java`](../../src/test/java/com/company/drools/core/engine/RuleCompilerTest.java) | `compileRules_assignsBumpedReleaseId` | Compile twice → second `ReleaseId.getVersion()` > first |
| [`DroolsEngineServiceTest.java`](../../src/test/java/com/company/drools/core/engine/DroolsEngineServiceTest.java) | `loadOrReplaceRule_preservesOtherRules` | Load 3 rules → loadOrReplaceRule(rule2_v2) → all 3 still in loadedRules; firing fact for rule1/rule3 still triggers them |
| [`DroolsEngineServiceTest.java`](../../src/test/java/com/company/drools/core/engine/DroolsEngineServiceTest.java) | `loadRules_keepsContainerInstance` | Container reference is identical across two `loadRules` calls (proves `updateToVersion` not swap) |
| [`GlobalExceptionHandlerTest.java`](../../src/test/java/com/company/drools/api/exception/GlobalExceptionHandlerTest.java) | `malformedJson_returns400` | Feed `HttpMessageNotReadableException` → 400 + `INVALID_INPUT` + body `"Request body is not valid JSON"` |

### Integration tests (new)

| File | Test | Asserts |
|---|---|---|
| `AdminControllerIntegrationTest` (locate or create — Phase 0) | `singleRuleRefresh_doesNotDegradeOtherRules` | Load 3 rules → `POST /admin/refresh-rules/{rule2.id}` → execute rule1 + rule3 → both still fire |
| `RuleExecutionControllerIntegrationTest` (or equivalent MockMvc test) | `executeRule_malformedJson_returns400` | `POST /execute-rule` body `"{"` → 400 + `INVALID_INPUT` |

### Regression checks (must still pass, no new code)

- Existing `RuleCompilerTest` cases (single/multiple rule compile, warnings, invalid syntax)
- Existing `DroolsEngineServiceTest` cases (load, concurrency, lock coordination)
- Existing `RuleExecutionIntegrationTest$SampleRulesExecution` (10-rule cookbook)
- The 10-refresh heap-stability check from [`e2e-validation-checklist.md`](e2e-validation-checklist.md) Phase E — must still show post-GC heap below pre-test baseline (confirms the new single-container model is leak-free).

---

## Critical files

### Modify
- [`src/main/java/com/company/drools/core/engine/RuleCompiler.java`](../../src/main/java/com/company/drools/core/engine/RuleCompiler.java)
- [`src/main/java/com/company/drools/core/engine/DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java)
- [`src/main/java/com/company/drools/api/controller/AdminController.java`](../../src/main/java/com/company/drools/api/controller/AdminController.java) — line 446 only
- [`src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java`](../../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java)
- All four test files listed above (some cases new, some existing files extended)
- [`project-documentation/36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md) — ADR-003 update note
- [`e2e-validation-findings.md`](e2e-validation-findings.md) — mark both findings as fixed with commit reference once done

### Create
- [`fix-findings-plan.md`](fix-findings-plan.md) — this file
- [`fix-findings-checklist.md`](fix-findings-checklist.md) — phase-by-phase checkboxes
- New `AdminControllerIntegrationTest` if it doesn't already exist (Phase 0 confirms)

### Read-only
- [`src/main/java/com/company/drools/core/engine/RuleExecutor.java`](../../src/main/java/com/company/drools/core/engine/RuleExecutor.java) — confirms stateless one-session-per-request model. No changes needed; new pattern is fully compatible.
- [`pom.xml`](../../pom.xml) — confirms `drools-engine 10.2.0` + `drools-mvel 10.2.0`. No dependency changes.

---

## Phases

### Phase 0 — locate or create the integration test class
Confirm whether `AdminControllerIntegrationTest` already exists. If not, create it using the same `@SpringBootTest` + `MockMvc` pattern as the existing controller integration tests.

### Phase 1 — `RuleCompiler` versioning
Add `AtomicLong`, generate synthetic POM with explicit `ReleaseId`, return `ReleaseId` from `CompilationResult`. Add the new `compileRules_assignsBumpedReleaseId` test. Run existing `RuleCompilerTest` and confirm green.

### Phase 2 — `DroolsEngineService` refactor (the hardest part)
Switch to single long-lived container; replace dispose/swap with `updateToVersion`; add `loadOrReplaceRule`. Run `DroolsEngineServiceTest`; add the two new unit tests. Run the broader test suite (`mvn test`) and confirm no regressions.

### Phase 3 — `AdminController` one-line swap + integration test
Change line 446. Add the new `singleRuleRefresh_doesNotDegradeOtherRules` integration test. Run `AdminControllerTest` and confirm still green.

### Phase 4 — `GlobalExceptionHandler` new handler
Add `@ExceptionHandler(HttpMessageNotReadableException.class)`. Add `GlobalExceptionHandlerTest` case. Add the MockMvc test in the controller integration test.

### Phase 5 — Memory regression check
Re-run the 10-refresh heap-stability sequence on the running stack (per [`e2e-validation-checklist.md`](e2e-validation-checklist.md) Phase E). Confirm post-GC heap remains below pre-test baseline. (If it doesn't: the new single-container `updateToVersion` model has a leak we missed in the API research, and we'd need to either fall back to Option A or investigate further.)

### Phase 6 — Documentation
Update ADR-003 with supersession note. Update [`e2e-validation-findings.md`](e2e-validation-findings.md) status: both findings → fixed (with commit hash).

### Phase 7 — Companion files + index
Already created: this plan file + the checklist. Update [`.ai-workspace/README.md`](../README.md) `project-plans/` row to reference the new pair.

---

## Verification (when done)

- [ ] All four new unit tests pass
- [ ] Both new integration tests pass
- [ ] All existing tests still green: `mvn clean test`
- [ ] All 10 sample-rule integration tests still match cookbook outputs
- [ ] 10-refresh heap-stability check still passes (post-GC heap below pre-test baseline)
- [ ] ADR-003 has the 2026-05-10 supersession note
- [ ] [`e2e-validation-findings.md`](e2e-validation-findings.md) marks both findings as fixed
- [ ] [`.ai-workspace/README.md`](../README.md) project-plans index entry updated
- [ ] No new ERROR-level logs from a clean stack startup + standard request mix (re-run e2e Phase F log review)
- [ ] [`fix-findings-checklist.md`](fix-findings-checklist.md) all boxes ticked

---

## Estimated effort

| Phase | Estimate |
|---|---|
| 0 — locate/create integration test | 15 min |
| 1 — RuleCompiler versioning | 30 min |
| 2 — DroolsEngineService refactor | 60–90 min |
| 3 — controller line + integration test | 20 min |
| 4 — exception handler | 20 min |
| 5 — memory regression check | 15 min |
| 6 — docs (ADR-003 + findings status) | 15 min |
| 7 — companion files + index | already done |
| **Total** | **~3–4 hours focused** |

Above the half-day "Option A" estimate because Option B requires the compiler-and-engine refactor that Option A would have skipped. User accepted this cost.

---

## Out of scope

- **Per-rule isolated KieModules** — Drools 10 doesn't expose a public per-rule incremental compile API. We submit the full rule set with a bumped version; Drools handles the diff internally at RETE level.
- **The handler-ladder audit** (415, 405, etc.) flagged as a follow-up in [`e2e-validation-findings.md`](e2e-validation-findings.md) — separate cleanup pass.
- **Empty-data-returns-200 plan-vs-reality mismatch from Phase D** — by design (validator allows empty maps; rule simply doesn't fire). Not a bug.
- **`updateToVersion` performance benchmarking vs full atomic swap** — at the project's documented rule-count scale (~100s of rules) any difference is negligible. If the project ever needs to support 10K+ rules, revisit then.

---

## References

- [`e2e-validation-findings.md`](e2e-validation-findings.md) — original detailed findings with three options for Finding #1
- [`e2e-validation-checklist.md`](e2e-validation-checklist.md) — Phase 8 e2e validation results where these surfaced
- [`stack-modernization-checklist.md`](stack-modernization-checklist.md#phase-8--full-docker-compose-end-to-end-validation--2026-05-09--2026-05-10) — Phase 8 summary
- [Drools 10 KieContainer Javadoc](https://docs.drools.org/latest/kie-api-javadoc/org/kie/api/runtime/KieContainer.html)
- [Drools 10 migration guide](https://kie.apache.org/docs/10.0.x/drools/drools/migration-guide/index.html)
- [DROOLS-765 — incremental update memory issues (historical context)](https://issues.redhat.com/browse/DROOLS-765)
- ADR-001 — single-KieBase architecture (still load-bearing under the new pattern)
- ADR-003 — atomic-swap with disposal (will be updated with supersession note in Phase 6)
