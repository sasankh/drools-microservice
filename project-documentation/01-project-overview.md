# 01 · Project Overview

| | |
|---|---|
| **Audience** | All readers — developers, architects, operators, external integrators, AI agents |
| **Purpose** | Answer "what is this project, why does it exist, and what does it do?" in five minutes |
| **Last verified against code** | 2026-05-24 (post-modernization, post-load-test, post-Redis-cache-+-pub/sub layer, post-Phase-9.4-hardening) |
| **Related docs** | [02-project-structure.md](02-project-structure.md), [03-tech-stack.md](03-tech-stack.md), [04-architecture.md](04-architecture.md), [10-api-reference.md](10-api-reference.md) |

---

## What this project is

The **Drools Rule Engine Microservice** is a small, focused Spring Boot service that executes business rules over JSON request data and returns the modified data. Rules are authored as `.drl` files (Drools Rule Language), stored in AWS S3, and hot-loaded into the running service. The service exposes a single rule-execution endpoint and a small set of admin/operator endpoints; everything else is configuration, caching, observability, and security.

In one sentence: **JSON in, rules fire, JSON out** — with the rules updatable independently of code deployments.

## Why it exists

Most production applications encode business rules directly in code: discount logic in checkout controllers, validation thresholds in form handlers, eligibility checks scattered across services. This is fast to ship initially but creates three structural problems:

1. **Business changes require code deploys.** A 10% promo becoming 12% on Black Friday should not require a sprint.
2. **Logic is hidden from non-engineers.** Pricing analysts, compliance officers, and risk managers cannot see or modify the rules they are responsible for.
3. **Rules are not auditable as a unit.** Logic spread across handlers cannot be reasoned about, tested, or versioned as a coherent set.

This service externalizes business rules into a dedicated execution layer. Engineering owns the runtime; rule authors (engineers, analysts, or AI tools using [the rule generation prompts](20-rule-generation-prompt.md)) own the rule files. Rules are deployed by uploading `.drl` files to S3 and calling `POST /admin/refresh-rules`.

## What it does (capabilities)

| Capability | How it works | Where it lives |
|---|---|---|
| **Execute a rule** | `POST /execute-rule` with `{"rule_id":"...","data":{...}}`. Service fires matching rules against the data Map and returns the modified data. | [`RuleExecutionController`](../src/main/java/com/company/drools/api/controller/RuleExecutionController.java), [`DroolsEngineService`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) |
| **Load rules from S3** | At startup or on `POST /admin/refresh-rules`, all `.drl` files under the bucket are fetched, sandbox-checked, and compiled. | [`S3RuleStorage`](../src/main/java/com/company/drools/storage/S3RuleStorage.java), [`RuleCompiler`](../src/main/java/com/company/drools/core/engine/RuleCompiler.java) |
| **Sandbox dangerous DRL** | Every rule is scanned by `DrlSanitizer` before compilation. Blocks `eval()`, dangerous classes (Runtime, ClassLoader, Thread, etc.), and unauthorized imports. | [`DrlSanitizer`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java); see [16-drl-sandboxing.md](16-drl-sandboxing.md) |
| **Cache compiled rules** | Compiled `KieBase` lives in a single long-lived `KieContainer` updated in place via `KieContainer.updateToVersion(ReleaseId)` on refresh (Drools 10 incremental-update pattern). When `REDIS_ENABLED=true`, `RedisCachedRuleStorage` decorates the base storage with a read-through cache of DRL text; `RuleRefreshPublisher`/`Subscriber` keeps multi-task ECS deployments in sync via pub/sub on `drools:rule:events`. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20). | [`RedisCachedRuleStorage`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) |
| **Authenticate admin endpoints** | When `ADMIN_API_KEY` is set, `/admin/*` requires `X-Admin-API-Key` header. Empty key = open in dev (warning logged). | [`AdminAuthFilter`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java); see [15-admin-authentication.md](15-admin-authentication.md) |
| **Rate limit clients** | Per-client multi-tier identification (X-API-Key → Bearer → X-Client-Id → IP). 1000 req/min, 10000 req/hour. Admin endpoints exempt. | [`RateLimitingFilter`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java); see [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) |
| **Set security headers** | 7 headers on every response (CSP, HSTS, X-Frame-Options, etc.). | [`SecurityHeadersFilter`](../src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java); see [14-security-architecture.md](14-security-architecture.md) |
| **Sanitize logs** | Sensitive fields (credit card, SSN, tokens, API keys) masked before log emission. | [`LogSanitizer`](../src/main/java/com/company/drools/common/LogSanitizer.java) |
| **Health, metrics, memory diagnostics** | `/admin/health`, `/admin/memory/info`, `/admin/thread-pools`, `/admin/info`, `/actuator/*`. Micrometer-instrumented. | [`AdminController`](../src/main/java/com/company/drools/api/controller/AdminController.java), [`MemoryController`](../src/main/java/com/company/drools/api/controller/MemoryController.java) |
| **Circuit-break flaky deps** | Resilience4j wraps S3 and Redis calls. Configured per-profile (stricter in prod). | [`CircuitBreakerConfig`](../src/main/java/com/company/drools/config/CircuitBreakerConfig.java); see [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) |

## Performance: targets vs reality

| Metric | Target | Measured | Source |
|---|---:|---:|---|
| Throughput | 100–1000 RPS | 45+ RPS observed in test (sustained) | Internal load tests |
| P99 latency (cached rule) | < 100 ms | 1–40 ms | `/admin/health` cache stats |
| P99 latency (cache miss) | < 500 ms | < 10 ms (LocalStack) | Test |
| Concurrent rules | 1000+ | Architecture supports | KieContainer holds all loaded rules |
| Cache hit rate | > 90% (multi-instance) | n/a in single-instance | Single-instance reads `loadedRules` directly; Redis hits accrue only when sibling tasks hit Redis after a publisher refresh. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20). |
| Startup time | < 60 s | ~1.3 s | Boot logs |
| Docker image size | < 400 MB | ~347 MB | `docker images` |
| Memory stability | Indefinite | ✅ Stable — 1 MB heap drift over 15-min mixed-workload soak with 98 refresh ops at 1000 rules | Load test 2026-05-10; see [39-load-test-findings.md](39-load-test-findings.md) |

## Production readiness

The service is **production-ready** as of 2026-02-26:

- **39 of 42 security findings closed** across 9 security phases.
- **548 unit tests** across 46 test files (down from 597 after deleting the dead `RuleCache` layer on 2026-05-20, then back up with `SCAN`-CB-wrap unit tests on 2026-05-24). 14 Testcontainers integration tests, surefire-excluded on macOS-DinD (run on Linux CI).
- **Docker integration test plan**: 30 checks across 9 steps, all passing as of last run.
- **Memory leak fixed** (2026-02-19, hardened 2026-05-10): KieContainer lifecycle now uses Drools 10's `updateToVersion` + explicit `KieRepository.removeKieModule(oldReleaseId)` cleanup. Verified leak-free under sustained refresh load (1 MB drift / 98 refreshes — see [39-load-test-findings.md](39-load-test-findings.md)).
- **Java 25 enforced** at build time via Maven Enforcer Plugin (bumped from Java 17 on 2026-05-09 — see [ADR-013](36-architecture-decision-records.md#adr-013-java-17--25--spring-boot-modernization-2026-05-09)).
- **Load tested at 1,000 rules** on 2026-05-10: P99=9 ms baseline, safe-RPS=500, 0 errors across ~197K execute requests. See [39-load-test-findings.md](39-load-test-findings.md). One architectural bug (LOADING-marker premature state) discovered + fixed during the run.

## Non-goals (what this is NOT)

- **Not a workflow engine.** Rules are evaluated in a single fire-all pass; this is not BPMN, not Camunda, not Temporal. If you need orchestration across services, use a workflow tool.
- **Not an event broker.** No subscriptions, no pub/sub. The service is a synchronous request/response API.
- **Not a complete Drools deployment.** Only the *traditional DRL subset* (Map-based pattern matching) is used. Rule units, OOPath, DataStream, decision tables — all unused. See [23-rule-language-reference.md](23-rule-language-reference.md) for what's intentionally unused.
- **Not a multi-tenant rule platform.** Single rule namespace per deployment. Multi-tenancy would require additional rule scoping (currently out of scope).
- **Not authoritative for primary auth.** `AdminAuthFilter` is *defense in depth* behind an upstream API gateway. For end-user authentication, use a real auth service.

## What's deferred (planned but not present)

| Item | Why deferred | Planned in |
|---|---|---|
| JMeter performance test suite | Closed 2026-05-10 — see `scripts/run-load-test.sh` orchestrator + [39-load-test-findings.md](39-load-test-findings.md) | Done |
| Redis auth/TLS (`requirepass`, TLS connector) | Skipped per user — not needed for current local/dev posture | Production deploy |
| Dependency version sweep | Closed 2026-05-09 via stack modernization (Java 17→25, Spring Boot 3.2.5→3.5.3, Drools 8.44.0→10.2.0) — see [ADR-013](36-architecture-decision-records.md#adr-013-java-17--25--spring-boot-modernization-2026-05-09) | Done |
| Rule versioning / rollback semantics | Currently relies on S3 versioning + manual refresh | Roadmap |
| A/B testing of rules | Not implemented — would require rule routing layer | Roadmap |
| Rule analytics dashboard | Out of scope for this service; consume metrics externally | Roadmap |
| GraphQL/gRPC API | REST-only by design | Not planned |
| SDK packages (Python, Java, JS) | Not yet authored | Future |

## How a rule actually executes (one paragraph for newcomers)

A client sends `POST /execute-rule` with a JSON body. Spring filters check headers, rate, size, and admin auth (in that order). The controller validates the request via `@ValidRuleId` and `@ValidRuleData` annotations. `DroolsEngineService` checks the rule's metadata is `ACTIVE` and obtains a fresh `KieSession` from the long-lived `KieContainer` (which holds the current `KieBase` for the entire rule set). `RuleExecutor` inserts the data Map, calls `fireAllRules(maxRuleFirings=10000)`, harvests the modified Map, and disposes the session. The result and timing return as JSON. Total path on a cached hit: typically 1–40 ms (P99=9 ms measured at 50 RPS with 1000 rules).

## How to use this corpus

| If you are... | Read these in order |
|---|---|
| **A new developer** | [32-getting-started.md](32-getting-started.md) → [27-development-setup.md](27-development-setup.md) → [04-architecture.md](04-architecture.md) → [28-testing-guide.md](28-testing-guide.md) |
| **An architect** | [04-architecture.md](04-architecture.md) → [14-security-architecture.md](14-security-architecture.md) → [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) → [36-architecture-decision-records.md](36-architecture-decision-records.md) |
| **An operator** | [06-deployment.md](06-deployment.md) → [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) → [31-troubleshooting.md](31-troubleshooting.md) → [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) |
| **An external integrator** | [10-api-reference.md](10-api-reference.md) → [11-integration-guide.md](11-integration-guide.md) → [12-error-code-catalog.md](12-error-code-catalog.md) → [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) |
| **A rule author** | [16-drl-sandboxing.md](16-drl-sandboxing.md) → [17-rule-development.md](17-rule-development.md) → [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) → [33-simple-start.md](33-simple-start.md) |
| **An AI agent** | [00-system-overview.md](00-system-overview.md) → this file → [02-project-structure.md](02-project-structure.md) → [37-glossary.md](37-glossary.md) → use [09-environment-variables-reference.md](09-environment-variables-reference.md) and [12-error-code-catalog.md](12-error-code-catalog.md) as primary lookups |
