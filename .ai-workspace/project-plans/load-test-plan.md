# Production-Readiness Load + Stress + Soak Test Plan

**Companion checklist**: [`load-test-checklist.md`](load-test-checklist.md)
**Companion plans**: [`fix-findings-plan.md`](fix-findings-plan.md), [`e2e-validation-plan.md`](e2e-validation-plan.md)
**Started**: 2026-05-10
**Estimated effort**: ~7–8 focused hours total (much of Phases 5–7 unattended; Phase 7 is a 1-hour soak)

---

## Context

The 2026-05-10 fix bundle ([`fix-findings-plan.md`](fix-findings-plan.md)) migrated rule loading to Drools 10's `KieContainer.updateToVersion(ReleaseId)` pattern. It passes unit + integration + e2e validation at small scale (10 rules, 10 refreshes; see [`e2e-validation-checklist.md`](e2e-validation-checklist.md) Phase 8). Before declaring the architecture **production-ready**, we want to verify it under conditions closer to expected production load:

- **Thousands of rules** in S3 (target: 1,000 first pass, can scale to 5k/10k later)
- **Continuous hot rule reloads** while traffic flows — both full refresh and single-rule refresh
- **Sustained concurrent execution** of many different rules in parallel
- **No memory leak** across a multi-thousand-refresh soak
- **Stable P99 latency** during hot reloads

Beyond a one-time pass, the user wants this to become a **re-runnable regression check** — a single shell orchestrator (`scripts/run-load-test.sh`) that runs the entire test end-to-end so it can be re-invoked any time the architecture changes (Drools version, Spring Boot version, rule shape, caching layer). That orchestrator is a first-class deliverable.

The user also confirmed that **before** running the load test, the curated `sample-rules/` cookbook should be expanded so:
- The documented corpus better represents real-world Drools patterns (`accumulate`, `exists`, `not`, `salience`, `eval`, temporal — currently missing from the cookbook).
- The load-test corpus generator can mirror those richer patterns at scale, producing a 1,000-rule KieBase whose RETE network shape is realistic rather than 1,000 identical synthetic rules.

This plan combines both deliverables.

### User-confirmed parameters (2026-05-10)

| Parameter | Value | Notes |
|---|---|---|
| Rule corpus size for load test | **1,000** | First pass; can scale up via `RULE_COUNT` env var |
| Soak duration | **1 hour** | Catches most JVM-level leaks; overnight re-run is a follow-up if needed |
| Load generator | **JMeter** | Aligned with project's backlog item; headless `.jmx` runs |
| Load runner location | **Same machine** as docker-compose stack | RPS targets capped at ~500 to leave JVM headroom |
| Re-runnability | **One-command orchestrator** | `scripts/run-load-test.sh` sequences all phases end-to-end |
| Sample-rules expansion | **Yes** — both expand cookbook AND have load corpus mirror those patterns | 6 new curated rules + cookbook entries + integration tests |

---

## Deliverable structure (3 tiers)

| Tier | What | Lives In |
|---|---|---|
| **Tier 1** — Curated cookbook expansion | 6 new rules covering missing Drools patterns + cookbook entries + integration tests | `sample-rules/`, `19-sample-rules-cookbook.md`, `RuleExecutionIntegrationTest` |
| **Tier 2** — Load-test tooling | Orchestrator + per-phase scripts + JMeter plans + corpus generator | `scripts/run-load-test.sh`, `scripts/lib/`, `scripts/jmeter/`, `scripts/README.md` |
| **Tier 3** — Test execution + verdict | Phase results, heap analysis, production-readiness verdict | `scripts/load-test-results/<timestamp>/` (gitignored) + this checklist's final summary |

---

## Tier 1 — Sample-rules cookbook expansion

Seven new curated rules, each adding a Drools pattern not currently represented in the cookbook. Each one ships with: (a) the `.drl` file under `sample-rules/<category>/<subcategory>/<name>.drl`, (b) a cookbook entry in [`19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md) with input + expected output, (c) a new integration test in [`RuleExecutionIntegrationTest$SampleRulesExecution`](../../src/test/java/com/company/drools/integration/RuleExecutionIntegrationTest.java).

| # | Rule ID | Pattern | What it demonstrates |
|---|---|---|---|
| 1 | `pricing.bundle.accumulate` | `accumulate` over a list | sum / avg / count aggregation; threshold-based bundle discount |
| 2 | `inventory.warning.exists` | `exists` | trigger when any element of a list satisfies a condition |
| 3 | `validation.cart.notempty` | `not` | fire when a pattern is **absent** (empty cart guard) |
| 4 | `pricing.loyalty.salience` | `salience` | priority override — loyalty members always get ≥15% even if other rules also fire |
| 5 | `validation.email.compound` | compound `and`/`or` LHS | explicit `and` / `or` operators combining sub-conditions; uses `matches` regex operator. (Substituted from the originally-planned `eval()` pattern after Phase 0 caught that DrlSanitizer.java:102 blocks `eval(` for security.) |
| 6 | `seasonal.expiry.temporal` | date comparison | promo code valid only within a date range |
| 7 | `validation.cart.forall` | `forall` | universal quantification — every cart item must satisfy a condition (e.g. all in stock) |

Constraints all 7 must satisfy:
- Use the safe wrapper-coercion pattern (`((Number)x).doubleValue()` / `intValue()`) — the Drools 10 gotcha.
- Pass the [`DrlSanitizer`](../../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) (no dangerous imports / classes / methods).
- `Map<String,Object>` data shape only — same as existing 10.
- Stateless one-shot `KieSession` compatible (no `agenda-group`, no globals, no `@PropertyReactive`).

After expansion: `sample-rules/` has **17 curated rules** (10 existing + 7 new); [`init-localstack.sh`](../../init-localstack.sh) automatically picks them up because it walks the directory recursively.

---

## Tier 2 — Load-test tooling

### The orchestrator: `scripts/run-load-test.sh`

Single command, end-to-end runner. Re-runnable on any branch / Drools version / config change.

**Goals**
- One command runs every phase in sequence; non-zero exit if any acceptance criterion fails.
- Idempotent setup (Docker / JMeter / port checks, auto-rebuild, auto-init LocalStack).
- Timestamped output dir (`scripts/load-test-results/<timestamp>/`) — re-runs don't clobber.
- Per-phase isolation — each phase is its own helper script; orchestrator can run a single phase via `--phase N`.
- Cleanup on exit — trap handlers always run `docker compose down -v`.

**Structure**
```
scripts/
├── run-load-test.sh                # orchestrator (the regression contract)
├── README.md                       # how to run, what output means, env-var knobs
├── lib/
│   ├── preflight.sh                # docker / jmeter / port checks
│   ├── stack.sh                    # up / down / wait-for-health
│   ├── corpus.sh                   # generate + upload N synthetic rules (templated from sample-rules)
│   ├── memory-poll.sh              # background memory-CSV poller
│   ├── refresh-loop.sh             # background full-refresh loop
│   ├── single-refresh-loop.sh      # background single-rule refresh loop
│   └── heap-dump.sh                # docker exec jmap helpers
├── jmeter/
│   ├── baseline.jmx                # Phase 3 plan
│   ├── concurrency-ramp.jmx        # Phase 4 plan
│   ├── execute-only.jmx            # reusable for Phases 5, 6, 7
│   └── rule-ids.csv                # generated by corpus.sh
└── load-test-results/              # gitignored
    └── <timestamp>/
        ├── phase-{0..8}/...
        ├── memory.csv
        ├── heap-dump-start.hprof
        ├── heap-dump-end.hprof
        ├── jtl/...
        ├── reports/...
        └── summary.md              # auto-generated PASS/FAIL verdict
```

**Run modes**
```bash
./scripts/run-load-test.sh                    # full run, all phases
./scripts/run-load-test.sh --phase 3          # only Phase 3 (assumes stack is up + corpus loaded)
./scripts/run-load-test.sh --from 4           # resume from Phase 4 (skip 0-3)
./scripts/run-load-test.sh --quick            # 100 rules, 5-min soak (smoke test)
./scripts/run-load-test.sh --no-cleanup       # leave stack up after exit
RULE_COUNT=5000 SOAK_MIN=240 ./scripts/run-load-test.sh   # scale up
```

### The corpus generator: `scripts/lib/corpus.sh`

Templates from the curated `sample-rules/` patterns. Produces N unique synthetic rules distributed equally across 12 distinct templates. Per-rule properties:
- Each rule's LHS shape mirrors one of the cookbook patterns
- Marker keys are unique per rule for predictable test inputs
- Output keys are unique per rule for verifiable test results
- All upload to LocalStack S3 in parallel

**Template count = 12** (5 covering the existing 10 cookbook entries + 7 covering the patterns added in Phase 1). The plan originally called for 17 templates matching every cookbook entry one-for-one, but the existing 10 cookbook entries collapse into ~5 distinct LHS shapes from the RETE engine's perspective (e.g. `simple-discount`, `holiday-discount`, and `seasonal.holiday.discount` are all "boolean+amount" shapes — splitting them into 3 templates wouldn't add RETE variety). 12 templates therefore hits the spirit of "equal split across all distinct cookbook patterns" without redundant cosmetic splits.

Equal-split distribution at 1,000 rules: ~83 rules per template × 12 templates = 996, padded to 1,000 by adding 4 more rules round-robin across the templates.

---

## Anticipated risks (what the load test should surface)

1. **`KieRepository` module accumulation** — the #1 risk. With `updateToVersion`, every refresh registers a new versioned `KieModule` in the singleton `KieRepository`. **Unclear whether Drools 10 drops the old version**, or if old compiled bytecode lingers indefinitely. Under thousands of refreshes this could be the actual leak. Phase 0 will research this BEFORE running soaks. If true, the fix is `kieRepository.removeKieModule(oldReleaseId)` after each successful `updateToVersion`.
2. **Compile time scaling** — every refresh recompiles the **full rule set**. With 1,000 rules, recompile may be seconds rather than ms. Need to measure.
3. **Write-lock duration during single-rule refresh** — `loadOrReplaceRule` holds the write lock through compilation. With 1,000 rules and slow compile, every single-rule refresh blocks executions for the compile duration. Could be SLO-killer.
4. **`updateToVersion` race conditions under high concurrency** — Drools claims in-flight sessions keep old definitions. Verify with high-rate session creation during refresh.
5. **Heap stability across thousands of refreshes** — even if `KieRepository` cleans up, `loadedRules` / `ruleMetadata` maps churn keys. Verify no map-level leak.
6. **Cache invalidation under refresh storm** — `LocalLRUCache` invalidated on each refresh. Verify no read/write deadlock under churn.

---

## Phases

### Phase 0 — Pre-flight + research + scaffolding (~90 min)

**Outcome (2026-05-10)**: Risk #1 confirmed. Drools 10.2.0 source-verified at the `10.2.0` git tag — `KieContainerImpl.updateToVersion()` does NOT call `removeKieModule`. Old `KieModule`s accumulate in `KieRepositoryImpl.KieModuleRepo` (capped only by LRU at 100×10 = 1,000 entries for a single-GA app); each retained module holds a `ProjectClassLoader` + every compiled rule class → real memory leak under sustained hot reloads. Corroborated by the [KIE blog memory troubleshooting guide](https://blog.kie.org/2022/09/drools-trouble-shooting-memory-issues.html) and DROOLS-765.

**Patch applied** (in this plan's run, not deferred):
- New `KieRepository` Spring bean in [`DroolsConfig`](../../src/main/java/com/company/drools/config/DroolsConfig.java).
- [`DroolsEngineService.loadRules`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) captures `oldReleaseId = kieContainer.getReleaseId()` before `updateToVersion`. After a successful swap, OUTSIDE the write lock (to avoid lock inversion with the repo's internal lock), calls `kieRepository.removeKieModule(oldReleaseId)`. Eviction failure is non-fatal (logged at WARN). On update failure, eviction is skipped — the old module remains as the rollback target.
- 2 new unit tests: `testLoadRules_EvictsPriorModuleFromRepository` and `testLoadRules_NoEvictionOnUpdateFailure`. 53/53 tests in the touched packages pass.

**Remaining Phase 0 work**:
- Install JMeter (`brew install jmeter`); verify headless mode works. ✓ Done (5.6.3).
- Write all `scripts/lib/*.sh` helpers, `scripts/jmeter/*.jmx` plans, `scripts/run-load-test.sh` orchestrator, `scripts/README.md`.
- Add `scripts/load-test-results/` to `.gitignore`.

### Phase 1 — Tier 1: sample-rules expansion (cookbook) (~120 min)

- Add the 6 new `.drl` files under `sample-rules/`.
- Update [`19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md) with 6 new entries (input + expected output).
- Add 6 new integration test cases to `RuleExecutionIntegrationTest$SampleRulesExecution`.
- Verify all 16 existing+new rules compile clean (`mvn test`); all 16 pass the integration test.
- [`init-localstack.sh`](../../init-localstack.sh) walks `sample-rules/` recursively, so no script change needed there — verify it picks up the 6 new ones (16 total in S3).

### Phase 2 — Tier 2 corpus generation + boot (~20 min)

- Orchestrator runs `corpus.sh` to generate **1,000 synthetic DRL files** templated from `sample-rules/` patterns.
- Upload to LocalStack S3.
- Verify `awslocal s3 ls s3://local-rules/ --recursive | wc -l` = 1,000.
- Boot stack; confirm app loads all 1,000.
- Measure first-refresh compile time (per-1k-rule baseline).

### Phase 3 — Baseline (steady state, ~35 min)

50 RPS sustained execute traffic, 30 min, no refreshes. Random rule ID from corpus.

**Measure**: P50/P95/P99, retained heap (post-GC every 5 min), error rate (target: 0).

### Phase 4 — Concurrency ramp (~30 min)

50 → 100 → 250 → 500 RPS in 5-min steps. 50 concurrent JMeter threads.

**Stop condition**: P99 > 1s OR error rate > 1%. Record safe-RPS ceiling to `safe_rps.txt` for downstream phases.

### Phase 5 — Hot full-refresh under load (30 min run, ~35 min total)

Background load = half safe RPS. Foreground: `POST /admin/refresh-rules` every 10s = 180 refreshes.

**Acceptance**: 0 errors, P99 within 2x baseline, heap drift after GC < 50 MB.

### Phase 6 — Hot single-rule refresh under load (30 min run, ~35 min total — riskiest)

Background load = same. Foreground: random-rule single-refresh every 5s = 360 refreshes.

**Acceptance**: 0 errors, P95 stable. P99 may spike during refresh; if >3x baseline the lock-held-through-compile design needs revisiting.

### Phase 7 — 1-hour soak (mixed scenario, the leak hunt) (~65 min)

Steady execute load (~half safe RPS), 1 hour. Background full refresh every 30s. Background single-rule refresh every 10s. Heap dumps at minute 5 and minute 60.

**Acceptance**: post-GC heap at end within 100 MB of post-GC heap at start; no GC-pause regression; 0 errors.

### Phase 8 — Heap analysis + verdict (~40 min)

- Compare two heap dumps (start vs end) via `jhat` or Eclipse MAT. Look for accumulated `KieModule` / `Rule` / `KieBase` / unexpected `KieSession`.
- GC log analysis: throughput minute-5 vs minute-60.
- Orchestrator emits `summary.md` with PASS/FAIL per criterion + final verdict.
- Append "Load tested 2026-05-10 — see [`load-test-plan.md`](.ai-workspace/project-plans/load-test-plan.md)" note to ADR-003.
- If Phase 0 / Phase 8 surfaces an architectural change, write a follow-up ADR.

---

## Critical files

### Create — Tier 1 (sample-rules expansion)
- [`sample-rules/pricing/bundle/accumulate.drl`](../../sample-rules/pricing/bundle/accumulate.drl)
- [`sample-rules/inventory/warning/exists.drl`](../../sample-rules/inventory/warning/exists.drl)
- [`sample-rules/validation/cart/notempty.drl`](../../sample-rules/validation/cart/notempty.drl)
- [`sample-rules/pricing/loyalty/salience.drl`](../../sample-rules/pricing/loyalty/salience.drl)
- [`sample-rules/validation/email/compound.drl`](../../sample-rules/validation/email/compound.drl)
- [`sample-rules/seasonal/expiry/temporal.drl`](../../sample-rules/seasonal/expiry/temporal.drl)
- [`sample-rules/validation/cart/forall.drl`](../../sample-rules/validation/cart/forall.drl)

### Modify — Tier 1
- [`project-documentation/19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md) — 7 new entries
- [`src/test/java/com/company/drools/integration/RuleExecutionIntegrationTest.java`](../../src/test/java/com/company/drools/integration/RuleExecutionIntegrationTest.java) — 7 new test cases in `$SampleRulesExecution`

### Create — Tier 2 (load-test tooling)
- `scripts/run-load-test.sh`
- `scripts/README.md`
- `scripts/lib/{preflight,stack,corpus,memory-poll,refresh-loop,single-refresh-loop,heap-dump}.sh`
- `scripts/jmeter/{baseline,concurrency-ramp,execute-only}.jmx`
- `.gitignore` entry: `scripts/load-test-results/`

### Modify (only if Phase 0 / Phase 8 surfaces issues)
- [`src/main/java/com/company/drools/core/engine/DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) — possible `kieRepository.removeKieModule(oldReleaseId)` after `updateToVersion`
- [`project-documentation/36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md) — ADR-003 load-test sign-off note (or new follow-up ADR)

### Read-only
- The 4 production files modified by [`fix-findings-plan.md`](fix-findings-plan.md)
- [`src/main/java/com/company/drools/core/engine/DrlSanitizer.java`](../../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) — sanitizer constraints for new rules
- [`docker-compose.yml`](../../docker-compose.yml), [`init-localstack.sh`](../../init-localstack.sh), [`test-localstack.sh`](../../test-localstack.sh)

---

## Tooling

```bash
brew install jmeter           # load gen
brew install jq               # already required
brew install gnuplot          # optional, for plotting memory CSV

# JVM diagnostics (against the running container)
docker top drools-microservice-app-1 -eo pid,cmd | grep java
docker exec drools-microservice-app-1 jmap -dump:live,format=b,file=/tmp/heap.hprof <PID>
docker exec drools-microservice-app-1 jstat -gc <PID> 5s
docker exec drools-microservice-app-1 jcmd <PID> GC.run
```

---

## Success criteria — production-ready means ALL of:

- [ ] Tier 1: 7 new rules added; cookbook updated; 7 new integration tests pass; all 17 rules compile and execute correctly
- [ ] [`init-localstack.sh`](../../init-localstack.sh) picks up all 17 rules without modification
- [ ] Tier 2: `scripts/run-load-test.sh` checked in, documented, re-runnable
- [ ] Phase 0: KieRepository question resolved (and patched if needed)
- [ ] Phase 3: 50 RPS sustained, P99 < 200 ms, retained heap stable
- [ ] Phase 4: safe-RPS ceiling identified (target ≥ 250 RPS)
- [ ] Phase 5: 0 errors across 180 full-refreshes under load, P99 within 2x baseline
- [ ] Phase 6: 0 errors across 360 single-rule-refreshes under load, P95 stable
- [ ] Phase 7: post-GC heap at end within 100 MB of start; 0 errors; no GC regression
- [ ] Phase 8: heap dumps show no accumulated KieModule/Rule/KieBase; verdict written

---

## Verification (when this plan is "done")

- `mvn test` — green (including the 6 new integration tests for Tier 1)
- `./scripts/run-load-test.sh` — exits 0, generates `summary.md` with PASS verdict on every criterion
- A future engineer running `./scripts/run-load-test.sh` after a Drools or Spring Boot version bump gets the same PASS verdict — that's the regression contract this plan delivers

---

## Out of scope

- **Distributed load** — single machine only. If we hit the runner's CPU before the JVM's, we revisit then.
- **Real AWS S3** — LocalStack only.
- **Network failure injection** (Redis down, S3 down) — lower priority; could be a Phase 9 follow-up.
- **JIT warm-up tuning** — measure post-warm-up P99 only.
- **Multi-KieBase architecture** — explicitly rejected by ADR-001.
- **CI integration of the orchestrator** — ship the script + docs; CI wiring is a follow-up.
- **Rules using `agenda-group` / globals / `@PropertyReactive`** — out of scope for Tier 1 expansion (would need RuleExecutor changes).

---

## References

- [`fix-findings-plan.md`](fix-findings-plan.md) — the architecture under test
- [`fix-findings-checklist.md`](fix-findings-checklist.md) — Phase 5 of that plan was a 10-refresh sanity check; this plan is the production-readiness extension
- [`e2e-validation-checklist.md`](e2e-validation-checklist.md) — 2026-05-09 functional baseline
- [Drools 10 KieContainer Javadoc](https://docs.drools.org/latest/kie-api-javadoc/org/kie/api/runtime/KieContainer.html) — for Phase 0 research
- [`project-documentation/25-memory-monitoring-guide.md`](../../project-documentation/25-memory-monitoring-guide.md) — existing memory monitoring approach
- [`project-documentation/36-architecture-decision-records.md`](../../project-documentation/36-architecture-decision-records.md) — ADR-003 (KieContainer lifecycle, may need a load-test sign-off note)
