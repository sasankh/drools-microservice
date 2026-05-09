# Security backlog — the 3 unaddressed findings (from "39 of 42")

> Personal reference. The "39/42" claim appears in 5+ places across the project corpus and at the top of `CLAUDE.md`, but no single page lists *which 3 are not closed*. This is that page.

**Date verified**: 2026-05-08 (against the linked source files)
**Authoritative source**: [`.ai-workspace/ai-summary/security-fix-plan.md`](../ai-summary/security-fix-plan.md)

---

## Quick answer

| # | Severity | Item | Real prod risk? |
|---|---|---|---|
| **#28 (H-6)** | **HIGH** | Redis without auth or TLS | **Yes — fix before production** |
| ~~**#30 (M-15)**~~ | ~~MEDIUM~~ | ~~Outdated dependencies~~ | **CLOSED 2026-05-09** — see ADR-013 / ADR-014 |
| **#38 (I-1)** | INFO | KieContainer disposal safety | No — already well-documented in code; counted differently across docs |

---

## Detail

### #28 (H-6) — Redis without authentication or TLS  ⚠️ PRODUCTION RISK

- **Severity**: HIGH
- **Files**: [`docker-compose.yml`](../../docker-compose.yml), [`application.yml`](../../src/main/resources/application.yml)
- **What's wrong**: `docker-compose.yml` runs Redis with `--requirepass ""` (empty password) and no TLS. The application connects with no credentials.
- **Why deferred**: Skipped per user — the dev stack is `127.0.0.1`-bound and treated as localhost-only. Documented at [`07-docker-and-compose.md:341`](../../project-documentation/07-docker-and-compose.md#L341).
- **Source**: [`security-fix-plan.md:136-137`](../ai-summary/security-fix-plan.md#L136)
- **What production needs**:
  - Set a Redis password (`REDIS_PASSWORD` env var; reference it in `application.yml`)
  - Enable TLS (Redis `tls-port` + cert config; client SSL options)
  - Bind Redis to a private network only — never publicly reachable
  - Audit `REDIS_ENABLED=true` deployments to confirm both are set before they ship

### #30 (M-15) — Outdated dependencies  ✅ **CLOSED 2026-05-09**

- **Severity**: MEDIUM
- **File**: [`pom.xml`](../../pom.xml)
- **What was wrong**: Dependency versions in `pom.xml` lagged current upstream.
- **How it was closed (2026-05-09)**: Bundled with the Java 17 → 25 stack modernization. Bumped Java, Spring Boot, Drools, Lombok, AWS SDK, Resilience4j, Micrometer, Testcontainers, and 5 Maven plugins to current latest stable in a single coordinated change. Verified via the full 584-test suite (Drools-dependent) on Java 25.
- **References**:
  - [ADR-013](../../project-documentation/36-architecture-decision-records.md#adr-013-java-17--25--spring-boot-modernization-2026-05-09) — Java + Spring Boot + plugins
  - [ADR-014](../../project-documentation/36-architecture-decision-records.md#adr-014-drools-8--10-migration-2026-05-09) — Drools 8 → 10
  - [`stack-modernization-plan.md`](stack-modernization-plan.md) — implementation plan
  - [`stack-modernization-checklist.md`](stack-modernization-checklist.md) — checklist tracking

**Updated tally**: 40 of 42 security findings closed (was 39/42). Remaining: #28 (Redis auth/TLS — production deploy concern) and #38 (KieContainer disposal — already documented).

### #38 (I-1) — KieContainer disposal safety

- **Severity**: INFO
- **File**: [`DroolsEngineService.java`](../../src/main/java/com/company/drools/core/engine/DroolsEngineService.java)
- **What was claimed**: Risk of double-disposal or use-after-dispose under concurrency.
- **Why effectively closed**: The fix plan marks this `[x]` because the existing code "already has well-documented lock discipline comments" — the verification was that the code was already correct, no change needed. It's counted in the 39/42 by the spreadsheet but in the "3 not closed" by the narrative summary at [`01-project-overview.md:60`](../../project-documentation/01-project-overview.md#L60).
- **Source**: [`security-fix-plan.md:178-179`](../ai-summary/security-fix-plan.md#L178)
- **What to do**: Nothing. If you ever rewrite the atomic-swap path (ADR-003), re-verify disposal ordering.

---

## The accounting (why 39/42 has fuzzy edges)

- **42 total** findings across 9 phases (3 Critical + 10 High + 15 Medium + 9 Low + 5 Info)
- **39 fixed in code** — Phases 1–9 closed all but #28 and #30
- **2 explicitly skipped** per user — #28 (Redis auth/TLS) and #30 (dependency updates)
- **1 doc-only** — #38 (KieContainer disposal); code was already correct, no change needed

The narrative says "39 fixed, 2 skipped, 1 doc-only" → "3 not closed" if you exclude doc-only from "closed." The summary table says "39/42" by counting #38 as done. Both are defensible. The real story: only #28 represents production risk.

---

## Where the "39/42" claim appears (so you can spot it in context)

- [`CLAUDE.md`](../../CLAUDE.md) — top banner
- [`README.md`](../../README.md) — Security Features section
- [`project-documentation/00-system-overview.md:134`](../../project-documentation/00-system-overview.md#L134) — Numbers worth knowing
- [`project-documentation/01-project-overview.md:60`](../../project-documentation/01-project-overview.md#L60) — only place that explains the 3
- [`project-documentation/14-security-architecture.md`](../../project-documentation/14-security-architecture.md)
- [`project-documentation/api-reference/openapi.yml:30`](../../project-documentation/api-reference/openapi.yml#L30)

---

## Pre-production checklist (the actionable distillation)

- [ ] Set `REDIS_PASSWORD` and configure Redis TLS for any non-localhost deployment
- [ ] Bind Redis to private network only (no public ingress)
- [ ] Run `mvn dependency-check:check` and triage CVEs
- [ ] Bump dependencies one family at a time, re-running `mvn test` after each
- [ ] Optional: re-verify KieContainer disposal if the atomic-swap path is rewritten

---

## Related

- Full per-phase fix log: [`.ai-workspace/ai-summary/security-fix-plan.md`](../ai-summary/security-fix-plan.md)
- Public security narrative: [`project-documentation/14-security-architecture.md`](../../project-documentation/14-security-architecture.md)
- Admin auth dev-mode bypass (separate operational risk, not in 42 findings): [`.ai-workspace/documentations/CODE_FINDINGS.md`](../documentations/CODE_FINDINGS.md) under F-?? (admin auth empty-key bypass)
