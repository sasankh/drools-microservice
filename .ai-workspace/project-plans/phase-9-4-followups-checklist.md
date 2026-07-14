# Phase 9.4 follow-ups — Implementation Checklist

**Status:** W1–W5 done (committed `5e047bc`, `58c91a1`, + bookkeeping). Phase 9.4 landed at **3/4** — (a)(c)(d) PASS; (b) accepted as a quick-RPS test-env limit (not the clean 4/4 originally targeted — see W4). W6 (PR) pending user go-ahead.
**Plan:** [`phase-9-4-followups-plan.md`](phase-9-4-followups-plan.md)
**Branch:** `feature/redis-cache-pubsub`
**Target effort:** ~90 min total — single PR closing the 2 deferred Phase 9.4 sub-criteria
**Driver:** Close `redis-cb-hardening-checklist.md` W6 — Phase 9.4 acceptance criteria green on all 4 sub-tests.

## Quick status (2026-05-26)

| Workstream | Status | Notes |
|---|---|---|
| 0. Pre-flight + audit | ✅ | 4 iterative re-runs; two root causes identified (harness restart-policy + missing exception class) |
| 1. Harness — recovery convergence settle sleep | ✅ done | `c7792ea` (part 1, 3s) → 10s in `5e047bc`; re-run `recovery_delta_ms=40` |
| 2. Harness — `docker update --restart=no` + per-tag CB CSV + app-log capture | ✅ done | Committed `5e047bc`; re-run produced all 3 forensic file types |
| 3. App — `QueryTimeoutException` in `recordExceptions` + predicate test | ✅ done | Committed `58c91a1`; 549 unit tests PASS; re-run proves `failed` CB counter 0→20/replica |
| 4. Verification — Phase 9.4 `--quick` re-run | ✅ done, **3/4 (not 4/4)** | (a)(c)(d) PASS; (b) CB-opens-within-30s reclassified as a quick-RPS test-env limit — window=40 can't be flipped by the ~20 failures a low-RPS test yields. Forcing it green rejected (test-only CB tuning, no new prod behavior). User decision 2026-07-14: accept 3/4 + document. |
| 5. Bookkeeping — cb-hardening + cache-layering checklists + 39-doc addendum | ✅ done | Caveats UPDATED (not dropped) to reflect 3/4 + reclassified (b); closed recovery + classification + restart-policy risks |
| 6. PR | ⏳ pending user go-ahead | `gh pr create` — body must state honest 3/4, not "clean PASS" |

**Test count target:** 548 → 549 unit tests (+1 predicate test). Integration tests unchanged.

---

## Workstream 0 — Pre-flight & audit

Already done; documented in [`phase-9-4-followups-plan.md`](phase-9-4-followups-plan.md) §1.2 (Forensic timeline) and §1.3 (The two real findings).

- [x] Reproduced Phase 9.4 FAIL on `--quick --phase 9.4`
- [x] Captured per-tag CB call CSV across pre-kill/pre-restart/post-restart points
- [x] Captured per-replica app logs to surface the actual exception path
- [x] Manually verified `docker update --restart=no` keeps the container `exited` 10s+ post-kill at the Docker layer
- [x] Confirmed `RedisCommandTimeoutException → QueryTimeoutException` translation via `LettuceExceptionConverter` from app log stack traces
- [x] Confirmed `QueryTimeoutException` is NOT a subtype of `RedisSystemException` (they're siblings under `DataAccessException`)
- [x] Confirmed Resilience4j behavior: exceptions not in `recordExceptions` are classified as `kind=successful` (not `kind=ignored`)

### Gate 0

- [x] Two root causes documented + decisions made (see plan §3 Decisions)
- [x] No further audit work required before W3

---

## Workstream 1 — Recovery convergence settle sleep

Goal: post-restart convergence sub-criterion reports `delta_ms ≤ 2000` consistently.

### 1.1 Production code

(no production-code change — harness-only)

### 1.2 Harness

- [x] Add `PHASE9_4_RECOVERY_SETTLE_S` env var (default initially 3s, bumped to 10s after observed insufficient) at [`scripts/run-load-test.sh:82`](../../scripts/run-load-test.sh#L82)
- [x] Document in `--help` env-var block at line 141
- [x] Insert `sleep "${PHASE9_4_RECOVERY_SETTLE_S}"` between "CB-exerciser stopped" log and "Post-restart convergence round" header (~line 1434)

### Gate 1

- [x] `bash -n scripts/run-load-test.sh` clean
- [x] Run 2 with 10s settle: `recovery_delta_ms=40` (well under 2000ms deadline)
- [x] Already partly committed in `c7792ea` (Phase 9.4 part 1, at 3s default); 10s bump bundled into Commit A this PR

---

## Workstream 2 — Harness restart-policy fix + observability

Goal: failure-mode kill window actually keeps Redis down (proving graceful degradation real), and the harness captures enough forensics to diagnose any future CB engagement anomaly.

### 2.1 `docker update --restart=no` around the kill window

- [x] Edit [`multi_stack::kill_redis`](../../scripts/lib/multi-stack.sh) — prepend `docker update --restart=no <name>` before `docker kill <name>`
- [x] Edit [`multi_stack::start_redis`](../../scripts/lib/multi-stack.sh) — append `docker update --restart=unless-stopped <name>` after `docker start <name>`
- [x] Add explanatory javadoc-style header to `kill_redis` documenting why this matters

### 2.2 Per-tag CB call CSV at three forensic points

- [x] Define `phase9_4::record_cb_calls "<phase-label>"` local helper inside `phase_9_4_failure_mode` that writes 4 rows per replica (kind=successful/failed/not_permitted/ignored) to `cb-calls-per-replica.csv`
- [x] Call it at pre-kill, pre-restart, and post-restart points (alongside the existing JSON snapshots)

### 2.3 App-log capture

- [x] At end of `phase_9_4_failure_mode` (before stack teardown), grep `docker logs drools-app-{1,2,3}` for Redis/CB-relevant terms and write to `app-{1,2,3}-redis-relevant.log`

### Gate 2

- [x] `bash -n` clean across both scripts
- [x] Manual verification: container stays `exited` after kill, restored after start; restart policy restored
- [x] Phase 9.4 re-run produces all 3 file types (`cb-calls-per-replica.csv`, `app-{1,2,3}-redis-relevant.log`, JSON snapshots)
- [ ] Commit A — `test(cache): Phase 9.4 follow-ups, part 2 — kill-restart-policy fix + per-tag CB CSV + app-log capture`

---

## Workstream 3 — Application fix: `QueryTimeoutException` in CB record list

Goal: Lettuce-translated timeouts register as CB failures and trip the breaker within the existing `CB_OPEN_DEADLINE_S=30s` window.

### 3.1 Production code

- [x] Edit [`src/main/java/com/company/drools/config/CircuitBreakerConfig.java`](../../src/main/java/com/company/drools/config/CircuitBreakerConfig.java) — added `org.springframework.dao.QueryTimeoutException.class` to the `.recordExceptions(...)` chain (now line 131)
- [x] Added a multi-line comment explaining the Lettuce → `LettuceExceptionConverter` → `QueryTimeoutException` translation and the sibling-not-subtype relationship

### 3.2 Unit test

- [x] Added `@Test testRedisCircuitBreakerRecordsExpectedExceptions` to [`CircuitBreakerConfigTest.java`](../../src/test/java/com/company/drools/config/CircuitBreakerConfigTest.java) (functionally the specced `redisCircuitBreakerRecordsQueryTimeoutAsFailure`)
- [x] Asserts `getRecordExceptionPredicate()` accepts all 5: `RedisConnectionFailureException`, `RedisSystemException`, `QueryTimeoutException`, `java.util.concurrent.TimeoutException`, `ConnectException`
- [x] Used fully-qualified class names inline (no new imports needed)

### Gate 3

- [x] `mvn -q test -Dtest=CircuitBreakerConfigTest` clean — 6 tests (was 5, +1)
- [x] `mvn -q test` clean — **549 PASS** (was 548)
- [x] `mvn -q spotless:check` clean

---

## Workstream 4 — Phase 9.4 verification

Goal: prove the W2 + W3 changes close all 4 acceptance sub-criteria.

### 4.1 Re-run (2026-07-14)

- [x] `./scripts/run-load-test.sh --quick --phase 9.4` — completed; run dir `scripts/load-test-results/2026-05-10T073852Z/phase-9-4`
- [x] `result.txt` → `FAIL err=0.000 recovery_delta_ms=40` — fails solely on (b); breakdown below

### 4.2 Per-sub-criterion confirmation

- [x] (a) JMeter err=0% across full window — **PASS**, now meaningful (Redis genuinely down after restart-policy fix)
- [~] (b) CB opens within 30s — **quick-RPS limit, not a defect**: `failed` counter delta = **20 on every replica** (0 pre-fix), proving the classification fix works; but window=40 dilution keeps the rate ~40–50%, under threshold. `events.csv` `cb_open_app-*` empty.
- [x] (c) CB closes within 90s of restart — PASS (vacuous; CB never opened)
- [x] (d) Post-restart convergence `delta_ms=40 ≤ 2000` — **PASS**

### 4.3 Commit B

- [x] `58c91a1` — `fix(cache): record QueryTimeoutException on Redis CB — Lettuce timeouts now trip the breaker`

### Gate 4

- [~] 3/4 sub-criteria PASS — (a)(c)(d) green; (b) accepted as documented quick-RPS test-env limit (user decision 2026-07-14)
- [x] result.txt captured + analyzed (`FAIL` on (b) only, with forensic CSV proving the fix)

---

## Workstream 5 — Bookkeeping

Goal: every existing tracking artifact reflects the new clean Phase 9.4 baseline.

### 5.1 `redis-cb-hardening-checklist.md`

- [ ] W6 row → ✅ (drop the 🟡 partial)
- [ ] Risk 3 (recovery convergence) → **Closed** (10s settle; recovery_delta_ms=40 vs 2000ms deadline)
- [ ] Risk 4 (`RedisCommandTimeoutException` not in `recordExceptions`) → **Re-opened then Closed**: previous "code inspection" conclusion was wrong — `QueryTimeoutException` is a sibling of `RedisSystemException`. Fixed in Commit B.
- [ ] Risk 6 (CB doesn't engage) → **Closed** — exception classification was the cause (not Lettuce cadence)
- [ ] Add new risk: "Phase 9.4 harness was `docker kill`-ing a container under `restart: unless-stopped`, masking the kill" — Closed via Commit A
- [ ] Definition of Done — tick "Phase 9.4 PASS — DEFERRED" line
- [ ] W6 sign-offs row updated with date

### 5.2 `redis-cache-layering-checklist.md`

- [ ] Top-line `**Status:**` line — drop "Phase 9.4 has 2 deferred follow-ups documented in `39-load-test-findings.md` Phase 9.4 addendum"
- [ ] Quick-status table Phase 9 row — drop "(with caveat)"; update Notes to reflect clean PASS
- [ ] §9.4 sub-section `[ ]` boxes → tick the now-passing items
- [ ] Definition of Done — tick "All quality gates green" line if it referenced 9.4

### 5.3 `project-documentation/39-load-test-findings.md`

- [ ] Append a "2026-05-26 follow-up update" sub-section to the existing Phase 9.4 addendum
- [ ] Document the two findings (harness restart-policy + QueryTimeoutException classification)
- [ ] Document the new baseline: all 4 sub-criteria PASS
- [ ] Note that the prior Phase 9.4 "JMeter err=0% proves graceful degradation" claim is now re-validated against a genuinely-down Redis

### Gate 5

- [ ] All 3 files updated
- [ ] No "deferred follow-up" or "with caveat" language remains for Phase 9.4
- [ ] Commit C — `docs(cache): Phase 9.4 follow-ups — close bookkeeping (3 files)`

---

## Workstream 6 — PR

- [ ] `git push -u origin feature/redis-cache-pubsub`
- [ ] `gh pr create` with body summarizing:
  - 9-phase delivery + Phase 9.4 hardening + this follow-up
  - Clean Phase 9.4 PASS — no deferred caveat
  - Two interesting findings: harness restart-policy bug (false-negative test) + `QueryTimeoutException` classification bug (would have shipped silently)
  - Operator migration checklist (from `redis-cache-layering-checklist.md`)
  - Test count: 549 unit + 14 integration (CI-only)
  - Links to ADR-016, `39-load-test-findings.md` Phase 9.4 addendum

### Gate 6

- [ ] PR created, URL captured
- [ ] PR body proofread

---

## Sign-offs

| Workstream | Owner | Date completed | Notes |
|---|---|---|---|
| 0. Pre-flight | self | 2026-05-26 | 4 iterative re-runs surfaced both root causes |
| 1. Settle sleep | self | 2026-05-25 | Committed `c7792ea` (Phase 9.4 part 1); 10s bump bundled into Commit A |
| 2. Restart-policy + observability | self | 2026-07-14 | Commit `5e047bc` |
| 3. QueryTimeoutException in recordExceptions | self | 2026-07-14 | Commit `58c91a1`; 549 tests PASS; fix proven by 0→20 failed-counter delta |
| 4. Phase 9.4 verification | self | 2026-07-14 | 3/4 PASS; (b) accepted as quick-RPS test-env limit per user decision |
| 5. Bookkeeping | self | 2026-07-14 | 39-doc addendum + cb-hardening + cache-layering checklists updated (caveats reflect 3/4, not removed) |
| 6. PR | | | Pending user go-ahead — body must state honest 3/4 |

---

## Risk log

| Date | Risk | Severity | Mitigation | Status |
|---|---|---|---|---|
| 2026-05-26 | Adding `QueryTimeoutException` to `recordExceptions` might trip the CB too aggressively if upstream Lettuce timeouts are transient | Low | `slowCallRateThreshold=80%` + `minimumNumberOfCalls=5` already guard against single-blip trips; `failureRateThreshold=60%` requires sustained timeouts | **Open** — re-evaluate after stage soak (Phase 10) |
| 2026-05-26 | The 10s settle adds wall-clock to every Phase 9.4 run | Low | Configurable via `PHASE9_4_RECOVERY_SETTLE_S` env var; operators can probe minimum on faster hardware | **Closed** — acceptable cost for a deterministic test |
| 2026-05-26 | Harness `docker update --restart=no` requires the container to exist before kill; on first-ever run the harness `multi_stack::up` creates it, so this is fine in normal use | Low | Pattern: `docker update --restart=no` returns non-zero on missing container; we `|| true` so kill loop continues | **Closed** — defended in code |
| 2026-05-26 | The fix is one Java line — easy to revert by accident in a future refactor | Low | Predicate test in `CircuitBreakerConfigTest` guards the allow-list contents; future drift = test failure | **Closed** — test added |
| 2026-05-26 | Prior `redis-cb-hardening-checklist.md` Risk 4 "Closed via code inspection" — the code-inspection conclusion was wrong | Med | New PR explicitly re-opens Risk 4 and re-closes with empirical evidence (predicate test + Phase 9.4 PASS) | **Closed via Commit B** |
| 2026-05-26 | Prior Phase 9.4 "JMeter err=0% during 60s Redis kill proves graceful degradation" was measuring a Redis container that was being respawned instantly by Docker — the claim was structurally unverified | Med | This PR fixes the harness so the JMeter-err=0% result is now real; the addendum to `39-load-test-findings.md` calls out that the earlier baseline gets retroactively re-validated | **Closed via Commit A + addendum** |

---

## Quality gates summary

| Gate | Threshold | When verified |
|---|---|---|
| Unit tests | All pass; 548 → 549 | After W3 |
| SpotBugs | 0 new bugs | After W3 |
| Spotless | clean | After W3 |
| Phase 9.4 `--quick` | All 4 acceptance criteria PASS | W4 |
| Bookkeeping | All 3 tracking artifacts updated | W5 |
| PR body | Lists clean PASS + 2 findings | W6 |

---

## Verification commands

```bash
# W3 — Java fix
mvn -q test -Dtest=CircuitBreakerConfigTest
mvn -q test
mvn -q spotless:check spotbugs:check

# W4 — Phase 9.4 re-run
./scripts/run-load-test.sh --quick --phase 9.4
RUN=$(ls -td scripts/load-test-results/*/ | head -1)
cat "${RUN}phase-9-4/result.txt"            # expect: PASS
awk -F, '$4=="failed" && $5+0 > 5' "${RUN}phase-9-4/cb-calls-per-replica.csv"
# expect at least 3 rows (one per replica) showing > 5 failed CB calls during kill window

# W5 — bookkeeping check
grep -nE "with caveat|deferred follow-up|Phase 9\.4.*pending" \
  .ai-workspace/project-plans/redis-cb-hardening-checklist.md \
  .ai-workspace/project-plans/redis-cache-layering-checklist.md \
  project-documentation/39-load-test-findings.md
# expect: 0 matches

# W6 — PR
gh pr view --web
```

---

## Migration checklist for operators

(none — no env-var changes or operator-facing config changes in this PR; the `QueryTimeoutException` allow-list addition is internal CB config and behaviour-positive)

---

## Definition of Done

This work is DONE only when ALL of the following are true:

- [ ] All 6 workstream gates passed
- [ ] All sign-offs row filled
- [ ] All risks Closed (or Open with explicit deferral)
- [ ] Quality gates: unit ✅ 549 PASS, spotless ✅, Phase 9.4 ✅
- [ ] Bookkeeping: 3 files updated, no "with caveat" / "deferred follow-up" language remains for Phase 9.4
- [ ] PR open with body that lists the clean Phase 9.4 PASS + the two interesting findings
