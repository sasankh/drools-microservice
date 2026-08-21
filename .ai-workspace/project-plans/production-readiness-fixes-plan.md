# Production-Readiness Fixes — Phased Plan (all findings except B1)

**Created:** 2026-08-19 · **Branch:** `re-review-fable-1`
**Source review:** [`../ai-summary/production-readiness-review-2026-07-14.md`](../ai-summary/production-readiness-review-2026-07-14.md)
**Checklist:** [`production-readiness-fixes-checklist.md`](production-readiness-fixes-checklist.md)

---

## Context

The 2026-07-14 review found the service is not production/public-ready. A fresh verification pass
(2026-08-19, branch `re-review-fable-1`) confirmed **every finding is still present and unchanged**.

**Decision:** fix everything now **EXCEPT B1** (the DRL-sandbox RCE), scoped as separate follow-up
work — its sound fix is architectural (Java 25 permanently disabled `SecurityManager` via JEP 486, so
the boundary must move to a load-time classloader allowlist + out-of-process isolation). This plan
covers **B2, P1–P7, S1–S12, and the nits**.

**Testing is Docker-only, end-to-end** (Java 25 is not installed locally):
- Unit/integration/build runs inside a `maven:3.9-eclipse-temurin-25` container.
- Functional verification uses the `docker-compose` stack and extends `full-docker-test-plan.md`.
- Nothing in verification relies on a local JDK/Maven.

**Working method:** each phase is a self-contained commit/PR that ends with a **Review Gate** — a
correctness review of that phase's diff (self-review checklist + an independent review subagent) plus
a Docker check scoped to that phase. A phase is not "done" until its Review Gate passes. The final
phase is the full Docker E2E run across all changes.

---

## Phase 0 — Setup
- Write this plan + the checklist into `.ai-workspace/project-plans/`.
- Baseline the Docker test harness before any change:
  ```bash
  docker run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-25 mvn -B clean test
  ```
  Record the baseline test count (expected ~549) so each phase can prove no regression.

## Phase 1 — Publishing hygiene (fast, unblocks going public)
Findings: **B2**, `.env.example` footgun (part of P1), `.scannerwork` nit, README nits.
- **B2** — add root `LICENSE` (MIT, matching `README.md:1212`), `SECURITY.md` (report contact + note
  that rule-store write access ≡ code execution until B1 lands), `CONTRIBUTING.md`. *Confirm
  copyright holder/year (default: "Drools Rule Engine Microservice contributors", 2026).*
- `.env.example:110-113` — replace phantom `ADMIN_API_USERNAME`/`PASSWORD`/`ENABLED` with the real
  `ADMIN_API_KEY`; fix `RULE_SOURCE` wording (`local`=in-memory / `file` / `s3`).
- `.scannerwork` — `git rm --cached .scannerwork/.sonar_lock`; add `.scannerwork/` to `.gitignore`.
- README — fix duplicate `### 3.` headings (`:101`,`:118`); fix security-posture wording (see Phase 6);
  `README.md:653` "per remote IP" reworded to match the P2 outcome.

## Phase 2 — Fail-open / fail-loud security defaults
Findings: **P1, P2, P4, P5**.
- **P1** `AdminAuthFilter.java` — inject `Environment`; deployable profiles = `{prod, docker}`; if a
  deployable profile is active AND key blank → **fail startup** (throw in constructor) instead of
  serve-open (`:36-42`,`:63-71`). `local`/`dev` with no key stays open-with-WARN. Constant-time key
  compare via `MessageDigest.isEqual` (`:53`). `docker-compose.yml` — add
  `- ADMIN_API_KEY=admin-secret` (matches the header the test plan already sends).
- **P2** `RateLimitingFilter.getClientIdentifier` (`:69-94`) — key on `getRemoteAddr()` only; drop the
  `X-API-Key`/`Authorization`/`X-Client-Id` chain; add opt-in `drools.rate-limiting.trust-proxy`
  (default false) + trusted-proxy XFF parsing. `RateLimitingConfig.InMemoryRateLimitingService` —
  LRU-evict oldest bucket at `max-clients` instead of 429-locking new clients.
- **P4** `application.yml` (`:13-24`) — add `management.health.redis.enabled: ${REDIS_ENABLED:false}`.
- **P5** `docker-compose.yml:10` — `"8081:8081"` → `"127.0.0.1:8081:8081"`; document internal-only
  8081 in `SECURITY.md`.

## Phase 3 — Execution & refresh robustness
Findings: **P3, P7**.
- **P3** `RuleExecutor.java` — hold the `KieSession` in an `AtomicReference` set by the async task
  (`:83-91`); on `TimeoutException` (`:65-68`) call `kieSession.halt()` + dispose (plus existing
  cancel). `ThreadPoolConfig.java:77` — rule-execution pool `CallerRunsPolicy` → `AbortPolicy`; map
  `RejectedExecutionException` → 503. New tests: runaway `while(true)` rule halts within timeout;
  saturation returns 503. (Document the residual: `halt()` stops between firings, not inside one
  infinite consequence — a B1-adjacent limitation.)
- **P7** `DroolsEngineService.java` — add `ReentrantLock refreshLock`; rewrite `loadOrReplaceRule`
  (`:248-258`) to snapshot `loadedRules` under a short `readLock`, then call `loadRules(combined)`
  which already compiles OUTSIDE the write lock and takes `writeLock` only for the swap
  (`:168`,`:180-215`). Route bulk refresh through `refreshLock` too.

## Phase 4 — Redis correctness & security
Findings: **P6, S1, S2, S5, S6, S10, S11**.
- **P6** `RedisConfig.createRuleJsonSerializer()` (`:60-73`) — remove `activateDefaultTyping` + the
  PTV (`Rule` is a flat POJO, `Rule.java:5-18`, so default typing is unnecessary and only widens the
  gadget surface; *verify `RuleMetadata` has no polymorphic fields first*). Add a `@Profile("prod")`
  startup validator requiring `rediss://` + credentials when `redis.enabled=true`.
- **S1** `RedisCachedRuleStorage.getAllRules` (`:144-177`) — after `collectFromRedis()`,
  `existing.keySet().retainAll(new HashSet<>(expectedIds))` so deleted-but-cached rules aren't
  resurrected.
- **S2** `RedisCachedRuleStorage.refreshRule` (`:223-229`) — after DEL + delegate refresh, write-through
  from base: `delegate.getRule(ruleId).ifPresent(this::cachePut)`.
- **S5** add `DroolsEngineService.removeRule(ruleId)` (snapshot → drop → `loadRules` under
  `refreshLock`); call it from `RuleRefreshSubscriber.handleDelete` (`:149-155`).
- **S6** `RedisConfig.redisMessageListenerContainer` (`:92-108`) — set a bounded single-thread
  `taskExecutor`; coalesce burst `RULE_REFRESHED_BULK` events in `RuleRefreshSubscriber`.
- **S10** `RedisCachedRuleStorage.java:88` — `delegate` → `volatile`.
- **S11** `S3RuleStorage.ruleIdToS3Key` (`:321-327`) — validate raw `ruleId` (reject `..`, leading
  `/`, backslashes) then `normalize()`+containment like `LocalFileStorage.java:156-163`; validate raw
  `ruleId` at the pub/sub entry (`RuleRefreshSubscriber:136`).

## Phase 5 — CI & quality gates (locks in every other fix)
Findings: **S3, S4, S7, S8, S9**.
- **S3** add `.github/workflows/ci.yml` (Ubuntu, Temurin 25, native Docker): `mvn -B verify` +
  spotless:check + spotbugs:check + dependency-check:check + the 3 Testcontainers ITs; gate PRs.
- **S4** `pom.xml` — bind spotless/spotbugs/dependency-check to `verify` (`:288-320`,`:349-370`); add
  `jacoco:check` with a floor (from S7 remeasure, a few points below measured).
- **S7** remeasure coverage in the Maven-25 container; update `CLAUDE.md`
  (`:39,:102,:286,:379`) + `project-documentation/00-system-overview.md`; set the `jacoco:check` floor.
- **S8** `S3StorageIntegrationTest.java:45` `localstack:latest` → `:2.3`; pin the Redis ITs' image
  (digest where practical).
- **S9** `Dockerfile:4,16` — pin base images by `@sha256:`; add `maven-failsafe-plugin`, rename
  `*IntegrationTest` → `*IT`, run in `integration-test`/`verify`; remove the surefire IT exclusions
  (`pom.xml:341-345`) once migrated.

## Phase 6 — Cleanup nits
Findings: **S12 + remaining nits**.
- **S12** remove unused beans after `grep` confirms no refs: `StorageConfig`,
  `CorsConfig.corsConfigurationSource()`, `RedisConfig.redisTtlDuration()` (`:76-79`),
  `TimeoutConfig.timeoutRestTemplate()`; clarify/rename `RequestTimeoutFilter` (informational only).
- Stats read-modify-write `DroolsEngineService:121-123` → `ruleMetadata.compute(...)`.
- Duplicate-ruleId last-wins WARN in `RuleCompiler` (~`:59-64`).
- `RefreshEvent` unknown-enum forward-compat (log + skip, don't drop event).
- Security-posture wording honest in `README.md:647` + docs (B1 tracked-open; #28 enforced-on-prod).
- Document per-`rule_id` metric cardinality risk + optional tag-drop mitigation.
- Optional: scrub `/Users/sbc/` paths from `.ai-workspace/` if it stays public.

## Phase 7 — Full Docker end-to-end verification
- Full build + all tests in the Maven-25 container: `mvn -B clean verify` (gates + jacoco floor green).
- Extend `full-docker-test-plan.md` with new steps (below) and run the whole 11-step plan
  (incl. Step 9 Redis-off) with 0 ERROR lines.
- Update `full-docker-test-plan.md` step list + Pass/Fail table; write a `snap-memory`.

### New Docker E2E checks (added to full-docker-test-plan.md)
- **P1** admin without key → 401; with `admin-secret` → 200; docker profile + blank key → container
  fails to start.
- **P2** rotating `X-Client-Id`/`X-API-Key` no longer resets the bucket (remaining keeps
  decrementing); IP flood evicts oldest, doesn't 429-lock new clients.
- **P4** Redis stopped → `:8081/actuator/health` stays UP; `REDIS_ENABLED=false` → UP.
- **P5** `:8081` reachable only on `127.0.0.1`.
- **P7** `execute-rule` loop during a single-rule refresh → no error spike / no multi-second stall.
- **S1** foreign `drools:rule:ghost` key + bulk refresh → engine rule count unchanged.
- **S2** change S3 rule content + `refresh-rules/{id}` → cache serves new content.
- **S5** delete rule + `RULE_DELETED` → rule stops firing.

---

## Review Gate procedure (applied at the end of every phase)
1. Re-read the phase diff; tick the phase checklist.
2. Spawn an independent review subagent to check that phase's changed files for correctness,
   regressions, and missed edge cases (cite file:line).
3. Run the phase's Docker check (unit tests in the Maven-25 container; compose smoke for
   behavior-visible changes). **All testing in Docker — no local JDK/Maven.**
4. Fix anything the review surfaces; only then proceed to the next phase.

## Deferred — B1 (DRL sandbox RCE), separate work
Sound fix = load-time classloader allowlist on `KieBuilder`/`KieContainer` (refuse non-allowlisted
classes so obfuscated FQCNs can't load) + prefer `ExecutableModel` + disable MVEL dialect +
out-of-process/container isolation (seccomp, dropped caps, read-only FS) — because Java 25 removed
`SecurityManager` (JEP 486). Interim mitigation already delivered by Phase 2 P1 (admin refresh now
authenticated in deployable profiles): treat rule-store write access as code execution and lock it down.
