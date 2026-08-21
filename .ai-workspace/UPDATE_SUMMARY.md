# Documentation full-sync — summary

**Date:** 2026-08-20 · **Branch:** `re-review-fable-1`
**Plan:** [`project-plans/doc-full-sync-plan.md`](project-plans/doc-full-sync-plan.md) · **Checklist:** [`project-plans/doc-full-sync-checklist.md`](project-plans/doc-full-sync-checklist.md)

## Scope
Full sync of the 40-doc `project-documentation/` corpus + `README.md` + `CLAUDE.md` against current
code, following this session's production-readiness, SonarQube, and CI changes. Three parallel audits
cataloged drift against the code; three editing passes applied fixes by doc-group; a final verification
sweep reconciled counts and retired-claim residue.

## Canonical counts (verified 2026-08-20, source of truth)
| Metric | Value | Command |
|---|---|---|
| Java source files | **61** | `find src/main/java -name '*.java' \| wc -l` |
| Test files | **48** | `find src/test/java -name '*.java' \| wc -l` |
| Unit tests | **536** (surefire) | `mvn test` summary line (534 `@Test`/`@ParameterizedTest` annotations; parameterized expansion → 536 executions) |
| Testcontainers ITs | **14** across 3 classes | surefire-excluded on macOS-DinD; run on Linux CI |
| Env vars | **68** | added `DROOLS_RATE_LIMITING_TRUST_PROXY` |
| Coverage | **90.1% instr / 78.4% branch** | remeasured 2026-08-19 on Java 25; `jacoco:check` floor 88/74 bound to `verify` |
| Error codes | **10** · Sample rules | **17** · Sandbox lists **20/19/12/19** (already correct) |

## Theme sweeps applied (recurring wrong claim → corrected across all affected docs)
1. **Rate limiter is IP-only** (`request.getRemoteAddr()` → `ip:{addr}`) — replaced every "multi-tier
   client ID (`X-API-Key`→`Bearer`→`X-Client-Id`→IP)" description; documented optional left-most
   `X-Forwarded-For` only under `trust-proxy=true`; LRU-evicts oldest bucket at capacity. Removed all
   advice to send app headers for a stable bucket. (13 rewritten; 00,01,02,04,08,09,10,11,12,14,26,35,37)
2. **Admin auth** — all `/admin/*` require `X-Admin-API-Key`; open only in local/dev, **prod/docker
   refuse to start** if the key is blank; constant-time `MessageDigest.isEqual`; 401 body =
   `error.code="UNAUTHORIZED"`. (04,08,09,10,12,14,15,25,30,31,32,33,35)
3. **Prod Redis** requires `rediss://` + auth via `RedisSecurityValidator` (fail-closed) — fixed prod
   `redis://…` examples that now crash on startup. (05,06,08,09,14)
4. **Docker/deploy** — healthchecks send the admin key; base images pinned by `@sha256`; 8081 published
   to `127.0.0.1`; compose sets `ADMIN_API_KEY`. (03,04,06,07)
5. **Execution resilience** — timeout `KieSession.halt()`s (→ 408); rule-exec pool `AbortPolicy` sheds
   load as 503 `ServiceUnavailableException`; `refreshLock` split from the write lock; dedicated
   single-thread `ruleRefreshListenerExecutor`. (04,12,14,26,29,30)
6. **Path traversal** via `common/RuleIds.requirePathSafe()` replacing dead inline `contains("../")`
   snippet; pub/sub is a 4th validated entry point. (14,18)

## Per-doc specifics
- **02** removed deleted `StorageConfig.java` reference; coverage line → remeasured 90.1/78.4.
- **08** replaced invented env vars / fictional `application.yml` with real names; `RULE_SOURCE` default `local`.
- **28** coverage → 90.1/78.4; deleted "no coverage threshold" (now floor at `verify`); per-file counts;
  removed invented `StorageConfigTest`; IT image pins + halt() note.
- **27, 37** spotless/spotbugs/jacoco now bound to `verify` (+ jacoco floor).
- **36** added ADR-017…023 for this session's decisions; updated ADR-006 + ADR-010; refreshed index.
- **37** added glossary terms: `ServiceUnavailableException`, `RuleIds`, `RedisSecurityValidator`,
  `trust-proxy`, `refreshLock`.
- **38** rewrote the "39/42 security findings" pitfall → honest two-tracked-open posture.
- **14/16** surfaced **B1** (DRL sandbox is not a sound boundary — FQCN needs no import; JEP 486 removed
  the SecurityManager) citing `SECURITY.md`.
- **CLAUDE.md** added a 2026-08-20 change-log entry; fixed `cache/` package comment (pub/sub only);
  dropped "39/42"; fixed counts.
- **README** verified `/execute-rule` + `/admin/health` response shapes against the DTOs and aligned.

## Final verification
- Retired claims (`39/42`, `548`, `96.2%`, `multi-tier`, `X-Client-Id` as advice, `redis://prod`) grep
  to **0 current-fact occurrences** — all remaining hits are intentional (the `38-for-ai-agents.md`
  honest-posture pitfall, dated changelog/ADR history, superseded-ADR preserved context, and the
  count-drift history in 28/38/39).
- Counts internally consistent across all docs (61/48/536/14/68, 90.1/78.4).
- Security posture reframed everywhere: "most findings addressed; **2 tracked-open**" — B1 (DRL-sandbox
  RCE, deferred) and #28 (Redis TLS/auth, enforced on prod).
- Out-of-scope files caught by the sweep and fixed: `project-documentation/README.md` manifest row and
  `project-documentation/api-reference/openapi.yml` (both carried stale `548`/`39/42`/`96.2%`).

## Known residual (documented, not a correctness defect)
- Keyless `curl …/admin/*` snippets remain in local-dev / troubleshooting sections of docs **06, 09,
  11, 12**. Each of those docs states the `X-Admin-API-Key` requirement in its reference tables, and
  the snippets are framed as local/dev (where admin is open). The canonical API doc (10) and its
  "Live verification" block were made fully consistent (all admin curls send the header). Left the
  local-dev troubleshooting snippets untouched to avoid mislabeling genuinely keyless local runs.
- README's long illustrative bash-script blocks (~585–1058) still show keyless admin loops; these are
  illustrative pseudo-scripts, not copy-paste reference commands.

## Notes
- Docs-only change — no build required. The underlying behaviors were Docker-verified earlier this
  session (production-readiness phases + CI). Nothing committed to git yet.
