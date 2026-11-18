# Checklist: Documentation full sync

**Plan:** [`doc-full-sync-plan.md`](doc-full-sync-plan.md) · **Created:** 2026-08-20 · **Completed:** 2026-08-20
**Summary:** [`../UPDATE_SUMMARY.md`](../UPDATE_SUMMARY.md)

## Prep
- [x] Write plan + checklist to `.ai-workspace/project-plans/`
- [x] Verify counts via commands (java files, test files, unit tests, ITs, env vars) — recorded below

## Theme sweeps (apply across all affected docs)
- [x] T1 Rate limiter IP-only (+ trust-proxy, LRU eviction, log line) — docs 00,01,02,04,08,09,10,11,12,13,14,26,35,37,README
- [x] T2 Admin auth: header on all `/admin/*` curls + fail-closed wording + 401 body + constant-time — docs 04,08,09,10,12,14,15,25,30,31,32,33,35,README,CLAUDE
- [x] T3 Prod Redis `rediss://`+auth enforced — docs 05,06,08,09,14
- [x] T4 Docker/deploy (healthcheck key, digest pins, 8081 loopback, ADMIN_API_KEY) — docs 03,04,06,07
- [x] T5 Execution resilience (halt timeout, AbortPolicy→503, refreshLock, listener executor) — docs 04,12,14,26,29,30
- [x] T6 Path traversal via RuleIds — docs 14,18

## Per-doc specifics
- [x] 02 remove deleted `StorageConfig.java` reference
- [x] 08 replace invented env vars / fictional application.yml with real names; RULE_SOURCE default
- [x] 28 coverage 90.1/78.4; delete "no threshold"; fix per-file counts; remove `StorageConfigTest`; IT pins + halt note
- [x] 27 + 37 spotless/spotbugs/jacoco bound to verify (+ floor)
- [x] 36 add ADR-017…023; update ADR-006 + ADR-010; refresh future-ADR list + index
- [x] 37 add glossary terms (ServiceUnavailableException, RuleIds, RedisSecurityValidator, trust-proxy, refreshLock)
- [x] 38 rewrite "39/42" pitfall (honest wording); refresh example counts
- [x] 14 + 16 surface B1 (sandbox not a sound boundary)
- [x] CLAUDE.md change-log entry; cache/ comment; drop 39/42; counts
- [x] README response-shape verified vs DTOs + aligned

## Counts (verified 2026-08-20)
- [x] Java source files: **61**  · test files: **48**  · unit tests: **536** (surefire; 534 annotations)  · Testcontainers ITs: **14** (3 classes)  · env vars: **68**
- [x] coverage 90.1%/78.4%, jacoco floor 88/74 — applied everywhere

## Verification
- [x] grep retired claims (`multi-tier`, `X-Client-Id`, `39/42`, `548`, `96.2%`, `redis://prod`) → 0 current-fact (only intentional historical/changelog/honest-posture context remains)
- [x] counts re-verified against docs — internally consistent (61/48/536/14/68, 90.1/78.4)
- [x] Out-of-scope stragglers fixed: `project-documentation/README.md` manifest row, `api-reference/openapi.yml`, doc 00 Numbers table, 01/04 test counts, 02 coverage line, doc 10 Live-verification block
- [x] `.ai-workspace/UPDATE_SUMMARY.md` written

## Known residual (documented in summary, not a correctness defect)
- [ ] Keyless `/admin/*` curls in local-dev/troubleshooting sections of docs 06,09,11,12 (each doc states the header requirement in its reference tables; snippets framed as local/dev where admin is open). README illustrative script blocks likewise. Left intentionally — see UPDATE_SUMMARY.md.
