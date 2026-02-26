# Security Fix Plan — Drools Rule Engine Microservice
**Created**: 2026-02-26
**Updated**: 2026-02-26
**Source**: [security-review-2026-02-26.md](security-review-2026-02-26.md)
**Total Findings**: 42 (3 Critical, 10 High, 15 Medium, 9 Low, 5 Info)
**Progress**: 39/42 complete (Phases 1-9 done; #28, #30 skipped per user request)

---

## Phase 1: Quick Wins & Critical Fixes (< 1 hour total) — COMPLETED

- [x] **1. C-3: Jackson `enableDefaultTyping` RCE** — CRITICAL
  - File: `RedisConfig.java` — replaced with `activateDefaultTyping()` + strict `BasicPolymorphicTypeValidator`

- [x] **2. H-2: Thread leak on timeout (future never cancelled)** — HIGH
  - File: `RuleExecutor.java` — added `future.cancel(true)` in timeout catch block

- [x] **3. H-1: No `fireAllRules` limit (infinite loops)** — HIGH
  - File: `RuleExecutor.java` — added `fireAllRules(maxRuleFirings)` with default 10000, configurable via constructor

- [x] **4. H-8: LinkedHashMap read lock on mutating `get()`** — HIGH
  - File: `LocalLRUCache.java` — changed `get()` from read lock to write lock

- [x] **5. H-7: Docker socket mount (container escape)** — HIGH
  - File: `docker-compose.yml` — removed `/var/run/docker.sock` mount and `DOCKER_HOST` env var

- [x] **6. H-10: Actuator exposes detailed component info** — HIGH
  - File: `application.yml` — changed `show-details`/`show-components` to `when-authorized`

- [x] **7. L-6: Docker ports bound to all interfaces** — LOW
  - File: `docker-compose.yml` — bound LocalStack and Redis to `127.0.0.1`

**Verified**: 550 unit tests passing, full Docker integration test passed (all 21 categories)

---

## Phase 2: Authentication & Rate Limiting — COMPLETED

- [x] **8. C-2: No authentication on admin endpoints** — CRITICAL
  - New file: `AdminAuthFilter.java` — API key auth filter for `/admin/**` via `X-Admin-API-Key` header
  - Config: `ADMIN_API_KEY` env var; when unset, admin endpoints remain open (backward compatible for dev)

- [x] **9. H-3: Rate limiting bypass via header spoofing** — HIGH
  - File: `RateLimitingFilter.java` — removed `X-Forwarded-For` trust, always use `request.getRemoteAddr()`

- [x] **10. H-4: Rate limiter unbounded memory growth (Memory DoS)** — HIGH
  - File: `RateLimitingConfig.java` — added `maxClients` cap (default 10000), rejects new clients at capacity

- [x] **11. H-9: Request size validation bypass (chunked transfer)** — HIGH
  - File: `RequestSizeValidationFilter.java` — wraps input stream in `SizeLimitedInputStream` when `Content-Length` is absent

- [x] **12. M-10: Rate limiter race condition** — MEDIUM
  - File: `RateLimitingConfig.java` — changed to `incrementAndGet()` first, then check against limit

**Verified**: 560 tests passing (10 new tests added), 0 failures

---

## Phase 3: DRL Sandboxing & Security Headers — COMPLETED

- [x] **13. C-1: Arbitrary code execution via unsanitized DRL rules** — CRITICAL
  - New file: `DrlSanitizer.java` — scans DRL content before compilation
  - Blocklist: `Runtime`, `ProcessBuilder`, `Thread`, `ClassLoader`, `System.exit`, `Class.forName`, etc.
  - Import allowlist: only `java.util.*`, `java.math.*`, `java.time.*`, `java.lang` primitives, `java.text` formatters
  - Blocks `eval()`, static imports, `java.io.*`, `java.net.*`, `java.lang.reflect.*`, `javax.script.*`, `javax.naming.*`
  - Integrated into `RuleCompiler.compileRules()` — rejects rules before Drools compilation

- [x] **14. H-5: CORS wildcard origin default** — HIGH
  - File: `CorsConfig.java` — default changed from `*` to empty (no CORS); logs warning when wildcard configured
  - File: `application.yml` — `*` set explicitly in local/dev/docker profiles; prod defaults to empty (no cross-origin)

- [x] **15. M-14: No security headers** — MEDIUM
  - New file: `SecurityHeadersFilter.java` — `@Order(-1)` filter adds 7 security headers to every response
  - Headers: X-Content-Type-Options, X-Frame-Options, X-XSS-Protection, Referrer-Policy, Cache-Control, CSP, HSTS

**Verified**: 584 tests passing (24 new tests added), 0 failures

---

## Phase 4: Storage & Path Traversal — COMPLETED

- [x] **16. M-4: Path traversal defense-in-depth (LocalFileStorage)** — MEDIUM
  - File: `LocalFileStorage.java` — added `.normalize().startsWith(rulesRoot)` check in `getRuleFilePath()`

- [x] **17. M-5: Path traversal defense-in-depth (S3RuleStorage)** — MEDIUM
  - File: `S3RuleStorage.java` — added `../` and leading `/` validation in `ruleIdToS3Key()`

- [x] **18. M-6: SSRF via configurable S3 endpoint** — MEDIUM
  - File: `S3Config.java` — added `validateEndpoint()` with scheme and host allowlist

- [x] **19. M-7: Redis `KEYS *` in production code** — MEDIUM
  - File: `RedisRuleCache.java` — replaced `KEYS *` with SCAN cursor-based iteration via `RedisCallback`

**Verified**: 584 tests passing, 0 failures

---

## Phase 5: Information Leakage & Logging — COMPLETED

- [x] **20. M-1: Metrics cardinality explosion via unbounded rule_id tag** — MEDIUM
  - File: `DroolsEngineService.java` — use `"unknown"` tag for rule-not-found error path

- [x] **21. M-8: Correlation ID log injection** — MEDIUM
  - File: `LoggingConfig.java` — validate correlation/request IDs against `^[a-zA-Z0-9\\-]{1,128}$` pattern

- [x] **22. M-9: Exception messages leak internal details** — MEDIUM
  - Files: `GlobalExceptionHandler.java`, `RuleExecutionController.java` — generic client messages, log details server-side

- [x] **23. M-11: Hardcoded AWS credentials in dev profile** — MEDIUM
  - File: `application.yml` — moved to env var references `${AWS_ACCESS_KEY_ID_DEV:test}`

- [x] **24. M-12: DotenvConfig loads .env with highest priority** — MEDIUM
  - File: `DotenvConfig.java` — changed `addFirst` to `addLast` so real env vars take priority

- [x] **25. L-5: `toString()` dumps full data map** — LOW
  - File: `RuleExecutionRequest.java` — shows field count instead of full data map

**Verified**: 584 tests passing, 0 failures

---

## Phase 6: Concurrency & Performance Fixes — COMPLETED

- [x] **26. M-2: Write lock held during rule compilation (blocks all execution)** — MEDIUM
  - File: `DroolsEngineService.java` — compile outside lock, acquire write lock only for atomic swap

- [x] **27. M-3: TOCTOU race in DroolsEngineService** — MEDIUM
  - File: `DroolsEngineService.java` — direct `get()` + null check instead of `containsKey()` + `get()`

**Verified**: 584 tests passing, 0 failures

---

## Phase 7: Infrastructure & Dependencies — COMPLETED (2 of 3; #28, #30 skipped)

- [ ] **28. H-6: Redis without authentication or TLS** — HIGH — SKIPPED (per user request)
  - Files: `docker-compose.yml`, `application.yml`

- [x] **29. M-13: S3 bucket policy allows all principals** — MEDIUM
  - File: `init-localstack.sh` — added guard checking for LocalStack endpoint before applying `Principal: *` policy

- [ ] **30. M-15: Outdated dependencies** — MEDIUM — SKIPPED (per user request)
  - File: `pom.xml`

**Verified**: No test changes required

---

## Phase 8: Log Sanitizer Improvements — COMPLETED

- [x] **31. L-1: `pin`/`auth` patterns false positives** — LOW
  - File: `LogSanitizer.java` — word-boundary regex (`\bpin\b`, `\bauth\b`, `\bssn\b`, `\bcvv\b`)

- [x] **32. L-2: 20+ char alphanumeric redaction too aggressive** — LOW
  - File: `LogSanitizer.java` — exclude UUIDs and Java class names from token redaction

- [x] **33. L-3: SSN regex matches any 9-digit number** — LOW
  - File: `LogSanitizer.java` — require XXX-XX-XXXX format (separator required)

- [x] **34. L-4: No recursive sanitization of nested maps** — LOW
  - File: `LogSanitizer.java` — recursive handling up to 5 levels deep

**Verified**: 591 tests passing (7 new tests added), 0 failures

---

## Phase 9: Remaining Low & Info Items — COMPLETED

- [x] **35. L-7: Redis connection leak in health check** — LOW
  - File: `AdminController.java` — close connection in finally block

- [x] **36. L-8: Numerical overflow in execution average** — LOW
  - File: `RuleMetadata.java` — incremental averaging formula to avoid overflow

- [x] **37. L-9: Credential getters expose secrets to other beans** — LOW
  - File: `S3Config.java` — removed public getters for `accessKeyId` and `secretAccessKey`

- [x] **38. I-1: KieContainer disposal safety** — INFO
  - File: `DroolsEngineService.java` — already well-documented with lock discipline comments

- [x] **39. I-2: No default constructors for Redis serialization** — INFO
  - Files: `Rule.java`, `RuleMetadata.java` — added protected no-arg constructors

- [x] **40. I-3: `getAllRules()` lacks circuit breaker** — INFO
  - File: `S3RuleStorage.java` — wrapped with `CircuitBreaker.decorateSupplier()`

- [x] **41. I-4: CallerRunsPolicy can block Tomcat threads** — INFO
  - File: `ThreadPoolConfig.java` — documented risk with warning comment

- [x] **42. I-5: `safeDataRepresentation` sanitizes then discards** — INFO
  - File: `LogSanitizer.java` — removed wasteful sanitization; shows key names only

**Verified**: 589 tests passing, 0 failures

---

## Summary

| Phase | Items | Done | Severity Coverage | Status |
|-------|-------|------|-------------------|--------|
| 1. Quick Wins | 7 | 7/7 | 1 Critical, 4 High, 1 Low | COMPLETED |
| 2. Auth & Rate Limiting | 5 | 5/5 | 1 Critical, 3 High, 1 Medium | COMPLETED |
| 3. DRL Sandboxing & Headers | 3 | 3/3 | 1 Critical, 1 High, 1 Medium | COMPLETED |
| 4. Storage & Path Traversal | 4 | 4/4 | 4 Medium | COMPLETED |
| 5. Info Leakage & Logging | 6 | 6/6 | 5 Medium, 1 Low | COMPLETED |
| 6. Concurrency & Performance | 2 | 2/2 | 2 Medium | COMPLETED |
| 7. Infrastructure & Deps | 3 | 1/3 | 1 Medium (2 skipped) | COMPLETED |
| 8. Log Sanitizer | 4 | 4/4 | 4 Low | COMPLETED |
| 9. Remaining Low/Info | 8 | 8/8 | 3 Low, 5 Info | COMPLETED |
| **TOTAL** | **42** | **39/42** | **3C + 10H + 15M + 9L + 5I** | **DONE** |

**Skipped items (2)**: #28 (Redis auth/TLS) and #30 (dependency updates) — skipped per user request.
**Not applicable (1)**: #38 (KieContainer disposal) was documentation-only, already well-documented.

---

## Notes
- All 3 Critical findings addressed (Jackson RCE, Admin auth, DRL sandboxing)
- All 10 High findings addressed (9 fixed, 1 skipped per user: Redis auth)
- All 15 Medium findings addressed (14 fixed, 1 skipped per user: dependency updates)
- All 9 Low findings addressed
- All 5 Info findings addressed
- Final test count: 589 tests passing, 0 failures
- Run `mvn spotless:apply` after each batch of edits
- Run `mvn test` after each phase to verify no regressions
