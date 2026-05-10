# Load + Stress + Soak Test — Phase Checklist

**Companion to**: [`load-test-plan.md`](load-test-plan.md)
**Started**: 2026-05-10
**Estimated effort**: ~7-8 hours total

Tick each box (`[ ]` → `[x]`) as completed. Each phase has exit criteria the next depends on.

---

## Phase 0 — Pre-flight + research + scaffolding

### Research
- [x] Researched `KieRepository` lifecycle under `KieContainer.updateToVersion(ReleaseId)` — verified via the actual Drools 10.2.0 source at the `10.2.0` git tag. Result: **modules linger**. `KieContainerImpl.updateToVersion` does not call `removeKieModule`; modules accumulate in `KieRepositoryImpl.KieModuleRepo` (LRU capped at 100×10 = 1,000 entries for our single-GA app). Each retained module holds a `ProjectClassLoader` + compiled rule classes → memory leak under sustained hot reloads.
- [x] **Patch applied**:
  - New `KieRepository` Spring `@Bean` in [`DroolsConfig`](../../src/main/java/com/company/drools/config/DroolsConfig.java)
  - `DroolsEngineService.loadRules` captures `oldReleaseId` before `updateToVersion`, calls `kieRepository.removeKieModule(oldReleaseId)` outside the write lock after a successful swap. Eviction skipped on failure (rollback target preserved). Eviction failure is logged at WARN, non-fatal.
  - 2 new unit tests: `testLoadRules_EvictsPriorModuleFromRepository`, `testLoadRules_NoEvictionOnUpdateFailure`
  - All 53 tests in touched packages pass

### Tooling install
- [x] `brew install jmeter` — verified 5.6.3 ✓
- [x] `brew install jq` — already present
- [ ] `brew install gnuplot` (optional, for plotting memory CSV)

### Scripts scaffolding
- [ ] `scripts/run-load-test.sh` — orchestrator skeleton with phase dispatch, `--phase`, `--from`, `--quick`, `--no-cleanup` flags, trap-based cleanup
- [ ] `scripts/README.md` — docs (prerequisites, run modes, output dir layout, env-var knobs)
- [ ] `scripts/lib/preflight.sh` — Docker-running check, port availability check, JMeter-installed check
- [ ] `scripts/lib/stack.sh` — `up` / `down` / `wait_for_health` helpers
- [ ] `scripts/lib/corpus.sh` — generate N synthetic `.drl` files templated from `sample-rules/` patterns + upload to LocalStack S3 + emit `rule-ids.csv`
- [ ] `scripts/lib/memory-poll.sh` — background poller hitting `/admin/memory/info`, appending CSV every 5s
- [ ] `scripts/lib/refresh-loop.sh` — background full-refresh loop with configurable interval
- [ ] `scripts/lib/single-refresh-loop.sh` — background random-rule single-refresh loop with configurable interval
- [ ] `scripts/lib/heap-dump.sh` — `docker exec` jmap helpers (capture into `/tmp/`, copy to host)
- [ ] `scripts/jmeter/baseline.jmx` — Phase 3 plan (50 RPS, configurable duration)
- [ ] `scripts/jmeter/concurrency-ramp.jmx` — Phase 4 plan (50 → 100 → 250 → 500 RPS)
- [ ] `scripts/jmeter/execute-only.jmx` — reusable for Phases 5/6/7 (configurable RPS via property)
- [ ] `.gitignore` — append `scripts/load-test-results/`

### Phase 0 exit gate
- [ ] All scripts above exist and are executable (`chmod +x`)
- [ ] `./scripts/run-load-test.sh --phase 0` (a no-op self-check if Phase 0 is purely scaffolding) exits 0
- [ ] `scripts/README.md` is enough for a future engineer to run the full test cold

---

## Phase 1 — Tier 1: sample-rules cookbook expansion

### Add new rules
- [ ] `sample-rules/pricing/bundle/accumulate.drl` — `accumulate` over a list (sum / count / avg); bundle discount when total > threshold
- [ ] `sample-rules/inventory/warning/exists.drl` — `exists` pattern; trigger when any list element matches
- [ ] `sample-rules/validation/cart/notempty.drl` — `not` pattern; reject when cart items list is missing/empty
- [ ] `sample-rules/pricing/loyalty/salience.drl` — `salience` pattern; loyalty members get ≥15% as override
- [ ] `sample-rules/validation/email/compound.drl` — compound `and`/`or` LHS using `matches` regex operator (substituted from the originally-planned `eval()` after Phase 0 found DrlSanitizer.java:102 blocks `eval(`)
- [ ] `sample-rules/seasonal/expiry/temporal.drl` — date comparison; promo code valid in date range
- [ ] `sample-rules/validation/cart/forall.drl` — `forall` universal quantification; every cart item must satisfy a condition

Each rule:
- [ ] Uses safe wrapper-coercion (`((Number)x).doubleValue()` / `intValue()`)
- [ ] Passes `DrlSanitizer` (no banned imports/classes/methods)
- [ ] `Map<String,Object>` data shape
- [ ] Stateless one-shot session-compatible (no `agenda-group`, no globals, no `@PropertyReactive`)
- [ ] Deterministic outputs (same input → same output every time)

### Cookbook + tests
- [ ] [`19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md) — added 7 new entries with input/output examples, organized in the existing structure
- [ ] [`RuleExecutionIntegrationTest$SampleRulesExecution`](../../src/test/java/com/company/drools/integration/RuleExecutionIntegrationTest.java) — 7 new test cases asserting cookbook outputs (one per new rule)

### Verification
- [ ] `mvn test` (full suite) — green (existing 589 + 2 KieRepo + 7 cookbook = 598 expected)
- [ ] All 17 rules compile cleanly (no DrlSanitizer rejection, no Drools warnings beyond the existing folder-vs-package mismatch)
- [ ] [`init-localstack.sh`](../../init-localstack.sh) picks up all 17 rules unchanged (verify by counting `sample-rules/**/*.drl`)

### Phase 1 exit gate
- [ ] 17 rules in `sample-rules/`
- [ ] Cookbook updated with 7 new entries
- [ ] All integration tests green
- [ ] No production Java code changes (this is purely additive)

---

## Phase 2 — Corpus generation + stack boot

- [ ] `scripts/lib/corpus.sh` invoked with `RULE_COUNT=1000`
  - [ ] 1,000 synthetic `.drl` files generated, **equally distributed across 12 templates** (5 covering the original 10 cookbook entries — same RETE-shape collapses — plus 7 covering the Phase 1 patterns); ~83 per template × 12 = 996, padded to 1,000 round-robin
  - [ ] Uploaded to LocalStack S3 in parallel
  - [ ] `rule-ids.csv` emitted for JMeter consumption
- [ ] Verify with `awslocal s3 ls s3://local-rules/ --recursive | wc -l` → 1,000 (orchestrator clears the bucket before populating to ensure exact count)
- [ ] `docker compose up -d --build` — all 3 services healthy
- [ ] App startup logs show all rules loaded (no per-rule failures)
- [ ] Measure first full-refresh compile time → record as `phase-2/first-refresh-ms.txt`
- [ ] `/admin/health` shows N active rules (whatever N was uploaded)

### Phase 2 exit gate
- [ ] Stack up, all rules loaded
- [ ] First-refresh baseline captured

---

## Phase 3 — Baseline (steady state, 30 min)

- [ ] JMeter `baseline.jmx` running at 50 RPS, 30 min
- [ ] No refreshes during this phase (foreground refresh loops disabled)
- [ ] Memory poller running (`memory-poll.sh` writing every 5s)
- [ ] Manual GC at start + every 5 min during phase (record retained heap)

### Recorded baseline
- [ ] P50: `____` ms
- [ ] P95: `____` ms
- [ ] P99: `____` ms
- [ ] Error rate: `____` % (target: 0)
- [ ] Retained heap min/max: `____` / `____` MB
- [ ] CPU saturation: `____` %

### Phase 3 exit gate
- [ ] P99 < 200 ms (acceptance criterion)
- [ ] Error rate = 0
- [ ] Retained heap stable across 30 min (no monotonic growth)

---

## Phase 4 — Concurrency ramp

- [ ] JMeter `concurrency-ramp.jmx`: 50 → 100 → 250 → 500 RPS in 5-min steps, 50 concurrent threads
- [ ] Per-step P99 / error rate captured

| RPS | P99 (ms) | Error rate | Pass/Fail |
|---|---|---|---|
| 50 | `____` | `____`% | [ ] |
| 100 | `____` | `____`% | [ ] |
| 250 | `____` | `____`% | [ ] |
| 500 | `____` | `____`% | [ ] |

- [ ] Stop condition triggered (P99 > 1s OR error rate > 1%) at: `____` RPS
- [ ] Safe-RPS ceiling = `____` RPS, written to `phase-4/safe_rps.txt`

### Phase 4 exit gate
- [ ] Safe-RPS ceiling captured
- [ ] Target met: ceiling ≥ 250 RPS

---

## Phase 5 — Hot full-refresh under load (30 min)

- [ ] Background load: half safe-RPS sustained
- [ ] Foreground: `POST /admin/refresh-rules` every 10s = 180 refreshes total
- [ ] Per-refresh duration captured (look for upward drift across the 30 min)

### Recorded
- [ ] Errors: `____` (target: 0)
- [ ] P99 during refresh window: `____` ms (target: ≤ 2x baseline = `____` ms)
- [ ] Heap drift after final GC: `____` MB (target: < 50 MB)
- [ ] Per-refresh duration: min `____` / median `____` / max `____` ms
- [ ] Drift in per-refresh duration across the 180 refreshes? Yes/No: `____`

### Phase 5 exit gate
- [ ] 0 errors
- [ ] P99 within 2x baseline
- [ ] Heap drift < 50 MB
- [ ] Per-refresh duration not drifting upward (would indicate compile-time leak)

---

## Phase 6 — Hot single-rule refresh under load (30 min, riskiest)

- [ ] Background load: half safe-RPS sustained
- [ ] Foreground: random rule from corpus, `POST /admin/refresh-rules/{id}` every 5s = 360 refreshes total
- [ ] Read-side latency spike measured during each refresh window (the lock-held-through-compile interval)

### Recorded
- [ ] Errors: `____` (target: 0)
- [ ] P95 during refresh windows: `____` ms (target: stable, ≤ 1.5x baseline)
- [ ] P99 during refresh windows: `____` ms (note: may spike up to ~compile time; if >3x baseline, flag for ADR revisit)
- [ ] Per-refresh duration: median `____` ms (this IS the P99 spike duration)

### Phase 6 exit gate
- [ ] 0 errors
- [ ] P95 stable
- [ ] If P99 spike >3x baseline: documented as a finding for follow-up (architectural change to consider)

---

## Phase 7 — 1-hour soak (mixed scenario, leak hunt)

- [ ] Steady execute load (~half safe-RPS), 1 hour
- [ ] Background full refresh every 30s = 120 refreshes
- [ ] Background single-rule refresh on random rule every 10s = 360 refreshes
- [ ] Heap dump captured at minute 5: `phase-7/heap-dump-start.hprof`
- [ ] Manual GC every 10 min, retained heap recorded
- [ ] Heap dump captured at minute 60: `phase-7/heap-dump-end.hprof`
- [ ] Memory CSV → `phase-7/memory.csv`, GC log → `phase-7/gc.log`

### Recorded
- [ ] Post-GC heap at minute 5: `____` MB
- [ ] Post-GC heap at minute 60: `____` MB
- [ ] Heap delta: `____` MB (target: < 100 MB)
- [ ] Errors over the hour: `____` (target: 0)
- [ ] GC pause time at minute 5 vs minute 60: `____` ms / `____` ms (target: no regression)
- [ ] Full GC frequency: `____` per hour (record for trend analysis)

### Phase 7 exit gate
- [ ] Heap delta < 100 MB
- [ ] 0 errors
- [ ] No GC regression

---

## Phase 8 — Heap analysis + verdict

### Heap diff
- [ ] Loaded both `.hprof` files in `jhat` or Eclipse MAT
- [ ] Compared dominator tree start vs end
- [ ] Specific classes checked for retention growth:
  - [ ] `org.kie.api.builder.KieModule` — count delta: `____` (expected: ≤ 1, the live module)
  - [ ] `org.kie.api.builder.ReleaseId` — count delta: `____` (expected: minimal)
  - [ ] `com.company.drools.core.model.Rule` — count delta: `____` (expected: ≤ rule count)
  - [ ] `com.company.drools.core.model.RuleMetadata` — count delta: `____`
  - [ ] `org.drools.core.impl.KnowledgeBaseImpl` — count delta: `____`
  - [ ] `org.kie.api.runtime.KieSession` — should be 0 retained
- [ ] Anything unexpectedly large in the end-dump retained set? Notes: `____`

### Verdict written
- [ ] `summary.md` populated by the orchestrator with PASS/FAIL per criterion
- [ ] Final verdict: PASS / CONDITIONAL / FAIL — `____`

### Documentation
- [ ] [`project-documentation/36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md) ADR-003 — appended "Load tested 2026-05-10" note with the headline numbers
- [ ] If Phase 0 / Phase 8 produced a code change (e.g., `kieRepository.removeKieModule`), wrote a follow-up ADR or appended to ADR-003

### Phase 8 exit gate
- [ ] Verdict written to `summary.md`
- [ ] ADR-003 updated with sign-off note
- [ ] Re-runnability: a future engineer can run `./scripts/run-load-test.sh` and get a comparable summary

---

## Final summary (fill in at end)

- **Sample-rules corpus**: 16 / 16 rules (10 existing + 6 new)
- **Load corpus size**: 1,000
- **Tests added (Tier 1)**: 6 / 6
- **mvn test result**: `____` / `____` passing (expecting 595)
- **Safe-RPS ceiling**: `____` RPS
- **P99 baseline**: `____` ms
- **Heap delta over 1-hour soak**: `____` MB
- **0-error phases**: `____` / 5 (Phases 3, 4, 5, 6, 7)
- **`KieRepository` cleanup gap**: NONE / PATCHED
- **Overall verdict**: PASS / CONDITIONAL / FAIL

---

## Working notes log

_(Append timestamped entries here as work proceeds.)_

- _(date/time)_ — _(observation)_
