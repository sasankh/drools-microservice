# Phase 9.4 deferred follow-ups — Redis CB engagement + recovery convergence

**Status:** Approved — partly executed (harness fixes already committed); Java fix + verification + bookkeeping pending
**Created:** 2026-05-26
**Owner:** self
**Driver:** Close the two deferred Phase 9.4 sub-criteria left over from the `redis-cb-hardening` workstream (W6: 🟡 partial). Four iterative re-runs surfaced two distinct root causes — one in the harness and one in the application — that were compounded and masked each other.

---

## 1. Context & motivation

### 1.1 What `redis-cb-hardening` left open

[`redis-cb-hardening-checklist.md`](redis-cb-hardening-checklist.md) closed W0–W5 cleanly, but W6 (verification) shipped 🟡 partial with two unresolved sub-criteria:

| Sub-criterion | Status on hardening PR | Why deferred |
|---|---|---|
| Phase 9.4 — JMeter err=0% across kill+restart | ✅ PASS (23,925 samples, 0 errors) | service-facing graceful-degradation observed |
| Phase 9.4 — CB opens per replica within `CB_OPEN_DEADLINE_S=30s` of Redis kill | ❌ FAIL — CB stayed `closed` all 29 polls | suspected Lettuce exception classification gap; harness lacked metric-snapshot instrumentation to diagnose |
| Phase 9.4 — CB closes per replica within `CB_CLOSE_DEADLINE_S=90s` of Redis restart | ⚠️ trivially PASS | (CB never opened → `closed → closed` is vacuous) |
| Phase 9.4 — post-restart convergence `delta_ms ≤ 2000` | ❌ FAIL — `t_received_ms=-1` | harness fired recovery round ~1s after CB-closed, before listener's 2s `FixedBackOff` retry cycle re-subscribed |

The hardening checklist documented these as harness/observability follow-ups, not data-plane safety issues.

### 1.2 The forensic timeline (4 Phase 9.4 re-runs)

| Run | Change | Result | Finding |
|---|---|---|---|
| 1 | Baseline (added 3s settle sleep + JSON snapshots) | `recovery_delta=-1`, CB never opens | 3s settle insufficient; snapshot helper doesn't capture per-tag CB call breakdown |
| 2 | Bumped settle 3s→10s; added per-tag `cb-calls-per-replica.csv` | `recovery_delta=40ms` (PASS for recovery), but CB calls show **25 successful, 0 failed** per replica during 60s kill | Recovery race solved; CB classification anomaly surfaced |
| 3 | Added `docker update --restart=no` in `multi_stack::kill_redis` (loadtest compose has `restart: unless-stopped` on redis) | Same exact numbers as run 2: 25 successful, 0 failed | Restart-policy fix verified at Docker layer (container stays `exited` 10s+) but CB metrics didn't change → restart policy wasn't actually the CB engagement blocker |
| 4 | Added per-replica app-log capture | Same exact numbers; **log capture revealed the real cause** | Lettuce throws `RedisCommandTimeoutException` → Spring's `LettuceExceptionConverter` translates to `org.springframework.dao.QueryTimeoutException` → that class is NOT in the Redis CB's `recordExceptions` → Resilience4j treats it as success (kind=successful), not failure |

### 1.3 The two real findings

**Finding A — Harness `docker kill` was masked by compose `restart: unless-stopped`.** The loadtest-multi compose has `restart: unless-stopped` on the redis service so the stack stays up during normal operation. `docker kill drools-redis` triggered an immediate respawn by the Docker daemon — the container was effectively never down. **The prior `redis-cb-hardening-checklist` Risk 4 "Closed via code inspection" call rested on the implicit assumption that the harness was actually exercising the failure mode it claimed; it wasn't.** Service-facing JMeter err=0% during the prior Phase 9.4 run was a real measurement of "Redis was barely down", not a measurement of "service degrades gracefully across a 60s Redis outage".

**Finding B — `QueryTimeoutException` is missing from the Redis CB's `recordExceptions`.** Once Finding A was fixed and Redis actually stayed down for 60s, the CB still didn't engage. App logs revealed the exception path:

1. Lettuce hits its 500ms `commandTimeout` → throws `io.lettuce.core.RedisCommandTimeoutException`
2. Spring Data Redis's `org.springframework.data.redis.connection.lettuce.LettuceExceptionConverter.convert()` translates this to `org.springframework.dao.QueryTimeoutException` ("Redis command timed out")
3. `QueryTimeoutException` extends `NonTransientDataAccessException` extends `DataAccessException` — it is **not** a subtype of `RedisSystemException`. They are siblings under `DataAccessException`.
4. The Redis CB's `recordExceptions` list ([`CircuitBreakerConfig.java:121-125`](../../src/main/java/com/company/drools/config/CircuitBreakerConfig.java#L121-L125)) contains `RedisConnectionFailureException`, `RedisSystemException`, `java.util.concurrent.TimeoutException`, `ConnectException` — none of which match `QueryTimeoutException`.
5. Resilience4j's behavior when an exception is thrown but doesn't match `recordExceptions`: the call is classified as **`kind=successful`**, not `kind=failed`. The sliding window never sees a failure and the CB never trips.

**Risk 4 of the prior hardening checklist was wrong.** It read "Spring Data Redis wraps it as QueryTimeoutException → subtype of RedisSystemException (already in allow-list) → no config change needed". The code-inspection conclusion confused sibling and subtype relations in the Spring DAO exception hierarchy.

### 1.4 Outcome of this change

- Phase 9.4 `--quick` re-run reports PASS on all four sub-criteria
- Per-replica CB opens within `CB_OPEN_DEADLINE_S=30s` of Redis kill (5th re-run will confirm)
- Per-replica CB closes within `CB_CLOSE_DEADLINE_S=90s` of Redis restart, then post-restart convergence rounds within 2000ms
- `redis-cb-hardening-checklist.md` W6 → 🟡 → ✅
- `redis-cache-layering-checklist.md` Phase 9 row → drop "(with caveat)"

---

## 2. What gets changed

### 2.1 Harness fixes (already done, staged for commit)

| File | Change | Effect |
|---|---|---|
| [`scripts/run-load-test.sh`](../../scripts/run-load-test.sh) | Add `PHASE9_4_RECOVERY_SETTLE_S` env var (default 10s) + `sleep` between CB-closed observation and post-restart convergence round | Recovery convergence sub-criterion PASS |
| [`scripts/run-load-test.sh`](../../scripts/run-load-test.sh) | Add per-tag `cb-calls-per-replica.csv` capture at pre-kill / pre-restart / post-restart points | Forensic per-replica CB call breakdown by `kind` for future incident analysis |
| [`scripts/run-load-test.sh`](../../scripts/run-load-test.sh) | Add per-replica `app-N-redis-relevant.log` capture at end of phase 9.4 | Surface app-level Redis exception detail for diagnosis |
| [`scripts/lib/multi-stack.sh`](../../scripts/lib/multi-stack.sh) | `multi_stack::kill_redis` → `docker update --restart=no <name>` before `docker kill`; `multi_stack::start_redis` → `docker update --restart=unless-stopped <name>` after `docker start` | Failure-mode kill window now actually keeps Redis down |

### 2.2 Application fix (this PR)

| File | Change | Effect |
|---|---|---|
| [`src/main/java/com/company/drools/config/CircuitBreakerConfig.java`](../../src/main/java/com/company/drools/config/CircuitBreakerConfig.java) | Add `org.springframework.dao.QueryTimeoutException.class` to `redisCircuitBreaker`'s `recordExceptions` chain (line 121-125) + one-line code comment explaining the translation chain | Lettuce timeouts now register as CB failures and trip the breaker |
| [`src/test/java/com/company/drools/config/CircuitBreakerConfigTest.java`](../../src/test/java/com/company/drools/config/CircuitBreakerConfigTest.java) | New test method `redisCircuitBreakerRecordsQueryTimeoutAsFailure` asserting all 5 record-exception classes match the predicate (defends against future drift) | Test count 548 → 549 |

### 2.3 Bookkeeping (this PR)

| File | Change |
|---|---|
| [`redis-cb-hardening-checklist.md`](redis-cb-hardening-checklist.md) | W6 → ✅ fully complete; Risks 3 + 4 + 6 → Closed; add new Risk for harness restart-policy bug |
| [`redis-cache-layering-checklist.md`](redis-cache-layering-checklist.md) | Phase 9 status row → drop "(with caveat)"; top-line status → drop deferred-followup sentence |
| [`project-documentation/39-load-test-findings.md`](../../project-documentation/39-load-test-findings.md) | Append "2026-05-26 follow-up" sub-section to the Phase 9.4 addendum documenting the two findings + the new clean baseline |

---

## 3. Decisions

### 3.1 Why `docker update --restart=no` over alternatives

- **`docker pause`/`docker unpause`** — simulates Redis hung-but-alive (different failure mode); the listener container's TCP connection isn't broken so pub/sub recovery isn't actually tested. Wrong shape.
- **Remove `restart: unless-stopped` from compose** — changes test topology; restart policy is desirable during normal harness operation between phases. Intrusive.
- **`docker update --restart=no`** — preserves test semantics (Redis crashes, stays down, gets started), restores normal policy on the way out. Minimal blast radius. ✅

### 3.2 Why `QueryTimeoutException` over restructuring the exception hierarchy

- Adding `QueryTimeoutException` to `recordExceptions` is one line. The other approach — registering a custom Spring `RedisExceptionConverter` that maps timeouts to `RedisSystemException` — would mask the failure type and conflict with Spring's own classifier. Wrong layer.
- The CB's `recordExceptions` IS the right knob: it's the explicit allow-list of exceptions that count as failures. The fact that one was missing is the bug, not the existence of the list. ✅

### 3.3 Why settle is 10s, not 3s or 5s

- `FixedBackOff(2s, ∞)` retry cycle on `RedisMessageListenerContainer` should reconnect every 2s. 3s headroom should be enough in theory.
- In practice (3s default in first commit), the recovery round still timed out — observed listener took >3s to actually re-subscribe after Docker reported the container healthy. 10s is generous but the cost is 7s of wall-clock per Phase 9.4 run.
- `PHASE9_4_RECOVERY_SETTLE_S` is configurable; operators can probe the minimum on faster hardware.

### 3.4 Out of scope

- **Lettuce-cadence tuning**: exerciser cadence (every 3s) was a suspected cause; irrelevant once exception classification is right. No change.
- **CB metric registry overhaul**: per-tag breakdown was added inline via `actuator::counter_at` queries in a CSV. Long-term, `actuator::snapshot_phase9_metrics` could be extended to expand all `kind=` tag values, but that's a separate ergonomics improvement.
- **`scanKeys`-only or `convertAndSend`-only audits**: the SCAN-CB-wrap and pub/sub recovery backoff from the prior hardening PR remain correct; they're orthogonal to the `recordExceptions` fix.

---

## 4. Verification

```bash
# Java fix landed cleanly:
mvn test -Dtest=CircuitBreakerConfigTest    # expect: redisCircuitBreakerRecordsQueryTimeoutAsFailure PASS
mvn test                                     # expect: 549 PASS, 0 failures, 0 errors
mvn spotless:check                           # clean

# Harness end-to-end (5th Phase 9.4 re-run):
./scripts/run-load-test.sh --quick --phase 9.4
RUN=$(ls -td scripts/load-test-results/*/ | head -1)
cat "${RUN}phase-9-4/result.txt"            # expect: PASS (all 4 sub-criteria green)
cat "${RUN}phase-9-4/cb-calls-per-replica.csv" | awk -F, '$4=="failed"'
# expect: per-replica `failed` delta > 5 across the kill window
cat "${RUN}phase-9-4/events.csv"
# expect: cb_open_app-{1,2,3} populated (non-empty)
```

## 5. References

- Prior hardening: [`redis-cb-hardening-plan.md`](redis-cb-hardening-plan.md) + [`redis-cb-hardening-checklist.md`](redis-cb-hardening-checklist.md) — Risk 4 is the line item this PR re-opens and closes correctly
- Phase 9 master tracker: [`redis-cache-layering-checklist.md`](redis-cache-layering-checklist.md) — Phase 9 row gets de-caveat-ed once this lands
- Load-test findings: [`project-documentation/39-load-test-findings.md`](../../project-documentation/39-load-test-findings.md) — Phase 9.4 addendum gets a 2026-05-26 follow-up section
- Already-staged commits (uncommitted as of plan writing):
  - `scripts/run-load-test.sh` — settle sleep 10s + per-tag CB CSV + app-log capture
  - `scripts/lib/multi-stack.sh` — `docker update --restart=no` fix
- Already-committed Phase 9.4 follow-up work:
  - `c7792ea` — Phase 9.4 follow-ups, part 1: settle sleep (initial 3s default) + CB metric snapshots
