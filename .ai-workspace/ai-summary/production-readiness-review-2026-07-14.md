# Fresh-Eyes Production / Public-Readiness Review — drools-microservice

**Date:** 2026-07-14 · **Reviewer:** independent pass (conclusions from code + live behavior, not project docs)
**Method:** 5-dimension static review + my own source verification of every reported finding + full live verification in Docker (compose stack, functional API, security behavior, Redis cache/pub-sub, Redis-off regression, memory, unit test suite).
**Repo state:** left unmodified (`git status` clean; only gitignored `target/` and an ephemeral LocalStack PoC rule that was deleted).

> Bottom line: the engineering is genuinely capable — the container-swap state machine, circuit-breaker fallback, and pub/sub fan-out all work live, and 549 unit tests pass. But **it is not ready to go public or to production as-is.** There is a live-proven remote-code-execution path through the rule sandbox, several fail-open security defaults, and a legal blocker (no LICENSE). Fix the Blockers and Production Risks first.

Severity legend: **BLOCKER** (must fix before public/prod) · **HIGH** · **MEDIUM** · **LOW**. Findings marked **[live-proven]** were reproduced against the running service.

---

## 1. Blockers — before the repo goes public / to production

### B1 · DRL sandbox is bypassable to arbitrary code execution **[live-proven]**
**Files:** `src/main/java/com/company/drools/core/engine/DrlSanitizer.java` (whole design; `:99-101`, `:40-75`, `:159-175`), invoked only from `RuleCompiler.java:40-51`.

`DrlSanitizer` is a set of regex/substring checks over DRL text, but a DRL consequence is compiled as ordinary Java, and **Java needs no `import` to use a fully-qualified class name**. The import allow/block lists (`checkImports`) only inspect lines matching `IMPORT_PATTERN`, so they never see class usage in the rule body. `BLOCKED_CLASS_REFERENCES` is a tiny fixed set that omits `File`, `FileWriter`, `InitialContext`, etc.

**Proven live:** I uploaded this rule to S3 and refreshed it — it **compiled through the sanitizer** and executed, reading the container filesystem:
```
then
  $data.put("etc_hostname_exists", new java.io.File("/etc/hostname").exists());
  $data.put("root_entry_count", new java.io.File("/").list().length);
```
Response: `{"etc_hostname_exists":true,"root_entry_count":20,...}` — despite `java.io.` being on the sanitizer's *blocked* import list. `Runtime.exec` is reachable the same way via string-concat/comment tricks (`"Run"+"time"`, `Class/**/.forName`).

**Threat model:** anyone who can write to the S3 rule bucket — or reach the admin single-rule refresh, which is unauthenticated by default (see P1) — gets code execution inside the ECS task. The docs advertise this sanitizer as the security boundary; it is not one.

**Fix:** a denylist over Turing-complete input cannot be made sound. Enforce at load time with a **classloader that refuses to load any non-allowlisted class** (installed on the `KieBuilder`/`KieContainer`), disable the MVEL dialect / prefer compiled `ExecutableModel` rules, and add process/container isolation for execution (Java 25 has removed `SecurityManager`, so you cannot rely on it). Keep the regex sanitizer only as a cheap first filter — and if kept, strip comments and normalize whitespace before matching, and reject any FQCN of a non-allowlisted package.

### B2 · No LICENSE file despite an MIT claim (legal blocker for public release)
**Files:** `README.md:1212` → "licensed under the MIT License - see the [LICENSE](LICENSE) file"; there is **no `LICENSE` file** and no `git`-tracked license anywhere.

A public repo with no license means *all rights reserved* — nobody may legally reuse it, directly contradicting the stated MIT intent, and the README link 404s. **Fix:** add a real `LICENSE` (MIT, matching the claim) before publishing, or correct the claim. While here, add `SECURITY.md` and `CONTRIBUTING.md` (absent) — expected for a public repo, and `SECURITY.md` matters given the findings above.

---

## 2. Production risks — would cause incidents or security exposure in a real deployment

### P1 · Admin API fails OPEN when `ADMIN_API_KEY` is unset **[live-proven]**
**Files:** `AdminAuthFilter.java:32,63-71`; `application.yml:62` (`api-key: ${ADMIN_API_KEY:}`); shipped `docker-compose.yml` sets no key. No Spring Security backstop exists (`pom.xml` has no `spring-security`) — the servlet filters are the entire perimeter.

`shouldAuthenticate()` returns `false` when the key is blank, so with no key **every `/admin/*` route is public.** Proven live against the shipped compose (no key set):
- `POST /admin/refresh-rules` → 200, forces a full S3 re-read + recompile → unauthenticated DoS (~46s stall at 1000 rules).
- `POST /admin/memory/gc` → 200, triggers stop-the-world GC on demand → unauthenticated DoS.
- `GET /admin/rules`, `/admin/health`, `/admin/memory/info` → 200, internal topology/heap disclosure.

Admin paths are also excluded from rate limiting, so these are neither authed nor throttled when the key is missing.

**Compounded by a documentation footgun:** `.env.example:110-113` tells operators to set `ADMIN_API_USERNAME` / `ADMIN_API_PASSWORD` / `ADMIN_API_ENABLED` — **none of which the code reads** (it uses `ADMIN_API_KEY` → header `X-Admin-API-Key`). An operator who "secures admin" per the example leaves it wide open.

**Fix:** fail *closed* — refuse to start (or deny all `/admin/*`) when the key is blank in non-local profiles; correct `.env.example` to `ADMIN_API_KEY`.

### P2 · Rate limiter is keyed on an unauthenticated header → full bypass + lockout DoS **[live-proven]**
**Files:** `RateLimitingFilter.java:69-94`; `RateLimitingConfig.java:86-91`.

Docs claim the limiter "uses `request.getRemoteAddr()` only" — **false.** `getClientIdentifier` keys on `X-API-Key` (`:74`), `Authorization: Bearer` (`:80`), then `X-Client-Id` (`:87`), with `getRemoteAddr()` only as a 4th fallback. These headers are unauthenticated on the public `/execute-rule` API.

**Proven live:** fixed `X-Client-Id` decremented the bucket (999→998→997); a **fresh `X-Client-Id` per request reset remaining to 999 every call** → unlimited throughput. Second attack: rotating the header fills `maxClients=10000` buckets (entries expire only after 1h idle), after which every genuinely-new client IP gets 429 — the memory cap becomes a denial-of-service against real users.

**Fix:** for an unauthenticated public API, key on `getRemoteAddr()` only; if proxy IPs are needed, parse `X-Forwarded-For` from a configured trusted-proxy list; LRU-evict oldest bucket instead of rejecting new clients.

### P3 · Rule-execution timeout is not enforced — thread leak + total bypass under load
**Files:** `RuleExecutor.java:59-68`; `ThreadPoolConfig.java:77`.

`future.cancel(true)` (`:66`) does **not** interrupt a running `fireAllRules` (documented `CompletableFuture` behavior), and the `KieSession` closes only when the internal method returns. A consequence with `then while(true){} end` (passes the sanitizer) runs forever on a `rule-exec-` thread; ~50 such calls exhaust the pool (max 50). Worse, the rejection policy is `CallerRunsPolicy`, so under saturation the task runs *synchronously on the Tomcat thread* and `future.get(timeout)` returns already-complete — the timeout is not applied at all exactly when load is highest.

**Fix:** enforce the timeout with `KieSession.halt()` from a watchdog (and dispose the session on the timeout path); reconsider `CallerRunsPolicy` for the execution pool (prefer `AbortPolicy` → 503).

### P4 · `/actuator/health` flips to 503 on any Redis outage **[live-proven]**
**Files:** `pom.xml:107-109` (non-optional `spring-boot-starter-data-redis`); no `management.health.redis.enabled=false`; `application.yml` always sets `spring.data.redis.url`.

Spring Boot autoconfigures the Redis health indicator regardless of the app's own `REDIS_ENABLED` flag. **Proven live:** with Redis stopped, `GET :8081/actuator/health` → **503 DOWN**, while the service was fully functional (`/admin/health` → 200 and `execute-rule` served correctly via circuit-breaker fallback). Any ALB/ECS probe pointed at `/actuator/health` would kill healthy tasks on a transient Redis blip; with `REDIS_ENABLED=false` and no Redis deployed, actuator is permanently DOWN. (The container's own healthcheck uses `/admin/health`, which is correctly gated — so this hides in local testing.)

**Fix:** `management.health.redis.enabled: ${REDIS_ENABLED:false}` (or exclude `RedisAutoConfiguration` when disabled).

### P5 · Actuator metrics/info exposed unauthenticated on 8081 **[live-proven]**
**Files:** `application.yml:18-19` (`exposure.include: health,metrics,info`); no auth on the management context.

`GET :8081/actuator/metrics` and `/actuator/info` return 200 with no auth (JVM/memory/pool internals readable). `docker-compose.yml` publishes 8081. **Fix:** bind 8081 to an internal interface only, or secure the management context.

### P6 · Redis ships with no auth / no TLS + broad polymorphic deserialization
**Files:** `application.yml:47` (`redis://…`, no password); `docker-compose.yml:110` (`--requirepass ""`); `RedisConfig.java:61-73` (`activateDefaultTyping(NON_FINAL)`, `allowIfBaseType("java.util")`).

This is the project's own still-open finding **#28**. Cached `Rule` objects are deserialized with permissive default typing; an unauthenticated/shared/compromised Redis broadens the gadget surface the project's earlier Jackson-RCE hardening tried to close. **Fix:** require `rediss://` + auth in prod (validate the URL scheme on the `prod` profile); tighten the `BasicPolymorphicTypeValidator` to the concrete model classes.

### P7 · Single-rule refresh recompiles the whole corpus *under the write lock* — reintroduces the refresh stall
**Files:** `DroolsEngineService.java:248-258` (holds `writeLock`) → `loadRules` compiles at `:168` under that lock.

`loadRules` deliberately compiles *outside* the write lock so reads aren't blocked. But `loadOrReplaceRule` takes the write lock first, so the reentrant `loadRules` compiles the **entire** rule set while holding it — the code comment even admits "reads block until the merge completes." This is the hot path: pub/sub `RULE_REFRESHED` (`RuleRefreshSubscriber.java:141`) and admin single-rule refresh (`AdminController.java:495`). At ~46s/1000-rule compile, one rule change stalls **all** executions on every instance for the whole window — exactly the outage the non-blocking design claims to prevent. **Fix:** serialize refreshes with a separate mutex; hold `writeLock` only for `updateToVersion` + map swap.

---

## 3. Should fix — correctness, robustness, and public-repo hygiene

- **S1 · `getAllRules()` returns a cache superset → resurrects deleted rules.** `RedisCachedRuleStorage.java:150-176` returns every `keyPrefix*` key, never filtered to `expectedIds`. A rule deleted from S3 but still cached (≤15-min TTL) is recompiled back into the engine on startup / bulk-refresh (both paths skip pre-invalidation). Fix: `existing.keySet().retainAll(expectedIds)`. *(Flagged independently by both storage reviewers.)*
- **S2 · Refresh doesn't bypass the cache → can silently serve stale DRL cluster-wide.** `refreshRule` does best-effort `DEL` (failures swallowed) then a read-through `GET`; if the DEL blips, it re-reads stale text, recompiles it, publishes, and every sibling reads the same stale entry — all while returning 200 "success". Fix: refresh must read the delegate directly and write-through `SET`.
- **S3 · No CI exists at all.** No `.github/workflows` or any pipeline. Tests, coverage, SpotBugs, Spotless, OWASP dependency-check, and the 14 integration tests all run only when a human types the command — a public "production-ready" repo with zero automated proof on any commit. Fix: GitHub Actions (Linux, Temurin 25): `mvn verify` + `spotless:check` + `spotbugs:check` + the 3 Testcontainers ITs + `dependency-check:check`, gating PRs.
- **S4 · Quality gates are configured but bound to no phase.** In `pom.xml`, `spotless`/`spotbugs`/`dependency-check` have **no `<executions>`**, so `mvn verify` runs none of them; `failBuildOnCVSS≥7` never triggers. JaCoCo has no `check` goal/threshold, so coverage is never enforced. Fix: bind each to `verify`; add a `jacoco:check` floor.
- **S5 · `RULE_DELETED` is a no-op on subscribers.** `RuleRefreshSubscriber.java:149-155` only logs — a rule deleted on one instance keeps firing on siblings. Fix: implement engine removal, or treat delete as a bulk-refresh trigger.
- **S6 · Pub/sub listener runs the full recompile on the dispatch thread, unbounded, no coalescing.** `RedisConfig` sets no `taskExecutor`; each bulk event does `getAllRules()` + ~46s recompile on the listener thread; bursts pile up. Fix: dedicated bounded single-thread executor + event coalescing.
- **S7 · Coverage claim is overstated and stale.** Freshly measured on the current tree under Java 25: **91.4% instruction / 81.2% branch** (line 90.5%), vs the published **96.2% / 89.7%**. The docs admit the number is a "pre-modernization baseline, not re-run" — it should be republished and enforced.
- **S8 · Testcontainers images unpinned.** `S3StorageIntegrationTest.java:45` uses `localstack/localstack:latest` (compose pins `2.3`); the Redis ITs use floating `redis:7-alpine`. Non-reproducible integration tests. Fix: pin exact tags/digests.
- **S9 · Build reproducibility / lifecycle hygiene.** Dockerfile base images float (`maven:3.9-eclipse-temurin-25`, `amazoncorretto:25-alpine-jdk`) — pin by digest. Integration tests run through Surefire, not Failsafe, so `*IntegrationTest` runs in the unit phase; add `maven-failsafe-plugin` and rename to `*IT`.
- **S10 · Setter-injected, non-volatile `delegate` in `RedisCachedRuleStorage`** (`:88`, set from `StorageFactory.java:44`, re-written per admin request). Reference visibility isn't guaranteed → possible spurious `IllegalStateException`. Fix: `volatile`, and let the factory be the sole constructor (drop `@Component` from the decorator).
- **S11 · Storage-layer path-traversal guard in `S3RuleStorage.java:321-327` is dead code** (runs `contains("../")` *after* `.`→`/` replacement, when no `..` can remain). `LocalFileStorage.java:156-164` does it correctly (`normalize()` + `startsWith(root)`). HTTP entry points are covered by `RuleIdValidator`, but the pub/sub `getRule(event.ruleId())` path is unvalidated — make the storage guard real by validating the *raw* ruleId first.
- **S12 · Dead / misleading config.** `StorageConfig` (unused `@ConfigurationProperties`), `CorsConfig.corsConfigurationSource()` (no Spring Security consumes it), `RedisConfig.redisTtlDuration()` bean, `TimeoutConfig.timeoutRestTemplate()` bean — all unused. `RequestTimeoutFilter` only sets informational headers and logs; it does **not** enforce a request timeout despite the name.

---

## 4. Nits

- README has two `### 3.` sections (`:101`, `:118`); README:653 says rate limiting is "per remote IP" (it isn't — see P2); `RULE_SOURCE` is documented as `local` but `.env` uses `memory`.
- **Security tally is inconsistent and now understated.** Public docs say "39/42"; the internal backlog says "40/42" (remaining #28, #38). This review adds new/reopened items (B1, P3), so restate the security posture honestly rather than by a fixed count.
- `.scannerwork/.sonar_lock` is **tracked** and `.scannerwork/` is not in `.gitignore` — untrack it and add the ignore before publishing.
- Non-atomic execution-stats read-modify-write under the shared read lock (`DroolsEngineService.java:122-123`) → metrics drift under concurrency. Use `ruleMetadata.compute(...)`.
- Duplicate `ruleId` in a load is silently last-wins (`RuleCompiler.java:59-64`) — no warning.
- `RefreshEvent` documents forward-compat for unknown event types but doesn't implement it (unknown enum → whole event dropped as a deserialize failure).
- Admin key comparison uses `String.equals` (`AdminAuthFilter.java:53`) — not constant-time. Use `MessageDigest.isEqual`.
- Per-`rule_id` metric tags are unbounded cardinality at 1000+ rules — can bloat the registry/scrape.
- **`.ai-workspace/` (kept public by your choice):** the local username/path `/Users/sbc/` appears ~23× across `snap-memory/` and `compact-logs/`, and `compact-logs/` are raw session-continuation logs (internal working narrative). None are secret, but consider scrubbing the absolute paths before publishing.

---

## 5. Verified working — the "are things actually working?" answer

Everything below was exercised live in Docker this session and passed:

- **Build & stack:** `docker compose up --build` → all three services (app, localstack, redis) healthy; the app builds and runs under Java 25 in the multi-stage image.
- **Unit tests:** **549 tests, 0 failures, 0 errors, BUILD SUCCESS** (run in the Java-25 Maven image; docs say 548 — trivial drift). The earlier "stack trace" is a benign JaCoCo instrumentation warning on a Drools-generated lexer, not a failure.
- **Functional API:** `execute-rule` valid → 200 with correct computed result (`discount:10.0, amount:90.0`); unknown rule → 404 `RULE_NOT_FOUND`; malformed JSON → 400 `INVALID_INPUT`; missing `rule_id` → 400. Clean, structured errors with **no stack-trace/internal-path leakage**.
- **Security headers:** all 7 present on responses (`X-XSS-Protection: 0` is the correct modern value; CSP `default-src 'none'`).
- **Redis cache:** read-through cache populated (17 keys under `drools:rule:*`, ~15-min TTL, JSON-serialized).
- **Pub/sub:** single (`RULE_REFRESHED`) and bulk (`RULE_REFRESHED_BULK`) events publish on `drools:rule:events` with the source instance UUID.
- **Redis-off regression (the resilience story works):** with Redis stopped, `execute-rule` kept serving; `refresh-rules` fell back to S3 (loaded 17, 0 failed); the circuit breaker **opened** (65% failure rate, 34 rejected calls) and latency dropped 11.2s → 1.0s once open; on restart it recovered to **CLOSED** and the pub/sub listener **resubscribed** (event received post-restart).
- **Memory:** stable across 10 refresh cycles (128 MB → 48 MB after GC); loaded rule count steady at 17 — no leak observed.
- **Integration behaviors:** the 3 Testcontainers ITs couldn't run inside the Maven container (no Docker environment reachable — the documented macOS-DinD limitation, an environment issue not a test failure), but I verified the **same behaviors live** against the compose stack: real S3 round-trips via LocalStack, Redis CB fallback on stop, and pub/sub fan-out.
- **One thing to double-check (not a hard finding):** the cache **hit rate stayed 0%** through all live testing. It's largely explained (execution reads the compiled `KieContainer`, not storage; refresh appears to invalidate-then-miss), but it's worth confirming the read-through cache actually produces hits on any path — otherwise it's effectively write-only, echoing the dead-cache layer this project already deleted once.

---

## Suggested fix order for the follow-up session

1. **B2** (add LICENSE) and the hygiene nits that gate publishing (`.scannerwork` untrack, README section numbers, `.env.example` admin vars) — fast, unblock going public.
2. **P1 / P2 / P5 / P4** — the fail-open/fail-loud security defaults; small, high-impact changes.
3. **B1** — the sandbox. The real fix (classloader allowlist + isolation) is larger; scope it deliberately. Until it lands, treat write access to the rule store as equivalent to code execution and lock it down.
4. **P3 / P7** — enforce the execution timeout; separate the refresh mutex from the write lock.
5. **P6, S1, S2, S5, S6** — Redis auth/TLS and the cache-coherence / cross-instance correctness bugs.
6. **S3 / S4** — stand up CI and bind the quality gates, so all of the above stays fixed.
