# Plan: Full-sync project-documentation/ (40 docs) + README + CLAUDE.md with current code

**Created:** 2026-08-20 · **Branch:** `re-review-fable-1`
**Checklist:** [`doc-full-sync-checklist.md`](doc-full-sync-checklist.md) · **Summary (on completion):** `.ai-workspace/UPDATE_SUMMARY.md`

## Context
Full sync requested after this session's production-readiness + Sonar + CI changes. Three parallel
audits checked every doc (00–13, 14–29, 30–39 + README/CLAUDE) against current code. Goal: every doc
reflects current behavior; no invented metrics/names/counts (per `38-for-ai-agents.md`).

## Theme sweeps (recurring wrong claim → fix, with affected docs)

1. **Rate limiter is IP-only (P2), not multi-tier headers.** Replace "multi-tier client ID
   (X-API-Key→Bearer→X-Client-Id→IP)" with: keyed on `getRemoteAddr()`; optional left-most
   `X-Forwarded-For` only when `drools.rate-limiting.trust-proxy=true`; at capacity **LRU-evicts** the
   oldest bucket (not "rejects new clients"). Remove advice to send `X-API-Key`/`X-Client-Id` for a
   stable bucket. Log line → `"Rate limiter at capacity ({}), evicted least-recently-used client bucket"`.
   Docs: 13 (rewrite core), 11, 04, 01, 00, 02, 08, 09, 10, 12, 14 (Layer 3), 26 (C2/C3), 35, 37, README:656.

2. **Admin auth: all `/admin/*` require `X-Admin-API-Key`; fail-closed in prod/docker.**
   (a) Add the header (or use `:8081/actuator/health`) to keyless `/admin/*` curls: 25, 30, 31, 32, 33, 35.
   (b) "empty key = open" → "open only in local/dev; prod/docker **refuse to start** if blank":
       04, 08, 09, 10, 12, 14, 15, 30 (alert), 31, 35, README, CLAUDE.
   (c) 401 body = nested `error.code="UNAUTHORIZED"`, `error.message="Admin API key required"`: 12:182, 31.
   (d) Constant-time compare (`MessageDigest.isEqual`), delete "equals is acceptable": 15.

3. **Prod Redis requires `rediss://` + auth (RedisSecurityValidator) — fail-closed.** Fix prod
   `redis://…` examples that now crash; frame Redis auth/TLS as enforced-on-prod: 05, 06, 08, 09, 14.

4. **Docker/deploy details.** Healthchecks send the admin key; base images pinned by `@sha256`; 8081
   published to `127.0.0.1`; compose sets `ADMIN_API_KEY=admin-secret`: 03, 04, 06, 07.

5. **New execution resilience (P3/P7/S6).** Timeout `KieSession.halt()`s (not just cancel); rule-exec
   pool `AbortPolicy` → pool saturation = **503 `ServiceUnavailableException`** (2nd 503 path); separate
   `refreshLock` compiles off the write lock; dedicated single-thread `ruleRefreshListenerExecutor`.
   Docs: 12, 04, 14 (Layer 4), 26 (H1/E1/D), 29, 30.

6. **S11 path traversal via `common/RuleIds`.** Replace old inline `s3Key.contains("../")` snippet
   (removed dead code) with `RuleIds.requirePathSafe(rawRuleId)`; note the new pub/sub 4th entry point:
   14 (Layer 5), 18.

## Per-doc specifics
- **02:147** remove reference to deleted `StorageConfig.java`.
- **08** replace the invented env vars + fictional `application.yml` (S3_CONNECTION_POOL_MAX_SIZE,
  THREAD_POOL_*, RESILIENCE4J_CIRCUITBREAKER_*, REDIS_PASSWORD, LOG_FORMAT, …) with the real
  `application.yml` names; fix `RULE_SOURCE` default (`local`).
- **28** coverage → 90.1%/78.4%; delete "no coverage threshold" (now 88/74 at verify); fix ~16 per-file
  counts; remove invented `StorageConfigTest`; add IT image pins + halt() note.
- **27, 37** spotless/spotbugs/jacoco now bound to `verify` (+ jacoco floor), not "manual/no threshold".
- **36** add ADRs for the 7 session decisions (P1/P2/P3/P7/P6/S10/S3+S4); update ADR-006 + ADR-010
  in place; refresh "future ADRs" list + index.
- **37** add glossary terms: ServiceUnavailableException, RuleIds, RedisSecurityValidator, trust-proxy,
  refreshLock; fix JaCoCo/spotless/spotbugs entries.
- **38** rewrite the "39/42 security findings" pitfall (honest: #28 enforced on prod; B1 sandbox RCE
  deferred + in SECURITY.md); refresh example counts.
- **14/16** surface **B1** (sandbox not a sound boundary — FQCN needs no import) citing SECURITY.md.
- **CLAUDE.md** add session change-log entry; fix `cache/` package comment (pub/sub only); drop "39/42";
  fix test/file counts.
- **README** verify `/execute-rule` + `/admin/health` response shape against the DTOs; align with the
  `error:null` / `execution_time_ms` / `.components.*` shape used elsewhere.

## Counts to correct (verify each with a fresh command first)
Java source ~**61** (docs say 59); test files ~**48** (45/46); unit tests ~**536** (548 — settle with
`mvn test` summary; audits measured 534); integration tests **14**; env vars **68** (added
`DROOLS_RATE_LIMITING_TRUST_PROXY`); coverage **90.1%/78.4%**; jacoco floor **88/74**. Already correct:
error codes **10**, sample rules **17**, sandbox lists (20/19/12/19).

## Verification
1. Grep corpus for retired claims → **0** (outside changelog/historical): `multi-tier`, `X-Client-Id`,
   `39/42`, `redis://prod`, `no.*threshold`, keyless `curl .*localhost:8080/admin`.
2. Re-run count commands; every doc number matches.
3. Cross-references resolve; bump "Last verified/updated" dates.
4. Write `.ai-workspace/UPDATE_SUMMARY.md`. Docs-only — no build needed (behaviors already Docker-verified).
