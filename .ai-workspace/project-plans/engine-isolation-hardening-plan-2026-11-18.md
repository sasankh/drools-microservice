# Engine Isolation & Hardening — Plan & Checklist

| | |
|---|---|
| **Created** | 2026-11-18 |
| **Status** | 🟡 Phases 1–3 complete; Phase 4 (execution limits & security) in progress |
| **Work branch** | `engine-isolation` (git worktree `.claude/worktrees/engine-isolation`, based on `improvements-1-11-18-2026` @ `e1dd059` = `develop` after PR #10) |
| **Delivered to** | `improvements-1-11-18-2026`, then one PR to `develop` (opened when the user asks) |
| **Release target** | Next minor after v3.3.0. This contains a **breaking semantic change**: rule stacking is removed. |
| **Owner** | Sasankh BC (decisions) / Claude (implementation) |
| **Input** | `DROOLS-ENGINE-HARDENING-IMPLEMENTATION-BRIEF.md` (external recommendations, 2026-11-17/18), checked against the code on 2026-11-18 |
| **Related** | `pre-merge-fixes-plan-2026-11-17.md` (§A11 backlog: several of its items are closed here); `project-documentation/39-load-test-findings.md` (the performance baseline) |

> This single file holds both the plan (Part A) and the checklist (Part B).
> Tick boxes as work lands, and refresh the "Last updated" line in the footer.

---

# Part A — Plan

## A1. Why this plan exists

### A1.1 The core problem (verified in code)
`POST /execute-rule {rule_id, data}` does **not** run the rule that was asked for. It runs **every loaded rule**.

- `RuleCompiler.compileRules` (`core/engine/RuleCompiler.java:56-73`) writes every DRL file into one `KieFileSystem`. There is no `kmodule.xml`, so Drools builds one **default KieBase holding all rules**.
- `RuleExecutor.executeRuleInternal` (`core/engine/RuleExecutor.java:126-131`) runs:
  ```java
  try (KieSession kieSession = kieContainer.newKieSession()) {   // default session over ALL rules
      kieSession.insert(inputData);
      int rulesFired = kieSession.fireAllRules(maxRuleFirings);  // 10000, result only DEBUG-logged
      return inputData;
  }
  ```
- `rule_id` is only used for a `loadedRules.containsKey` existence check (`DroolsEngineService.java:90-104`), the ACTIVE check (`:108`), metric tags and logs.

**Consequences:**
- **Wrong results.** `pricing.discount.vip` with `amount 100` returns **72**: VIP's 20% plus simple's 10% on top. It should return **80**.
- **Cross-rule crashes.** A forall request with `items:[{stockLevel:10}]` fails, because the *bundle* rule's LHS casts the missing `price` and throws an NPE (`run-docker-test-plan.sh` X1.K).
- **Output collisions.** `discount`, `discountReason`, `validationMethod` and `validationResult` are written by several unrelated rules; the last write wins.
- **Wasted work.** Every request evaluates the LHS of all rules: 17 today, 1,000 in the load test.

### A1.2 Other verified problems (code evidence)

| # | Problem | Evidence |
|---|---|---|
| P1 | Changing one rule recompiles **all** rules. ~510 ms median at 1,000 rules (warm), ~46–50 s cold. | `DroolsEngineService.loadOrReplaceRule` :274-290, `removeRule` :301-321 → `doLoadRules` (full set); `39-load-test-findings.md:23,100,119` |
| P2 | One invalid file fails the **whole** batch. Bulk refresh reports `rule_id:"ALL"` and still returns 200. | `RuleCompiler.java:47-51, 77-84`; `AdminController.java:444-460` |
| P3 | An S3 per-object read failure is swallowed. The partial list is treated as the full set, and `retainAll` drops the missing rules. An empty list wipes every rule and is fanned out to siblings via pub/sub. | `S3RuleStorage.java:334-346`; `DroolsEngineService.java:216-224`; `AdminController.java:429-443` |
| P4 | `LocalFileStorage` returns `[]` when the directory is missing or unreadable. | `LocalFileStorage.java:47-50, 60-63, 72-76` |
| P5 | A startup load failure is swallowed, so the app runs with 0 rules. `/admin/health` always returns HTTP 200, so the Docker healthcheck passes. There are no liveness/readiness groups. | `RuleLoadingConfig.java:37-44`; `AdminController.java:165, 197-200`; `application.yml:13-31` |
| P6 | An unknown `drools.rule-source` falls back to 2 demo rules with only a WARN. Profile documents hard-code `rule-source`, so the `RULE_SOURCE` env var is ignored. | `StorageFactory.java:73-76`; `application.yml:191, 210, 258, 319` |
| P7 | Pub/sub is fire-and-forget. A missed message leaves an instance stale indefinitely. The subscriber ignores compile results. Bulk events within 500 ms are dropped. The listener's `CallerRunsPolicy` breaks the single-thread assumption. | `RuleRefreshPublisher.java:16-17, 84-98`; `RuleRefreshSubscriber.java:52, 155, 161-168`; `RedisConfig.java:122` |
| P8 | The docs promise a 5-minute auto-refresh in prod, but `drools.refresh.auto-enabled` / `interval-minutes` are dead config. There is no `@Scheduled`. | `application.yml:79-81, 259-260`; docs `05:197,262`, `09:85`, `30:109`, `35:280` |
| P9 | Out-of-order refresh: the storage read happens **outside** `refreshLock`, so an older snapshot can be installed last. | `AdminController.java:427-432`; `RuleRefreshSubscriber.java:167-168` |
| P10 | No content version or digest. `version` is hard-coded `"1.0"`. `lastModified` from storage is thrown away. | `Rule.java:7-9`; `RuleMetadata.java:9-15, 38`; `DroolsEngineService.java:228-232` |
| P11 | Reaching the firing cap succeeds silently (HTTP 200). The cap is hard-coded at 10000. | `RuleExecutor.java:25, 129-130` |
| P12 | The timeout cannot stop a non-yielding RHS loop (a JVM limit). `future.cancel(true)` on a `CompletableFuture` doesn't even interrupt. | `RuleExecutor.java:61-84, 105-108` |
| P13 | `executeRule` holds `rulesLock.readLock()` for the whole execution (up to 30 s). A refresh's write lock waits behind slow requests, and new requests then queue behind the writer. | `DroolsEngineService.java:87-162, 204` |
| P14 | `DrlSanitizer` bypasses: `import x // comment` is never matched; `System .exit` (whitespace) passes; fully-qualified names in rule bodies aren't checked; the `java.util.` allowlist admits `ServiceLoader`, `concurrent` and `logging`. | `DrlSanitizer.java:17-38, 99-101, 171` |
| P15 | Docs call the sanitizer a "sandbox" that "prevents arbitrary code execution", which contradicts `SECURITY.md` (B1). | `README.md:673`; `16-drl-sandboxing.md` (title, :40); `37-glossary.md:132`; `17-rule-development.md:760` |
| P16 | Refresh actions have no audit trail: no actor, no rule IDs, no digest. | `AdminController.java:417`; `AdminAuthFilter.java:84-87` |
| P17 | Small defects: the `drools.rules.loaded` gauge is never updated (always 0); `/admin/health` calls `createRuleStorage()` on every request; a refresh-single `not_found` response loses its body. | `MetricsConfig.java:131, 145-151`; `StorageFactory.java:37-47`; `AdminController.java:483-486` |

### A1.3 Facts that make isolation safe to do
- **No cross-file dependencies.** None of the 17 sample files uses `global`, `function`, `declare` or `query`. Imports are only `java.util.Map`, `java.util.List` and `java.time.*`. Each file has exactly **one** rule, and every LHS is `$data : Map(...)`.
- **Every file compiles on its own.** 11 of the 17 are already compiled one at a time in `RuleExecutionIntegrationTest.SampleRulesExecution`, and they assert the **isolated** values there (VIP 200 → 160, loyalty 100 → 85).
- **No rule-name collisions** across the 17 files.
- **Packages are shared per directory.** Examples: `com.company.rules.pricing.discount` holds simple, vip, bulk and first-time; `…pricing.shipping` holds express and standard. **Package-based selection alone cannot isolate files.**
- **No Java test asserts stacked values.** They are asserted only in scripts and docs (inventory in A4.6).
- **Isolated values** for the documented payloads:

  | Payload | Stacked (today) | Isolated |
  |---|---|---|
  | VIP $100 | 72 | **80** |
  | bulk 15 × $200 | 153 | **170** |
  | first-time $100 | 85.5 | **95** |
  | loyalty $100 | 76.5 | **85** |
  | Black Friday $200 | 135 | **150** |
  | holiday $100 | 79.2 | **88** |

  Simple, shipping, age, credit, bundle, email, temporal and the validation rules are unchanged.

### A1.4 Assessment of the brief — what we do and why

| Brief item | Decision | Reason |
|---|---|---|
| §3 rule_id = execution boundary (P0) | **Do. Phases 1–2, first.** | Real and the most impactful bug (A1.1). Safe to do (A1.3). |
| §3.3 Option C: AgendaFilter / agenda groups | **Reject** (Phase 1 proves it with a test) | It filters *activations*, but other rules' LHS (`from`, `accumulate`, casts) still evaluate, so the bundle NPE remains. Rules don't declare groups. It is not a boundary. |
| §3.3 Option B: one module with a named KieBase per ID | **Spike as the fallback** | It works only if kbase `packages` selection is folder-based; verify. It still recompiles the whole module on every change, so P1 isn't fixed. |
| §3.3 Option A: an isolated KieBase per ID | **Spike; expected choice** | Smallest unit. Gives a true incremental refresh (P1), per-ID failure isolation (P2), and immutable handles, which remove the execution read lock (P13). |
| §4 DRL is executable code: honest docs + threat model | **Do (Phase 4)** | P15. `SECURITY.md` already says B1. The rest of the docs must agree. |
| §4 sanitizer as defense in depth | **Do, cheaply (Phase 4)** | P14. Documented as a *policy check*, not a sandbox. |
| §4 audit trail for refresh | **Do (Phase 4)** | P16. |
| §4.3 a separate low-privilege worker process for untrusted DRL | **Defer, documented** | User decision: DRL is trusted-admin only. It becomes a **prerequisite** before any customer- or AI-supplied DRL. |
| §4.2 approval workflow, IAM separation | **Out of repo** | There is no write/publish API in the service, and IAM is infrastructure. Documented as an operational control in `SECURITY.md`. |
| §5 release baseline, CI, CVEs | **Already done** | PR #10 merged (`e1dd059`); CI green; OWASP moved to Dependabot (2026-11-18). |
| §6 incremental compile | **Do (falls out of Option A)** | P1. |
| §6 bounded memory-weighted eviction cache, leases, single-flight cold loads | **Defer** | Everything stays resident. That is the current behaviour, and it is fine at 17 / 1,000 rules. Immutable handles mean in-flight requests keep their own reference, and the JVM frees retired ones, so no leases are needed. Revisit only if the A8 heap numbers demand it. |
| §6.3 store compiled KJAR artifacts for cold replicas | **Defer** | No evidence it's needed; there's already a separate `kjar-precompilation-plan.md`. |
| §6.4 benchmark matrix (5,000 IDs × 100 versions …) | **Reduced** | Measure 17 real + 1,000 synthetic rule IDs with the existing harness (`scripts/run-load-test.sh`, `scripts/lib/corpus.sh`) against the 2026-05-10 baseline. |
| §7.1–7.3 transactional refresh, complete snapshots, startup | **Do (Phase 3)** | P2–P6. |
| §7.5 distributed reconciliation, monotonic versions | **Do (Phase 3)** | P7–P10. A digest-based scheduled reconcile makes the existing `auto-enabled` config real. |
| §7.7 readiness vs liveness, metrics | **Do (Phase 3)** | P5, P17. |
| §7.8 firing cap and timeout | **Do (Phase 4)** | P11, P12. User decision: the cap is an **error**. |
| Not in the brief: the out-of-order refresh race and the execution read lock | **Do** | P9, P13. Found while verifying the code. |

### A1.5 Decisions confirmed with the user (2026-11-18)
1. **Isolation without a legacy fallback.** `rule_id` runs only its own DRL file. Stacking is removed; scripts and docs move to the isolated values. This is recorded as a breaking semantic change.
2. **DRL is trusted-admin only.** We document the trust model honestly and harden the sanitizer as defense in depth. Process isolation is a future prerequisite, not built now.
3. **Firing cap → error.** HTTP **422 `RULE_LIMIT_EXCEEDED`**, a configurable cap, and a metric.
4. **Delivery.** One branch, a commit per phase after its gate, and one PR to `develop` at the end, opened when the user asks.
5. Plan + checklist go in this single dated file. Work starts after it is written (user instruction, 2026-11-18).

### A1.6 Ground rules
- **No change** to the `POST /execute-rule` request/response shape, the `rule_id` validation, the ID → storage-key mapping, the input-copy semantics, numeric coercion, or output keys.
- Compile once, execute many. Never compile on the request path. Storage and Redis stay off the warm path.
- A fresh `KieSession` per request, always closed.
- Every claim is backed by a test or a measurement. Drools 10.2.0 API behaviour is proven with executable tests, not assumed.
- Docker for everything (`DOCKER_HOST=unix:///var/run/docker.sock`); Maven runs in `maven:3.9-eclipse-temurin-25`.
- Commits use the noreply email (`11912318+sasankh@users.noreply.github.com`), one commit per phase after its gate passes.

---

## A2. Phase 0 — Plan ✅
- This file written: plan + checklist, dated.
- Worktree `.claude/worktrees/engine-isolation` on branch `engine-isolation`, from `improvements-1-11-18-2026` @ `e1dd059`.

---

## A3. Phase 1 — Spike + decision (ADR-025)

### A3.1 Red regression test first
- `integration/RuleIsolationIntegrationTest#simpleDoesNotRunVip`: load `simple.drl` + `vip.drl`; execute `pricing.discount.simple` with `{customerType:VIP, amount:100}`; assert `amount == 90.0`.
- Today this gives 72.0. It stays red in the spike commit (`@Disabled("red until Phase 2")` with the observed value in the message), so the branch never has a failing build. Phase 2 enables it.

### A3.2 Spike options (test scope only: `src/test/java/com/company/drools/spike/`)

| Option | How | What to prove |
|---|---|---|
| **A1** — KieBase per ID via its own KieModule | Per file: `KieFileSystem` with its own `ReleaseId` (`com.company.drools:rules-<sanitized id>:<n>`); `KieBuilder.buildAll()`; `newKieContainer(releaseId).getKieBase()` | Execution still works after `KieRepository.removeKieModule(releaseId)` and after `KieContainer.dispose()`. Retired bases are garbage-collected (`WeakReference` + `System.gc()` loop). The repository doesn't grow across 100 rebuilds. |
| **A2** — KieBase per ID without a repository | `KieHelper().addContent(drl, ResourceType.DRL).build()`, and/or `KnowledgeBuilderFactory` + `KnowledgeBaseFactory` (check which is public API in 10.2.0) | Same as A1, plus whether `KieHelper` registers modules in the `KieRepository` (a leak risk) |
| **B** — one module, named KieBase per ID | `KieModuleModel.newKieBaseModel("kb_<id>").addPackage("rules.<id path>")`, each file in its own folder | Whether `packages` selection is **folder-based**, so that two files with the **same DRL package** in different folders isolate. Measure the recompile cost of a single-file change. |
| **C** — AgendaFilter | The current global base + `fireAllRules(filter)` matching only that rule's names | Show that the bundle NPE (another rule's LHS) still happens, so this option is rejected. |

Each option runs the same functional checks: simple-with-VIP-fields = 90, vip = 80, same-package isolation, a 2-rule file with `salience` fires both in order, and a refresh doesn't affect another ID.

### A3.3 Measurements (one JVM, Docker Maven, recorded in ADR-025)
- **Compile time:** the 17 sample files, and 1,000 synthetic files from the `corpus.sh` patterns, cold and warm, sequential vs parallel (storage pool).
- **Heap after GC:** with 17 and with 1,000 resident bases, against today's single base with the same rules.
- **Metaspace and class count:** after 100 single-ID refreshes. Must plateau, not grow.
- **Warm execution latency:** p50/p99 over 10k executions, against today's global base.
- **Single-ID refresh time** at 1,000 resident rules. Target: much less than 510 ms.

### A3.4 Decision
- **ADR-025 "Per-rule-ID compiled KieBase isolation"** in `project-documentation/36-architecture-decision-records.md`: context, the options with the measured numbers, the decision, consequences (including the breaking change and the migration note), and references. Add it to the index.
- Renumber the placeholders "ADR-025/026/027" (HTTP client, Maven, Corretto) to **026/027/028**, and update the "Pick the next number" text.
- **Expected:** A1 or A2. Fall back to B only if the per-base heap at 1,000 IDs is unacceptable (more than ~2× the single base, or more than 256 MB extra).
- Remove the spike harness after the decision, but keep the regression and functional checks as permanent tests in Phase 2.

### A3.5 Gate
The spike tests pass (except the documented red test), ADR-025 is written, and `mvn -B clean verify` is green.

---

## A4. Phase 2 — Isolation (must ship first)

### A4.1 New types (`core/engine/`)
- `CompiledRuleset` (immutable record): `ruleId`, `contentDigest` (SHA-256 hex of the DRL), `KieBase kieBase`, `Instant compiledAt`, `List<String> ruleNames`, plus the lifecycle handle the chosen option needs (e.g. `ReleaseId` for A1).
- `CompiledRulesetRegistry` (Spring `@Component`):
  - Backed by a `ConcurrentHashMap<String, CompiledRuleset>`.
  - Methods: `Optional<CompiledRuleset> get(id)`, `install(CompiledRuleset)` (atomic replace; returns the previous one for retirement), `remove(id)`, `Map<String, CompiledRuleset> snapshot()`, `int size()`.
  - Retirement of the previous handle follows the option (A1: `removeKieModule`/`dispose` after the swap, safe because the `KieBase` stays usable; verified in A3).
- The API layer never sees Drools types.

### A4.2 `RuleCompiler`
- `RuleCompilation compileRule(Rule)`: run `DrlSanitizer` on that one file, then build it in isolation. Returns `success(CompiledRuleset)` or `failure(ruleId, errors)`.
- `BatchCompilation compileRules(List<Rule>)`: per-ID results. **One failure never blocks the others.** Compiles in parallel on the storage executor if A3 shows a gain.
- Delete the single-module, global `versionCounter` path.

### A4.3 `RuleExecutor`
- `executeRule(CompiledRuleset ruleset, Map<String,Object> data, int timeoutSeconds)`: `try (KieSession s = ruleset.kieBase().newKieSession())`, `insert(data)`, `fireAllRules(cap)`. The Phase 4 cap and timeout changes plug in here.
- Same `ExecutionResult` mapping and the same `TimeoutException` / `ServiceUnavailableException` behaviour.

### A4.4 `DroolsEngineService`
- `executeRule(ruleId, data)` does `registry.get(ruleId)`. A missing ID gives the same "Rule not found" failure as today. ERROR/DISABLED metadata → the existing "Rule is not active" behaviour. **No `rulesLock` read lock**: the request holds its own immutable `CompiledRuleset` reference for the whole execution.
- `loadRules(List<Rule>)` (bulk):
  - Compile every candidate.
  - Install the successes.
  - A failed ID keeps its **last good** handle, and its metadata goes to `ERROR` with the message via `RuleMetadata.withError`. A failed ID with no previous version stays absent, recorded as ERROR.
  - IDs absent from the snapshot are removed; Phase 3 adds the complete-snapshot guard.
  - Returns a `LoadReport` (loaded / unchanged / failed with messages / removed) instead of a boolean.
- `loadOrReplaceRule(Rule)` compiles **only** that ID. `removeRule(id)` removes only that ID. `refreshLock` still serializes the mutations.
- `rulesLock` is removed. `ruleMetadata` stays a `ConcurrentHashMap`, and stats update through `compute`.
- Callers adapt to the `LoadReport`: `AdminController.refreshAllRules` / `refreshRule`, `RuleRefreshSubscriber`, `RuleLoadingConfig`.

### A4.5 Config, metrics, health
- `DroolsConfig`: remove the `KieContainer` bean. Keep the `KieServices` / `KieRepository` beans if option A1 needs them.
- Update every `kieContainer` user (health/admin info, tests).
- Metrics:
  - `drools.rule.compile.time` (timer; tags `rule_id`, `result`)
  - `drools.rule.compile.error` (counter)
  - `drools.rules.loaded` (gauge, actually bound to `registry.size()`, fixing P17)
  - the existing execution metrics keep their `rule_id` tag
- Logs: the resolved `ruleId` + `contentDigest` (short) on compile/install; no payloads.

### A4.6 Tests
- **New `integration/RuleIsolationIntegrationTest`:**
  - simple + VIP fields → 90
  - vip → 80
  - an unrelated matching rule doesn't change the output or appear in it
  - two files in the **same package** isolate
  - one file with 2 rules + `salience`: both fire, in order
  - forall payload without `price` → success, `allItemsInStock=true` (the bundle NPE is gone)
  - missing / removed / ERROR IDs never fall back
  - 8 threads × 2 IDs concurrently: no cross-contamination
  - refresh X while Y executes: Y correct; new X requests use the new version; an in-flight X finishes on the old one
  - broken refresh of X: the old X keeps serving and Y is untouched
  - 100 refreshes of X: heap and metaspace stable
- **Update:**
  - `RuleExecutorTest` and `DroolsEngineServiceTest` (new signatures, `LoadReport`)
  - `RuleCompilerTest` (the duplicate rule name across two IDs now **compiles**; within one file it still fails)
  - `RuleRefreshIntegrationTest` (`removeKieModule` assertions → registry/retirement assertions)
  - `AdminControllerTest` (report shape)
  - `RuleRefreshSubscriberTest`

### A4.7 Moving scripts and docs to isolated values (same commit, so the gates pass)
- **Scripts:**
  - `scripts/lib/e2e_load.py` CASES: vip 80, bulk 170, first-time 95, loyalty 85, blackfriday 150, holiday 88; update the "assume ALL 17 loaded" comment.
  - `scripts/e2e-load-test.sh`: smoke expectations (vip 80, bulk 170, blackfriday 150); drop "stacking-aware" wording.
  - `scripts/run-docker-test-plan.sh`: 4.2 8000, 4.3 17000, 4.4 9500, 4.7 15000, 4.8 8800, X1.2 8500; **X1.K becomes a PASS** (forall without `price` succeeds); remove the "stacking applies" note.
- **Docs:**
  - `full-docker-test-plan.md` (Step 4 table + summary rows; remove "Rules stack").
  - `project-documentation/19-sample-rules-cookbook.md`: remove the "⚠️ rules stack" section; use isolated responses; fix the internal "Math" contradiction; the Phase 1 examples now run exactly as documented.
  - `00-system-overview.md:235`, `32-getting-started.md:140-154` (80, not 72), `35-faq.md:54-56, 108+` (rewrite as "rules are isolated per rule_id"), `37-glossary.md:150-151`, `38-for-ai-agents.md:69-71`, `project-documentation/README.md:21`, `scripts/README.md:34`.
  - `README.md:966-1029` payload fixes (wrong keys); `openapi.yml:104-157` (fix `simple.discount` → `pricing.discount.simple`, `customer_type` → `customerType`, isolated example results).
  - `04-architecture.md:545, 735`: execution flow with a per-ID KieBase.

### A4.8 Gate
`mvn -B clean verify` green (coverage ≥ 88/74), the Testcontainers ITs green, `run-docker-test-plan.sh` all PASS with isolated values, and `e2e-load-test.sh --duration 60` PASS.

---

## A5. Phase 3 — Refresh, storage, startup correctness

### A5.1 Complete snapshots (P3, P4)
- `RuleStorage.getAllRules()` contract: return the **complete** set or throw `RuleStorageException`. It never returns a partial list.
- `S3RuleStorage`: any per-object failure → throw, inside the circuit breaker, so it counts. `getRuleIds()` errors throw too, instead of returning `new ArrayList<>()`.
- `LocalFileStorage`: a missing or unreadable directory or a file IO error → throw.
- `RedisCachedRuleStorage`: propagate delegate failures; never mix stale cached values over a failed delegate listing.
- Bulk refresh on an exception: **nothing changes**, the report says `snapshot_incomplete`, and **no** bulk pub/sub event is published.
- **Empty-set guard:** a complete snapshot with 0 rules while rules are loaded is refused unless `drools.refresh.allow-empty=true` (default false).

### A5.2 Content digest + metadata (P10)
- `Rule` gains `contentDigest()` (SHA-256, computed once). `RuleMetadata` stores `contentDigest`, the real `lastModified` from storage, and `compiledAt`. Stop discarding storage metadata.
- Load/refresh: an unchanged digest → skip the compile and report `unchanged`.
- `/admin/rules` and the refresh responses show the previous and new digest. `previousVersion` gets real data.

### A5.3 Ordering (P9)
- Every refresh path (admin bulk/single, subscriber, scheduled reconcile) does its **storage read inside `refreshLock`**, so the newest read is always the last install.

### A5.4 Reconciliation (P7, P8)
- Implement `drools.refresh.auto-enabled` / `drools.refresh.interval-minutes` (existing properties) as a `@Scheduled` reconcile (`@EnableScheduling`):
  - list storage IDs and digests (a complete snapshot)
  - compare with the registry
  - recompile only changed IDs, add new ones, remove deleted ones (with the empty-set guard)
- Reconcile results never publish pub/sub events (no loops).
- Metrics: `drools.reconcile.runs`, `drools.reconcile.changed`, `drools.reconcile.failures`. Log the drift found, which shows that pub/sub was missed.
- The defaults match the docs: `auto-enabled` true in `prod` (5 min) and false in `local`/`dev`/`docker`, unless the env var sets it. Check against `application.yml`.

### A5.5 Subscriber + listener (P7)
- `RuleRefreshSubscriber` acts on the `LoadReport`: WARN plus a `drools.refresh.event.failed` metric on failure. A single-rule event for an ID that is missing in storage → remove that ID (it was deleted).
- `RedisConfig` listener executor: replace `CallerRunsPolicy` with a reject handler that logs and counts. Reconciliation repairs whatever was missed.

### A5.6 Startup, readiness, health (P5, P17)
- New `RulesHealthIndicator` (`management.health.rules`):
  - DOWN if the startup load failed, if 0 rules are loaded (unless `allow-empty`), or if the last full load reported failures.
  - Details: loaded count, ERROR IDs, last refresh time and result.
- `application.yml`: `management.endpoint.health.probes.enabled=true`; the `readiness` group includes `readinessState,rules`; `liveness` = `livenessState`.
- `/admin/health` returns **HTTP 503** when its overall status is DOWN. Docker compose's healthcheck therefore fails truthfully.
  - Before this lands, check that the compose `depends_on`/healthcheck flow still starts: LocalStack is seeded before the app loads.
- `/admin/health` stops calling `storageFactory.createRuleStorage()` on every request and uses the injected storage bean.
- `RuleLoadingConfig`: record the load result for the indicator. The new `drools.startup.fail-on-load-error` (default false) exits on failure when true.
- Startup window: readiness stays OUT_OF_SERVICE until the first load completes.

### A5.7 `RULE_SOURCE` (P6)
- `StorageFactory`: an unknown value → `IllegalStateException`, so startup fails with a clear message.
- `application.yml` profile documents use `${RULE_SOURCE:<current literal>}` so the env var is honoured. Profile defaults are unchanged.

### A5.8 Admin refresh responses (P2, P17)
- Bulk: total failure (snapshot incomplete or every ID failed) → **500**; partial → 200 with per-ID `errors[]` and `rules_loaded` / `rules_failed` / `rules_unchanged` / `rules_removed`. This matches `10-api-reference.md:336-352`.
- Single: compile failure → 500 with the compiler message (sanitized); not found → 404 **with** the body.

### A5.9 Tests
- S3 partial failure: a unit test with a mocked client, plus an S3 IT case where one object is unreadable. No rules are lost.
- `LocalFileStorage` missing directory throws.
- Empty-set guard.
- Digest skip.
- Concurrent out-of-order refresh: an older read can't win.
- Reconcile fixes a missed event.
- Readiness DOWN on 0 rules / failed startup, UP after a load; `/admin/health` 503.
- Unknown `RULE_SOURCE` fails context startup.
- Admin status codes and the `not_found` body.
- Subscriber failure metric.

### A5.11 Implementation notes / deviations (2026-11-18)
- **One refresh owner.** New `RuleRefreshService` (startup, admin, pub/sub, reconcile) reads storage and applies under one lock. `RuleLoadState` records the phase and the last refresh, for health.
- **Per-ID compile failures don't make the instance DOWN.** The `rules` indicator is DOWN only when the startup load hasn't succeeded or 0 rules are compiled. Failed IDs are listed in the details. Reason: one bad file must not take every replica out of rotation.
- **`/admin/health`: UP / DEGRADED / DOWN.** HTTP 503 only for DOWN (can't serve rules). Storage unreachable while rules serve → DEGRADED (200), so an S3 blip doesn't fail container health checks.
- **Empty-set refusal → HTTP 409 `rejected_empty`** (storage failure → 500 `failed`).
- **`rules_loaded` keeps its meaning.** It counts every rule ID serving from the snapshot (recompiled + unchanged). The new `rules_unchanged` / `rules_removed` give the split.
- **Pub/sub bulk coalescing removed.** The digest skip makes repeats cheap, and the 500 ms window could drop a real change.
- **Reconcile reads the source directly** (`RuleStorage.getAllRulesFromSource()`, bypassing Redis). Startup and pub/sub reads may still be answered by the Redis DRL cache (15-min TTL, ADR-016). Direct S3 edits are picked up by reconcile, or by an admin refresh (which invalidates Redis first).
- **No `RuleMetadata` digest field.** `RuleMetadata` is part of the Redis JSON, so the digest lives on `CompiledRuleset` (`getServingDigest`); this avoids mixed-version JSON issues during a rolling deploy.
- `StorageFactory` builds the storage once; the health check no longer re-wires it per call.

### A5.10 Gate
Same as A4.8, plus live: kill LocalStack during a refresh → all rules still loaded; start with an empty bucket → readiness DOWN and `/admin/health` 503.

---

## A6. Phase 4 — Execution limits & security hardening

### A6.1 Firing cap (P11) — user decision: error
- New `drools.execution.max-rule-firings` (default 10000) in `application.yml` and the env var `DROOLS_MAX_RULE_FIRINGS`.
- Call `fireAllRules(cap + 1)`. If `fired > cap`, more activations existed than allowed, so this is an exact limit-exceeded signal with no false positive at exactly `cap`.
- New `api/exception/RuleLimitExceededException` → `GlobalExceptionHandler` → **HTTP 422**, `error.code = "RULE_LIMIT_EXCEEDED"`, message with the cap. No partial result is returned.
- Metric `drools.rule.execution.limit_exceeded` (tag `rule_id`).
- Add it to `12-error-code-catalog.md` and `openapi.yml`.

### A6.2 Timeout (P12)
- `RuleExecutor`: submit with `ExecutorService.submit(Callable)`, which returns a `FutureTask`, so `cancel(true)` **interrupts** the worker. This makes interruptible work (sleep, wait, IO) stoppable.
- Keep `halt()`.
- New gauge `drools.rule.execution.stuck`: workers still running after their request timed out (tracked set, removed in `finally`).
- ADR-019 addendum: a non-yielding RHS loop (`while(true){}`) still holds its thread until restart, a JVM limit. The real fix is process isolation (future prerequisite, A6.5).
- Tests: an interruptible `Thread.sleep` rule is stopped after the timeout and the stuck gauge returns to 0. The non-yielding case is documented with a 408, as today.

### A6.3 Sanitizer hardening (P14) — defense in depth, not a sandbox
- Normalize before checks: strip `//` and `/* */` comments and string literals, then collapse whitespace around `.`, so `System .exit` → `System.exit`.
- Import matching works after comment stripping, so a trailing comment no longer hides an import.
- Block dangerous fully-qualified names in rule bodies: `java.io.`, `java.nio.`, `java.net.`, `java.lang.reflect.`, `java.lang.invoke.`, `java.lang.Runtime`, `java.lang.ProcessBuilder`, `java.lang.ClassLoader`, `java.lang.Thread`, `java.lang.System`, `javax.script.`, `sun.`, `jdk.`.
- Narrow the `java.util.` allowlist: deny `java.util.concurrent.`, `java.util.ServiceLoader`, `java.util.logging.`, `java.util.prefs.`, `java.util.jar.`, `java.util.zip.`. Check that the 17 samples and the test DRLs still pass.
- Javadoc: "Policy check for trusted DRL. Not a security boundary (see SECURITY.md B1)." The class name is kept, so no churn.
- Tests: each bypass from P14 is rejected; all 17 samples are accepted.

### A6.4 Audit log (P16)
- A dedicated logger `AUDIT` (logback: same appender, `audit=true` field). One line per action:
  - bulk refresh, single refresh, reconcile, pub/sub-driven refresh or delete
  - fields: `action`, `source` (admin/pubsub/scheduler/startup), `remote_addr` (admin only; the shared-key auth has no user identity), `rule_ids`, `old_digest` → `new_digest`, `result`
- Never logs DRL content, payloads or keys.
- Test: an admin refresh emits the audit line with the expected fields and no secrets.

### A6.5 Honest documentation + threat model (P15)
- `SECURITY.md`: a new **"Threat model: DRL is code"** section.
  - **Trusted** = reviewed DRL published by operators. **Untrusted** = customer- or AI-supplied DRL.
  - Today only trusted DRL is supported.
  - Write access to the bucket or rule directory, refresh access, and Redis channel write access are all equivalent to code execution. They need least privilege and review; this is an operational control outside the service.
  - Prerequisites before any untrusted DRL: a separate low-privilege worker process or container (restricted IAM, filesystem and network egress; CPU and memory limits; a hard kill on timeout).
- `README.md:673`: replace "DRL Sandboxing … prevents arbitrary code execution" with "DRL policy checks (not a sandbox)".
- `16-drl-sandboxing.md`: retitle to "DRL policy checks (not a sandbox)"; fix `:40` ("stops all of that"); fix the `:189` `MyThreadPool` error; document the new checks and their limits.
- `37-glossary.md:132`, `17-rule-development.md:760`, `14-security-architecture.md` and `00` / `38` consistency.

### A6.6 Gate
Same as A4.8, plus live: a runaway rule → 408 and the stuck gauge = 1; a cap-loop rule (a self-retriggering `modify` loop) → 422; the audit lines appear in `docker compose logs`.

---

## A7. Phase 5 — Docs & bookkeeping
- `CLAUDE.md`: a change-log entry "Engine isolation & hardening (2026-11)".
  - **Breaking change:** `rule_id` runs only its own DRL file; stacked results such as VIP 72 → 80.
  - Migration: callers that relied on stacking must call each rule explicitly. Combined behaviour needs a future explicit composition feature.
  - New config keys.
  - Updated counts, coverage and test files.
- `project-documentation/`:
  - `04-architecture` (execution and refresh flow, locking)
  - `10-api-reference` (refresh responses, 422)
  - `12-error-code-catalog`
  - `28-testing-guide` (new tests)
  - `29-circuit-breakers` (snapshot failures count)
  - `30-runbooks-and-monitoring` (readiness, reconcile and stuck metrics, audit log)
  - `05` / `09` (refresh config truthful), `00-system-overview` (numbers, ADR count)
- ADR-025 is final. Addenda to ADR-003 (global container swap retired), ADR-016 (pub/sub + reconciliation) and ADR-019 (cap error + timeout interrupt).
- Tick the closed items in `pre-merge-fixes-plan-2026-11-17.md` §A11: `rule_id` design, partial S3 read, one bad DRL → 0 rules, invalid `RULE_SOURCE`, `/admin/health` always 200, bulk refresh always 200, pub/sub ignores failures, profile literals.

---

## A8. Phase 6 — Full verification (gate before push)
1. `mvn -B clean verify` (all tests, Spotless, SpotBugs, JaCoCo ≥ 88/74) and the Testcontainers ITs, on a **fresh copy** of the tree.
2. `scripts/run-docker-test-plan.sh`: all PASS with isolated values, X1.K included.
3. `scripts/e2e-load-test.sh` (300 s): 0 errors, 0 wrong values, the reload passes.
4. Stress: `e2e_load.py` with 50/100/200 workers × 60 s with `--reload-every 15`, plus `ab -k -c 400`. 0 errors and 0 wrong values; only load-shed 503s under saturation.
5. **Benchmark against the 2026-05-10 baseline** (`39-load-test-findings.md`), using `scripts/run-load-test.sh` with 1,000 rules:
   - cold load time
   - single-ID refresh (old: 510 ms median)
   - warm p50/p95/p99 (expected to improve, since only one file's LHS is evaluated)
   - heap after GC, metaspace
   - stability over 100 single-ID refreshes
   
   Record the numbers in B6 and in `39-load-test-findings.md` (new section).
6. Live probes:
   - simple + VIP fields = 90
   - kill S3 mid-refresh → no rules lost
   - empty bucket → not ready / 503
   - cap loop → 422
   - runaway → 408 + stuck gauge
   - path bypass still 400
   - graceful shutdown
7. Commit, push `engine-isolation` → `improvements-1-11-18-2026`, and open the PR to `develop` when the user asks.

---

## A9. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| A caller relies on stacking | Unknown (no known external clients) | Changed amounts | User accepted the breaking change; `CLAUDE.md` / ADR-025 migration note; release notes |
| Per-ID KieBases use much more heap or metaspace than one base | Medium | OOM at 1,000+ IDs | Measured in Phase 1 before committing; option B is the fallback; the bounded cache is a documented follow-up |
| Cold start with 1,000 separate compiles is slower than one batch | Medium | Longer startup | Parallel compile; readiness DOWN until loaded (no wrong answers while starting); measured in A8 |
| Retiring a handle frees resources an in-flight request still uses | Low | Execution error during refresh | Phase 1 proves a `KieBase` survives `removeKieModule`/`dispose`; the "refresh X while X executes" test |
| A `KieRepository` / classloader leak per refresh | Medium | Metaspace growth | 100-refresh stability test plus a live check over 100 refreshes |
| A stricter sanitizer rejects legitimate DRL | Low | Rules fail to load | Every sample and test DRL is checked; per-ID failure keeps the last good version; clear error |
| `/admin/health` 503 breaks compose startup ordering | Low | Stack won't start | Check compose healthcheck timing; LocalStack seeds before the app's first load |
| Reconcile fights a concurrent pub/sub refresh | Low | Duplicate compiles | Everything is serialized on `refreshLock`; digest skip makes repeats cheap |

## A10. Rollback
- Each phase is its own commit, so `git revert` per phase is possible. Phase 2 is the semantic change; reverting it restores stacking.
- No data or storage format changes: DRL files and S3 keys are untouched, and Redis keeps raw DRL only. Rolling back the deployment is enough.
- New config keys all have backward-compatible defaults.

## A11. Out of scope (logged with reasons)
- Bounded memory-weighted eviction cache, leases, single-flight cold loads: not needed while everything is resident. Revisit if the A8 numbers show pressure.
- Compiled-artifact (KJAR) storage for cold replicas: see `kjar-precompilation-plan.md`.
- A separate worker process for untrusted DRL: a documented prerequisite (A6.5); a separate design.
- An approval workflow and IAM separation for publishers: there's no write API in the service; operational control.
- Explicit rule composition (running several IDs in one request): a future feature, if wanted.
- Spring Boot 4 migration.
- Other `pre-merge-fixes` §A11 items not touched here: logback policy and duplicate output, `LogSanitizer` null NPE, the JMeter harness admin key, setup-script bugs, unused Lombok, Drools package WARN noise, 405/415 → 500.

---

# Part B — Checklist

### B0. Phase 0 — Plan
- [x] Brief checked against the code (3 code sweeps, 2026-11-18); assessment in A1.4
- [x] User decisions recorded (A1.5)
- [x] Worktree `.claude/worktrees/engine-isolation` on `engine-isolation` from `improvements-1-11-18-2026` @ `e1dd059`
- [x] This plan + checklist written (`engine-isolation-hardening-plan-2026-11-18.md`)

### B1. Phase 1 — Spike + ADR-025
- [x] Red regression test (simple + VIP fields → 90; **observed 72.0**), `@Disabled` with the observed value
- [x] Spike A1 (KieModule per ID + `removeKieModule` + GC) — all checks pass
- [x] Spike A2 (`KieHelper` / `KnowledgeBuilder`) — all checks pass; no growth over 2,000 refreshes
- [x] Spike B — **rejected**: kbase `packages` matches the declared DRL package, not the folder (folder-selected kbase is empty); needs `drools-xml-support`
- [x] Spike C — **rejected**: bundle LHS still NPEs under an AgendaFilter allowing only the forall rule
- [x] Shared functional checks per option (90 / 80 / salience order / forall without price / 17 independent / GC)
- [x] Measurements (1,000 IDs): KnowledgeBuilder cold 4.1 s (global 1.3 s), heap 52 MB (global 34.5), exec p50 14 µs (global 99), single-ID refresh 3.6 ms (global 281 ms), metaspace 44.4 MB (global 46.9); 2,000-refresh leak check flat. Parallel compile deferred to Phase 2 tuning
- [x] ADR-025 written with numbers + decision (A2b KnowledgeBuilder); index row; placeholders renumbered 026–028
- [x] Gate: spike green; `mvn -B clean verify` green — 613 tests, 0 failures, 2 skipped (measure + red test), coverage met
- [ ] Commit

### B2. Phase 2 — Isolation
- [x] `CompiledRuleset` + `CompiledRulesetRegistry` (atomic install/remove/get/snapshot, retirement)
- [x] `RuleCompiler.compileRule` (per-ID sanitize + build); `compileRules` → per-ID results; single-module path removed
- [x] `RuleExecutor` executes a `CompiledRuleset`; session always closed
- [x] `DroolsEngineService`: registry lookup, **no read lock**, `LoadReport`, per-ID replace/remove, failed ID keeps last good + `ERROR`
- [x] Callers on the `LoadReport` (AdminController, subscriber, startup)
- [x] `KieContainer` bean retired; all users updated
- [x] Metrics: compile time/error; `drools.rules.loaded` gauge bound
- [x] `RuleIsolationIntegrationTest`: all A4.6 cases; regression test enabled and green
- [x] Existing tests updated (Executor, Service, Compiler duplicate-name, Refresh IT, Admin, Subscriber)
- [x] Scripts on isolated values (`e2e_load.py`, `e2e-load-test.sh`, `run-docker-test-plan.sh`; X1.K → PASS)
- [x] Docs on isolated values (25 files: test plan, cookbook, 00/01/02/04/10/16/18/19/25/26/28/29/30/31/32/35/37/38, READMEs, openapi)
- [x] Gate: `mvn verify` 620 tests / 0 failures, coverage 92.1 % / 82.1 %; Testcontainers ITs 20/20; docker test plan **60 PASS / 0 FAIL** (X1.K now PASS, X1.11 new); e2e 60 s 306,723 req, 0 errors, 0 wrong
- [x] Commit

### B3. Phase 3 — Refresh / storage / startup
- [x] `getAllRules()` complete-or-throw contract (S3, Local, Redis decorator); CB counts failures
- [x] Incomplete snapshot → no change, no bulk event; empty-set guard + `drools.refresh.allow-empty`
- [x] SHA-256 digest on `Rule`/`RuleMetadata`; real `lastModified`; unchanged → skip
- [x] Storage read inside `refreshLock` on every path
- [x] Scheduled reconcile (`auto-enabled` / `interval-minutes`) + metrics; no pub/sub from reconcile
- [x] Subscriber acts on the report; missing ID → remove; listener reject policy + metric
- [x] `RulesHealthIndicator`; readiness/liveness probes; `/admin/health` 503; injected storage (no per-call creation)
- [x] `drools.startup.fail-on-load-error`; readiness waits for the first load
- [x] Unknown `RULE_SOURCE` fails startup; profiles use `${RULE_SOURCE:…}`
- [x] Admin refresh codes (500 total / 200 partial with per-ID errors); `not_found` body
- [x] Tests (A5.9)
- [x] Gate: `mvn verify` 695 tests / 0 failures, coverage 93.5 % / 85.4 %; Testcontainers 22/22; docker test plan **69 PASS / 0 FAIL** incl. Step 13 (S3 down → 500 no loss; empty bucket → 409; start with 0 rules → not ready/503; reconcile restores in 60 s; direct S3 edit picked up in 55 s; S3 delete → removed); e2e 60 s 311,404 req, 0 errors
- [x] Commit

### B4. Phase 4 — Limits / security
- [ ] `drools.execution.max-rule-firings`; `fireAllRules(cap+1)`; `RuleLimitExceededException` → 422 `RULE_LIMIT_EXCEEDED`; metric
- [ ] `FutureTask` submit (`cancel(true)` interrupts); stuck-worker gauge
- [ ] Sanitizer: normalization, import-comment fix, FQN blocks, narrowed `java.util.`; Javadoc "not a boundary"
- [ ] `AUDIT` log for every refresh/reconcile/delete (no payloads/keys)
- [ ] `SECURITY.md` threat model (trusted vs untrusted; prerequisites)
- [ ] Docs honesty: README:673, doc 16 retitle + fixes, glossary, rule-dev, 14
- [ ] Tests (cap 422, interruptible timeout + gauge, each bypass, audit fields)
- [ ] Gate: A4.8 + live runaway 408/gauge, cap 422, audit lines
- [ ] Commit

### B5. Phase 5 — Docs & bookkeeping
- [ ] `CLAUDE.md` entry: breaking change + migration, new config, counts/coverage
- [ ] Docs 04 / 05 / 09 / 10 / 12 / 28 / 29 / 30 / 00
- [ ] ADR-025 final; addenda ADR-003 / ADR-016 / ADR-019
- [ ] `pre-merge-fixes-plan-2026-11-17.md` §A11 items ticked
- [ ] Commit

### B6. Phase 6 — Verification (record numbers)
- [ ] `mvn -B clean verify` on a fresh copy: tests ____ / 0 failures; coverage ____ % / ____ %
- [ ] Testcontainers ITs: ____ / ____
- [ ] `run-docker-test-plan.sh`: PASS ____ / FAIL 0 (X1.K PASS)
- [ ] `e2e-load-test.sh` 300 s: requests ____, RPS ____, errors 0, wrong 0, p99 ____ ms
- [ ] Stress 50/100/200 + reloads: errors 0, wrong 0; p99 ____ / ____ / ____ ms; `ab -c 400` only 503 load-shed
- [ ] Benchmark (1,000 rules) vs 2026-05-10: cold load ____ s (was ~46–50 s); single-ID refresh ____ ms (was 510 ms); warm p99 ____ ms; heap after GC ____ MB; metaspace stable over 100 refreshes ____
- [ ] Live probes: isolation 90 · S3-kill no loss · empty bucket 503 · cap 422 · runaway 408 + gauge · path bypass 400 · graceful stop
- [ ] Numbers added to `39-load-test-findings.md`
- [ ] Pushed `engine-isolation` → `improvements-1-11-18-2026`
- [ ] (When asked) PR to `develop` with evidence
- [ ] Worktree removed after merge

---

_Plan created: 2026-11-18 · Last updated: 2026-11-18 (Phase 3 complete — refresh/storage/startup; Phase 4 in progress)_
