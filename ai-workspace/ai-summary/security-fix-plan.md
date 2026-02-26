# Security Fix Plan — Drools Rule Engine Microservice
**Created**: 2026-02-26
**Updated**: 2026-02-26
**Source**: [security-review-2026-02-26.md](security-review-2026-02-26.md)
**Total Findings**: 42 (3 Critical, 10 High, 15 Medium, 9 Low, 5 Info)
**Progress**: 12/42 complete (Phase 1-2 done)

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

## Phase 3: DRL Sandboxing & Security Headers (5-10 hours total)

- [ ] **13. C-1: Arbitrary code execution via unsanitized DRL rules** — CRITICAL — 4-8h
  - Files: `RuleCompiler.java:23-67`, `RuleExecutor.java:69-90`
  - Fix: Implement DRL content scanning before compilation:
    - Block dangerous classes: `Runtime`, `ProcessBuilder`, `System.exit`
    - Block dangerous packages: `java.io`, `java.net`, reflection APIs
    - Allowlist permitted imports
    - Consider sandboxed execution environment

- [ ] **14. H-5: CORS wildcard origin default** — HIGH — 15 min
  - Files: `CorsConfig.java:21`, `application.yml:111`
  - Fix: Set restrictive origins in prod profile (default `*` carried into all profiles)

- [ ] **15. M-14: No security headers** — MEDIUM — 1h
  - Files: New filter or Spring Security config
  - Fix: Add HSTS, X-Content-Type-Options, X-Frame-Options, CSP headers

---

## Phase 4: Storage & Path Traversal (1-2 hours total)

- [ ] **16. M-4: Path traversal defense-in-depth (LocalFileStorage)** — MEDIUM — 15 min
  - File: `LocalFileStorage.java:157-159`
  - Fix: Add path canonicalization check: `filePath.normalize().startsWith(rulesRoot)`

- [ ] **17. M-5: Path traversal defense-in-depth (S3RuleStorage)** — MEDIUM — 15 min
  - File: `S3RuleStorage.java:325-327`
  - Fix: Validate key doesn't contain `../` or start with `/`

- [ ] **18. M-6: SSRF via configurable S3 endpoint** — MEDIUM — 30 min
  - File: `S3Config.java:75-79`
  - Fix: Validate `AWS_ENDPOINT` against allowlist

- [ ] **19. M-7: Redis `KEYS *` in production code** — MEDIUM — 1h
  - File: `RedisRuleCache.java:172,186,205`
  - Fix: Replace `KEYS *` with `SCAN` cursor-based iteration

---

## Phase 5: Information Leakage & Logging (3-4 hours total)

- [ ] **20. M-1: Metrics cardinality explosion via unbounded rule_id tag** — MEDIUM — 15 min
  - File: `DroolsEngineService.java:70-77`
  - Fix: Use fixed tag value for error path (e.g., `"unknown"` instead of user-supplied rule ID)

- [ ] **21. M-8: Correlation ID log injection** — MEDIUM — 30 min
  - File: `LoggingConfig.java:40-49`
  - Fix: Validate `X-Correlation-ID` against UUID pattern, strip control characters before MDC

- [ ] **22. M-9: Exception messages leak internal details** — MEDIUM — 2h
  - Files: `GlobalExceptionHandler.java:28,40,70,91,103`, `RuleExecutionController.java:126`
  - Fix: Return generic messages to clients, log `e.getMessage()` details server-side only

- [ ] **23. M-11: Hardcoded AWS credentials in dev profile** — MEDIUM — 15 min
  - File: `application.yml:213-214`
  - Fix: Move test credentials to env vars only, remove from source code

- [ ] **24. M-12: DotenvConfig loads .env with highest priority** — MEDIUM — 15 min
  - File: `DotenvConfig.java:29`
  - Fix: Only load `.env` in dev/local profiles, or use `addLast` instead of `addFirst`

- [ ] **25. L-5: `toString()` dumps full data map** — LOW — 5 min
  - File: `RuleExecutionRequest.java:59-61`
  - Fix: Redact or limit data map output in `toString()`

---

## Phase 6: Concurrency & Performance Fixes (1-2 hours total)

- [ ] **26. M-2: Write lock held during rule compilation (blocks all execution)** — MEDIUM — 1h
  - File: `DroolsEngineService.java:140-192`
  - Fix: Compile outside lock, acquire write lock only for atomic swap of compiled result

- [ ] **27. M-3: TOCTOU race in DroolsEngineService** — MEDIUM — 15 min
  - File: `DroolsEngineService.java:68-82`
  - Fix: Use `getOrDefault()` or null check on metadata instead of `containsKey` then `get`

---

## Phase 7: Infrastructure & Dependencies (2-4 hours total)

- [ ] **28. H-6: Redis without authentication or TLS** — HIGH — 1h
  - Files: `docker-compose.yml:111`, `application.yml:47`
  - Fix: Set strong password, use TLS (`rediss://`), don't publish port 6379 in production

- [ ] **29. M-13: S3 bucket policy allows all principals** — MEDIUM — 15 min
  - File: `init-localstack.sh:94-118`
  - Fix: Add guard checking for LocalStack endpoint before applying `Principal: *` policy

- [ ] **30. M-15: Outdated dependencies** — MEDIUM — 1-2h
  - File: `pom.xml:21-26`
  - Fix: Update Spring Boot 3.2.5 → 3.4.x, AWS SDK 2.20.56 → 2.25+, run `mvn versions:display-dependency-updates`

---

## Phase 8: Log Sanitizer Improvements (1 hour total)

- [ ] **31. L-1: `pin`/`auth` patterns false positives** — LOW — 15 min
  - File: `LogSanitizer.java:14,20`
  - Fix: Use word-boundary regex (`\bpin\b`, `\bauth\b`) to avoid matching "shipping", "author"

- [ ] **32. L-2: 20+ char alphanumeric redaction too aggressive** — LOW — 15 min
  - File: `LogSanitizer.java:151`
  - Fix: Increase threshold or add UUID/class-name exclusion patterns

- [ ] **33. L-3: SSN regex matches any 9-digit number** — LOW — 15 min
  - File: `LogSanitizer.java:116,148`
  - Fix: Use more specific SSN pattern (e.g., require `XXX-XX-XXXX` format)

- [ ] **34. L-4: No recursive sanitization of nested maps** — LOW — 30 min
  - File: `LogSanitizer.java:76-85`
  - Fix: Add recursive handling for nested map values

---

## Phase 9: Remaining Low & Info Items (1-2 hours total)

- [ ] **35. L-7: Redis connection leak in health check** — LOW — 15 min
  - File: `AdminController.java:245`
  - Fix: Close connection in `finally` block

- [ ] **36. L-8: Numerical overflow in execution average** — LOW — 5 min
  - File: `RuleMetadata.java:60-65`
  - Fix: Use `long` or `BigDecimal` for accumulator

- [ ] **37. L-9: Credential getters expose secrets to other beans** — LOW — 15 min
  - File: `S3Config.java:143-157`
  - Fix: Remove public getters for credentials

- [ ] **38. I-1: KieContainer disposal safety** — INFO — N/A
  - File: `DroolsEngineService.java:164-178`
  - Action: Document lock discipline (no code change needed)

- [ ] **39. I-2: No default constructors for Redis serialization** — INFO — 5 min
  - Files: `Rule.java:5`, `RuleMetadata.java:7`
  - Fix: Add no-arg constructors

- [ ] **40. I-3: `getAllRules()` lacks circuit breaker** — INFO — 30 min
  - File: `S3RuleStorage.java:131-179`
  - Fix: Add circuit breaker annotation

- [ ] **41. I-4: CallerRunsPolicy can block Tomcat threads** — INFO — N/A
  - File: `ThreadPoolConfig.java:74,112`
  - Action: Document risk or change to `AbortPolicy` with proper error handling

- [ ] **42. I-5: `safeDataRepresentation` sanitizes then discards** — INFO — 15 min
  - File: `LogSanitizer.java:162-185`
  - Fix: Fix return value usage (sanitized values are currently discarded)

---

## Summary

| Phase | Items | Severity Coverage | Est. Time |
|-------|-------|-------------------|-----------|
| 1. Quick Wins | 7 | 1 Critical, 4 High, 1 Low | < 1h |
| 2. Auth & Rate Limiting | 5 | 1 Critical, 3 High, 1 Medium | 3-6h |
| 3. DRL Sandboxing & Headers | 3 | 1 Critical, 1 High, 1 Medium | 5-10h |
| 4. Storage & Path Traversal | 4 | 4 Medium | 1-2h |
| 5. Info Leakage & Logging | 6 | 5 Medium, 1 Low | 3-4h |
| 6. Concurrency & Performance | 2 | 2 Medium | 1-2h |
| 7. Infrastructure & Deps | 3 | 1 High, 2 Medium | 2-4h |
| 8. Log Sanitizer | 4 | 4 Low | 1h |
| 9. Remaining Low/Info | 8 | 3 Low, 5 Info | 1-2h |
| **TOTAL** | **42** | **3C + 10H + 15M + 9L + 5I** | **~17-32h** |

---

## Notes
- Phase 1 items are all independent — can be done in parallel or any order
- Phase 2 item #8 (Spring Security) is the largest single task and may require new test infrastructure
- Phase 3 item #13 (DRL sandboxing) is the most architecturally significant change
- Items marked INFO/N/A are documentation or design observations, not code fixes
- All fixes should include corresponding unit tests
- Run `mvn spotless:apply` after each batch of edits
- Run `mvn test` after each phase to verify no regressions
