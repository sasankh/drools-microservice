# Security Review Report - Drools Rule Engine Microservice
**Date**: 2026-02-26
**Scope**: Full project review (all source, config, infrastructure)
**Reviewed by**: Claude Opus 4.6 (4 parallel review agents)

---

## Executive Summary

| Severity | Count | Key Themes |
|----------|-------|-----------|
| CRITICAL | 3 | Arbitrary code execution, no authentication, Jackson deserialization RCE |
| HIGH | 10 | Rate limiting bypasses, CORS wildcard, Redis no auth, thread leaks, concurrency bugs |
| MEDIUM | 15 | Info leakage, path traversal defense-in-depth, outdated deps, log injection, memory growth |
| LOW | 9 | Log sanitizer false positives, Docker port exposure, verbose logging |
| INFO | 5 | Design observations, non-functional Redis serialization |
| **TOTAL** | **42** | |

**Top 3 most impactful issues:**
1. **Arbitrary code execution via unsanitized DRL rules** (C-1) — anyone who can write to S3 gets full server access
2. **No authentication on any endpoint** (C-2) — admin endpoints (rule reload, GC trigger) completely open
3. **Jackson `enableDefaultTyping` in Redis** (C-3) — classic deserialization RCE vector

---

## CRITICAL Findings (3)

### C-1. Arbitrary Code Execution via Unsanitized DRL Rules
- **Files**: `RuleCompiler.java:23-67`, `RuleExecutor.java:69-90`
- **Description**: DRL rules loaded from S3 are compiled and executed with zero content inspection. Drools DRL `then` blocks execute as compiled Java bytecode. A malicious rule can call `Runtime.getRuntime().exec()`, access the filesystem, open network connections, or crash the JVM. There is no SecurityManager, no class/package filtering, and no static analysis.
- **Impact**: Full server compromise via S3 bucket poisoning
- **Fix**: Implement DRL content scanning (block `Runtime`, `ProcessBuilder`, `System.exit`, `java.io`, `java.net`, reflection). Use allowlist of permitted imports. Consider sandboxed execution.

### C-2. No Authentication or Authorization on Any Endpoint
- **Files**: All controllers (`RuleExecutionController.java`, `AdminController.java`, `MemoryController.java`)
- **Also**: `RateLimitingFilter.java:61-66` explicitly excludes `/admin/` from rate limiting
- **Description**: Zero authentication on all endpoints. Admin endpoints (`/admin/refresh-rules`, `/admin/memory/gc`, `/admin/rules`) are unauthenticated AND unrate-limited. Anyone can trigger rule reloads (DoS), force GC, and enumerate all rules.
- **Impact**: DoS via rule reload spam, information disclosure, forced GC degradation
- **Fix**: Add `spring-boot-starter-security`. Implement auth for `/admin/**`. Apply rate limiting to admin endpoints.

### C-3. Jackson Polymorphic Deserialization (RCE via Redis)
- **File**: `RedisConfig.java:58`
- **Description**: `objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL)` — this deprecated API enables arbitrary class instantiation during deserialization. An attacker with Redis access can inject a malicious JSON payload to achieve RCE (CVE-2017-7525 and many subsequent CVEs).
- **Impact**: Remote code execution if Redis is compromised
- **Fix**: Replace with `activateDefaultTyping()` using a strict `BasicPolymorphicTypeValidator` that whitelists only `com.company.drools.core.model`.

---

## HIGH Findings (10)

### H-1. No `fireAllRules` Limit — Infinite Rule Loops
- **File**: `RuleExecutor.java:79`
- **Description**: `kieSession.fireAllRules()` has no maximum. A malicious rule with infinite insertion/retrigger consumes CPU indefinitely. The timeout wrapper doesn't cancel the underlying future (H-2).
- **Fix**: Use `fireAllRules(maxRuleFirings)` with configurable max (e.g., 1000).

### H-2. Thread Leak on Timeout (Future Never Cancelled)
- **File**: `RuleExecutor.java:45-60`
- **Description**: On `TimeoutException`, the `CompletableFuture` is never cancelled. The thread keeps running, eventually exhausting the pool (50 threads). Combined with `CallerRunsPolicy`, this blocks Tomcat threads.
- **Fix**: Call `future.cancel(true)` in the timeout catch block.

### H-3. Rate Limiting Bypass via Header Spoofing
- **File**: `RateLimitingFilter.java:69-101`
- **Description**: Client ID derived from user-controlled headers (`X-API-Key`, `X-Client-Id`, `X-Forwarded-For`). Attacker rotates header values for unlimited fresh rate limit buckets.
- **Fix**: Use `request.getRemoteAddr()` as primary identifier. Trust `X-Forwarded-For` only from configured proxies.

### H-4. Rate Limiter Unbounded Memory Growth (Memory DoS)
- **File**: `RateLimitingConfig.java:54-101`
- **Description**: `ConcurrentHashMap<String, ClientRateData>` has no max size. Combined with H-3, attacker creates millions of entries via unique headers. Cleanup only every 5 minutes.
- **Fix**: Add max size limit. Use bounded cache (Caffeine) with TTL.

### H-5. CORS Wildcard Origin Default
- **File**: `CorsConfig.java:21`, `application.yml:111`
- **Description**: Default `*` for all origins, carried into all profiles including prod. Any website can make cross-origin POST requests.
- **Fix**: Set restrictive origins in prod profile.

### H-6. Redis Without Authentication or TLS
- **File**: `docker-compose.yml:111`, `application.yml:47`
- **Description**: Redis runs with empty password, plaintext connection, port published to host. Combined with C-3, Redis access = RCE.
- **Fix**: Set strong password, use TLS (`rediss://`), don't publish port 6379.

### H-7. Docker Socket Mount (Container Escape)
- **File**: `docker-compose.yml:96`
- **Description**: LocalStack has `/var/run/docker.sock` mounted — full Docker daemon control from inside the container.
- **Fix**: Remove mount. LocalStack S3 doesn't need it.

### H-8. LinkedHashMap Read Lock on Mutating Operation
- **File**: `LocalLRUCache.java:66-91`
- **Description**: `get()` uses read lock on access-ordered `LinkedHashMap`, but `get()` is a mutating operation (moves entry to end). Concurrent reads corrupt internal linked list → infinite loops, data loss.
- **Fix**: Change `get()` to use write lock, or replace with Caffeine/ConcurrentLinkedHashMap.

### H-9. Request Size Validation Bypass (Chunked Transfer)
- **File**: `RequestSizeValidationFilter.java:42-64`
- **Description**: Only checks `Content-Length`. `Transfer-Encoding: chunked` (no Content-Length) bypasses the check entirely. Tomcat backup limit is 10MB vs intended 1MB.
- **Fix**: Wrap input stream in counting stream when Content-Length absent.

### H-10. Actuator Exposes Detailed Component Info
- **File**: `application.yml:22-24`
- **Description**: `show-details: always` on port 8081 exposes Redis status, S3 bucket names, circuit breaker states.
- **Fix**: `show-details: when-authorized` for production.

---

## MEDIUM Findings (15)

### M-1. Metrics Cardinality Explosion via Unbounded rule_id Tag
- **File**: `DroolsEngineService.java:70-77`
- Attacker submits requests for non-existent rule IDs, each creating a unique metric time series → OOM and metrics backend cost explosion.
- **Fix**: Use fixed tag value for error path (e.g., `"unknown"`).

### M-2. Write Lock Held During Rule Compilation (Blocks All Execution)
- **File**: `DroolsEngineService.java:140-192`
- Write lock held through entire compilation → all rule execution requests blocked.
- **Fix**: Compile outside lock, acquire write lock only for atomic swap.

### M-3. TOCTOU Race in DroolsEngineService
- **File**: `DroolsEngineService.java:68-82`
- `containsKey` then `get` on metadata could NPE if maps modified between checks.
- **Fix**: Use `getOrDefault()` or null check on metadata.

### M-4. Path Traversal Defense-in-Depth Gap (LocalFileStorage)
- **File**: `LocalFileStorage.java:157-159`
- Storage layer has zero validation — relies entirely on upstream `RuleIdValidator`.
- **Fix**: Add path canonicalization check: `filePath.normalize().startsWith(rulesRoot)`.

### M-5. Path Traversal Defense-in-Depth Gap (S3RuleStorage)
- **File**: `S3RuleStorage.java:325-327`
- Same issue as M-4 but for S3 keys.
- **Fix**: Validate key doesn't contain `../` or start with `/`.

### M-6. SSRF via Configurable S3 Endpoint
- **File**: `S3Config.java:75-79`
- `AWS_ENDPOINT` can redirect S3 traffic to attacker server.
- **Fix**: Validate endpoint against allowlist.

### M-7. Redis `KEYS` Command in Production Code
- **File**: `RedisRuleCache.java:172,186,205`
- `KEYS *` is O(N) and blocks Redis. Called during `/admin/refresh-rules`.
- **Fix**: Replace with `SCAN`.

### M-8. Correlation ID Log Injection
- **File**: `LoggingConfig.java:40-49`
- `X-Correlation-ID` put into MDC without sanitization → log injection/forging.
- **Fix**: Validate against UUID pattern, strip control characters.

### M-9. Exception Messages Leak Internal Details
- **File**: `GlobalExceptionHandler.java:28,40,70,91,103`, `RuleExecutionController.java:126`
- `e.getMessage()` returned to clients — can contain S3 bucket names, file paths, compilation errors.
- **Fix**: Return generic messages, log details server-side only.

### M-10. Rate Limiter Race Condition
- **File**: `RateLimitingConfig.java:110-139`
- Check-then-increment is not atomic — concurrent requests bypass limits.
- **Fix**: Use `incrementAndGet()` then check, or proper token bucket.

### M-11. Hardcoded AWS Credentials in Dev Profile
- **File**: `application.yml:213-214`
- Test credentials in source code. Could accidentally activate in prod.
- **Fix**: Use env vars only.

### M-12. DotenvConfig Loads .env with Highest Priority
- **File**: `DotenvConfig.java:29`
- `.env` overrides all environment variables including production ones.
- **Fix**: Only load in dev/local profiles, or use `addLast`.

### M-13. S3 Bucket Policy Allows All Principals
- **File**: `init-localstack.sh:94-118`
- `"Principal": "*"` — if run against real AWS, makes bucket public.
- **Fix**: Add guard checking for LocalStack endpoint.

### M-14. No Security Headers
- **File**: Absence in all config
- Missing HSTS, X-Content-Type-Options, X-Frame-Options, CSP.
- **Fix**: Add Spring Security or custom filter for headers.

### M-15. Outdated Dependencies
- **File**: `pom.xml:21-26`
- Spring Boot 3.2.5 (current 3.4.x), AWS SDK 2.20.56 (current 2.25+), others.
- **Fix**: Run `mvn versions:display-dependency-updates`.

---

## LOW Findings (9)

| # | File | Issue |
|---|------|-------|
| L-1 | `LogSanitizer.java:14,20` | `pin`/`auth` patterns match "shipping", "author" |
| L-2 | `LogSanitizer.java:151` | 20+ char alphanumeric redaction masks UUIDs, class names |
| L-3 | `LogSanitizer.java:116,148` | SSN regex matches any 9-digit number |
| L-4 | `LogSanitizer.java:76-85` | No recursive sanitization of nested maps |
| L-5 | `RuleExecutionRequest.java:59-61` | `toString()` dumps full data map |
| L-6 | `docker-compose.yml:9-10,85,109` | All ports published to host (not localhost-bound) |
| L-7 | `AdminController.java:245` | Redis connection leak in health check |
| L-8 | `RuleMetadata.java:60-65` | Numerical overflow in execution average |
| L-9 | `S3Config.java:143-157` | Credential getters expose secrets to other beans |

---

## INFO Findings (5)

| # | File | Issue |
|---|------|-------|
| I-1 | `DroolsEngineService.java:164-178` | KieContainer disposal safety depends on lock discipline |
| I-2 | `Rule.java:5`, `RuleMetadata.java:7` | No default constructors → Redis deserialization silently broken |
| I-3 | `S3RuleStorage.java:131-179` | `getAllRules()` lacks circuit breaker |
| I-4 | `ThreadPoolConfig.java:74,112` | CallerRunsPolicy can block Tomcat threads under load |
| I-5 | `LogSanitizer.java:162-185` | `safeDataRepresentation` sanitizes values then discards them |

---

## Recommended Fix Priority

### Phase 1: Critical & Quick Wins (Week 1)
1. **Remove `enableDefaultTyping`** from RedisConfig.java (C-3) — 5 min fix
2. **Add Spring Security** for `/admin/**` endpoints (C-2) — 2-4 hours
3. **Cancel future on timeout** in RuleExecutor (H-2) — 5 min fix
4. **Add `fireAllRules(max)` limit** (H-1) — 5 min fix
5. **Fix LinkedHashMap read lock** to write lock in LocalLRUCache (H-8) — 5 min fix
6. **Remove Docker socket mount** from docker-compose.yml (H-7) — 1 min fix
7. **Fix rate limiter** to use `getRemoteAddr()` (H-3) — 30 min

### Phase 2: High Priority (Week 2)
8. **DRL content scanning** before compilation (C-1) — 4-8 hours
9. **Restrict CORS** in prod profile (H-5) — 15 min
10. **Redis auth + TLS** for production (H-6) — 1 hour
11. **Add security headers** filter (M-14) — 1 hour
12. **Fix request size validation** for chunked transfers (H-9) — 1 hour
13. **Bound rate limiter HashMap** (H-4) — 30 min
14. **Path traversal defense-in-depth** in storage layers (M-4, M-5) — 30 min

### Phase 3: Medium Priority (Week 3)
15. **Sanitize error messages** returned to clients (M-9) — 2 hours
16. **Fix correlation ID log injection** (M-8) — 30 min
17. **Replace Redis KEYS with SCAN** (M-7) — 1 hour
18. **Update dependencies** (M-15) — 1-2 hours
19. **Fix compile-under-write-lock** (M-2) — 1 hour
20. **Remaining medium/low fixes**

---

## Notes
- Many findings overlap across review areas (e.g., "no auth on admin" appears in API, engine, and config reviews). They are deduplicated in this report.
- The project design notes state: "No authentication at microservice level (handled by API Gateway)". This is a valid architectural choice IF the service is always behind an API Gateway. However, the admin endpoints and actuator MUST still be protected.
- Redis is currently dormant (not in the execution path), which reduces the practical risk of C-3 and H-6. However, the code exists and could be activated.
