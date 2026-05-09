# 37 · Glossary

| | |
|---|---|
| **Audience** | All readers, especially AI agents looking up unfamiliar terms |
| **Purpose** | Definitions of every Drools term, project-specific concept, and infrastructure word used in this corpus |
| **Last updated** | 2026-05-08 |
| **Related docs** | All — this is the lookup reference |

---

## Drools terms

### `KieSession`
A stateless evaluation session. Created from a `KieBase`, used once for a single `fireAllRules()` call, then disposed. Each `/execute-rule` request creates a new `KieSession` — they are not reused. This is the unit of thread-isolation for rule execution.

### `KieBase`
The compiled rule set. Immutable once compiled. Multiple `KieSession`s can be created from one `KieBase`. In this project, the `KieBase` is held by the `KieContainer`.

### `KieContainer`
The top-level Drools container that holds compiled `KieBase` objects. **Memory-heavy** — holds compiled bytecode for all rules. The service replaces the `KieContainer` atomically on rule refresh and disposes the old one to prevent OOM (see ADR-003 in [36-architecture-decision-records.md](36-architecture-decision-records.md)).

### `KieBuilder`
Drools API for compiling `.drl` text into a `KieContainer`. Invoked by [`RuleCompiler`](../src/main/java/com/company/drools/core/engine/RuleCompiler.java) after the `DrlSanitizer` has approved the content.

### `KieFileSystem`
In-memory virtual filesystem that the `KieBuilder` reads from. We populate it with `.drl` content fetched from S3 / local files / in-memory.

### Working memory
Drools terminology for the set of facts (objects) currently being matched against rules during a single `KieSession.fireAllRules()` call. In this project's traditional-DRL pattern, working memory contains a single `Map<String,Object>` (the request's `data` field).

### Fact
An object inserted into working memory. In this project's DRL style, the fact is always a `java.util.Map`. In Drools modern style, facts can be typed POJOs.

### Agenda
Drools' internal queue of activated rules (rules whose `when` clause matched). When `fireAllRules()` is called, Drools dequeues activations from the agenda and executes their `then` blocks. Without explicit ordering (`salience`, `agenda-group`), the order is implementation-defined.

### Activation
A specific instance of a rule whose `when` clause has matched against a specific fact. Activations are placed on the agenda. One rule can have multiple activations if it matches multiple facts.

### Rule firing
Executing a rule's `then` block. "Fire" is the verb Drools uses; this project documentation uses it interchangeably with "execute".

### `salience`
A rule attribute that sets execution priority. Higher salience fires first. In this project's sample rules, **none use salience** — which is why rules stack in unpredictable order. See [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md).

### `no-loop`
A rule attribute that prevents the rule from re-firing if its own `then` block modifies a fact in a way that would re-match its `when`. Without `no-loop true`, you can get infinite loops (capped by `maxRuleFirings = 10000`).

### `lock-on-active`
Stronger than `no-loop`. Prevents re-activation by **any** modification, not just self-modification. Useful for calculation rules.

### `agenda-group`
A rule attribute used to partition rules. Only the group with focus fires. **Legacy** (superseded by rule units, which this project doesn't use).

### `activation-group`
A rule attribute defining a group where only ONE rule can fire. Useful for mutually exclusive rule sets.

### `eval(...)` (in DRL)
A `when` clause that embeds arbitrary Java boolean expression. **BLOCKED by `DrlSanitizer`** in this project — see [16-drl-sandboxing.md](16-drl-sandboxing.md).

### DRL (Drools Rule Language)
The text format for rules. Files end in `.drl`. Drools 10 supports both "traditional" DRL (Map patterns + plain Java) and "modern" DRL (rule units + OOPath). This project uses **traditional only**.

### OOPath
Drools modern syntax for pattern matching. Looks like XPath: `/persons[ age > 18 ]`. **Not used in this project.**

### Rule unit
Drools modern construct for grouping rules with their data sources (`DataStream`, `DataStore`). **Not used in this project.**

### `DataStream` / `DataStore`
Drools typed data sources. Append-only / read-write respectively. **Not used in this project.**

### Decision table
Spreadsheet representation of rules (XLS/XLSX/CSV). Drools compiles them to DRL. **Not used in this project.**

### DSL (Domain Specific Language)
Drools' DSL feature lets you create natural-language rule files. **Not used in this project.**

### MVEL
An expression language Drools uses internally. Drools dialect setting `dialect "mvel"` activates it for rule expressions. This project uses Java dialect only.

### `KieServices`
Top-level Drools API. Used by `DroolsConfig` to create `KieFileSystem`, `KieBuilder`, etc.

### `RETE algorithm`
The pattern-matching algorithm Drools uses. You don't interact with it directly, but it's why pattern matching is fast and `eval()` is slow (eval bypasses the network).

---

## Project-specific terms

### Rule ID
The dot-separated identifier for a rule. Format: `{domain}.{category}.{specific}`. Example: `pricing.discount.vip`. Maps to the S3 key `pricing/discount/vip.drl` and the Java package `com.company.rules.pricing.discount`. See [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).

### Rule source
The storage backend, controlled by `RULE_SOURCE` env var. Three values: `local` (in-memory), `file` (filesystem), `s3` (AWS S3 / LocalStack). Default `local`.

### LRU cache
The in-process `Map`-based cache of compiled rules (`LocalLRUCache`). `@Primary` Spring bean. Uses **write lock on `get()`** because `LinkedHashMap` with `accessOrder=true` mutates internally on access (see ADR-004).

### `RedisRuleCache`
Distributed cache using Redis. Bean exists but is **dormant by default** because `LocalLRUCache` is `@Primary`. Activated when `REDIS_ENABLED=true` AND another bean is no longer marked primary. See ADR-005.

### `DrlSanitizer`
The text-level scanner that rejects DRL content with dangerous imports/classes/methods/`eval()`. Defined in [`DrlSanitizer.java`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java). See [16-drl-sandboxing.md](16-drl-sandboxing.md).

### Sandbox (DRL)
The set of restrictions enforced by `DrlSanitizer`. Specifically: import allowlist (20 prefixes) + import blocklist + 12 blocked class references + 19 blocked method calls + `eval()` ban + static-import ban.

### Admin API key
The secret value that protects `/admin/*` endpoints when `ADMIN_API_KEY` env var is set. Sent via `X-Admin-API-Key` header. See [15-admin-authentication.md](15-admin-authentication.md).

### Sample rules
The 10 `.drl` files in [`sample-rules/`](../sample-rules/). Used as both demonstration content and reference for rule format. See [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md).

### LocalStack
AWS service emulator. Used in dev for S3. Runs as a Docker container. `init-localstack.sh` populates the bucket with sample rules on container startup.

### Atomic-swap (rule loading)
The pattern where new `KieContainer` is compiled OUTSIDE the write lock, then atomically swapped INSIDE the lock. Old container is disposed inside the lock. Prevents reader blocking during compilation. See [04-architecture.md](04-architecture.md) and [DroolsEngineService.java:165-194](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java#L165-L194).

### TOCTOU (Time-Of-Check-Time-Of-Use)
A race condition where state checked at one moment differs at the moment of use. The service avoids this in rule lookup by doing a single atomic `loadedRules.get(ruleId)` + null check, instead of `containsKey` followed by `get`.

### Discount stacking
The behavior where multiple sample rules fire on the same input, multiplicatively reducing the amount. Example: VIP $100 → $80 (VIP rule) → $72 (simple rule). **Not a bug** — happens because no rule sets `salience` or `activation-group`. See [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md).

### Multi-tier client identification
The rate limiter's algorithm for assigning a client identity: tries `X-API-Key` → `Authorization: Bearer` → `X-Client-Id` → `request.getRemoteAddr()` (fallback). `X-Forwarded-For` is **explicitly ignored**. See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md).

### `maxRuleFirings`
The cap on rule activations per execution. Set to 10,000 in [`RuleExecutor.java:22`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java#L22). Prevents infinite-loop rules.

### snake_case JSON
The convention this service uses for JSON field names: `rule_id`, not `ruleId`. Java fields are camelCase; Jackson maps them via `@JsonProperty("rule_id")`.

### Filter chain order
Spring `@Order` values for the four filters: SecurityHeadersFilter (-1), AdminAuthFilter (0), RateLimitingFilter (1), RequestSizeValidationFilter (no @Order = last).

---

## Infrastructure terms

### `cgroup`
Linux kernel feature for limiting resource usage (CPU, memory) of a process group. Container memory limits are enforced via cgroups. The JVM flag `-XX:+UseContainerSupport` makes Java respect cgroup limits.

### G1GC (Garbage-First Garbage Collector)
Java's default GC since Java 9. Low-pause, region-based, concurrent. Configured via `-XX:+UseG1GC -XX:MaxGCPauseMillis=100`. See [24-jvm-optimization.md](24-jvm-optimization.md).

### Resilience4j
Java library for resilience patterns (circuit breaker, retry, rate limiter). This project uses it for circuit breakers around S3 and Redis calls. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

### Circuit breaker
A pattern that stops calling a failing dependency after a threshold of failures. Three states: CLOSED (normal), OPEN (fast-fail), HALF_OPEN (testing). See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

### Sliding window
The recent N calls considered when computing a circuit breaker's failure rate. This project uses count-based windows (e.g., the last 100 calls).

### Slow-call rate
A circuit breaker's separate threshold based on call duration, not just success/failure. The project's S3 breaker trips if 70% of calls exceed 5 seconds.

### Testcontainers
Java library that runs Docker containers in test fixtures. Used for `@Testcontainers` JUnit 5 integration tests with real LocalStack and Redis. See [28-testing-guide.md](28-testing-guide.md).

### Spotless
Maven plugin that enforces code formatting. This project uses Google Java Format 1.17.0. Run `mvn spotless:apply` before committing. See [27-development-setup.md](27-development-setup.md).

### JaCoCo
Java code coverage tool. Generates `target/site/jacoco/index.html`. Currently no threshold gate is configured.

### SpotBugs
Java static analysis tool. Detects common bug patterns. Run `mvn spotbugs:check`.

### Maven Enforcer Plugin
Maven plugin that enforces build-time invariants. This project uses it to require Java 25 (`[25,26)` range).

### Spring Boot Actuator
Spring Boot module that exposes management endpoints under `/actuator/*` (port 8081). Provides `/actuator/health`, `/actuator/metrics`, etc.

### Micrometer
Vendor-agnostic metrics facade for Java. Same metric calls work with CloudWatch, Prometheus, Datadog, Grafana. Configured via [`MetricsConfig`](../src/main/java/com/company/drools/config/MetricsConfig.java).

### MDC (Mapped Diagnostic Context)
SLF4J / Logback feature for adding key-value context to every log line in the current thread. Used for correlation IDs, request URIs, etc. See [`LoggingConfig`](../src/main/java/com/company/drools/config/LoggingConfig.java).

### Lettuce
Async Redis client for Java. Used by Spring Data Redis. Project's choice over Jedis (which is sync-only).

### Dotenv
Library for loading `.env` files into the JVM's environment. The project uses `dotenv-java 3.0.0`. Configured to use `addLast` priority — real env vars override `.env` (important for production).

---

## HTTP / API terms

### Correlation ID
A request-scoped identifier propagated through MDC, included in every log line. The service accepts client-supplied `X-Correlation-ID` (validated against `^[a-zA-Z0-9\-]{1,128}$`) or auto-generates one.

### Request ID
Per-request UUID, always auto-generated. Distinct from correlation ID (which can be sticky across hops).

### Rate limit headers
`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `X-RateLimit-Reset-After`. All four set on every response from `/execute-rule`.

### `X-Admin-API-Key`
Custom header for admin-endpoint authentication. See [15-admin-authentication.md](15-admin-authentication.md).

### CSP (Content Security Policy)
HTTP response header restricting what content the browser can load. Set to `default-src 'none'; frame-ancestors 'none'` (maximally restrictive — appropriate for a JSON API).

### HSTS (HTTP Strict Transport Security)
HTTP response header instructing browsers to always use HTTPS. Set to `max-age=31536000; includeSubDomains` (1 year).

### CORS (Cross-Origin Resource Sharing)
Browser security mechanism for cross-domain HTTP requests. The service's CORS config defaults to **empty** (no CORS) and is wildcard `*` only in `local`/`dev`/`docker` profiles. Production must set `DROOLS_CORS_ALLOWED_ORIGINS` explicitly.

### Bearer token
HTTP authentication scheme: `Authorization: Bearer <token>`. The rate limiter recognizes this for client identification (hashes the value).

---

## Observability terms

### Heap dump (`.hprof`)
A snapshot of the JVM heap. Triggered by `-XX:+HeapDumpOnOutOfMemoryError` on OOM, or manually via `jmap`/`jcmd`. Analyzed with Eclipse MAT or VisualVM. The project mounts `./heap-dumps/` to capture them.

### Thread dump
A snapshot of every thread's call stack. `jcmd <pid> Thread.print`. Useful for diagnosing deadlocks and hung threads.

### JFR (Java Flight Recorder)
Built-in JVM profiler. Records CPU samples, allocation, GC, lock contention. Enable: `-XX:+FlightRecorder`. Run: `jcmd <pid> JFR.start duration=60s filename=profile.jfr`.

### Synthetic monitoring
Periodic external probes (Pingdom, CloudWatch Synthetics, Datadog Synthetic) that hit the service from outside. Different from internal metrics — measures user-perceived availability.

### Metric cardinality
The number of distinct tag combinations on a metric. High cardinality (thousands of unique tags) can blow up monitoring backends. The service tags `rule_id` for unloaded rules as `unknown` to prevent cardinality explosion via spoofed rule IDs.

---

## Concepts referenced in ADRs

### Defense in depth
Security pattern of having multiple independent controls. If one fails, others still protect. The service has 8 security layers (see [14-security-architecture.md](14-security-architecture.md)).

### Last-writer-wins
The semantic of `Map.put(key, value)` — if multiple rules write the same key, the last one wins. Drives the discount-stacking behavior.

### Idempotent
An operation that produces the same result when called multiple times. `POST /execute-rule` is idempotent (rules are referentially transparent). `POST /admin/refresh-rules` is NOT idempotent (S3 contents may have changed).

### Fail-fast
Returning an error immediately rather than blocking/timing out. Circuit breakers fail-fast when OPEN.

### Backpressure
Slowing down or rejecting incoming work when the system can't keep up. The thread pool's `CallerRunsPolicy` is a backpressure mechanism.

### Atomic-swap
Replacing one reference with another in a single non-interruptible operation. Used for `KieContainer` rotation during rule refresh.

### Stateless
The service holds no per-request state across requests. Every replica handles every request identically. Enables horizontal scaling.

---

## Acronyms

| Acronym | Expansion |
|---|---|
| API | Application Programming Interface |
| AWS | Amazon Web Services |
| CSP | Content Security Policy |
| CORS | Cross-Origin Resource Sharing |
| CPU | Central Processing Unit |
| DRL | Drools Rule Language |
| DSL | Domain Specific Language |
| ECS | Elastic Container Service (AWS) |
| GC | Garbage Collection |
| HSTS | HTTP Strict Transport Security |
| HTTPS | HTTP Secure |
| IAM | Identity and Access Management (AWS) |
| IDE | Integrated Development Environment |
| JFR | Java Flight Recorder |
| JMX | Java Management Extensions |
| JSON | JavaScript Object Notation |
| JVM | Java Virtual Machine |
| LRU | Least Recently Used |
| LTS | Long-Term Support |
| MDC | Mapped Diagnostic Context |
| MVEL | MVFLEX Expression Language |
| OOM | Out Of Memory |
| OOPath | Object-Oriented Path |
| RPS | Requests Per Second |
| RTT | Round-Trip Time |
| S3 | Simple Storage Service (AWS) |
| SDK | Software Development Kit |
| SLA | Service Level Agreement |
| SLI | Service Level Indicator |
| SLO | Service Level Objective |
| SRE | Site Reliability Engineer |
| TCP | Transmission Control Protocol |
| TLS | Transport Layer Security |
| TOCTOU | Time-Of-Check-Time-Of-Use |
| URI | Uniform Resource Identifier |
| URL | Uniform Resource Locator |
| UUID | Universally Unique Identifier |
| VPC | Virtual Private Cloud (AWS) |
| WAF | Web Application Firewall |
| YAML | YAML Ain't Markup Language |
