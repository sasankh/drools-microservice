# End-to-End Docker-Compose Validation Plan
## Java 25 + Drools 10.2.0 + Spring Boot 3.5.3 — full stack validation

**Date drafted**: 2026-05-09
**Author**: Drafted with AI assistance; reviewed by user
**Status**: Approved — implementation pending
**Companion to**: [`stack-modernization-plan.md`](stack-modernization-plan.md) (the modernization itself, completed earlier today)

---

## Context

The 2026-05-09 stack modernization (Java 17→25, Spring Boot 3.2.5→3.5.3, Drools 8.44.0→10.2.0) was completed and live-tested only with a **standalone Docker container** running `RULE_SOURCE=memory`. That mode uses the built-in `InMemoryRuleStorage` rules (exactly 2 hardcoded rules embedded in Java source).

The **full docker-compose stack** (app + LocalStack + Redis), the 10 actual DRL files in [`sample-rules/`](../../sample-rules/), the S3 rule-loading path, the Redis caching layer, the rule-refresh flow, and the memory-stability benchmark — none of these were exercised on the new stack.

The repo contains a comprehensive 9-step / 30-check end-to-end test plan at [`full-docker-test-plan.md`](../../full-docker-test-plan.md), last validated 2026-02-26 (pre-modernization). Re-running it on the new stack is the right way to confirm the production-shaped configuration actually works.

---

## What was NOT tested during the modernization (the gap this plan closes)

| Path | Status before this plan |
|---|---|
| `docker-compose up -d --build` (app + LocalStack + Redis healthy) | ❌ not run |
| `init-localstack.sh` (uploads 10 sample DRL files to LocalStack S3) | ❌ not run |
| `test-localstack.sh` (validates the S3 upload) | ❌ not run |
| App boots with `RULE_SOURCE=s3` and pulls rules from LocalStack | ❌ not exercised |
| Redis caching layer (`REDIS_ENABLED=true` in docker-compose env) | ❌ not exercised |
| All 10 actual sample rules executed via S3 path | ❌ only 2 in-memory rules tested |
| `POST /admin/refresh-rules` rule refresh flow | ❌ not tested |
| Memory stability under 10+ refreshes (atomic-swap on Drools 10 + S3) | ❌ not benchmarked |
| Path-traversal validation, error handling, edge cases | ❌ not tested |
| `SPRING_PROFILES_ACTIVE=docker` profile config | ❌ not exercised |
| `docker-build-test.sh` (project's automated build+health validation) | ❌ not run |

---

## Anticipated risks (what could surface)

1. **Spring Data Redis / Lettuce** — Spring Boot 3.2 → 3.5 may have changed Lettuce client config or serialization. Could surface as "Redis circuit breaker open" or serialization errors.
2. **AWS SDK 2.20.56 → 2.34.0** — S3 client API is stable but timeouts, retries, or BOM-managed transitive versions may shift behavior.
3. **`drools-engine` aggregator at runtime in container** — the host build worked, but the container has Alpine + Corretto 25 which may surface classloader or JNI quirks not seen on host.
4. **Sample DRL files** — the 10 `.drl` files in `sample-rules/` already use the safer `((Number)…).doubleValue()` pattern, so they *should* work. But none have been actually compiled + executed by Drools 10 yet. Same class of bug as the `InMemoryRuleStorage` Integer→Double regression we already fixed could surface again.
5. **Memory stability** — Drools 10's `KieContainer` lifecycle was preserved per the migration guide, but the 2000-refresh benchmark from the original 2026-02-19 fix has not been re-run. A leak here would be silent until production stress.
6. **Cache layer multi-tier flow** — S3 → Redis → LRU → KieBase has multiple touch points; any of them could regress under the new versions.
7. **Docker profile config** — `application.yml` `docker` profile properties may have a Spring Boot 3.5 rename we missed during Phase 4 review.
8. **LocalStack image compatibility** — `docker-compose.yml` may pin a LocalStack version with its own AWS CLI version. If `init-localstack.sh` runs `awslocal` from inside the container, it should work. From host, falls back to `aws --endpoint-url=...`.

---

## Phase plan

The plan follows the existing 9-step structure of [`full-docker-test-plan.md`](../../full-docker-test-plan.md). Each phase has explicit exit criteria; if any fails, plan splits to investigate.

### Phase A — Stack startup (Steps 1–2 of full-docker-test-plan.md) | ~5 min

1. Stop any conflicting local Redis: `brew services stop redis 2>/dev/null || true`
2. Clean prior state: `docker-compose down -v 2>/dev/null`
3. Build + start: `docker-compose up -d --build`
4. Wait for all 3 services healthy (script-driven loop, typically ~30s)
5. `docker-compose ps` — all 3 services Up + healthy
6. `curl /admin/health` — 200 UP, all components UP (cache, drools, storage, circuit-breakers, jvm-memory)
7. Hit Actuator endpoints on port 8081 (`/actuator/health`, `/actuator/metrics`)

**Exit criteria**: all 3 containers healthy; `/admin/health` returns UP; Actuator on 8081 responds.

**If fails**: check `docker-compose logs --tail=50 app`. Likely culprits: Spring Boot 3.5 property rename, Redis Lettuce version mismatch, S3 client init.

### Phase B — S3 + Redis rule loading (Step 3) | ~5 min

1. `./init-localstack.sh` — uploads `sample-rules/*.drl` to LocalStack S3 bucket `local-rules`
2. `./test-localstack.sh` — validates upload (10 .drl files, sample rule content valid)
3. `GET /admin/rules` — lists 10 rules with metadata
4. `POST /admin/refresh-rules` — `rules_loaded: 10, rules_failed: 0`
5. `POST /admin/refresh-rules/pricing.discount.simple` — single-rule refresh succeeds
6. `GET /admin/rules` again — rule list unchanged

**Exit criteria**: 10 rules uploaded; app loads all 10 from S3 via the S3 → cache → KieBase path; refresh works.

**If fails**: this is the most likely place for Drools 10 executable-model regression. Look for `MissingDependencyException`, `ClassCastException`, or Drools compile errors in logs.

### Phase C — All 10 sample rules executed via S3 path (Step 4 — the critical part) | ~10 min

Execute each rule with documented input → expected output (per [`19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md)):

| # | Rule ID | Input | Expected Output |
|---|---|---|---|
| 1 | `pricing.discount.simple` | `{"amount": 100}` | `amount: 90.0, discount: 10.0` |
| 2 | `pricing.discount.vip` | `{"customerType": "VIP", "amount": 100}` | `amount: 80.0, discount: 20.0` |
| 3 | `pricing.discount.bulk` | `{"quantity": 15, "amount": 180}` | `amount: 153.0, discount: 27.0` |
| 4 | `pricing.discount.first-time` | `{"firstTime": true, "amount": 100}` | (per cookbook) |
| 5 | `pricing.shipping.standard` | `{"weight": 10, "amount": 100}` | (per cookbook) |
| 6 | `pricing.shipping.express` | `{"weight": 5, "express": true}` | (per cookbook) |
| 7 | `seasonal.holiday.blackfriday` | `{"isBlackFriday": true, "amount": 100}` | (per cookbook) |
| 8 | `seasonal.holiday.discount` | `{"isHoliday": true, "amount": 100}` | (per cookbook) |
| 9 | `validation.customer.age` | `{"age": 17}` | `valid: false` (underage) |
| 10 | `validation.customer.credit` | `{"creditScore": 750}` | (per cookbook) |

Outputs must be **byte-identical** to what the cookbook documents. Any divergence is a Drools 10 executable-model regression that needs a per-DRL fix (same pattern as the `InMemoryRuleStorage` Integer→Double fix).

**Exit criteria**: all 10 rules return expected outputs.

**If fails**: investigate the failing DRL file. Most likely cause: a `(Double)` cast or wrapper-coercion pattern that worked under MVEL but is strict under executable model. Fix the DRL, re-upload via `init-localstack.sh`, refresh via `POST /admin/refresh-rules`, re-test.

### Phase D — Edge cases (Step 5) | ~5 min

- Non-existent rule (`rule_id: "does.not.exist"`) → 404 RULE_NOT_FOUND
- Missing rule_id → 400 INVALID_INPUT
- Empty data → 400 INVALID_INPUT
- Invalid JSON → 400
- Path traversal (`rule_id: "../etc/passwd"`) → 400 INVALID_INPUT (validator rejects; storage layer is defense in depth)

**Exit criteria**: every edge case returns the documented error code.

### Phase E — Memory stability (Step 6) | ~5 min

1. Snapshot `/admin/memory/info` (heap.usedMB before)
2. 10 successive `POST /admin/refresh-rules` (loop with small delay)
3. Snapshot again (heap.usedMB after)
4. Heap growth `after - before` should be **< 5 MiB**

The 2026-02-19 benchmark established the baseline: ~16 KB growth per refresh after the KieContainer disposal fix. If the new stack regresses this, we'd see > 50 MiB growth over 10 refreshes.

**Exit criteria**: heap growth < 5 MiB across 10 refreshes.

**If fails**: Drools 10's `KieContainer.dispose()` lifecycle may have changed. Investigate; potentially update [ADR-003](../../project-documentation/36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal) if the implementation needs adjustment.

### Phase F — GC trigger + logs review (Steps 7–8) | ~3 min

- `POST /admin/memory/gc` — returns freed memory delta (sanity check on the manual-GC endpoint)
- `docker-compose logs --tail=200 app` — look for `ERROR` or `Exception` (other than expected validation rejects from Phase D)
- All 7 security headers still present in responses

**Exit criteria**: GC endpoint works; no unexpected ERROR-level log entries; security headers present.

### Phase G — Cleanup + doc updates (Step 9) | ~5 min

1. `docker-compose down -v` — remove containers and volumes
2. Update [`full-docker-test-plan.md`](../../full-docker-test-plan.md) "Last Validated" → 2026-05-09
3. Update [`e2e-validation-checklist.md`](e2e-validation-checklist.md) (companion) with all results filled in
4. Update [`stack-modernization-checklist.md`](stack-modernization-checklist.md) — append "Phase 8: End-to-End Docker-Compose Validation" section
5. Append a "Validated end-to-end on 2026-05-09" note to [ADR-014](../../project-documentation/36-architecture-decision-records.md#adr-014-drools-8--10-migration-2026-05-09)
6. If any DRL files were modified, `init-localstack.sh` re-uploads — sample rules in S3 are auto-refreshed on each script run; no separate doc update needed for those

**Exit criteria**: containers cleaned; docs updated; checklist reflects actual results.

---

## Critical files (read-only — already in repo)

- [`full-docker-test-plan.md`](../../full-docker-test-plan.md) — the canonical 30-check test plan
- [`docker-compose.yml`](../../docker-compose.yml) — stack definition (app + LocalStack + Redis)
- [`init-localstack.sh`](../../init-localstack.sh) — uploads `sample-rules/` to LocalStack S3
- [`test-localstack.sh`](../../test-localstack.sh) — validates S3 upload
- [`docker-build-test.sh`](../../docker-build-test.sh) — single-container smoke test (optional)
- [`sample-rules/`](../../sample-rules/) — the 10 DRL files that need to execute correctly
- [`project-documentation/19-sample-rules-cookbook.md`](../../project-documentation/19-sample-rules-cookbook.md) — source-of-truth for expected outputs

## Files I expect to potentially modify (only if validation surfaces issues)

- One or more `sample-rules/*.drl` files — if Phase C surfaces executable-model gotchas
- [`src/main/resources/application.yml`](../../src/main/resources/application.yml) — if Phase A surfaces a Spring Boot 3.5 property rename
- [`docker-compose.yml`](../../docker-compose.yml) — if a LocalStack/Redis version pin needs bumping
- [`stack-modernization-checklist.md`](stack-modernization-checklist.md) — append end-to-end results
- [ADR-014](../../project-documentation/36-architecture-decision-records.md#adr-014-drools-8--10-migration-2026-05-09) — append validation note
- [`full-docker-test-plan.md`](../../full-docker-test-plan.md) — update "Last Validated" date

---

## Estimated effort

- Happy path (everything works): **~30 min** to run the full plan + record results
- Pessimistic (1–2 sample rules need executable-model fixes): **+30–60 min** per rule fix + rebuild + retest
- Worst case (Spring Boot config or Redis serialization regression): **2–4 hours** to root-cause + fix

Realistic estimate: **45–90 min** including some doc fixes.

---

## Out of scope

- **Performance benchmarking** (JMeter at 100/1000 RPS) — still on the project-improvement-plan backlog; not part of this validation pass
- **Migration to Spring Boot 4.x** — wait for ecosystem to stabilize
- **Drools 10 Rule Units / OOPath adoption** — explicitly rejected by ADR-001
- **2000-refresh stress test** — out of scope for this pass; the 10-refresh memory check in Phase E is the smoke test. Document the 2000-refresh as a separate manual benchmark to be run before any production deploy.
- **Adding Permissions-Policy header** — the project's "7 security headers" claim refers to existing 7 (X-Content-Type-Options, X-Frame-Options, X-XSS-Protection, Referrer-Policy, Cache-Control, Content-Security-Policy, Strict-Transport-Security)

---

## Companion files

- [`e2e-validation-checklist.md`](e2e-validation-checklist.md) — actionable phase-by-phase checklist; track progress here as work proceeds
- [`stack-modernization-plan.md`](stack-modernization-plan.md) — the modernization that this validates
- [`stack-modernization-checklist.md`](stack-modernization-checklist.md) — modernization progress (this validation closes the last open box: "2000-refresh memory benchmark — deferred")
- [`full-docker-test-plan.md`](../../full-docker-test-plan.md) — canonical test-plan in repo root (this plan executes it)
