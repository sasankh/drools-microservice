# 00 · System Overview

**The single starting point for this documentation corpus.** Read this first; from here, jump to the docs your role needs.

---

## What this project is

A **Spring Boot microservice** that executes business rules over JSON request data and returns the modified data. Rules are authored as Drools `.drl` files, stored in AWS S3, and hot-loaded into the running service. JSON in → rules fire → JSON out, with rules updatable independently of code deployments.

```
Client                     Service                          Storage
──────                     ───────                          ───────
POST /execute-rule  ───→  ┌────────────────────┐  ───→  ┌────────────────┐
{rule_id, data}           │ Filter chain       │        │ AWS S3 / Local │
                          │  ├ Headers (-2)    │        │  └ rules/...   │
                          │  ├ StrictPath (-1) │        └────────────────┘
                          │  ├ Admin Auth (0)  │
                          │  ├ Rate Limit (1)  │                  ▲
                          │  └ Size Validation │                  │
                          │       ↓            │              cache miss
                          │ Validation         │                  │
                          │   @ValidRuleId     │     ┌────────────┴───┐
                          │   @ValidRuleData   │     │ Redis decorator │
                          │       ↓            │  ←  │ (read-through   │
                          │ DroolsEngine       │     │  cache + pub/   │
                          │  └ KieSession      │     │  sub fan-out)   │
                          │                    │     └─────────────────┘
                          │     fireAllRules   │              ▲
                          │       ↓            │              │
                          │ Response           │ ─→ resilience│
                          │   {result, ...}    │   (Resilience4j circuit breakers)
                          └────────────────────┘
                            Port 8080: API + admin
                            Port 8081: Spring Actuator
```

**Read [01-project-overview.md](01-project-overview.md)** for the full one-page introduction.

---

## Where to start, by role

Pick the path matching your role. Each path is 3-5 docs in dependency order.

### 👨‍💻 New developer working on the code

1. [32-getting-started.md](32-getting-started.md) — 30-min quickstart with Docker
2. [27-development-setup.md](27-development-setup.md) — local Java + Maven setup, conventions
3. [02-project-structure.md](02-project-structure.md) — annotated directory tree (clickable)
4. [04-architecture.md](04-architecture.md) — system design
5. [28-testing-guide.md](28-testing-guide.md) — test suite map (55 files, 766 tests + 22 Testcontainers ITs)

### 🏛️ Architect / design reviewer

1. [01-project-overview.md](01-project-overview.md) — capabilities + non-goals
2. [04-architecture.md](04-architecture.md) — full architecture (8 security layers, threading, per-rule-ID KieBase isolation)
3. [14-security-architecture.md](14-security-architecture.md) — threat model + 8 layers
4. [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) — failure handling
5. [36-architecture-decision-records.md](36-architecture-decision-records.md) — 24 ADRs (001–025; 015 reserved) explaining "why"

### ⚙️ Operator / SRE

1. [06-deployment.md](06-deployment.md) — Docker + AWS ECS reference
2. [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) — operational procedures + alerts
3. [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) — memory diagnostics
4. [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) — performance decision tree
5. [31-troubleshooting.md](31-troubleshooting.md) — every common issue

### 🔌 External integrator / partner

1. [10-api-reference.md](10-api-reference.md) — every endpoint, request/response shape
2. [11-integration-guide.md](11-integration-guide.md) — code examples in 4 languages
3. [12-error-code-catalog.md](12-error-code-catalog.md) — every error code + HTTP status
4. [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) — IP-based client identification
5. [api-reference/openapi.yml](api-reference/openapi.yml) — machine-readable spec

### ✍️ Rule author

1. [33-simple-start.md](33-simple-start.md) — write and deploy your first rule
2. [16-drl-sandboxing.md](16-drl-sandboxing.md) — what the DRL policy check blocks; it is not a sandbox (read this BEFORE authoring)
3. [17-rule-development.md](17-rule-development.md) — rule patterns + best practices
4. [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md) — rule ID format + storage paths
5. [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) — 17 working examples with live curl tests

### 🤖 AI agent (Claude Code, Codex, NotebookLM)

1. This file — orientation
2. [38-for-ai-agents.md](38-for-ai-agents.md) — verification rules and pitfalls (read before claiming or writing)
3. [01-project-overview.md](01-project-overview.md) — capabilities and non-goals
4. [02-project-structure.md](02-project-structure.md) — clickable file index for code navigation
5. [37-glossary.md](37-glossary.md) — every acronym and term defined
6. [09-environment-variables-reference.md](09-environment-variables-reference.md) — exhaustive config catalog
7. [12-error-code-catalog.md](12-error-code-catalog.md) — error codes for diagnostics
8. [16-drl-sandboxing.md](16-drl-sandboxing.md) — required reading before generating any DRL

---

## Tech stack at a glance

| Layer | Choice | Version |
|---|---|---|
| Language | Java (LTS) | 25 (enforced) |
| Framework | Spring Boot | 3.5.16 |
| Rule engine | Drools | 10.2.0 |
| Storage | AWS S3 (via SDK v2) | 2.34.0 |
| Cache | `RedisCachedRuleStorage` decorator (opt-in via `REDIS_ENABLED`) + Redis pub/sub fan-out | — |
| Resilience | Resilience4j | 2.4.0 |
| Metrics | Micrometer (CloudWatch registry) | 1.15.12 |
| Logging | Logback + logstash-logback-encoder | 7.4 |
| Test | JUnit 5, Mockito, AssertJ, Testcontainers | — |
| Container | Amazon Corretto 25 Alpine | — |

Full tech stack rationale: [03-tech-stack.md](03-tech-stack.md).

---

## Numbers worth knowing

| Stat | Value |
|---|---|
| Total documentation files | **40** (including this one) |
| Total Java source files | 70 |
| Total test files | 55 |
| Total tests (`@Test` + `@ParameterizedTest`) | **766** in `mvn test` (unit + plain ITs) + 22 Testcontainers integration (surefire-excluded; CI-only) |
| Test coverage (instruction / branch) | 93.9% / 86.1% (measured 2026-11-18, Java 25) |
| Sample rules in `sample-rules/` | 17 |
| Environment variables catalogued in doc 09 | 73 |
| Distinct error codes | 11 (incl. `RULE_LIMIT_EXCEEDED` 422) |
| Spring profiles | 4 (`local`, `dev`, `prod`, `docker`) |
| Filter chain order | 5 filters (-2, -1, 0, 1, none) — see ADR-024 |
| Security headers on every response | 7 |
| Ports exposed | 2 (8080 main+admin, 8081 actuator) |
| Sustained throughput target / measured | 100-1000 RPS / 45+ RPS sustained |
| P99 latency target / measured (cached) | < 100ms / 1-40ms |
| Docker image size | ~347 MB (Corretto 25 Alpine multi-stage) |
| Architecture decision records | 24 written (001–025; 015 reserved) |
| Docker test plan (`run-docker-test-plan.sh`) | 72 PASS / 0 FAIL, Steps 1–13 (2026-11-18) |
| Security posture | Most findings addressed; 2 tracked-open (B1 DRL-sandbox RCE — deferred; #28 Redis TLS/auth — enforced on prod) |

---

## The 40-doc corpus, by category

### Foundation (00-04)
- [00-system-overview.md](00-system-overview.md) — this file
- [01-project-overview.md](01-project-overview.md) — what/why/business value
- [02-project-structure.md](02-project-structure.md) — annotated repo tree, clickable file index
- [03-tech-stack.md](03-tech-stack.md) — every dependency with rationale
- [04-architecture.md](04-architecture.md) — system design (the canonical architecture doc)

### Infrastructure & deployment (05-09)
- [05-environments-and-profiles.md](05-environments-and-profiles.md) — 4 Spring profiles, override matrix
- [06-deployment.md](06-deployment.md) — local + AWS ECS reference
- [07-docker-and-compose.md](07-docker-and-compose.md) — Dockerfile + compose deep dive
- [08-configuration.md](08-configuration.md) — high-level configuration primer
- [09-environment-variables-reference.md](09-environment-variables-reference.md) — every env var

### APIs & integration (10-13)
- [10-api-reference.md](10-api-reference.md) — every endpoint
- [11-integration-guide.md](11-integration-guide.md) — code examples in curl/Python/Java/Node.js
- [12-error-code-catalog.md](12-error-code-catalog.md) — every error code
- [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) — IP-based client ID

### Security (14-16)
- [14-security-architecture.md](14-security-architecture.md) — 8-layer model
- [15-admin-authentication.md](15-admin-authentication.md) — admin API key flow
- [16-drl-sandboxing.md](16-drl-sandboxing.md) — DRL policy checks (not a sandbox): allowlist/blocklist and their limits

### Rule authoring (17-23)
- [17-rule-development.md](17-rule-development.md) — comprehensive rule-author guide
- [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md) — rule ID format, S3 paths
- [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) — 17 sample rules with live curl
- [20-rule-generation-prompt.md](20-rule-generation-prompt.md) — AI prompt (full)
- [21-rule-generation-prompt-enhanced.md](21-rule-generation-prompt-enhanced.md) — AI prompt (with safety patterns)
- [22-rule-generation-prompt-concise.md](22-rule-generation-prompt-concise.md) — AI prompt (concise)
- [23-rule-language-reference.md](23-rule-language-reference.md) — upstream Drools reference

### Performance & memory (24-26)
- [24-jvm-optimization.md](24-jvm-optimization.md) — JVM tuning
- [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) — memory diagnostics
- [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) — diagnostic decision tree

### Development (27-29)
- [27-development-setup.md](27-development-setup.md) — onboarding + conventions
- [28-testing-guide.md](28-testing-guide.md) — test suite map + how-to
- [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) — Resilience4j wiring

### Operations (30-31)
- [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) — operational procedures + monitoring
- [31-troubleshooting.md](31-troubleshooting.md) — common issues + fixes

### Onboarding & reference (32-35)
- [32-getting-started.md](32-getting-started.md) — 30-min quickstart
- [33-simple-start.md](33-simple-start.md) — rule author quickstart
- [34-java-setup-guide.md](34-java-setup-guide.md) — Java 25 install
- [35-faq.md](35-faq.md) — 65+ Q&A

### Advanced (36-39)
- [36-architecture-decision-records.md](36-architecture-decision-records.md) — 24 ADRs (001–025; 015 reserved) + extension points
- [37-glossary.md](37-glossary.md) — every term defined
- [38-for-ai-agents.md](38-for-ai-agents.md) — verification rules and pitfalls for AI sessions working on this repo
- [39-load-test-findings.md](39-load-test-findings.md) — measured numbers, architectural trade-offs, production-planning guidance from the 2026-05-10 single-container load test (1,000 rules, mixed-workload soak) + 2026-05-24 Phase 9.4 addendum (3-replica + pub/sub convergence + Redis-kill failure mode)

### Reference assets
- [api-reference/openapi.yml](api-reference/openapi.yml) — OpenAPI 3.0 spec

---

## How a request flows through the service

This is the elevator-pitch version. Full detail in [04-architecture.md](04-architecture.md).

```
1. POST /execute-rule {rule_id, data}
2. SecurityHeadersFilter (Order -2) — adds 7 response headers
   StrictRequestPathFilter (Order -1) — 400 on ambiguous raw paths (`;` `%` `\` `//` dot-segments, ADR-024)
3. AdminAuthFilter (Order 0) — skipped for /execute-rule
4. RateLimitingFilter (Order 1) — checks per-client limit, keyed on source IP
5. RequestSizeValidationFilter (no Order) — 1 MiB body cap
6. RuleExecutionController — @ValidRuleId / @ValidRuleData via Jakarta Validation
7. DroolsEngineService.executeRule() — lock-free lookup of THIS rule_id's compiled KieBase (ADR-025)
8. RuleExecutor — submits a FutureTask to the thread pool; get(timeout=30s) → 408 on timeout (worker halted + interrupted)
   - Inside: new KieSession on that KieBase only, insert data Map, fireAllRules(cap + 1); more than the cap (default 10000) → 422 RULE_LIMIT_EXCEEDED
9. Metrics emitted (Micrometer); LogSanitizer scrubs response before logging
10. Response: {rule_id, result: <modified data>, error: null, execution_time_ms}
```

Typical cached-hit latency: 1-40ms.

---

## What's surprising about this codebase

If you take only one thing from this overview:

1. **A request runs only its own rule file**. `rule_id` is an execution boundary: each rule ID is compiled into its own KieBase, so VIP $100 → $80, and no other loaded rule fires. Before 2026-11-18 every matching rule fired and discounts stacked (VIP $100 → $72); that is gone, with no fallback. To combine discounts, call each `rule_id`. See [ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18) and [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md).
2. **`ADMIN_API_KEY` empty = admin endpoints are open — but only in `local`/`dev`**. In those profiles a blank key leaves `/admin/*` open with a startup WARN. In `prod`/`docker` a blank key is a hard error: the app **fails to start**. See [15-admin-authentication.md](15-admin-authentication.md).
3. **`eval()` is BANNED in DRL** — and the sample rules don't need it. See [16-drl-sandboxing.md](16-drl-sandboxing.md).
4. **Redis is a real cache when `REDIS_ENABLED=true`** — `RedisCachedRuleStorage` decorates the base storage with read-through caching; `RuleRefreshPublisher`/`Subscriber` provide cross-task fan-out via `drools:rule:events` channel. Replaced the old dead `LocalLRUCache`/`RedisRuleCache` layer on 2026-05-20. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20).
5. **Rate-limit buckets are keyed on source IP only**. `X-API-Key` / `Authorization` / `X-Client-Id` do **not** create separate buckets (keying on unauthenticated headers would let callers defeat the limiter). `X-Forwarded-For` is ignored by default; set `DROOLS_RATE_LIMITING_TRUST_PROXY=true` only behind a trusted proxy that overwrites it. See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md).
6. **Rule refresh doesn't block readers** — refreshing one rule ID recompiles only that ID and swaps its immutable handle in the `CompiledRulesetRegistry`; execution takes no lock, and in-flight requests finish on the version they took. See [ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18).
7. **A refresh never half-applies**. Every storage read is all-or-nothing: a failed S3/file read changes nothing, and an empty read never removes loaded rules (409 `rejected_empty`). An instance with no rules is **not ready** (`/actuator/health/readiness` and `/admin/health` return 503). See [10-api-reference.md](10-api-reference.md) and [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md).
8. **JSON uses snake_case (`rule_id`)** — not camelCase. The Java field is `ruleId` but mapped via `@JsonProperty`. See [ADR-008](36-architecture-decision-records.md#adr-008-snake_case-json-via-jsonproperty).

---

## What's NOT here (intentional non-goals)

- **No workflow orchestration** — this is single-pass rule execution, not BPMN.
- **No event broker** — synchronous request/response only.
- **No multi-tenancy** — single rule namespace per deployment.
- **No Drools modern syntax** — rule units / OOPath / DataStream not used. See [ADR-001](36-architecture-decision-records.md#adr-001-traditional-drl-syntax-only-not-rule-units--oopath).
- **No primary authentication** — `AdminAuthFilter` is defense in depth; primary auth is at API gateway.
- **No Terraform / IaC** — reference architecture documented, not packaged. See [ADR-007](36-architecture-decision-records.md#adr-007-no-terraform-aws-deployment-documented-as-reference-only).
- ~~**No JMeter performance suite** — load testing deferred per project plan.~~ Delivered 2026-05-10 — see [39-load-test-findings.md](39-load-test-findings.md) and `scripts/run-load-test.sh`.
- **No SDK packages** — partners use HTTP directly. See [11-integration-guide.md](11-integration-guide.md) for code patterns.

---

## Source code anchor points

When you read this corpus and want to verify a claim against code, these are the primary files to check:

| Concern | File |
|---|---|
| Rule execution orchestrator | [`DroolsEngineService.java`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) |
| Rule compiler (one KieBase per rule ID) + policy check | [`RuleCompiler.java`](../src/main/java/com/company/drools/core/engine/RuleCompiler.java) |
| Async execution + timeout cancellation + firing cap | [`RuleExecutor.java`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java) |
| Every refresh (startup, admin, pub/sub, reconcile) | [`RuleRefreshService.java`](../src/main/java/com/company/drools/core/engine/RuleRefreshService.java) |
| Readiness (`rules` health indicator) | [`RulesHealthIndicator.java`](../src/main/java/com/company/drools/config/RulesHealthIndicator.java) |
| **DRL policy check** (not a sandbox; see `SECURITY.md`) | [`DrlSanitizer.java`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) |
| Filter chain | [`api/filter/`](../src/main/java/com/company/drools/api/filter/) |
| Storage abstraction | [`storage/`](../src/main/java/com/company/drools/storage/) |
| Cache layer | [`cache/`](../src/main/java/com/company/drools/cache/) |
| Configuration beans | [`config/`](../src/main/java/com/company/drools/config/) |
| All controllers | [`api/controller/`](../src/main/java/com/company/drools/api/controller/) |
| Default config | [`application.yml`](../src/main/resources/application.yml) |
| Container build | [`Dockerfile`](../Dockerfile) |
| Dev compose stack | [`docker-compose.yml`](../docker-compose.yml) |
| Maven build | [`pom.xml`](../pom.xml) |
| Sample rules | [`sample-rules/`](../sample-rules/) |
| Test base classes | [`BaseUnitTest.java`](../src/test/java/com/company/drools/BaseUnitTest.java), [`BaseIntegrationTest.java`](../src/test/java/com/company/drools/BaseIntegrationTest.java) |

---

## Documentation health

This corpus was produced through a 5-phase, code-grounded rewrite (2026-05-08):

- **Phase 1**: Renamed and revised existing 13 docs; wrote 4 foundation docs.
- **Phase 2**: 9 new docs for APIs, security, infrastructure.
- **Phase 3**: 4 docs for rule authoring + performance.
- **Phase 4**: 7 docs for ops, dev, and advanced topics.
- **Phase 5**: This entry-point doc + cross-corpus audit + repo integration.

Tracking artifacts: [`.ai-workspace/documentations/CHECKLIST.md`](../.ai-workspace/documentations/CHECKLIST.md), [`.ai-workspace/documentations/CODE_FINDINGS.md`](../.ai-workspace/documentations/CODE_FINDINGS.md).

Code findings discovered during documentation work (32 items, all severity Low; doc-only fixes applied here, code-side recommendations logged for separate triage): [`.ai-workspace/documentations/CODE_FINDINGS.md`](../.ai-workspace/documentations/CODE_FINDINGS.md).

Every claim in every doc is backed by a citation to either:
- A specific Java/YAML/Dockerfile line (`path/file.ext:line` markdown link)
- A specific test that proves the behavior
- An ADR explaining a deliberate choice

When code and docs disagree, **code wins**. Tests are the most accurate spec. Docs are the human-readable layer atop code+tests.

---

## Quick links

- 🚀 [Get running in 30 minutes](32-getting-started.md)
- 📚 [Read the architecture](04-architecture.md)
- 🔧 [API reference](10-api-reference.md)
- ❓ [FAQ](35-faq.md)
- 🚨 [Troubleshooting](31-troubleshooting.md)
- 📖 [Glossary](37-glossary.md)

Welcome.
