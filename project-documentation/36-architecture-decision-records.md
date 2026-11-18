# 36 · Architecture Decision Records (ADRs)

| | |
|---|---|
| **Audience** | Architects, senior developers, future contributors trying to understand "why was this done this way?" |
| **Purpose** | Capture the load-bearing design decisions and their rationale, so future changes don't re-litigate the same choices unaware |
| **Last updated** | 2026-08-20 (re-review hardening — added ADR-017…023: admin fail-closed, rate-limiter IP-keying, exec timeout/load-shed, refreshLock split, Redis prod TLS + Jackson-typing removal, delegate AtomicReference, CI + verify-bound quality gates; ADR-006 and ADR-010 updated in place) |
| **Related docs** | All — ADRs reference specific implementation files |

---

## What's an ADR?

An Architecture Decision Record is a short note capturing one decision: what was decided, what alternatives were considered, why this option was chosen, and what trade-offs come with it. ADRs are **historical** — they describe the state at the time of the decision. If circumstances change, a new ADR supersedes an old one rather than editing the old one.

Format used here: each ADR has Status, Context, Decision, Alternatives Considered, Consequences, References.

---

## ADR Index

| # | Title | Status |
|---:|---|---|
| [001](#adr-001-traditional-drl-syntax-only-not-rule-units--oopath) | Traditional DRL syntax only (not rule units / OOPath) | Accepted |
| [002](#adr-002-map-based-facts-not-typed-pojos) | Map-based facts (not typed POJOs) | Accepted |
| [003](#adr-003-kiecontainer-atomic-swap-with-disposal) | KieContainer atomic-swap with explicit disposal | Accepted (2026-02-19 fix) |
| [004](#adr-004-locallrucache-uses-write-lock-on-get) | LocalLRUCache uses WRITE lock on `get()` | Superseded by ADR-016 (2026-05-20) |
| [005](#adr-005-redis-bean-exists-but-is-dormant-by-default) | Redis bean exists but is dormant by default | Superseded by ADR-016 (2026-05-20) |
| [006](#adr-006-adminauthfilter-instead-of-spring-security) | AdminAuthFilter (lightweight) instead of Spring Security | Accepted (fail-closed + constant-time addendum 2026-08-20) |
| [007](#adr-007-no-terraform-aws-deployment-documented-as-reference-only) | No Terraform — AWS deployment documented as reference only | Accepted |
| [008](#adr-008-snake_case-json-via-jsonproperty) | snake_case JSON via `@JsonProperty` | Accepted |
| [009](#adr-009-eval-banned-in-drl) | `eval()` banned in DRL via `DrlSanitizer` | Accepted |
| [010](#adr-010-rate-limiting-in-memory-not-redis-backed) | Rate limiting in-memory (not Redis-backed) | Accepted (IP-keying + trust-proxy addendum 2026-08-20) |
| [011](#adr-011-custom-validation-annotations-vs-jakarta-only) | Custom validation annotations alongside Jakarta | Accepted |
| [012](#adr-012-drools-8440-not-latest-8x-or-9x) | Drools 8.44.0.Final (not latest 8.x or 9.x) | Superseded by ADR-014 |
| [013](#adr-013-java-17--25--spring-boot-modernization-2026-05-09) | Java 17 → 25 + Spring Boot modernization | Accepted (2026-05-09) |
| [014](#adr-014-drools-8--10-migration-2026-05-09) | Drools 8 → 10 migration | Accepted (2026-05-09) |
| [016](#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20) | Redis decorator + pub/sub for multi-instance DRL cache | Accepted (2026-05-20) |
| [017](#adr-017-admin-auth-fail-closed-on-proddocker-profiles-2026-08-20) | Admin auth fail-closed on prod/docker profiles | Accepted (2026-08-20) |
| [018](#adr-018-rate-limiter-keys-on-ip-only-optional-trust-proxy-2026-08-20) | Rate limiter keys on IP only; optional trust-proxy | Accepted (2026-08-20) |
| [019](#adr-019-rule-execution-timeout-halt--thread-pool-load-shed-503-2026-08-20) | Rule-execution timeout halt + thread-pool load-shed 503 | Accepted (2026-08-20) |
| [020](#adr-020-refreshlock-separated-from-the-rule-read-write-lock-2026-08-20) | refreshLock separated from the rule read-write lock | Accepted (2026-08-20) |
| [021](#adr-021-redis-prod-tlsauth-enforcement--removal-of-jackson-default-typing-2026-08-20) | Redis prod TLS/auth enforcement + removal of Jackson default typing | Accepted (2026-08-20) |
| [022](#adr-022-storage-delegate-held-in-an-atomicreference-2026-08-20) | Storage delegate held in an `AtomicReference` | Accepted (2026-08-20) |
| [023](#adr-023-ci-workflow--verify-bound-quality-gates-2026-08-20) | CI workflow + verify-bound quality gates | Accepted (2026-08-20) |

---

## ADR-001: Traditional DRL syntax only (not rule units / OOPath)

**Status**: Accepted
**Date**: project inception

### Context

Drools 8 introduced two parallel DRL syntaxes:
1. **Traditional**: `Map()` patterns, `eval()` predicates, `then` blocks of plain Java. Familiar from Drools 5/6/7.
2. **Modern**: Rule units (`unit MyRules;`), OOPath (`/persons[ age > 18 ]`), `DataStream`/`DataStore` data sources.

We had to choose one to standardize on.

### Decision

This project uses **traditional DRL syntax only**. Modern features (rule units, OOPath, DataStream) are not used and are explicitly rejected if they appear in rule files (via [`DrlSanitizer`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) and reviewer convention).

### Alternatives considered

- **Modern syntax (rule units + OOPath)**: more concise for typed-object rules; promoted by upstream Drools docs. Rejected because: requires JVM-side typed fact classes per domain object, which doesn't fit our Map-based JSON-input model; smaller body of community examples; more cognitive overhead for rule authors.
- **Mix both styles**: rejected. One style per project keeps the rule-author cognitive load low.

### Consequences

- **Positive**: rule authors only need to learn one syntax. Drools 7 → 8 docs and Stack Overflow answers remain mostly applicable. The sandbox has fewer feature surfaces to guard.
- **Positive**: rules don't require companion Java POJOs. A rule author can ship a rule without coordinating with a Java change.
- **Negative**: we don't get the OOPath ergonomics for nested data access (e.g., `/orders/items[ price > 100 ]`).
- **Negative**: [23-rule-language-reference.md](23-rule-language-reference.md) (the upstream reference) describes many features we don't use, requiring readers to consult [17-rule-development.md](17-rule-development.md) for project-specific patterns.

### References
- [17-rule-development.md](17-rule-development.md) — project-specific rule patterns
- [23-rule-language-reference.md](23-rule-language-reference.md) — upstream reference with "[Not used in this project]" tags
- [`sample-rules/`](../sample-rules/) — 17 examples in the chosen style

---

## ADR-002: Map-based facts (not typed POJOs)

**Status**: Accepted
**Date**: project inception

### Context

Rules need to operate on input data. Drools supports either:
1. **Typed Java POJOs**: `Order { amount, customerType, ... }`. The `when` clause uses `Order(...)`.
2. **`Map<String,Object>`**: untyped key-value bag. The `when` clause uses `Map(this["amount"] != null)`.

This choice affects every rule and the JSON deserialization path.

### Decision

Use `Map<String,Object>` exclusively. Every `RuleExecutionRequest.data` is a Map; every rule pattern matches on `Map(...)`.

### Alternatives considered

- **Typed POJOs**: traditional Drools approach. Rejected because:
  - Every new domain object would require a Java class change deploy. Defeats the purpose of externalized rules.
  - Field names and types would be enforced at the Java layer, not at the rule layer. Less flexibility for rule authors.
  - `RuleExecutionRequest.data` would have to be a typed shape, complicating the API contract.
- **Mixed**: some rules use POJOs, some use Maps. Rejected. Two patterns is worse than one.

### Consequences

- **Positive**: API is fully dynamic. New input fields require zero Java code change.
- **Positive**: JSON → Map is trivial via Jackson. No DTO classes needed per domain.
- **Positive**: rule authors deal with one fact type forever.
- **Negative**: type safety is at the rule level, not compile time. A typo in a rule's `$data.get("ammount")` (vs `amount`) returns null silently — no compile error. Mitigation: tests should cover field name correctness.
- **Negative**: numeric type coercion is manual. JSON deserializes integers as `Integer` (or `Long` for big numbers); rules use `((Number) data).doubleValue()` defensively.
- **Negative**: we forgo Drools' type-aware optimizations (e.g., index-based pattern matching on typed fields).

### References
- All sample rules in [`sample-rules/`](../sample-rules/)
- [`RuleExecutionRequest.java`](../src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java) — data field is `Map<String,Object>`

---

## ADR-003: KieContainer atomic-swap with disposal

**Status**: Accepted (fix landed 2026-02-19)
**Date**: 2026-02-19

### Context

Drools' `KieContainer` is heavy — holds compiled bytecode for every rule, plus internal RETE network state. Each `KieContainer` consumes 10-100 MB of heap.

The original implementation in `DroolsEngineService` replaced the container reference on rule refresh but **did not dispose** the old container. Old containers were still reachable via finalizer queues / classloaders / bytecode caches and accumulated in memory. Repeated refreshes (every 5 minutes for a few hours) caused OOM exit-137 in production.

Additionally, the original implementation held a write lock during compilation. Compilation can take seconds. While the lock was held, **no rule executions could proceed** (they need the read lock). The service was effectively offline during refresh.

### Decision

1. **Compile outside the lock**. New `KieContainer` is built with no synchronization.
2. **Acquire write lock only for the atomic swap**. Inside the lock: read old container reference, write new reference, dispose old.
3. **Always call `oldContainer.dispose()`** before unlocking.

```java
// Pseudo-code from DroolsEngineService.java:165-194
KieContainer newContainer = ruleCompiler.compile(rules);  // OUTSIDE lock
rulesLock.writeLock().lock();
try {
  KieContainer old = currentKieContainer;
  currentKieContainer = newContainer;
  if (old != null && old != newContainer) {
    old.dispose();
  }
  // ... update loadedRules / metadata maps
} finally {
  rulesLock.writeLock().unlock();
}
```

### Alternatives considered

- **Compile inside the lock** (the original approach): rejected for the latency reason above.
- **Skip disposal** (the original approach): rejected for the OOM reason above. Verified by load test: 3000 refreshes without disposal → +1.5 GB heap; with disposal → flat.
- **Use Drools' `KieScanner`** for hot-reload: rejected. KieScanner is designed for KJAR-based reloads from a Maven repo, not S3 polling. Doesn't fit our model.

### Consequences

- **Positive**: refresh is fully non-blocking for readers. Latency P99 stays flat during refresh.
- **Positive**: memory is stable across thousands of refreshes (verified by 3000-refresh load test on 2026-02-19).
- **Negative**: there's a brief window (microseconds) during the swap where some readers see the old container and others see the new. Readers that captured `currentKieContainer` before the swap continue with the old container; their KieSession is created from the old KieBase. After the swap completes, all new lookups see the new container. **This is actually fine** — both old and new containers are valid KieContainers; the request just uses whichever was current when it started.

### References
- [`DroolsEngineService.java`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) — implementation (now uses `updateToVersion`; see 2026-05-10 update below)
- [04-architecture.md](04-architecture.md) — "Atomic-Swap Rule Loading Pattern" section
- [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) — verification load tests

### 2026-05-10 update — superseded by `KieContainer.updateToVersion(ReleaseId)`

The two-container atomic-swap with explicit `dispose()` documented above is **replaced** by Drools 10's canonical `KieContainer.updateToVersion(ReleaseId)` pattern. The change was driven by [`fix-findings-plan.md`](../.ai-workspace/project-plans/fix-findings-plan.md) (Finding #1 — single-rule refresh wiping out other rules). The fix had to give the engine a "merge one rule into the current set" primitive; the cleanest path was to switch the whole refresh pipeline to the canonical Drools 10 idiom rather than maintain two parallel architectures.

What changed:
- `RuleCompiler` now emits each compiled rule set as a versioned `KieModule` with a synthetic `ReleaseId` (`com.company.drools:rules-runtime:1.0.<n>`) registered in the `KieRepository`.
- `DroolsEngineService` holds **one long-lived `KieContainer`** for the lifetime of the JVM; refresh calls `kieContainer.updateToVersion(newReleaseId)`. Drools handles the internal KieBase swap.
- The explicit `oldContainer.dispose()` block was removed — there is no "old container" to dispose. Drools releases the previous internal KieBase as part of `updateToVersion`.

What is preserved:
- **No leak.** Verified by re-running the 10-refresh heap-stability check on 2026-05-10 with the new pattern: pre-test 118 MB → after 10 refreshes 215 MB → post-GC 47 MB. Post-GC heap is below the pre-test baseline, matching the original ADR-003 leak-free claim.
- **Compile outside the lock**, then take the write lock only for the swap. Latency profile for concurrent readers is unchanged.
- The **3000-refresh load test rationale** still holds. The new pattern has, if anything, lower memory churn because there is no double-container window during the swap.

What is new (and why):
- A new method `loadOrReplaceRule(Rule)` is the merge primitive used by `POST /admin/refresh-rules/{id}`. It snapshots `loadedRules`, swaps in the new rule, and re-runs `loadRules(combined)`. The whole sequence runs under the write lock (reentrant) so concurrent merges cannot lose each other's updates.
- `loadRules(List<Rule>)` is now strictly **replacement** semantics — the new rule set is authoritative. Previous code was accidentally additive on the in-memory map; the bug surfaced as Finding #1 (caller passes 1 rule, KieContainer rebuild loses the other 9, but `loadedRules` map kept stale entries from prior calls).
- Initial `KieContainer` (`DroolsConfig.kieContainer`) now uses the matching `groupId:artifactId` (`com.company.drools:rules-runtime:1.0.0`). Required so `updateToVersion` can resolve newly-built modules from the `KieRepository`.

What is unchanged in the codebase:
- ADR-001 (single-KieBase architecture).
- One stateless `KieSession` per execute-rule call.
- The 7 security headers, the rate-limiting filter chain, the observability hooks.
- `KieScanner` still rejected — same reason as 2026-02-19.

References:
- [`fix-findings-plan.md`](../.ai-workspace/project-plans/fix-findings-plan.md) + [`fix-findings-checklist.md`](../.ai-workspace/project-plans/fix-findings-checklist.md) — the fix plan/checklist
- [`e2e-validation-findings.md`](../.ai-workspace/project-plans/e2e-validation-findings.md) — the original Finding #1 + #2 write-up
- [Drools 10 `KieContainer.updateToVersion` docs](https://docs.drools.org/latest/kie-api-javadoc/org/kie/api/runtime/KieContainer.html)

### 2026-05-10 update — load tested at 1,000 rules

**Status**: validated end-to-end via the load-test harness ([`load-test-plan.md`](../.ai-workspace/project-plans/load-test-plan.md) / [`load-test-checklist.md`](../.ai-workspace/project-plans/load-test-checklist.md)) on 2026-05-10. Result: **PASS** with one bug discovered + fixed during the run, and one architectural note for production planning.

Headline numbers (full report: [`scripts/load-test-results/2026-05-10T073852Z/summary.md`](../scripts/load-test-results/2026-05-10T073852Z/summary.md)):

- Baseline (50 RPS × 30 min, 1,000 rules): **P99=9 ms, 0 errors over 93,002 samples, post-GC heap 67 MB**.
- Concurrency ramp 50 → 100 → 250 → 500 RPS: P99 stayed ≤ 5 ms; safe-RPS ceiling = 500 (cliff never reached).
- Hot full-refresh under load: 0 errors, P99 = 9 ms (no measurable spike during the swap).
- Hot single-rule refresh under load: 0 errors, P95 = 11 ms, P99 = 483 ms during ~1 % of the run that overlapped the lock-held-through-compile window. Architecturally correct behaviour for atomic rule swaps; SLO consideration for sub-100 ms tier services.
- 15-min mixed-workload soak (12 full + 86 single-rule refreshes under 50 RPS): **heap drift 1 MB**, 0 errors over 43,397 samples.

**Bug surfaced + fixed**: `DroolsEngineService.loadRules` was setting all rules to `LOADING` state upfront before the compile, then back to `ACTIVE` after. With 10-rule corpora the LOADING window was sub-millisecond and never observable; at 1,000 rules and a 46-second cold-JIT compile, ~1.5 % of concurrent execute requests hit the LOADING window and got `400 "Rule is not active"`. The pre-mark defeated the goal of "compile outside the lock = non-blocking reads of the OLD KieBase". Fix: removed the pre-mark; rules stay ACTIVE in the OLD KieBase during compile, and metadata is updated under the write lock only after `updateToVersion` succeeds. 5 unit tests in `DroolsEngineServiceTest` updated to reflect the corrected semantics. Phase 5 re-run after fix → 0 errors.

**KieRepository cleanup verified**: 1 MB heap drift over 98 refresh operations confirms `kieRepository.removeKieModule(oldReleaseId)` (the Phase 0 patch) is doing its job. Without it, each refresh would leak one `ProjectClassLoader` + every compiled rule class — hundreds of MB across the soak.

**Architectural note** (not a bug): single-rule refresh holds the write lock through a full ~510 ms warm-JIT compile of the entire rule set; a per-rule incremental KieBase rebuild is technically possible but currently not implemented. See the load-test summary for production-planning mitigations.

---

## ADR-004: LocalLRUCache uses WRITE lock on `get()`

**Status**: ~~Accepted~~ **Superseded by [ADR-016](#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20)** (2026-05-20)
**Date**: project inception

> **Superseded note**: `LocalLRUCache` was deleted in the 2026-05-20 cache layering work — it was dead code (`.get()` was never called from production code paths). Decoration is now done by `RedisCachedRuleStorage` on the `RuleStorage` interface; no in-process LRU layer exists anymore. The original ADR is preserved below for historical context.

### Context

`LocalLRUCache` is implemented as a `LinkedHashMap<String, Rule>` with `accessOrder=true`. The `accessOrder=true` flag means **`get()` mutates the internal linked list** (moves the accessed entry to the head as most-recently-used). This is what makes LRU eviction work.

Since `get()` mutates internal structure, concurrent `get()` calls without synchronization corrupt the LinkedHashMap. The standard fix is `Collections.synchronizedMap(...)` (full-map lock on every operation). We chose a different approach.

### Decision

Use `ReentrantReadWriteLock` but acquire the **write lock** on every `get()`, not the read lock.

### Alternatives considered

- **Read lock on `get()`**: would seem natural since `get` is "read" semantically. Rejected because LinkedHashMap's `accessOrder` mutates on get → multiple concurrent reads corrupt state.
- **`ConcurrentHashMap`**: rejected because doesn't support LRU eviction. We'd have to implement LRU on top of CHM, which is more code than the LinkedHashMap approach.
- **Caffeine cache**: a sophisticated cache library with proper concurrent LRU. Rejected because:
  - One more dependency to manage.
  - Caffeine's full feature set (loaders, expiration, weights) is overkill for our 100-entry cache.
  - The overhead of Caffeine's internal data structures > our simpler approach for our cache size.
- **`Collections.synchronizedMap(linkedHashMap)`**: works correctly but slightly worse contention pattern than read-write lock.

### Consequences

- **Positive**: simple implementation, minimal dependencies.
- **Positive**: write lock on get is conceptually clear once the underlying behavior is understood (the comment in code explains it).
- **Negative**: zero benefit from the read lock side of `ReentrantReadWriteLock`. Could be a plain `ReentrantLock` and behavior would be identical.
- **Negative**: surprise factor — readers see "ReadWriteLock" and assume `get()` is contention-free. Easy to mis-edit.
- **Mitigation**: explicit comment in [`LocalLRUCache.java:73`](../src/main/java/com/company/drools/cache/LocalLRUCache.java#L73): "Write lock required: access-ordered LinkedHashMap mutates internal structure on get()".

### References
- [`LocalLRUCache.java:73`](../src/main/java/com/company/drools/cache/LocalLRUCache.java#L73)
- [Java docs for LinkedHashMap accessOrder](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/LinkedHashMap.html)

---

## ADR-005: Redis bean exists but is dormant by default

**Status**: ~~Accepted (under review for production)~~ **Superseded by [ADR-016](#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20)** (2026-05-20)
**Date**: project inception

> **Superseded note**: The "dormant Redis bean" pattern was deleted on 2026-05-20. Redis is now a real read-through decorator (`RedisCachedRuleStorage`) that actually wraps the storage chain when `REDIS_ENABLED=true`, and `RuleRefreshPublisher`/`Subscriber` provide cross-task pub/sub fan-out. The old `RedisRuleCache` class was removed (it was written to during refresh but never read). Original ADR preserved below.

### Context

The architecture envisions a multi-tier cache: LocalLRUCache (L1, in-process) → Redis (L2, shared across replicas) → S3 (source of truth). [`RedisRuleCache`](../src/main/java/com/company/drools/cache/RedisRuleCache.java) is fully implemented with circuit breaker integration and SCAN-based key listing.

But [`LocalLRUCache`](../src/main/java/com/company/drools/cache/LocalLRUCache.java) is annotated `@Primary`. When a service code path asks for a `RuleCache` bean, Spring injects `LocalLRUCache` — Redis is never consulted in the normal request path.

### Decision

Keep both beans wired but `LocalLRUCache` as `@Primary`. Redis stays "dormant" (initialized when `REDIS_ENABLED=true`, but not in the hot path).

### Alternatives considered

- **Make Redis primary, LRU secondary**: would require code changes to use multi-cache lookups. Defer until we actually need shared caching.
- **Remove Redis entirely**: rejected because horizontal scale-out plans benefit from shared caching, and removing-then-re-adding is more work.
- **Multi-cache wrapper bean**: a `RuleCache` that delegates to LRU first, then Redis on miss. Defer to when needed.

### Consequences

- **Positive**: LocalLRUCache is fast (in-process, no network). Latency stays low.
- **Positive**: zero operational dependency on Redis at runtime — Redis can be down with no user-visible impact.
- **Positive**: Redis bean wiring is in place, ready to activate.
- **Negative**: each replica's cache is independent. Cache-miss path always hits S3, which is slow.
- **Negative**: per-replica memory cost (each LRU holds ~100 rules × ~50 KB = ~5 MB).
- **Negative**: someone reading the architecture doc might assume Redis is doing something it isn't.

### When to revisit

- Cluster has > 5 replicas AND cache hit rate per replica < 50% (replicas spending too much time on S3 lookups that could be Redis hits).
- Production deploy enables `REDIS_ENABLED=true` for "future-proofing" without realizing it's unused.
- Cluster-wide rate limiting becomes a requirement (see ADR-010) — Redis would be needed there too, so might as well use it for caching.

### References
- [`LocalLRUCache.java:24`](../src/main/java/com/company/drools/cache/LocalLRUCache.java#L24) — `@Primary` annotation
- [`RedisRuleCache.java:34`](../src/main/java/com/company/drools/cache/RedisRuleCache.java#L34) — `@ConditionalOnProperty`

---

## ADR-006: AdminAuthFilter (lightweight) instead of Spring Security

**Status**: Accepted
**Date**: project inception

### Context

Admin endpoints (`/admin/*`) need authentication when exposed externally. Spring's recommended path is `spring-boot-starter-security` — comes with OAuth, OIDC, JWT, RBAC, etc.

We have one specific requirement: a single API key gates `/admin/*`. Nothing more.

### Decision

Implement a custom ~90-line filter ([`AdminAuthFilter`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) — actual is 93 lines) instead of pulling in Spring Security.

### Alternatives considered

- **Spring Security**: pulls ~50 KLOC of dependency, configures security via complex Java DSL. Overkill for one API key check. Rejected.
- **Servlet `Filter` (no Spring)**: equivalent simplicity to our `OncePerRequestFilter`. We chose Spring's `OncePerRequestFilter` because we're already in a Spring app — better integration with `@Order`, profile activation, etc.
- **API gateway-only auth**: external gateway (Cloudflare, AWS API Gateway) does API-key check; the service has no auth at all. Rejected as defense-in-depth: if gateway is misconfigured or compromised, admin endpoints are wide open.

### Consequences

- **Positive**: 93 lines vs 50,000. Audit-friendly. No CVE-driven dependency churn.
- **Positive**: Filter uses `@Order(0)` to slot cleanly into the filter chain alongside SecurityHeadersFilter, RateLimitingFilter.
- **Positive**: dev-mode bypass (empty `ADMIN_API_KEY` = no auth) is one line in the filter; would be annoying with Spring Security.
- **Negative**: if we ever need OAuth, JWT, or per-user roles, we'd need to add Spring Security. Refactor cost is moderate (replace one filter, keep the rest).

### 2026-08-20 addendum — fail-closed on deployable profiles + constant-time compare

Two hardening changes superseded the original "empty key = open, and `String.equals` compare" behavior (see also [ADR-017](#adr-017-admin-auth-fail-closed-on-proddocker-profiles-2026-08-20)):

- **Fail-closed startup.** `AdminAuthFilter` now defines `KEY_REQUIRED_PROFILES = {prod, docker}`. If `ADMIN_API_KEY` is blank while one of those profiles is active, the filter **throws `IllegalStateException` at construction** and the context fails to start. Only `local`/`dev` retain the open-with-WARN dev bypass. A running-but-unauthenticated production instance is therefore no longer possible.
- **Constant-time compare.** The key check is now `MessageDigest.isEqual(...)` (constant-time) rather than `String.equals`, closing the timing-attack note in the original Consequences.

### References
- [`AdminAuthFilter.java`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) — full implementation (`KEY_REQUIRED_PROFILES`, `MessageDigest.isEqual`)
- [15-admin-authentication.md](15-admin-authentication.md) — the user-facing flow
- [ADR-017](#adr-017-admin-auth-fail-closed-on-proddocker-profiles-2026-08-20) — the fail-closed decision in full

---

## ADR-007: No Terraform — AWS deployment documented as reference only

**Status**: Accepted
**Date**: project inception

### Context

Most production projects ship infrastructure-as-code (Terraform / Pulumi / CDK / CloudFormation) alongside the application code, so anyone can stand up a complete environment from the repo.

Our deployment story is: build a Docker image, push to a registry, deploy with whatever orchestrator the customer uses (ECS Fargate, EKS, EC2 + systemd, Kubernetes, etc.). The infrastructure is highly customer-specific.

### Decision

This repo ships **no IaC**. [06-deployment.md](06-deployment.md) documents the **reference architecture** (ALB → ECS Fargate → S3 + ElastiCache) but the actual infrastructure provisioning is owned by the deploying team.

### Alternatives considered

- **Ship Terraform for ECS Fargate**: would be opinionated about VPC layout, IAM policies, alarming. Customer environments vary too much. Rejected.
- **Ship docker-compose for "production"**: dangerous — docker-compose is not production-grade. Some teams might run it anyway. Rejected to avoid that footgun.
- **CDK in TypeScript or Java**: same concern as Terraform. Plus introduces a non-Java toolchain.
- **Helm chart**: closest to a "drop-in" deployment artifact, but assumes Kubernetes. Rejected to keep the orchestrator unopinionated.

### Consequences

- **Positive**: nobody is misled into thinking the repo's IaC is production-ready.
- **Positive**: deploying team retains full control over their cloud account.
- **Negative**: more work for new adopters — they must read [06-deployment.md](06-deployment.md) and translate to their tooling.
- **Negative**: documentation drift risk — if AWS changes service names / behaviors, the reference doc may go stale faster than IaC would.

### References
- [06-deployment.md](06-deployment.md) — reference architecture
- [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) — operational expectations

---

## ADR-008: snake_case JSON via `@JsonProperty`

**Status**: Accepted
**Date**: project inception

### Context

The wire format for `POST /execute-rule` uses `rule_id` (snake_case). The Java field on `RuleExecutionRequest` is `ruleId` (camelCase). Jackson maps between them via `@JsonProperty("rule_id")`.

This is a per-DTO choice — Jackson global configuration (`PropertyNamingStrategies.SNAKE_CASE`) would change the convention everywhere.

### Decision

Use snake_case in JSON, camelCase in Java, with explicit per-field `@JsonProperty` annotations.

### Alternatives considered

- **Camel case both**: simpler, but snake_case is more conventional in REST APIs (e.g., GitHub, Stripe).
- **Snake case both**: would require non-idiomatic Java field names (`String rule_id`). Rejected.
- **Global Jackson config (`SNAKE_CASE` strategy)**: would auto-translate every field. Rejected because:
  - Implicit translation makes the API contract less obvious.
  - Some fields don't follow the rule (`execution_time_ms` is mostly snake but had to handle "ms" — explicit annotation makes the choice clear).
  - Easier to break something inadvertently when the global rule changes.

### Consequences

- **Positive**: API contract is obvious from the DTO source.
- **Positive**: explicit override possible (e.g., `@JsonProperty("ruleId")` would un-snake one field if needed).
- **Negative**: every field needs an annotation. ~20 annotations in the DTO classes.
- **Negative**: easy to typo — `@JsonProperty("rule_di")` would silently break the API contract for that field.

### References
- [`RuleExecutionRequest.java:12`](../src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java#L12) — the canonical example
- [10-api-reference.md](10-api-reference.md) — wire format documented for partners

---

## ADR-009: `eval()` banned in DRL via `DrlSanitizer`

**Status**: Accepted
**Date**: project inception

### Context

Drools' `eval(...)` clause in `when` blocks lets rules embed arbitrary Java boolean expressions. While useful, it has two major problems:
1. **Security**: `eval()` is a code-execution surface. A rule author can run any Java code in `eval(...)` as long as the rule compiles.
2. **Performance**: pattern-matched conditions go through the RETE network and benefit from indexing. `eval()` predicates are evaluated per activation, no indexing. Slow.

### Decision

[`DrlSanitizer`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) rejects any DRL containing `\beval\s*\(`. Rules that use `eval()` fail to load.

### Alternatives considered

- **Allow `eval()` but warn**: a soft prohibition. Rejected because (a) warnings are easy to ignore, and (b) the security concern is hard.
- **Allow `eval()` only for trusted authors**: would require some access control we don't have.
- **Disable `eval()` via Drools configuration**: there's no Drools-native way to do this — the language supports it. Sanitizer-based blocking is our only option.

### Consequences

- **Positive**: removes a major code-execution risk.
- **Positive**: forces rule authors to use proper pattern matching, which is faster.
- **Negative**: some rule patterns become more verbose. Example: `eval(((Number)$data.get("amount")).doubleValue() > 50)` must be rewritten as `Map(((Number)this["amount"]).doubleValue() > 50)`. The latter is also valid DRL but less familiar to authors.
- **Negative**: rule authors copying examples from the upstream Drools docs may use `eval()` and hit the sandbox. The doc warns about this in [16-drl-sandboxing.md](16-drl-sandboxing.md) and [17-rule-development.md](17-rule-development.md).

### References
- [`DrlSanitizer.java:182-186`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L182-L186) — the eval check
- [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) — `EvalBlocking` nested test class
- [16-drl-sandboxing.md](16-drl-sandboxing.md) — full sandbox reference

---

## ADR-010: Rate limiting in-memory (not Redis-backed)

**Status**: Accepted (consider revisiting at scale)
**Date**: project inception

### Context

The rate limiter tracks per-client request counts. Storage options:
1. **In-memory `ConcurrentHashMap`** — simple, fast, but per-replica.
2. **Redis-backed** — cluster-wide, but adds latency and a dependency.

### Decision

In-memory. Per-replica buckets. A client's effective limit is `configured_limit × replica_count`.

### Alternatives considered

- **Redis-backed (using Redis-Rate-Limiter pattern)**: adds 1-3ms per request for Redis call. Adds Redis as critical-path dependency. Rejected initially.
- **Hybrid (cluster-wide ceiling + per-replica burst)**: more complex; defer until needed.

### Consequences

- **Positive**: zero added latency.
- **Positive**: no external dependency on Redis being healthy for rate limiting to work.
- **Positive**: simple to reason about per-replica.
- **Negative**: cluster-wide rate is `configured × replica_count`. A configured 1000/min limit with 4 replicas = 4000/min effective ceiling.
- **Negative**: client requests load-balanced across replicas → effective per-replica rate is `actual_rate / replica_count`. So the limit you configure is roughly the effective limit a single client can hit at one replica, but the cluster sees 4× that.
- **Negative**: cap of 10,000 distinct clients per replica (`max-clients`). Beyond that, new clients are rejected.

### When to revisit

- More than 10 replicas AND need precise cluster-wide limits.
- Specific contractual obligation to enforce a hard ceiling.
- Spoofed-client-ID attack fills `max-clients` cap on multiple replicas.

### 2026-08-20 addendum — client keying is IP-only, with an opt-in trust-proxy toggle

The original doc described a multi-tier client identity (`X-API-Key` → `Authorization` → `X-Client-Id` → IP fallback). That has been simplified and hardened (see [ADR-018](#adr-018-rate-limiter-keys-on-ip-only-optional-trust-proxy-2026-08-20)): `RateLimitingFilter` now keys **only** on `ip:{request.getRemoteAddr()}`. The application-level headers are unauthenticated on the public `/execute-rule` API and are no longer read for keying (trusting them let any caller forge a bucket). `X-Forwarded-For` is ignored by default and honored (left-most entry) only when `drools.rate-limiting.trust-proxy=true`. LRU eviction still bounds the client map at `max-clients`.

### References
- [`RateLimitingConfig.java`](../src/main/java/com/company/drools/config/RateLimitingConfig.java) — in-memory implementation
- [`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) — IP-only keying + `trust-proxy`
- [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md)
- [ADR-018](#adr-018-rate-limiter-keys-on-ip-only-optional-trust-proxy-2026-08-20)

---

## ADR-011: Custom validation annotations alongside Jakarta

**Status**: Accepted
**Date**: project inception

### Context

Jakarta Bean Validation provides standard annotations (`@NotNull`, `@Size`, `@Pattern`). They cover ~80% of validation needs.

Project-specific validation (rule ID format with path-traversal check, data field count + value type checks) needs custom logic.

### Decision

Use Jakarta annotations where they fit (`@NotNull`, `@Size`). Add custom annotations (`@ValidRuleId`, `@ValidRuleData`) for project-specific rules. Custom annotations use Jakarta's `ConstraintValidator` framework — they integrate seamlessly with `@Valid` on controller signatures.

### Alternatives considered

- **Jakarta only**: would require pre-validation logic in controllers. Less declarative, harder to test.
- **Custom validation framework (no Jakarta)**: rejected. Reinvents the wheel.
- **Schema validation (JSON Schema)**: would work for syntactic validation but not for project-specific semantic rules (e.g., "no path traversal patterns").

### Consequences

- **Positive**: validation is declarative on the DTO. Reads naturally.
- **Positive**: `@Valid` triggers all validators; `MethodArgumentNotValidException` is uniformly mapped to `INVALID_INPUT` (HTTP 400).
- **Positive**: easy to test — instantiate the validator, call `isValid()`.
- **Negative**: custom annotations are project-specific code, must be maintained.
- **Negative**: `RuleIdValidator` has a quirk (silently trims whitespace; see CODE_FINDINGS F-032) that's a paper cut.

### References
- [`ValidRuleId`](../src/main/java/com/company/drools/api/validation/ValidRuleId.java) + [`RuleIdValidator`](../src/main/java/com/company/drools/api/validation/RuleIdValidator.java)
- [`ValidRuleData`](../src/main/java/com/company/drools/api/validation/ValidRuleData.java) + [`RuleDataValidator`](../src/main/java/com/company/drools/api/validation/RuleDataValidator.java)

---

## ADR-012: Drools 8.44.0.Final (not latest 8.x or 9.x)

**Status**: Superseded by [ADR-014](#adr-014-drools-8--10-migration-2026-05-09) on 2026-05-09
**Date**: project inception

### Context

Drools releases 8.x patch versions every few months. Drools 9.x is in development. We had to pin a version.

### Decision

Pin to **8.44.0.Final**. Don't auto-upgrade.

### Alternatives considered

- **Latest 8.x**: tempting but each upgrade requires testing. Defer until there's a concrete reason (security fix, needed feature).
- **Drools 9.x**: in development; breaking changes possible. Wait for stable release + community validation.
- **Drools 7.x**: end-of-life; missing security patches. Rejected.

### Consequences

- **Positive**: stable, well-tested baseline.
- **Negative**: missing patches in newer 8.x releases. Schedule a periodic re-evaluation.
- **Negative**: when the team eventually upgrades, they'll need to re-test against the sample-rules + DrlSanitizer behavior.

### When to revisit

- Security advisory in 8.44.0 (or below).
- Specific feature in newer 8.x version that we want to use.
- Drools 9.x stable release + community-validated migration guide.

### Status update (2026-05-09)

Revisited and superseded. The project bumped to **Drools 10.2.0** as part of the 2026-05-09 stack modernization. See [ADR-014](#adr-014-drools-8--10-migration-2026-05-09) for the current state.

### References
- [`pom.xml`](../pom.xml) — `drools.version`
- [03-tech-stack.md](03-tech-stack.md) — version table

---

## ADR-013: Java 17 → 25 + Spring Boot modernization (2026-05-09)

**Status**: Accepted
**Date**: 2026-05-09

### Context

By May 2026, the project had been running on Java 17 (LTS, Sept 2021) with Spring Boot 3.2.5 (April 2024) for ~14 months. Both were two LTS / 3 minor versions behind current. The deferred security finding **#30** ("Outdated dependencies — skipped per user") covered the broader dependency stack — Lombok 1.18.30, AWS SDK 2.20.56, Maven plugins on 2-year-old versions.

The user decided to invest in a single coordinated modernization rather than incremental bumps. Three options on the table:
1. Java 17 → 21 only — zero library bumps, minimal benefit.
2. Java 25 + Spring Boot 3.5.x + dependency sweep — current latest stable, closes #30.
3. Java 25 + Drools 10 + everything — option 2 plus the Drools major bump (see ADR-014).

### Decision

Bump to **Java 25** + **Spring Boot 3.5.3** + bring all build plugins, Lombok, AWS SDK, and Resilience4j to current latest stable. Maven Enforcer rule changes from `[17,18)` to `[25,26)`. Closes security finding #30 in the same change.

### Alternatives considered

- **Stay on Java 17 + bump dependencies separately**: rejected. Bundling reduces total test/regression cost.
- **Jump to Spring Boot 4.x**: rejected. Spring Boot 4 was approaching GA but not yet on Maven Central as of 2026-05-09. Stay on the latest stable 3.x.
- **Java 21 (one LTS forward) instead of 25**: rejected. The user explicitly chose maximum modernization.

### Consequences

- **Positive**: project on current latest LTS with Premier support through Sept 2030.
- **Positive**: closed deferred security finding #30 (dependency sweep).
- **Positive**: a single coordinated test pass validates everything.
- **Required code changes**:
  - `RestTemplateBuilder.setConnectTimeout/setReadTimeout` deprecated in Spring Boot 3.5 → use `connectTimeout/readTimeout` ([`TimeoutConfig.java`](../src/main/java/com/company/drools/config/TimeoutConfig.java)).
  - `@MockBean` deprecated in Spring Boot 3.4, removed in 3.5+ → use `@MockitoBean` ([`RuleExecutionControllerTest.java`](../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java)).
- **Negative**: Spring Boot 3.5 doesn't officially list Java 25 in its tested matrix; project relies on forward-compat from the Java 17 baseline. So far works.

### Concrete versions pinned

| Component | Old | New |
|---|---|---|
| Java | 17 | **25** |
| Spring Boot | 3.2.5 | **3.5.3** |
| AWS SDK BOM | 2.20.56 | **2.34.0** |
| Lombok | 1.18.30 | **1.18.38** |
| Resilience4j | 2.2.0 | **2.3.0** |
| Micrometer | 1.12.4 | **1.14.7** |
| Testcontainers | 1.19.7 | **1.21.3** |
| maven-compiler-plugin | 3.11.0 | **3.15.0** |
| maven-enforcer-plugin | 3.3.0 | **3.6.2** |
| jacoco-maven-plugin | 0.8.8 | **0.8.13** |
| spotless-maven-plugin | 2.36.0 | **2.44.5** |
| google-java-format | 1.17.0 | **1.27.0** |
| spotbugs-maven-plugin | 4.7.3.0 | **4.9.3.0** |

### When to revisit

- Java 26 LTS (expected ~Sept 2026).
- Spring Boot 4.x reaches GA + ecosystem stabilizes.
- New security advisory in any of the bumped libraries.

### References
- [`pom.xml`](../pom.xml)
- [`Dockerfile`](../Dockerfile) — base images bumped to `maven:3.9-eclipse-temurin-25` and `amazoncorretto:25-alpine-jdk`
- [03-tech-stack.md](03-tech-stack.md) — full version table
- [`.ai-workspace/project-plans/stack-modernization-plan.md`](../.ai-workspace/project-plans/stack-modernization-plan.md) — full modernization plan
- [`.ai-workspace/project-plans/security-backlog.md`](../.ai-workspace/project-plans/security-backlog.md) — finding #30 closure

---

## ADR-014: Drools 8 → 10 migration (2026-05-09)

**Status**: Accepted (supersedes [ADR-012](#adr-012-drools-8440final-not-latest-8x-or-9x))
**Date**: 2026-05-09

### Context

Drools 10.x is the current major as of 2026-05-09. Drools 8.x line is in maintenance. As part of the 2026-05-09 stack modernization (see [ADR-013](#adr-013-java-17--25--spring-boot-modernization-2026-05-09)), the team chose to bump Drools too.

The [Drools 10 migration guide](https://kie.apache.org/docs/10.0.x/drools/drools/migration-guide/index.html) explicitly states: *"All APIs and DRL syntax are compatible"* between Drools 8 and 10. The traditional `KieServices`/`KieContainer`/`KieBase`/`KieSession` API the project uses is "still supported but discouraged" — meaning it works without code rewrites.

### Decision

Bump to **Drools 10.2.0**. Replace `drools-core` + `drools-compiler` + `drools-mvel` (3 dependencies) with the single `drools-engine` aggregator. Keep `drools-mvel` as an explicit dependency because traditional DRL `then` blocks default to MVEL semantics and `drools-engine` no longer bundles MVEL by default.

Keep [ADR-001](#adr-001-traditional-drl-syntax-only-not-rule-units--oopath) (traditional DRL only — no Rule Units / OOPath) — the migration guide confirms traditional DRL is still fully supported in Drools 10.

Keep [ADR-003](#adr-003-kiecontainer-atomic-swap-with-disposal) (atomic-swap KieContainer pattern) — Drools 10 didn't change KieContainer lifecycle. Memory tests confirm the disposal-based leak fix still applies.

### Alternatives considered

- **Drools 9.x**: same migration cost as 10, less support runway. Rejected.
- **Stay on latest 8.x (e.g., 8.45.x)**: lowest risk, no rewrite at all. Rejected — user chose maximum modernization.
- **Adopt Rule Units / OOPath as part of the migration**: would require rewriting [`DroolsEngineService.java`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) orchestration. Out of scope; the existing ADR-001 rejection still stands.

### Consequences

- **Positive**: Drools 10 baselines on JDK 17+, so Java 25 is forward-compatible.
- **Positive**: single `drools-engine` aggregator replaces 3 individual dependencies. Cleaner pom.
- **Positive**: future-proof — Drools 8.x is in maintenance, 10.x is the active line.
- **Negative**: `drools-mvel` is officially deprecated by the Drools team but still required at runtime for this project's DRL dialect. Will need attention if Drools removes it in a future major.
- **Negative**: executable model (the new default in `drools-engine`) has three documented behavior differences from MVEL: invalid type coercion (`(String) intValue` no longer tolerated), strict generics, wrapper coercion (`10` doesn't auto-coerce to `Long`). All 17 sample DRL files were reviewed; no rewrites needed (none of them use those patterns).

### Code changes

Single coordinate swap in [`pom.xml`](../pom.xml):

```xml
<!-- OLD -->
<dependency><groupId>org.drools</groupId><artifactId>drools-core</artifactId><version>${drools.version}</version></dependency>
<dependency><groupId>org.drools</groupId><artifactId>drools-compiler</artifactId><version>${drools.version}</version></dependency>
<dependency><groupId>org.drools</groupId><artifactId>drools-mvel</artifactId><version>${drools.version}</version></dependency>

<!-- NEW -->
<dependency><groupId>org.drools</groupId><artifactId>drools-engine</artifactId><version>${drools.version}</version></dependency>
<dependency><groupId>org.drools</groupId><artifactId>drools-mvel</artifactId><version>${drools.version}</version></dependency>
```

No application Java code changes required.

### Validation

- All 584 non-Docker tests pass on Java 25 + Drools 10.2.0 + Spring Boot 3.5.3.
- All 17 sample rules produce identical outputs to pre-migration (verified via `RuleExecutionIntegrationTest$SampleRulesExecution`).
- KieContainer disposal still functions; memory leak fix from prior Phase 6 still applies.
- **Validated end-to-end on 2026-05-09 / 2026-05-10** against the full docker-compose stack (app + LocalStack + Redis) using all 17 production-shaped sample DRL files loaded from S3. Result: **PASS** with 0 DRL changes and 0 application config changes required. KieContainer atomic-swap disposal confirmed leak-free (post-GC heap below pre-test baseline after 10 successive full refreshes). 2 pre-existing findings (single-rule refresh KieContainer-replacement bug; malformed JSON returns 500 instead of 400) raised to backlog — neither is a Drools 10 regression. Full results: [`.ai-workspace/project-plans/e2e-validation-checklist.md`](../.ai-workspace/project-plans/e2e-validation-checklist.md).

### When to revisit

- Drools removes `drools-mvel` artifact entirely in a future major. We'd need to either explicitly switch DRL files to Java dialect via `dialect "java"` per rule, or accept whatever replacement Drools provides.
- Drools 11.x stable release + community-validated migration guide.

### References
- [`pom.xml`](../pom.xml) — Drools dependencies
- [03-tech-stack.md](03-tech-stack.md) — Rule engine section
- [Drools 10 migration guide](https://kie.apache.org/docs/10.0.x/drools/drools/migration-guide/index.html)
- [Drools 10 traditional DRL reference](https://kie.apache.org/docs/10.0.x/drools/drools/language-reference-traditional/index.html)
- [`.ai-workspace/project-plans/stack-modernization-plan.md`](../.ai-workspace/project-plans/stack-modernization-plan.md)

---

## ADR-016: Redis decorator + pub/sub for multi-instance DRL cache (2026-05-20)

**Status**: Accepted
**Date**: 2026-05-20
**Supersedes**: [ADR-004](#adr-004-locallrucache-uses-write-lock-on-get), [ADR-005](#adr-005-redis-bean-exists-but-is-dormant-by-default)

### Context

Forensic trace of the `cache/` package revealed two problems:

1. **The existing cache layer was dead code.** Both `LocalLRUCache` (`@Primary` `RuleCache` bean) and `RedisRuleCache` (`@ConditionalOnProperty("redis.enabled")`) were written to during refresh — `warmUp()`, `put()`, `clear()` — but `.get()` was never called from any production code path. The "cache hit rate ~95%" metric reported in earlier docs was statistically true but operationally meaningless because the cache was never on the read path. Execution reads from `DroolsEngineService.loadedRules` (a `ConcurrentHashMap` populated by `loadRules()`) and `kieContainer` directly.

2. **Multi-instance compiled-state divergence.** At the target deployment scale (3–5 ECS tasks × 10,000+ rules), when one task receives `POST /admin/refresh-rules/{id}`, only that task recompiles its `kieContainer`. Sibling tasks keep serving the stale compiled rule until they themselves refresh. The dead Redis cache didn't help here because execution doesn't read from Redis (it reads from `kieContainer`).

### Decision

**Two coordinated changes:**

1. **Read-through Redis cache decorator.** Replace the parallel `RuleCache` abstraction with `RedisCachedRuleStorage` — a decorator on `RuleStorage` that transparently wraps the base storage (`S3RuleStorage` / `LocalFileStorage` / `InMemoryRuleStorageAdapter`) when `REDIS_ENABLED=true`. Cache key prefix `drools:rule:{ruleId}`, TTL configurable via `REDIS_DRL_RULES_TTL_MINUTES` (default 15 min). All Redis ops wrapped in resilience4j circuit breaker; failures silently degrade to delegate-only.

2. **Redis Pub/Sub for cross-task fan-out.** `RuleRefreshPublisher` publishes a `RefreshEvent` (JSON via Jackson) on `drools:rule:events` after a successful local refresh. `RuleRefreshSubscriber` on every task receives events and:
   - Skips events emitted by itself (per-instance UUID dedup via `droolsInstanceId` bean)
   - For `RULE_REFRESHED`: fetches the rule (cache is already warm from publisher), calls `DroolsEngineService.loadOrReplaceRule()`
   - For `RULE_REFRESHED_BULK`: fetches all rules, calls `DroolsEngineService.loadRules()`
   - For `RULE_DELETED`: logs INFO only (v1 limitation; admin has no delete endpoint today)

`AdminController` is wired to call the publisher after local refresh succeeds.

Active feature flags:
- `REDIS_ENABLED` (default `false`) — wraps storage in `RedisCachedRuleStorage`
- `REDIS_PUBSUB_ENABLED` (default `true` when Redis on) — wires publisher + subscriber + listener container

### Alternatives considered

| Option | Why rejected |
|---|---|
| **Keep dead cache, leave LRU in code "in case someone needs it later"** | Misleading. Three years of "cache hit rate" metrics that meant nothing. Removed to clarify the actual data flow. |
| **Auto-refresh polling instead of pub/sub** | Cross-task convergence latency ≥ polling interval. At any usable interval (60s) that's slower than pub/sub (~1 sec). Also wastes S3 fetches on every poll. |
| **AWS SNS/SQS for cross-task events** | Adds infra dependency. We already have Redis. Pub/sub on Redis is "free". |
| **Operator fan-out** (CI calls each task IP after refresh) | Operationally awkward; needs task IP discovery; doesn't work over ALB. |
| **In-process L1 cache above Redis** | After analysis, in-process LRU above Redis adds no value in this access pattern: `loadedRules` already holds all rules in memory, and execution doesn't read from storage at all. Removed entirely. |

### Consequences

**Positive:**
- Cross-task compiled-state converges within ~1 sec for single-rule refresh (~7 min for bulk, gated by per-task compile time — same as the kjar-deferred limitation).
- Redis is now a *real* cache: multi-task ECS deployments share S3 fetches.
- Cross-service consumers can subscribe to `drools:rule:events` for rule-invalidation notifications.
- Service degrades gracefully when Redis is down (circuit breaker → fallback to base storage).
- ~600 LOC of misleading dead code removed.

**Negative:**
- Env var migration: `REDIS_TTL_MINUTES` → `REDIS_DRL_RULES_TTL_MINUTES`; `LRU_CACHE_MAX_SIZE` removed. Operators with custom values silently fall back to defaults.
- `/admin/rules` response no longer has the `cached` boolean field (it was always meaningless).
- Pub/sub events are fire-and-forget — a disconnected subscriber can miss events between disconnect and reconnect. Mitigation: 15-min TTL acts as eventual-consistency ceiling.

### References

- [`RedisCachedRuleStorage.java`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java)
- [`RuleRefreshPublisher.java`](../src/main/java/com/company/drools/cache/RuleRefreshPublisher.java)
- [`RuleRefreshSubscriber.java`](../src/main/java/com/company/drools/cache/RuleRefreshSubscriber.java)
- [`RefreshEvent.java`](../src/main/java/com/company/drools/cache/RefreshEvent.java)
- [`InstanceIdConfig.java`](../src/main/java/com/company/drools/config/InstanceIdConfig.java)
- [`StorageFactory.java`](../src/main/java/com/company/drools/storage/StorageFactory.java) — wiring
- [`.ai-workspace/project-plans/redis-cache-layering-plan.md`](../.ai-workspace/project-plans/redis-cache-layering-plan.md)
- [`full-docker-test-plan.md`](../full-docker-test-plan.md) — Steps 8 and 9

### 2026-05-24 update — Phase 9.4 follow-up hardening

Phase 9.4 of the load-test harness (Redis kill mid-load) surfaced three gaps in this ADR's "All Redis ops wrapped in circuit breaker" / "Service degrades gracefully" claims. All three landed as production-side changes on `feature/redis-cache-pubsub` (commits `952f0a1` harness + `4b8997e` hardening):

1. **`scanKeys()` was bypassing the CB.** Every other Redis op was correctly wrapped, but the SCAN call inside `invalidateAll` / `collectFromRedis` executed `redisTemplate.execute(RedisCallback)` directly. Bulk-path failures during outages didn't count toward the sliding window. Fixed by wrapping SCAN in `CircuitBreaker.decorateSupplier(redisCircuitBreaker, ...)` at [`RedisCachedRuleStorage.java:344`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java#L344); on `CallNotPermittedException` or generic `Exception` returns `Set.of()` (both callers already short-circuit on empty key set and degrade to delegate).

2. **Lettuce timeout was tied to the CB's slow-call threshold.** `spring.data.redis.timeout=2000ms` was an exact match for the CB's `slowCallDurationThreshold=2s`, putting Lettuce timeouts in an ambiguous classification window. Lowered to `${REDIS_TIMEOUT:500ms}` so command timeouts unambiguously count as CB failures (not slow calls). Operators raise `REDIS_TIMEOUT=1000ms` for distant Redis.

3. **`RedisMessageListenerContainer` had no explicit recovery backoff.** Spring's implicit default doesn't guarantee re-subscription after a transport-level disconnect. Added `setRecoveryBackoff(new FixedBackOff(2_000L, Long.MAX_VALUE))` to bound the worst-case re-subscribe latency to ≤2s after Redis is reachable.

Phase 9.4 results (`--quick`): graceful-degradation acceptance PASS (0% JMeter errors across kill+restart). CB-engagement timing and recovery-convergence race remain as **deferred follow-ups** documented in [`39-load-test-findings.md`](39-load-test-findings.md) Phase 9.4 addendum + [`.ai-workspace/project-plans/redis-cb-hardening-plan.md`](../.ai-workspace/project-plans/redis-cb-hardening-plan.md).

---

## ADR-017: Admin auth fail-closed on prod/docker profiles (2026-08-20)

**Status**: Accepted
**Date**: 2026-08-20

### Context

[ADR-006](#adr-006-adminauthfilter-instead-of-spring-security) shipped a dev-friendly bypass: a blank `ADMIN_API_KEY` left `/admin/*` open (with a WARN log) on every profile. That is convenient for local work but means a production deploy that simply forgot to set the variable would boot happily with an unauthenticated admin surface (refresh-rules, thread-pools, memory endpoints). A re-review flagged this as the top-severity issue.

### Decision

`AdminAuthFilter` distinguishes **deployable profiles** (`KEY_REQUIRED_PROFILES = {prod, docker}`) from developer profiles. When `ADMIN_API_KEY` is blank **and** a deployable profile is active, the filter throws `IllegalStateException` at construction — the Spring context fails to start (fail-closed). On `local`/`dev`, a blank key still starts open with a WARN. The key comparison also moved to `MessageDigest.isEqual` (constant-time). The `docker` profile's compose file supplies `ADMIN_API_KEY=admin-secret` so the local docker stack starts cleanly.

### Alternatives considered

- **Keep open-with-WARN everywhere**: rejected — a missed env var is a silent, high-impact misconfiguration; a WARN in a log nobody reads is not a control.
- **Fail-closed on all profiles including local/dev**: rejected — would break the zero-config local developer loop for no security gain (localhost admin surface is not the threat model).
- **Default to a generated key**: rejected — a per-boot random key breaks scripted admin calls and hides the requirement rather than surfacing it.

### Consequences

- **Positive**: production cannot boot with an open admin surface; the failure is loud and at startup, not latent.
- **Positive**: constant-time compare removes the timing-attack paper cut from ADR-006.
- **Negative**: a `prod`/`docker` deploy now hard-requires the env var — an intentional trade (fail fast over fail open). Documented in the runbook and getting-started guides.

### References
- [`AdminAuthFilter.java`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) — `KEY_REQUIRED_PROFILES`, startup guard, `MessageDigest.isEqual`
- [15-admin-authentication.md](15-admin-authentication.md), [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md)
- [ADR-006](#adr-006-adminauthfilter-instead-of-spring-security)

---

## ADR-018: Rate limiter keys on IP only; optional trust-proxy (2026-08-20)

**Status**: Accepted
**Date**: 2026-08-20

### Context

An earlier design keyed the rate limiter on a multi-tier client identity: `X-API-Key` → `Authorization: Bearer` → `X-Client-Id` → IP fallback. On the public, unauthenticated `/execute-rule` API those headers are attacker-controlled: any caller could set a unique `X-API-Key` per request and mint a fresh bucket, defeating the limiter entirely. Similarly, honoring `X-Forwarded-For` unconditionally lets a caller spoof arbitrary source IPs.

### Decision

Key **only** on the transport source address: `ip:{request.getRemoteAddr()}`. The application-level headers are never consulted for keying. `X-Forwarded-For` is ignored by default and trusted (left-most entry becomes the key) only when `drools.rate-limiting.trust-proxy=true` — an opt-in for deployments sitting behind a proxy/LB that overwrites the header. The client map keeps LRU eviction bounded by `max-clients`.

### Alternatives considered

- **Keep the multi-tier header identity**: rejected — trivially bypassable on an unauthenticated endpoint.
- **Always trust `X-Forwarded-For`**: rejected — spoofable; would let a caller impersonate many source IPs. The `trust-proxy` toggle makes trusting it a deliberate, environment-scoped decision.
- **Authenticate `/execute-rule` and key on identity**: out of scope — auth is delegated to the gateway per ADR-006's threat model; can be revisited if the service takes on first-party auth.

### Consequences

- **Positive**: the limiter can no longer be trivially bypassed by header forgery.
- **Positive**: `trust-proxy` gives correct per-client limiting behind a trusted LB without opening a spoofing hole elsewhere.
- **Negative**: without `trust-proxy`, everyone behind a shared NAT/LB shares one bucket (documented in the FAQ and rate-limiting doc).

### References
- [`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) — `getClientIdentifier`, `trust-proxy`
- [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md), [35-faq.md](35-faq.md)
- [ADR-010](#adr-010-rate-limiting-in-memory-not-redis-backed)

---

## ADR-019: Rule-execution timeout halt + thread-pool load-shed 503 (2026-08-20)

**Status**: Accepted
**Date**: 2026-08-20

### Context

Two failure modes needed bounded, well-defined behavior: (1) a rule (or fact set) that runs far longer than expected, and (2) inbound execute load that exceeds the rule-execution pool's capacity. Left unhandled, the first ties up a worker thread indefinitely and the second either queues unboundedly (latency blowup / OOM) or fails opaquely.

### Decision

- **Timeout → halt → 408.** Rule execution runs under a timeout; on expiry the engine calls `KieSession.halt()` to stop the firing session and the request is mapped to HTTP 408 (Request Timeout). Halting releases the worker rather than letting a runaway session hold it.
- **Pool saturation → load-shed → 503.** The rule-execution `ThreadPoolExecutor` uses `AbortPolicy`. When the pool and its queue are full, submissions are rejected; the rejection is translated to `ServiceUnavailableException` → HTTP 503. This is a distinct 503 source from the circuit breakers and from graceful-shutdown request refusal.

### Alternatives considered

- **`CallerRunsPolicy` for the exec pool**: rejected — running rule execution on the servlet/acceptor thread under overload couples request intake to execution latency and can stall the container. Explicit shed (503) gives callers a clear, retryable signal.
- **Interrupt the thread instead of `KieSession.halt()`**: rejected — Drools' supported cooperative stop is `halt()`; thread interruption doesn't reliably unwind a firing session.
- **Unbounded queue**: rejected — converts overload into latency and heap growth instead of a fast, honest 503.

### Consequences

- **Positive**: both runaway rules and overload have deterministic, documented outcomes (408 / 503) instead of hung threads.
- **Positive**: 503 is a retryable backpressure signal; operators scale `DROOLS_THREAD_POOL_MAX_SIZE` / queue depth in response.
- **Negative**: a 408 means the partial session is discarded; the caller must retry idempotently. Two separate 503 paths (breaker-open and pool-shed) exist — the troubleshooting guide distinguishes them.

### References
- [`DroolsEngineService.java`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) — timeout + `KieSession.halt()`
- [`ServiceUnavailableException.java`](../src/main/java/com/company/drools/api/exception/ServiceUnavailableException.java), [`GlobalExceptionHandler.java`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java)
- [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) — "Diagnose 503 errors"

---

## ADR-020: refreshLock separated from the rule read-write lock (2026-08-20)

**Status**: Accepted
**Date**: 2026-08-20

### Context

ADR-003 established "compile outside the lock, take the write lock only for the swap." But refresh operations also need to **serialize with each other** — two concurrent single-rule merges, or a bulk refresh racing a single-rule refresh, must not interleave and lose updates. Reusing the rule read-write lock to serialize refreshes would either re-introduce lock-held-through-compile latency or make the locking discipline hard to reason about.

### Decision

Introduce a dedicated `ReentrantLock refreshLock` in `DroolsEngineService`, separate from the `ReentrantReadWriteLock` that guards the compiled rule state. Every refresh path (`loadRules`, `loadOrReplaceRule`) takes `refreshLock` for the whole compile-then-swap sequence, so refreshes are strictly serialized; the compile still runs **outside** the read-write write lock, which is acquired only for the brief metadata/state swap. Pub/sub-driven refreshes from siblings run on a dedicated single-thread `ruleRefreshListenerExecutor`, so listener callbacks never block the Redis message thread and are naturally serialized before contending on `refreshLock`.

### Alternatives considered

- **Serialize refreshes on the write lock**: rejected — either holds the write lock through compilation (blocks all readers, the exact latency problem ADR-003 solved) or requires fragile lock upgrade/downgrade.
- **Allow concurrent refreshes (no refresh serialization)**: rejected — concurrent merges can lose each other's rule updates (the Finding #1 class of bug).
- **Run pub/sub refreshes inline on the listener thread**: rejected — a slow compile would stall the Redis message container and delay/redeliver subsequent events.

### Consequences

- **Positive**: refreshes are serialized and correct without ever holding the read-write write lock during compilation; reader latency stays flat.
- **Positive**: the single-thread listener executor decouples sibling-refresh compilation from the Redis transport.
- **Negative**: two lock primitives to keep straight — `refreshLock` (serialize refreshes) vs `rulesLock` (guard the swap). Documented in the engine's Javadoc.

### References
- [`DroolsEngineService.java`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) — `refreshLock`, `rulesLock`, `ruleRefreshListenerExecutor`
- [ADR-003](#adr-003-kiecontainer-atomic-swap-with-disposal)

---

## ADR-021: Redis prod TLS/auth enforcement + removal of Jackson default typing (2026-08-20)

**Status**: Accepted
**Date**: 2026-08-20

### Context

Two Redis-related exposures remained after ADR-016. (1) Nothing forced production Redis connections to be encrypted or authenticated — a misconfigured deploy could talk to Redis over plaintext `redis://` with no credentials (this is deferred security finding **#28**). (2) `RedisConfig`'s value serializer previously configured Jackson with default typing (polymorphic type info embedded in the cached JSON) — a known deserialization-gadget (RCE) risk if an attacker can influence cached bytes.

### Decision

- **`RedisSecurityValidator` (`@Profile("prod")`).** On the `prod` profile with Redis enabled, a startup validator throws `IllegalStateException` unless `REDIS_URL` uses the `rediss://` (TLS) scheme **and** includes credentials (`rediss://user:password@host:port`). Non-prod profiles are unaffected. This makes finding #28 enforced-on-prod rather than merely documented.
- **No Jackson default typing in `RedisConfig`.** The Redis value serializer uses a plain `ObjectMapper` with default typing removed, so cached payloads carry no polymorphic `@class` metadata to exploit.

### Alternatives considered

- **Document the TLS/auth requirement without enforcing it**: rejected — the whole point of finding #28 was that documentation alone hadn't prevented insecure configs; a startup guard fails closed.
- **Enforce TLS on all profiles**: rejected — local/dev and the docker stack use a plaintext LocalStack/Redis; enforcing `rediss://` there would break the zero-config loop.
- **Keep default typing but add an allow-list `PolymorphicTypeValidator`**: rejected for this cache — the cached values are simple DRL-text/rule payloads that don't need polymorphism, so removing typing entirely is simpler and strictly safer.

### Consequences

- **Positive**: production Redis is guaranteed encrypted and authenticated, or the app refuses to start.
- **Positive**: the Redis cache path is no longer a polymorphic-deserialization gadget surface.
- **Negative**: prod operators must provision a TLS Redis endpoint with credentials (ElastiCache in-transit encryption + AUTH). Documented in the deployment/runbook docs.

### References
- [`RedisSecurityValidator.java`](../src/main/java/com/company/drools/config/RedisSecurityValidator.java) — prod-profile startup guard
- [`RedisConfig.java`](../src/main/java/com/company/drools/config/RedisConfig.java) — serializer without default typing
- `SECURITY.md` — finding #28 status

---

## ADR-022: Storage delegate held in an `AtomicReference` (2026-08-20)

**Status**: Accepted
**Date**: 2026-08-20

### Context

`RedisCachedRuleStorage` is a decorator whose underlying `delegate` (`RuleStorage`) is set once by `StorageFactory` during wiring, after construction (`setDelegate(...)`), and then read on every request thread. A plain `volatile` field published the reference safely but expressed "set-once, read-many" only by convention.

### Decision

Hold the delegate in an `AtomicReference<RuleStorage>` (replacing the previous `volatile` field). `setDelegate` does `delegate.set(Objects.requireNonNull(...))`; read paths call `delegate.get()`. This gives safe cross-thread publication of the set-once reference and a clear home for the null-guard, and leaves room for atomic swap semantics if the delegate ever needs to be replaced at runtime.

### Alternatives considered

- **Keep `volatile RuleStorage delegate`**: works for safe publication but doesn't express the set-once/atomic intent and scatters null-handling.
- **`final` delegate via constructor injection**: rejected — the decorator must be constructed before `StorageFactory` decides and injects the concrete base storage; the reference genuinely isn't known at construction time.

### Consequences

- **Positive**: safe publication is explicit; the reference has one guarded setter and a single read accessor.
- **Negative**: negligible — an `AtomicReference` indirection on a per-request read (immeasurable next to Redis/S3 I/O).

### References
- [`RedisCachedRuleStorage.java`](../src/main/java/com/company/drools/storage/RedisCachedRuleStorage.java) — `AtomicReference<RuleStorage> delegate`, `setDelegate`
- [`StorageFactory.java`](../src/main/java/com/company/drools/storage/StorageFactory.java) — wiring

---

## ADR-023: CI workflow + verify-bound quality gates (2026-08-20)

**Status**: Accepted
**Date**: 2026-08-20

### Context

Quality tooling (Spotless, SpotBugs, JaCoCo, OWASP dependency-check) existed in the pom but several checks weren't enforced automatically, and there was no CI pipeline running the Testcontainers integration tests (which need a real Docker daemon and are surefire-excluded on the macOS dev loop). Gates that aren't bound to a phase and run in CI are advisory at best.

### Decision

- **Bind the checks to `verify`.** `spotless:check`, `spotbugs:check`, and `jacoco:check` execute in the `verify` phase, so `mvn verify` fails on formatting drift, static-analysis findings, or coverage below floor. The JaCoCo floor is **0.88 instruction / 0.74 branch**. OWASP dependency-check runs in CI.
- **Add `.github/workflows/ci.yml`.** On Linux (`ubuntu-latest`, which has a Docker daemon) it runs `mvn -B clean verify` (unit suite + the verify-bound gates) and then the three Testcontainers integration test classes (`S3StorageIntegrationTest`, `RedisCachedStorageIntegrationTest`, `RedisPubSubIntegrationTest`) that are excluded from the default local run.

### Alternatives considered

- **Leave checks unbound / manual**: rejected — advisory gates drift; binding to `verify` makes them non-optional.
- **Run integration tests on every developer machine by default**: rejected — the Testcontainers ITs need Docker-in-Docker that isn't reliable on the macOS dev loop; CI (Linux) is the right place, with a surefire exclusion locally.
- **Set the JaCoCo floor at the measured coverage**: rejected — a small margin (floor below the measured 90.1/78.4) avoids flaky failures on incidental line moves while still preventing regressions.

### Consequences

- **Positive**: formatting, static analysis, coverage, dependency CVEs, and the integration suite are all enforced on every push.
- **Positive**: the coverage floor is a real gate, not documentation.
- **Negative**: `mvn verify` is slower and stricter than `mvn test`; contributors run `spotless:apply` before pushing (documented in CONTRIBUTING.md).

### References
- [`pom.xml`](../pom.xml) — `spotless-check` / `spotbugs-check` / `jacoco-check` bound to `verify`; dependency-check
- [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) — verify + Testcontainers ITs on Linux
- [28-testing-guide.md](28-testing-guide.md), `CONTRIBUTING.md`

---

## Extension points

These are the documented seams for extending the service without forking it.

### Add a custom storage backend

1. Implement [`RuleStorage`](../src/main/java/com/company/drools/storage/RuleStorage.java) interface (9 methods: get, getAllRules, etc.).
2. Annotate your bean with a Spring name: `@Component("myBackend")`.
3. Add a case in [`StorageFactory.java`](../src/main/java/com/company/drools/storage/StorageFactory.java):
   ```java
   case "mybackend" -> applicationContext.getBean("myBackend", RuleStorage.class);
   ```
4. Set `RULE_SOURCE=mybackend` to activate.
5. Implement path-traversal protection in your backend (defense in depth — see existing `S3RuleStorage` and `LocalFileStorage`).
6. Document the new backend in [09-environment-variables-reference.md](09-environment-variables-reference.md) and [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).

### Add a custom validation rule

1. Define an annotation (`@MyValidRule`) with `@Constraint(validatedBy = MyValidator.class)`.
2. Implement `ConstraintValidator<MyValidRule, MyTargetType>`.
3. Apply the annotation to the DTO field.
4. Add a test in `api/validation/`.

### Add a new Micrometer metric

```java
@Component
class MyService {
  private final MeterRegistry meterRegistry;
  // constructor injection ...

  void doThing() {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      // work
      meterRegistry.counter("drools.mything.count", "outcome", "success").increment();
    } finally {
      sample.stop(meterRegistry.timer("drools.mything.duration"));
    }
  }
}
```

Tag namespaces:
- Avoid high-cardinality tags (don't tag with raw user input or rule_id without `unknown` fallback for unknowns).
- Prefix custom metrics with `drools.` to keep them grouped.

### Add a new HTTP filter

```java
@Component
@Order(2)  // pick a slot in the chain — after RateLimitingFilter (@Order 1)
public class MyFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                   FilterChain chain) throws IOException, ServletException {
    // pre-processing
    chain.doFilter(req, resp);
    // post-processing
  }
}
```

Coordinate with existing `@Order` values (-1, 0, 1, none) to avoid conflicts.

### Swap the Redis cache decorator for a different cache backend

The current cache layer is `RedisCachedRuleStorage`, a decorator on `RuleStorage` activated by `@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")`. To use a different backend (e.g., Memcached, Caffeine on-instance):

1. Implement a new decorator class on `RuleStorage` with the same constructor signature pattern (a delegate set via `setDelegate(...)` from `StorageFactory`).
2. Add the matching `@ConditionalOnProperty` and update [`StorageFactory.createRuleStorage`](../src/main/java/com/company/drools/storage/StorageFactory.java) to wire the new decorator when its flag is set.
3. Disable Redis (`REDIS_ENABLED=false`) or keep both decorators with mutually-exclusive conditions.
4. If pub/sub fan-out is still needed, keep `RuleRefreshPublisher`/`Subscriber` wired against Redis — they're independent of the cache decorator.

### Swap rate limiter to Redis-backed

1. Implement a new `RateLimitingService` that uses Redis (e.g., via Lettuce + Lua atomic increment).
2. Replace the bean factory in [`RateLimitingConfig`](../src/main/java/com/company/drools/config/RateLimitingConfig.java).
3. The filter [`RateLimitingFilter`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) is unchanged (uses the interface).
4. Reverses ADR-010.

---

## Future ADRs to write (placeholders)

These decisions are anticipated but not yet documented as ADRs. The last written ADR is 023; the next free number is 024 (ADR-015 was reserved for the structured-logging decision and remains the lone gap in the index).

- **ADR-015**: Logback + logstash-logback-encoder for structured logs (over alternatives) — number reserved, write-up still pending
- **ADR-024**: Choice of Apache HTTP client for AWS SDK v2 (over Netty)
- **ADR-025**: Maven over Gradle
- **ADR-026**: Container Java distribution: Amazon Corretto (over Temurin / Liberica)

If anyone is making a major change to the corresponding component, write the ADR before changing the code.

---

## How to write a new ADR

1. Pick the next number (024, 025, ... — ADR-015 is reserved but not yet written; ADR-023 is the last written).
2. Add an entry to the index above.
3. Add the section below.
4. Cite specific code lines.
5. Include the alternatives — that's what makes it useful in 6 months.
6. Note when this ADR should be revisited (if applicable).

Avoid:
- ADRs for trivial decisions (variable naming, code style — those go in CONTRIBUTING).
- ADRs that duplicate code-level docs (cite the code instead).
- ADRs that don't include alternatives (then it's not really a decision, it's a fact).
