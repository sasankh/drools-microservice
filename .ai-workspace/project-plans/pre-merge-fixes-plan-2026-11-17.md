# Pre-merge Fixes — Plan & Checklist (PR #9 → develop)

| | |
|---|---|
| **Created** | 2026-11-17 |
| **Status** | 🟢 Phases 0–7 done and verified (2026-11-17); Phase 8 (push to PR #9, CI + OWASP, PR description) waiting on the user |
| **Work branch** | `pre-merge-fixes` (git worktree `.claude/worktrees/pre-merge-fixes`, based on `118f843`) |
| **Delivered to** | `re-review-fable-4` → updates **PR #9** (`re-review-fable-4` → `develop`); `amd-review-restart-11-17-2026` fast-forwarded to the same commit |
| **Release target** | v3.3.0 on `develop` (tag created by the user after merge) |
| **Owner** | Sasankh BC (decisions) / Claude (implementation) |
| **Related** | `production-readiness-fixes-plan.md` / `-checklist.md` (Aug 2026 hardening that this PR carries), `.ai-workspace/ai-summary/production-readiness-review-2026-07-14.md` |

> This single file holds both the plan (Part A) and the checklist (Part B), as requested.
> Tick checklist boxes as work lands, and refresh the "Last updated" line in the footer.

---

# Part A — Plan

## A1. Why this plan exists

PR #9 carries the Aug-2026 re-review hardening (admin fail-closed, IP-only rate limiting, halt→408, AbortPolicy→503, refreshLock, Redis TLS validator, CI and gates). `develop` / **v3.2.0** — the version that is actually released — has none of it.

Before merging, a full end-to-end test was run on **2026-11-17** on Linux with the real Docker Engine 29.10.0 (Compose v5.7.0) against branch HEAD `118f843`.

### A1.1 What passed (the application itself is healthy)

| Test | Result |
|---|---|
| `mvn -B clean verify` (Docker, Temurin 25.0.4.1) | BUILD SUCCESS — 536 tests, 0 failures; spotless/spotbugs/JaCoCo gates pass |
| `scripts/e2e-load-test.sh` functional steps | 17 rules uploaded/loaded (~1.1 s, 0 failed), smoke executions, hot reload, 5 rapid reloads, post-GC heap 46 MB (no leak) |
| Keep-alive stress, 20 workers × 300 s, reload at 120 s | 1,715,310 req, ~5,700 RPS, **0 errors, 0 wrong values**, p50/p99/p99.9 = 3.1/8.7/61.6 ms |
| Keep-alive stress, 50/100/200 workers × 60 s, reload every 15 s | ~1.05 M req, 0 errors, 0 wrong values, p99 22/46/92 ms; 12 reloads under load, all 17 loaded / 0 failed |
| ApacheBench `-k`, 50/200/400 connections | Peak ~8,760 RPS; at 400 connections 81 clean **503s** (pool saturation — designed load-shedding); immediate recovery |
| `full-docker-test-plan.md` Steps 1–12 + 21 value checks | **65 PASS / 4 FAIL**. All 4 FAILs were test artifacts or known-issue probes, not app defects. All 17 rules produced correct (stacked) values: VIP $100 → $72, loyalty → $76.5, bulk → $153, Black Friday → $135, holiday → $79.20, … |

### A1.2 What must be fixed before merging

| # | Problem | Evidence | Severity |
|---|---|---|---|
| **F1** | **Admin auth bypass.** `GET /admin;x/health` and `GET /%61dmin/health` **with no key returned 200** and full health data. `AdminAuthFilter.shouldAuthenticate` (`api/filter/AdminAuthFilter.java:~107`) matches the raw `request.getRequestURI().startsWith("/admin/")`, but Spring MVC routes the decoded, `;`-stripped path. This also reaches `POST` refresh / `memory/gc`. The same raw-URI matching is in `RateLimitingFilter.shouldApplyRateLimit` (`startsWith("/execute-rule")`, e.g. `/%65xecute-rule` skips the limiter) and `RequestSizeValidationFilter` (`contains("/execute-rule")`). | Live probe on 2026-11-17; `//admin/health` → 404 (not exploitable) | **Critical** — defeats the Aug-2026 P1 fix |
| **F2** | **Fresh clone crash-loops on Linux.** `docker-compose.yml` bind-mounts `./gc-logs` and `./heap-dumps`. They are gitignored, so Docker creates them **root-owned**. The app runs as `appuser` (uid 1000), and `-Xlog:gc*:file=/tmp/gc-logs/gc.log` makes the JVM exit: *"Error opening log file … Permission denied … Could not create the Java Virtual Machine"*. The container then restart-loops. macOS Docker Desktop hides this because its bind mounts are user-owned. Also present on v3.2.0. | First e2e run failed at Step 1; reproduced from the container logs | **High** — the documented quick start fails on Linux |
| **F3** | **`scripts/e2e-load-test.sh` reports a false PASS.** Each bash worker runs one new `curl` per request. Linux has 28,232 ephemeral ports (32768–60999) and a 60 s TIME_WAIT, so the ports ran out at **28,205 requests (~30 s)**. Under `set -euo pipefail`, the failing `HTTP=$(curl …)` kills each worker silently without counting an error. The script then printed "0 errors … ALL CHECKS PASSED" for a test that stopped after 30 s. Also: 4 of its 6 load payloads don't trigger the rule they name (e.g. `age` instead of `customerAge`); it needs the host `aws` CLI; and it sed-patches the tracked `docker-compose.yml`. | Success counter frozen at 28,205 from t=30 s to t=300 s | **High** — the merge gate itself is unreliable |
| **F4** | **CI is red.** (a) OWASP dependency-check fails on CVSS ≥ 7 CVEs in spring-boot 3.5.3, spring-core/web 6.2.8, tomcat-embed-core 10.1.42, netty 4.1.122, jackson-databind 2.19.1, kotlin-stdlib 1.9.25 (log4j-api 2.24.3 at 6.9). (b) All 3 Testcontainers ITs fail with *"Could not find a valid Docker environment"* on the `ubuntu-26.04` runner. Testcontainers 1.21.2 is effective, because the Boot BOM is imported first and overrides the 1.21.3 property; 1.21.4 is the release that adds Docker 29 support. (c) The workflow triggers on `main`, which doesn't exist (default branch is `develop`). (d) `-Dsurefire.failIfNoSpecifiedTests=false` would hide a renamed IT. | CI run 44209307946 (2026-11-18 00:37 UTC) | **High** — can't merge red |

### A1.3 Decisions confirmed with the user (2026-11-17)

1. **Branch:** do the work on an isolated branch and deliver it to `re-review-fable-4` so **PR #9 updates in place**. `amd-review-restart-11-17-2026` is fast-forwarded to match.
2. **F2:** **named Docker volumes + Dockerfile-created, `appuser`-owned directories**, instead of host bind mounts.
3. **F4 dependencies:** **Spring Boot 3.5.16 + targeted overrides**. Spring Boot 4 migration is a separate future plan.
4. **Test tooling:** **commit both harnesses** — a repaired `scripts/e2e-load-test.sh` (Python keep-alive load generator with value checks) and a new `scripts/run-docker-test-plan.sh` that automates `full-docker-test-plan.md`.
5. **Plan format:** one file (this one) with plan + checklist, dated.

### A1.4 Ground rules for execution

- All work happens in the worktree. The user's main checkout is not modified.
- Docker commands use the real engine: `export DOCKER_HOST=unix:///var/run/docker.sock`. The session's default `DOCKER_HOST` points at Podman.
- Maven runs in Docker: `docker run --rm -v "$PWD":/app -w /app -v "$HOME/.m2":/root/.m2 maven:3.9-eclipse-temurin-25 mvn -B …`. There's no host Java 25.
- Each phase ends with a **gate**: `mvn -B clean verify` green, plus the phase-specific verification. The phase is committed only after its gate passes.
- No scope creep. Anything else found goes in the backlog (A11).

---

## A2. Phase 0 — Setup ✅

- Worktree: `git worktree add .claude/worktrees/pre-merge-fixes -b pre-merge-fixes 118f843`. It's hidden from the main checkout via `.git/info/exclude`, so no repo file changes.
- Write this file.
- **Stop for the user's go-ahead** before Phase 1.

---

## A3. Phase 1 — F1: request-path bypass (security)

### A3.1 Design
Two layers, matching the defense-in-depth style used elsewhere:

1. **Reject ambiguous paths up front (primary control).** New `api/filter/StrictRequestPathFilter` (`OncePerRequestFilter`, `@Component`, `@Order(-1)`). Returns **400** with the standard error envelope (`error.code = "INVALID_INPUT"`, `message = "Malformed request path"`) when the raw `request.getRequestURI()` contains any of:
   - `;` (matrix / path parameters)
   - `%` (any percent-encoding)
   - `\` (backslash)
   - `//` (empty segment)
   - a `.` or `..` path segment (`/./`, `/../`, trailing `/.` or `/..`)

   This is safe because the API has no legitimate encoded paths. Rule IDs are `[A-Za-z0-9._-]`, the only path variable is `/admin/refresh-rules/{ruleId}`, and the query string isn't part of `getRequestURI()`. It is the same idea as Spring Security's `StrictHttpFirewall`, without adding Spring Security (ADR-006 rejected it).

2. **Match on the normalized path in every filter (defense in depth).** New `common/RequestPaths.normalizedPath(HttpServletRequest)`:
   - `UrlPathHelper.defaultInstance.getPathWithinApplication(request)` — URL-decodes and removes `;` content, the same view Spring MVC uses for routing.
   - Then collapse repeated `/`.

   Used by:
   - `AdminAuthFilter.shouldAuthenticate` → `path.startsWith("/admin/") || path.equals("/admin")`
   - `RateLimitingFilter.shouldApplyRateLimit` → normalized `startsWith("/execute-rule")` / `startsWith("/api/")`
   - `RequestSizeValidationFilter` → normalized `startsWith("/execute-rule")`, replacing the inconsistent `contains(...)`

3. **Filter order.** `SecurityHeadersFilter` moves from `@Order(-1)` to `@Order(-2)`, so the 400 still gets the 7 security headers. New order: SecurityHeaders(-2) → StrictRequestPath(-1) → AdminAuth(0) → RateLimiting(1) / RequestTimeout(1) → RequestSize → Logging.

4. **Error body.** Reuse the JSON shape already written by `AdminAuthFilter.writeUnauthorizedResponse` / `RateLimitingFilter` (`{rule_id:null, result:null, error:{code,message,details,timestamp}}`). Keep it a private helper in the new filter, the same as the other two filters do (no new shared class needed).

### A3.2 Files
- **New:**
  - `src/main/java/com/company/drools/api/filter/StrictRequestPathFilter.java`
  - `src/main/java/com/company/drools/common/RequestPaths.java`
- **Edit:**
  - `api/filter/AdminAuthFilter.java`
  - `api/filter/RateLimitingFilter.java`
  - `api/filter/RequestSizeValidationFilter.java`
  - `api/filter/SecurityHeadersFilter.java` (order only)
- **Tests (new):**
  - `src/test/java/com/company/drools/api/filter/StrictRequestPathFilterTest.java`
  - `src/test/java/com/company/drools/common/RequestPathsTest.java`
  - `src/test/java/com/company/drools/integration/PathBypassIntegrationTest.java`
- **Tests (edit):**
  - `AdminAuthFilterTest` (normalized-path cases, `/admin` exact)
  - `RateLimitingFilterTest` (`/%65xecute-rule`-style cases are limited via normalization)
  - `RequestSizeValidationFilterTest` (the existing test that pins `contains` for `/api/execute-rule` → update to the new `startsWith` semantics)

### A3.3 Test cases
- `StrictRequestPathFilterTest` (parameterized):
  - **Rejected → 400:** `/admin;x/health`, `/admin/health;jsessionid=1`, `/%61dmin/health`, `/admin/%2e%2e/admin/health`, `/execute-rule;x`, `/%65xecute-rule`, `//admin/health`, `/admin//health`, `/admin/./health`, `/admin/../admin/health`, `/admin\health`.
  - **Allowed (chain called):** `/execute-rule`, `/admin/health`, `/admin/refresh-rules/pricing.discount.simple`, `/admin/memory/info`, `/actuator/health`, `/`.
  - The body has `error.code=INVALID_INPUT`, content type JSON, and the chain is not called.
- `RequestPathsTest`: decoding (`/%61dmin/x` → `/admin/x`), `;` stripping, `//` collapse, plain paths unchanged.
- `PathBypassIntegrationTest` (real embedded Tomcat — the true regression guard; MockMvc does not reproduce Tomcat's raw-URI behaviour):
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("local")`, properties `drools.admin.api-key=test-key`, `management.server.port=0`, `redis.enabled=false`.
  - Uses `java.net.http.HttpClient` with `URI.create(...)` so raw paths are sent unmodified.
  - **Expect 400:** `/admin;x/health`, `/%61dmin/health`, `/admin/health;x`, POST `/%61dmin/refresh-rules`, POST `/%61dmin/memory/gc`, POST `/%65xecute-rule`.
  - **Expect 401:** `/admin/health` with no key, and with a wrong key.
  - **Expect 200:** `/admin/health` with the key; POST `/execute-rule` with a valid body.
  - **Expect:** the 7 security headers are present on the 400.
  - No Docker needed, so it runs in default `mvn test` and CI.
  - It is the first test that boots the full context. Startup should be ~5–10 s (local profile, in-memory rules).

### A3.4 Docs
- `project-documentation/36-architecture-decision-records.md`: **ADR-024 — Strict request-path handling (reject ambiguous paths + normalized matching)**. Context: live bypass. Rejected options: Spring Security (ADR-006), normalization only. Update the index.
- Filter order and new filter in: `00-system-overview.md`, `04-architecture.md`, `13-rate-limiting-and-throttling.md`, `14-security-architecture.md`, `15-admin-authentication.md`, `12-error-code-catalog.md` (new 400 trigger).
- `SECURITY.md`: note the path-normalization control.

### A3.5 Gate
- `mvn -B clean verify` green, coverage ≥ 88/74.
- Live: rebuild the stack, then:
  - `curl` `/admin;x/health`, `/%61dmin/health`, POST `/%61dmin/refresh-rules`, POST `/%61dmin/memory/gc`, POST `/%65xecute-rule` → **400**
  - `/admin/health` → 401 without the key, 200 with it
  - `/execute-rule` → 200
- Commit: `fix(security): reject ambiguous request paths and match filters on normalized path (ADR-024)`.

---

## A4. Phase 2 — F2: Linux startup (gc-logs / heap-dumps)

### A4.1 Design
- `Dockerfile` (runtime stage, before `USER appuser`):
  `RUN mkdir -p /tmp/gc-logs /tmp/heap-dumps && chown appuser:appgroup /tmp/gc-logs /tmp/heap-dumps`
- `docker-compose.yml` `app.volumes`: replace `./heap-dumps:/tmp/heap-dumps` and `./gc-logs:/tmp/gc-logs` with **named volumes** `heap-dumps:/tmp/heap-dumps` and `gc-logs:/tmp/gc-logs`, and declare both under top-level `volumes:`. On first use, a named volume copies the image directory's contents and ownership, so `appuser` can write on any host OS.
- Included small fix in the same file (low risk, same area): `ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]`. With `exec`, SIGTERM reaches the JVM, so `server.shutdown: graceful` and executor drain actually run on `docker stop`.
- Check `scripts/docker-compose.loadtest.yml`, `-disabled.yml`, and `-multi.yml` for the same bind mounts and apply the same change if present.

### A4.2 Docs (host-folder instructions change)
- `tail -f gc-logs/gc.log` → `docker compose exec app tail -f /tmp/gc-logs/gc.log`
- `ls -lh heap-dumps/` → `docker compose cp app:/tmp/heap-dumps ./heap-dumps && ls -lh heap-dumps/`
- Files: `CLAUDE.md` (memory monitoring section), `README.md`, `project-documentation/07-docker-and-compose.md`, `25-memory-monitoring-guide.md`, `30-runbooks-and-monitoring.md`, `31-troubleshooting.md`. Add a short "Linux note" explaining why named volumes are used.
- `scripts/lib/heap-dump.sh` already uses `docker cp` — confirm it still works.

### A4.3 Gate
- Fresh `git archive` copy with **no** `gc-logs/` or `heap-dumps/` on the host: `docker compose up -d --build` → app **healthy**. `docker compose exec app ls -l /tmp/gc-logs/gc.log` exists and is growing.
- `docker compose stop app` → app log shows graceful shutdown (Tomcat "Commencing graceful shutdown" / executor shutdown lines), and the stop takes less than the 10 s kill timeout.
- `mvn -B clean verify` green.
- Commit: `fix(docker): use named volumes and image-owned dirs for gc-logs/heap-dumps; exec JVM in entrypoint`.

---

## A5. Phase 3 — F4a: dependency CVEs (`pom.xml`)

### A5.1 Version facts (checked on Maven Central, 2026-11-17)
- Spring Boot latest 3.5.x = **3.5.16** (published 2026-06-25, likely the final 3.5 OSS release). Its BOM manages:
  - Spring Framework 6.2.19 (latest 6.2.x)
  - Tomcat 10.1.55 (latest 10.1.62)
  - Netty 4.1.135 (latest 4.1.141)
  - Jackson 2.21.4 (latest 2.21.8)
  - Kotlin 1.9.25
  - Log4j 2.24.3
  - Testcontainers **1.21.4** (Docker 29 support)
  - Micrometer 1.15.12, Logback 1.5.34, Lettuce 6.6.0
- Resilience4j **2.4.0** has **no Kotlin dependency** (2.3.0's `resilience4j-core` pulls `kotlin-stdlib-jdk8`).
- dependency-check-maven latest 12.x = 12.2.2.

### A5.2 Changes
1. `spring.boot.version` 3.5.3 → **3.5.16**.
2. Add overrides **above** the `spring-boot-dependencies` import in `<dependencyManagement>`. The Boot BOM is imported, not a parent, so property overrides (e.g. `<tomcat.version>`) have no effect; the first declaration wins.
   - `org.apache.tomcat.embed:tomcat-embed-core`, `tomcat-embed-el`, `tomcat-embed-websocket` → **10.1.62** (explicit entries)
   - `io.netty:netty-bom` **4.1.141.Final** (import)
   - `com.fasterxml.jackson:jackson-bom` **2.21.8** (import)
   - `org.apache.logging.log4j:log4j-bom` **2.26.1** (import)
3. `resilience4j.version` 2.3.0 → **2.4.0** (removes kotlin-stdlib).
4. `micrometer-registry-cloudwatch2`: remove the explicit `${micrometer.version}` (1.14.7) and the property, so Boot manages a matching 1.15.12 and the version skew goes away.
5. Remove the `testcontainers-bom` import and `testcontainers.version` property. Boot 3.5.16 manages Testcontainers 1.21.4, and the separate BOM was being overridden anyway.
6. `dependency-check-maven` 12.1.1 → **12.2.2**.
7. Keep AWS SDK 2.34.0, Drools 10.2.0, and Lettuce unless the OWASP run flags them.

### A5.3 Verification
- `mvn -B dependency:tree -Dincludes=org.jetbrains.kotlin` → **empty**.
- `mvn -B dependency:list` shows: spring-* 6.2.19, spring-boot 3.5.16, tomcat-embed-* 10.1.62, netty-* 4.1.141.Final, jackson-* 2.21.8, log4j-* 2.26.1, testcontainers 1.21.4, micrometer-* 1.15.12.
- `mvn -B clean verify` green. Drools 10.2.0 with Jackson 2.21 / Spring 6.2.19 is the main compatibility risk, and the full suite plus the E2E cover it.
- OWASP: run CI `workflow_dispatch` on the pushed branch (Phase 8), or locally if an NVD API key is available.

### A5.4 Residual-CVE rule
Anything still flagged at CVSS ≥ 7 is handled in this order:
1. Pin a fixed patch if one exists.
2. Suppress in `dependency-check-suppressions.xml` **only** with a written justification that the CVE doesn't apply (e.g. vulnerable module not used), including the CVE id, package, and reason.
3. Otherwise record it as a **blocker for the user to decide**: accept and document, or start the Spring Boot 4 plan.

The likely residual is **Spring Framework 6.2.19**, the last 6.2 OSS patch; CVEs published after 2026-06 may only be fixed in Spring 7 / Boot 4.

### A5.5 Commit
`build(deps): Spring Boot 3.5.16 + Tomcat/Netty/Jackson/Log4j patch pins; Resilience4j 2.4.0 drops kotlin-stdlib`.

---

## A6. Phase 4 — F4b–d: CI

### A6.1 Changes
- `.github/workflows/ci.yml`:
  - `on.push.branches` and `on.pull_request.branches` → `[ develop, main ]`. Keep `schedule` and `workflow_dispatch`.
  - Add a top-level `permissions: { contents: read }`.
  - Integration-test step: remove `-Dsurefire.failIfNoSpecifiedTests=false`, so a renamed or missing IT class fails the build.
  - Dependency-check job: keep it skipped on PRs. Its comment should mention `develop`.
- `src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java`: replace the local `buildJsonSerializer()` (which still calls `activateDefaultTyping`) with the production serializer from `config/RedisConfig.java` (`createRuleJsonSerializer`, widened to package-private/static if needed), so the IT checks the real wire format. Remove the dead `awaitNoop` helper.
- Testcontainers 1.21.4 comes from Phase 3 (Boot 3.5.16). No other change is needed for Docker 29.

### A6.2 Gate
- Locally, on Docker Engine 29, run the 3 Testcontainers ITs. They need the host Docker socket and host networking for mapped ports:
  ```bash
  docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$PWD":/app -w /app -v "$HOME/.m2":/root/.m2 maven:3.9-eclipse-temurin-25 \
    mvn -B test -Dtest='S3StorageIntegrationTest,RedisCachedStorageIntegrationTest,RedisPubSubIntegrationTest'
  ```
  → **20 tests pass** (10 + 4 + 6).
- `mvn -B clean verify` green.
- Commit: `ci: trigger on develop, least-privilege token, fail on missing ITs; Redis IT uses production serializer`.
- **User action (optional):** add a repo secret `NVD_API_KEY` (free from NVD) so the OWASP job runs in minutes instead of a long rate-limited download.

---

## A7. Phase 5 — F3: test tooling

### A7.1 `scripts/lib/e2e_load.py` (new, Python 3 stdlib only)
- CLI: `e2e_load.py --workers N --duration S [--reload-at S | --reload-every S] [--host] [--port] [--admin-key] [--min-rps R] --label L`.
- Keep-alive `http.client` connections, so there's no ephemeral-port exhaustion. Reconnects on error and **counts every exception** (nothing dies silently).
- A fixed set of payloads, each with an expected-value predicate. Stacking with all 17 rules loaded:

  | Rule | Input | Expected |
  |---|---|---|
  | simple | `{amount:150}` | 135 |
  | vip | `{customerType:"VIP", amount:100}` | 72 |
  | bulk | `{quantity:15, amount:200}` | 153 |
  | first-time | `{isFirstTimeCustomer:true, amount:100}` | 85.5 |
  | loyalty | `{loyaltyMember:true, amount:100}` | 76.5 |
  | blackfriday | `{promotionCode:"BLACK2024", amount:200}` | 135 |
  | holiday | `{isHolidaySeason:true, amount:100}` | 79.2 |
  | standard shipping | `{shippingType:"standard", weight:0.5}` | cost 5.99 |
  | express | `{shippingType:"express", weight:3, amount:150}` | free shipping |
  | age | `{customerAge:15}` | REJECTED |
  | credit | `{creditScore:720, requestedAmount:10000}` | Good / 5000 |
  | bundle | items price 50+60, all with stockLevel | final 99 |
  | email | `{email:"alice@company.com", customerType:"internal"}` | VALID |
  | temporal | 2026-05-10 → 2026-12-31 | ACTIVE / 235 |

- Measures: totals, HTTP status histogram, wrong-result count per rule, client exceptions, p50/p95/p99/p99.9/max latency, requests per 10 s window (stall detection), reload results, rules loaded after, heap after.
- Prints one JSON summary line and **exits non-zero** if any gate fails:
  - non-200 > 0 (503 tolerated only with `--allow-503`)
  - wrong results > 0
  - exceptions > 0
  - RPS < `--min-rps`
  - any 10 s window with 0 requests
  - rules loaded after ≠ expected

### A7.2 `scripts/e2e-load-test.sh` (rework)
- Start the stack with `docker compose -f docker-compose.yml -f scripts/docker-compose.loadtest.yml up -d --build`. The overlay already sets `DROOLS_RATE_LIMITING_ENABLED=false`, so the sed patch and restore trap on the tracked `docker-compose.yml` go away.
- Wait on the app's Docker healthcheck (`docker inspect … State.Health.Status == healthy`) instead of only actuator.
- Drop the host-side `aws` and `init-localstack.sh` run; the LocalStack ready hook already uploads the rules. Count objects with `docker compose exec -T localstack awslocal s3 ls s3://local-rules/ --recursive | grep -c '\.drl$'` → 17.
- Keep: refresh (17 / 0), smoke tests (now with payloads that really fire and value checks), hot-reload test, 5 rapid reloads + GC check.
- The load step calls `python3 scripts/lib/e2e_load.py --workers 20 --duration "$DURATION" --reload-at 120 --min-rps 200`, and the script's exit code follows it.
- Flags: `--duration S` (default 300), `--workers N` (default 20), `--no-teardown`, `--skip-build`.
- Requirements line: `docker` (Engine ≥ 24, Compose v2), `curl`, `jq` *or* `python3`, `bc` (drop it if no longer used). No AWS CLI.
- Note at the top: on Linux with Podman installed, export `DOCKER_HOST=unix:///var/run/docker.sock` to use Docker Engine.

### A7.3 `scripts/run-docker-test-plan.sh` (new)
An automated `full-docker-test-plan.md`, productionized from the 2026-11-17 runner:
- Steps 1–12 with the same IDs, plus:
  - X1 value checks (21)
  - live rate-limit spoof test (1,100 requests with rotating `X-Client-Id`/`X-API-Key` → exactly 1,000 × 200 / 100 × 429)
  - runaway rule → 408 while the service stays responsive
  - RULE_DELETED 17 → 16 → 17
  - **12.7 path bypass** (expects 400)
- Fixes over the 2026-11-17 runner:
  - 8.2 greps the full app log, not `--tail`.
  - The email check sends `customerType`.
  - Known-issue probes are `INFO`, not `FAIL` (items without price, 405/415).
  - Step 10 ignores log lines caused by the plan itself (runaway timeout, probes).
  - It runs in place, with `trap` restoring `docker-compose.yml` if Step 9 has patched it. Prefer a compose override file for Redis-off instead of sed, so nothing tracked is modified.
- Output: a PASS/FAIL/INFO table, a summary `PASS=n FAIL=m`, and a non-zero exit on any FAIL. Optional `--keep-stack`.

### A7.4 Docs for the tooling
- `full-docker-test-plan.md`:
  - 5.4 → expect `400 INVALID_INPUT`
  - `docker-compose` → `docker compose`
  - Remove the macOS `brew services stop redis` lines (keep a generic "free port 6379" note)
  - Step 4 payloads that fire their rules, with expected values
  - Add Step 12.7 path bypass
  - 8.2 uses the full log
  - Fix the Notes: 8081 is loopback-only; admin key is required on docker/prod
  - Header line: "Automated by `scripts/run-docker-test-plan.sh`"
- `scripts/README.md` and `README.md` "Testing → Quick E2E & Load Test": new flags, no AWS CLI, Linux `DOCKER_HOST` note, how to run the test-plan script.

### A7.5 Gate
- `bash -n` on both scripts.
- `scripts/e2e-load-test.sh --duration 60` passes locally.
- `scripts/run-docker-test-plan.sh` passes locally (full run in Phase 7).
- `mvn -B clean verify` green.
- Commit: `test(e2e): keep-alive value-checking load harness; automate full-docker-test-plan; fix false-pass`.

---

## A8. Phase 6 — Docs & bookkeeping
- `CLAUDE.md`:
  - New change-log entry at the top, **"Pre-merge fixes (2026-11)"**: F1–F4, ADR-024, named volumes, deps, CI, test tooling.
  - Update the versions in "Tech Stack" and "Health Status".
  - Update the test counts (re-count from the `mvn test` summary).
  - Fix the stale Docker-validation curl (`9081/admin/health` → `9080/admin/health`).
  - Fix the 7th-sample pattern note (`forall`, not "accumulate-with-collect").
- `README.md`: versions, Testing section (A7.4), Linux note.
- `project-documentation/03-tech-stack.md`: version table (Boot 3.5.16, Spring 6.2.19, Tomcat 10.1.62, Netty 4.1.141, Jackson 2.21.8, Resilience4j 2.4.0, Testcontainers 1.21.4, Micrometer 1.15.12, dependency-check 12.2.2; Kotlin removed).
- `project-documentation/28-testing-guide.md`: new tests; IT counts (20 Testcontainers + plain ITs incl. `PathBypassIntegrationTest`); both scripts.
- `project-documentation/00-system-overview.md`: "Numbers worth knowing" (test counts, ADR count 024, filter order).
- `SECURITY.md`: `main` → `develop`.
- This file: tick items, update Status and the footer date.
- Commit: `docs: pre-merge fixes — versions, ADR-024, testing, Linux notes`.

---

## A9. Phase 7 — Full verification (gate before pushing)
Run from a **fresh `git archive` copy** of the final commit (no host `gc-logs`/`heap-dumps`, real Docker Engine):

1. `mvn -B clean verify` → BUILD SUCCESS: all unit tests + `PathBypassIntegrationTest`, spotless, spotbugs, JaCoCo ≥ 88 % instruction / 74 % branch.
2. Testcontainers ITs on Docker 29 (A6.2 command) → 20/20 pass.
3. `scripts/e2e-load-test.sh` (full 300 s) → PASS. 0 errors, 0 wrong values, RPS ≥ floor, no stall window, 17/17 rules.
4. `scripts/run-docker-test-plan.sh` → all PASS (INFO allowed).
5. Stress: `e2e_load.py` at 50 / 100 / 200 workers × 60 s with `--reload-every 15` → 0 errors / 0 wrong; plus `ab -k -c 400 -t 45` → only 503s under saturation, immediate recovery.
6. Live bypass probes (A3.5) → 400 / 401 / 200 as specified.
7. `docker compose stop app` → graceful shutdown logged.
8. `git status` clean in the worktree; the user's main checkout is unchanged.

Record the results (numbers) in Part B §B7 and in the PR description.

---

## A10. Phase 8 — Deliver to PR #9
1. All phase commits are on `pre-merge-fixes`.
2. Push: `git push origin pre-merge-fixes:re-review-fable-4`. This is a fast-forward of PR #9's head, no force push.
3. Fast-forward the local `amd-review-restart-11-17-2026` to `pre-merge-fixes` and push it to keep the mirror branch in sync.
4. CI: the push triggers the `build` job (on PR); also run `workflow_dispatch` on `re-review-fable-4` for the OWASP job. Triage residual CVEs per A5.4 **with the user**.
5. Update PR #9's title and description. Scope: Aug-2026 hardening + pre-merge fixes F1–F4; test evidence (A1.1 + Phase 7 numbers); residual-risk notes (B1 sandbox deferred, backlog link).
6. **User:** review → merge to `develop` → tag **v3.3.0**.
7. Afterwards: remove the worktree (`git worktree remove .claude/worktrees/pre-merge-fixes`) and drop the `.git/info/exclude` line if wanted.

---

## A11. Out of scope — backlog (logged so nothing is lost)
Found during the 2026-11-17 review and E2E. To be planned after the merge:

- **Design:** `rule_id` doesn't select a rule — every loaded rule fires on every request (stacking, output-key collisions, cross-rule NPE on `items` without `price`).
- **Security:** B1 — the DRL sandbox is bypassable (fully-qualified names, string concatenation + reflection). `RedisSecurityValidator` accepts `rediss://:@host`. The S3 endpoint allowlist only warns. Admin auth fails closed only for `prod`/`docker`.
- **Rule-loading robustness:**
  - A partial or empty S3 read silently drops rules from the engine.
  - One bad DRL means 0 rules at startup.
  - An invalid `RULE_SOURCE` falls back to the demo rules.
  - A KieModule leaks if `updateToVersion` fails.
- **Execution:**
  - An infinite loop inside one consequence leaks a worker thread forever.
  - Timeouts and 503s are missing from the execution metrics.
  - Hitting the firing cap is silent.
- **Resilience:**
  - The S3 circuit breaker doesn't count `RuleStorageException`.
  - S3 writes and listings aren't wrapped by the breaker.
  - A transient S3 error on delete reports "not found".
- **API status codes:**
  - 405 / 415 / 406 / invalid path variable → 500.
  - Unexpected errors → 400.
  - `/admin/health` is always 200.
  - Bulk refresh is always 200.
  - Single-rule refresh 404 has an empty body and leaves the rule loaded.
  - F-032 trim.
- **Config:**
  - Profile literals shadow most env vars.
  - `RULE_EXECUTION_TIMEOUT_SECONDS` is dead.
  - Auto-refresh isn't implemented.
  - `burst-size` is unused.
- **Pub/sub:** engine failures are ignored; coalescing can drop events; there's no catch-up; `publishDelete` is unused.
- **Logging:**
  - logback rolling-policy misconfiguration and duplicate output in prod/docker.
  - `LogSanitizer` NPE on null values.
  - User-Agent goes into MDC unsanitized.
  - The correlation-ID filter runs last.
  - Drools "package/folder mismatch" WARN on every reload.
- **Tooling:**
  - JMeter `run-load-test.sh` harness (no admin key; multi-replica can't start).
  - `setup-dev-environment.sh` / `scripts/test-localstack.sh` (`ruleId`, `success`, v1 compose, Redis `curl`).
  - `set-java-env.sh` is macOS-only.
- **Cleanup:** unused Lombok, `MetricsConfig` meters, `storageExecutor`, `BaseIntegrationTest`; stale `application-test.yml`.
- **Bigger items:** Spring Boot 4 migration; kjar precompilation; Redis/pub-sub staged rollout + operator migration; full (non-quick) Phase 9 load test; 1-hour soak.
- **Docs staleness:** OpenAPI spec; docs teaching `eval()`; cookbook field names (`type` vs `customerType`); image size 347 MB → ~438 MB; "14 ITs".

---

# Part B — Checklist

### B0. Phase 0 — Setup
- [x] Worktree `.claude/worktrees/pre-merge-fixes` on branch `pre-merge-fixes` at `118f843`
- [x] Worktree hidden from the main checkout via `.git/info/exclude` (no repo change)
- [x] This plan + checklist written (`pre-merge-fixes-plan-2026-11-17.md`)
- [x] **User go-ahead to start Phase 1** (given 2026-11-17)

### B1. Phase 1 — F1 path bypass
- [x] `common/RequestPaths.normalizedPath()` (context-path strip + `;` strip + `UriUtils.decode` + `//` collapse)
- [x] `api/filter/StrictRequestPathFilter` — 400 `INVALID_INPUT` on `;` `%` `\` `//` `.`/`..`; `@Order(-1)`; standard error body
- [x] `SecurityHeadersFilter` → `@Order(-2)` (headers present on the 400)
- [x] `AdminAuthFilter.shouldAuthenticate` uses the normalized path; covers `/admin` exactly
- [x] `RateLimitingFilter.shouldApplyRateLimit` uses the normalized path
- [x] `RequestSizeValidationFilter` uses the normalized path + `startsWith("/execute-rule")`
- [x] `StrictRequestPathFilterTest` (rejected + allowed cases)
- [x] `RequestPathsTest`
- [x] `AdminAuthFilterTest` / `RateLimitingFilterTest` / `RequestSizeValidationFilterTest` updated
- [x] `PathBypassIntegrationTest` (real Tomcat): 400 / 401 / 200 cases + security headers on 400
- [x] ADR-024 + index; docs 00, 04, 12, 13, 14, 15; `SECURITY.md`
- [x] Gate: `mvn -B clean verify` green (coverage ≥ 88/74)
- [x] Gate: live probes → 400; `/admin/health` 401 / 200; `/execute-rule` 200
- [x] Commit

### B2. Phase 2 — F2 Linux startup
- [x] Dockerfile: appuser-owned `/tmp/gc-logs`, `/tmp/heap-dumps`
- [x] Dockerfile: `exec java $JAVA_OPTS -jar app.jar`
- [x] `docker-compose.yml`: named volumes `gc-logs`, `heap-dumps` (declared)
- [x] `scripts/docker-compose.loadtest*.yml` checked/updated
- [x] Docs: CLAUDE.md, README, 07, 25, 30, 31 (new commands + Linux note); `scripts/lib/heap-dump.sh` still OK
- [x] Gate: fresh copy (no host dirs) → healthy; `gc.log` written in the container
- [x] Gate: `docker compose stop app` → graceful shutdown logged
- [x] Gate: `mvn -B clean verify` green
- [x] Commit

### B3. Phase 3 — F4a dependencies
- [x] `spring.boot.version` 3.5.16
- [x] Tomcat 10.1.62 explicit entries above the Boot BOM
- [x] `netty-bom` 4.1.141.Final above the Boot BOM
- [x] `jackson-bom` 2.21.8 above the Boot BOM
- [x] `log4j-bom` 2.26.1 above the Boot BOM
- [x] Resilience4j 2.4.0
- [x] `micrometer-registry-cloudwatch2` version removed (Boot-managed); `micrometer.version` property removed
- [x] `testcontainers-bom` import + property removed (Boot → 1.21.4)
- [x] dependency-check-maven 12.2.2
- [x] Gate: `dependency:tree -Dincludes=org.jetbrains.kotlin` empty
- [x] Gate: `dependency:list` shows the expected versions
- [x] Gate: `mvn -B clean verify` green
- [x] Commit
- [ ] OWASP run (Phase 8 CI dispatch) — residuals triaged with the user per A5.4

### B4. Phase 4 — F4b–d CI
- [x] Triggers `push` / `pull_request` on `develop` + `main`
- [x] `permissions: contents: read`
- [x] `-Dsurefire.failIfNoSpecifiedTests=false` removed
- [x] `RedisCachedStorageIntegrationTest` uses the production serializer; dead helper removed
- [x] Gate: 3 Testcontainers ITs pass locally on Docker 29 (20 tests)
- [x] Gate: `mvn -B clean verify` green
- [x] Commit
- [ ] (User, optional) `NVD_API_KEY` repo secret

### B5. Phase 5 — F3 test tooling
- [x] `scripts/lib/e2e_load.py` (keep-alive, value checks, stall detection, exit codes)
- [x] `scripts/e2e-load-test.sh` reworked (overlay, no host aws, real payloads + values, healthcheck wait, flags, real gates)
- [x] `scripts/run-docker-test-plan.sh` (Steps 1–12 + X1 + rate-limit spoof + 408 + RULE_DELETED + 12.7 bypass; no tracked-file edits; exit code)
- [x] `full-docker-test-plan.md` updated (5.4, compose v2, no brew, Step 4 payloads, 12.7, 8.2, notes, header)
- [x] `scripts/README.md` + `README.md` Testing section
- [x] Gate: `bash -n` both scripts; `e2e-load-test.sh --duration 60` PASS; `run-docker-test-plan.sh` PASS
- [x] Gate: `mvn -B clean verify` green
- [x] Commit

### B6. Phase 6 — Docs
- [x] CLAUDE.md change log + versions + counts + 9081→9080 + `forall` note
- [x] README versions
- [x] `03-tech-stack.md` versions
- [x] `28-testing-guide.md` tests / ITs / scripts
- [x] `00-system-overview.md` numbers
- [x] `SECURITY.md` `main` → `develop`
- [x] Commit

### B7. Phase 7 — Full verification (record numbers)
- [x] `mvn -B clean verify`: tests **591** / 0 failures (spotless, spotbugs, jacoco:check all pass); coverage **90.7 % / 80.1 %**
- [x] Testcontainers ITs: **20 / 20** on Docker Engine 29.10
- [x] `e2e-load-test.sh` 300 s: requests **1,727,453**, RPS **~5,750**, errors 0, wrong 0, p99 **8.6 ms** (p999 53 ms); hot reload at 120 s 17/17 in 1.1 s; retained heap 46 → 47 MB after 5 reloads
- [x] `run-docker-test-plan.sh`: PASS **58** / FAIL 0 / INFO 3 (known backlog issues: bundle-rule NPE, GET/text-plain → 500)
- [x] Stress 50/100/200 × 60 s + reloads: errors 0, wrong 0; 4,312 / 5,737 / 5,680 RPS; p99 **86.7 / 45.6 / 91.9 ms**; 12 reloads all 17/17
- [x] `ab -k -c 400`: RPS **8,545** (384,537 req, 45 s); 58 non-2xx = 503 load-shed (pool-saturated log lines present); p50 8 ms, p99 1,078 ms at saturation; next request after → 200
- [x] Live bypass probes: `/admin;x/health`, `/%61dmin/health`, POST `/%61dmin/refresh-rules`, POST `/%61dmin/memory/gc`, POST `/%65xecute-rule` → 400; `/admin/health` no key → 401
- [x] Graceful shutdown logged (`docker compose stop app` took 1 s)
- [x] Worktree clean; main checkout unchanged

### B8. Phase 8 — Deliver
- [ ] Pushed `pre-merge-fixes` → `re-review-fable-4` (fast-forward)
- [ ] `amd-review-restart-11-17-2026` fast-forwarded + pushed
- [ ] CI `build` job green (unit + gates + 20 ITs)
- [ ] CI OWASP job (dispatch) green, or residuals accepted by the user
- [ ] PR #9 title/description updated (scope, evidence, residual risks, backlog link)
- [ ] (User) merged to `develop`
- [ ] (User) tag v3.3.0
- [ ] Worktree removed

---

_Plan created: 2026-11-17 · Last updated: 2026-11-17 (Phases 0–7 complete and verified; Phase 8 — push, CI/OWASP, PR description — pending user permission)_
