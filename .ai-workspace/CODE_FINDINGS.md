# Code-vs-Documentation Findings

**Purpose**: Catalog of every code-vs-doc mismatch discovered during deep code review.
**Use**: Separate triage by the user. **No code changes are made during the documentation overhaul** — each item below results in a doc fix, not a code fix.
**Format**: Each finding has Severity, Category (Doc-Fix-Only / Code-Bug / Convention), Location (code path:line), What docs say, What code does, Recommended action.

**Status legend**: 🟢 = doc fix only, 🟡 = consider code change, 🔴 = potential bug requiring code review.

---

## Phase 0 findings (from initial Explore-agent runs, 2026-05-08)

### F-001 🟢 — JSON field name: `ruleId` vs `rule_id`
- **Severity**: Medium (visible to every API consumer)
- **Where docs are wrong**: `architecture.md` lines 149-150, 180-184 (and possibly elsewhere)
- **What docs say**: Request body uses `ruleId` (camelCase)
- **What code does**: `RuleExecutionRequest` field is `ruleId` (Java) but mapped to `rule_id` (JSON) via `@JsonProperty("rule_id")` per [RuleExecutionRequest.java:12](src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java#L12). README and `rule-development.md` are correct.
- **Action**: Doc fix in `04-architecture.md` (Phase 1b)

### F-002 🟢 — Security header values
- **Severity**: High (security claim accuracy)
- **Where docs are wrong**: `architecture.md` Security Architecture section, possibly `configuration.md`
- **What docs claim**:
  - `X-XSS-Protection: 1; mode=block`
  - `Cache-Control: no-cache, no-store, must-revalidate`
  - `Content-Security-Policy: default-src 'self'`
- **What code does** ([SecurityHeadersFilter.java:21-27](src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java#L21-L27)):
  - `X-Content-Type-Options: nosniff` ✓ matches
  - `X-Frame-Options: DENY` ✓ matches
  - `X-XSS-Protection: 0` ✗ docs say `1; mode=block`
  - `Referrer-Policy: strict-origin-when-cross-origin` ✓ matches
  - `Cache-Control: no-store` ✗ docs say longer string
  - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` ✗ docs say `default-src 'self'`
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains` ✓ matches
- **Action**: Doc fix in `04-architecture.md` and `14-security-architecture.md` (Phase 1b/2b). Note: `X-XSS-Protection: 0` is the **modern recommendation** — old header is unsafe. The code is right.

### F-003 🟢 — RULE_SOURCE default value
- **Severity**: Medium (operator confusion)
- **Where docs are wrong**: Multiple docs claim default is `s3`
- **What code does**: `application.yml:59` — `rule-source: ${RULE_SOURCE:local}`. Default is `local`.
- **Action**: Doc fix in `09-environment-variables-reference.md`, `06-deployment.md`, `08-configuration.md` (Phase 1b/2a)

### F-004 🟢 — StorageFactory accepts three values, not two
- **Severity**: Medium
- **Where docs are wrong**: Multiple docs imply `s3` and `local` only
- **What code does** ([StorageFactory.java:23-44](src/main/java/com/company/drools/storage/StorageFactory.java#L23-L44)): Recognizes `local` (→ InMemoryRuleStorageAdapter), `file` (→ LocalFileStorage), `s3` (→ S3RuleStorage). Default falls to InMemoryRuleStorageAdapter with warning.
- **Action**: Doc fix in `09-environment-variables-reference.md`, `18-rule-id-and-storage-layout.md`

### F-005 🟢 — Rate limiting client identification is multi-tier, not just IP
- **Severity**: Medium (partner-facing surprise)
- **Where docs are wrong**: All current docs imply rate limit identity = remote IP only
- **What code does** ([RateLimitingFilter.java:65-94](src/main/java/com/company/drools/api/filter/RateLimitingFilter.java#L65-L94)):
  1. `X-API-Key` header → `api-key:{key}`
  2. `Authorization: Bearer {token}` → `bearer:{hash}`
  3. `X-Client-Id` header → `client-id:{id}`
  4. `request.getRemoteAddr()` → `ip:{addr}` (fallback only)
- **Action**: Doc in `13-rate-limiting-and-throttling.md` and reference in `14-security-architecture.md`

### F-006 🟢 — Deprecated JVM flag in jvm-optimization.md
- **Severity**: Low (Java 11+ ignores the flag silently)
- **Where docs are wrong**: `jvm-optimization.md:41` recommends `-XX:+UseCGroupMemoryLimitForHeap`
- **What's true**: Flag deprecated in Java 9, removed in Java 11+. Java 17 uses `-XX:+UseContainerSupport` (already correctly listed at line 63).
- **Action**: Remove the deprecated line in Phase 1b. No code change.

### F-007 🟢 — Example Dockerfile in deployment.md doesn't match actual
- **Severity**: Low (cosmetic; example is illustrative)
- **Where docs are wrong**: `deployment.md` Dockerfile example uses `openjdk:17-jre-slim`
- **What code does**: Actual `Dockerfile` uses Amazon Corretto 17 Alpine multi-stage with detailed JAVA_OPTS
- **Action**: Replace example with actual Dockerfile (Phase 1b)

### F-008 🟢 — rule-language-reference.md describes features not used in this project
- **Severity**: High (causes major reader confusion)
- **Where docs are misleading**: `rule-language-reference.md` 3167 lines describing Drools 8 modern syntax (rule units, OOPath, DataStream)
- **What's true**: This project uses **traditional DRL syntax only** (Map() with eval()-free conditions). Sample rules confirm this.
- **Action**: Add prominent disclaimer at top of `23-rule-language-reference.md` and tag unused-feature sections (Phase 1b)

### F-009 🟢 — rule-development.md links to Drools 7.74.1
- **Severity**: Low
- **Where docs are wrong**: `rule-development.md:917` links to `docs.drools.org/7.74.1.Final/...`
- **What's true**: Project uses Drools 8.44.0
- **Action**: Update links in `17-rule-development.md` (Phase 1b)

### F-010 🟢 — Env var coverage gap
- **Severity**: High (operational friction)
- **Where docs are incomplete**: Existing docs cover ~30 env vars
- **What's true**: Code reads ~60+ env vars across config classes (full list in `09-environment-variables-reference.md` plan)
- **Action**: Create exhaustive reference in Phase 2a

### F-011 🟢 — Filter chain has unordered last filter
- **Severity**: Low (information completeness)
- **Where docs are silent**: Existing docs imply ordered chain `-1, 0, 1, 2`
- **What code does**: Filters are at `@Order(-1, 0, 1)` and `RequestSizeValidationFilter` has **no @Order** (runs last by default)
- **Action**: Document accurately in `14-security-architecture.md` and `04-architecture.md`

### F-012 🟢 — Eval contradiction was wrong
- **Severity**: Reset (this was a misread by me, not a code/doc bug)
- **Where I was wrong**: Earlier in the session I stated DrlSanitizer blocks eval() but sample rules use eval()
- **What's true**: DrlSanitizer DOES block eval() ([DrlSanitizer.java:182-185](src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L182-L185), test at [DrlSanitizerTest.java:530-551](src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java#L530-L551)). Sample rules do NOT use eval() — they use plain `if/else` in then-blocks.
- **Action**: Document the actual blocking accurately in `16-drl-sandboxing.md`. No drift to fix.

### F-013 🟢 — Admin port description in deployment.md
- **Severity**: Low (architectural clarity)
- **Where docs are misleading**: `deployment.md:29-38` ASCII diagram shows "Admin Portal (Port 8080)" as a separate box
- **What's true**: `/admin/*` runs on **the same port 8080** as the main API. Only Spring Actuator (`/actuator/*`) is on 8081.
- **Action**: Fix diagram in `06-deployment.md` (Phase 1b)

### F-014 🟢 — DrlSanitizer blocks more methods than docs suggest
- **Severity**: Medium
- **Where docs are incomplete**: Some docs list ~6 blocked methods
- **What code does**: Blocks 15 methods including `Class.getMethod`, `Class.getDeclaredField`, `.getClass().getMethod`, `.getClass().forName`, `System.getProperty`, `System.runFinalization`, etc. Full list in [DrlSanitizer.java:55-75](src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L55-L75).
- **Action**: Authoritative list in `16-drl-sandboxing.md` (Phase 2b)

### F-015 🟢 — DrlSanitizer import allowlist broader than docs suggest
- **Severity**: Low
- **What code does**: 18 allowed prefixes including `java.lang.Number`, `Comparable`, `Object`, `Enum`, `java.text.{DecimalFormat,NumberFormat,SimpleDateFormat}` per [DrlSanitizer.java:17-38](src/main/java/com/company/drools/core/engine/DrlSanitizer.java#L17-L38)
- **Action**: Authoritative list in `16-drl-sandboxing.md`

### F-016 🟢 — `LogSanitizer` masks more patterns than docs claim
- **Severity**: Low
- **What code does**: 17 sensitive patterns + 23 key names; masking strategies for credit card (last 4), SSN (last 4), email (first char), tokens (UUID/classname exclusions)
- **Action**: Document fully in `14-security-architecture.md` Layer 7 section

### F-017 🟢 — VIP discount example produces $72, not $80
- **Severity**: Low (minor doc claim accuracy)
- **Where docs are misleading**: `README.md` and others claim VIP rule on $100 → $80
- **What's true**: Both `pricing.discount.simple` (10% over $50) and `pricing.discount.vip` (20% VIP) match a VIP $100 order. They stack: $100 → $90 → $72.
- **Action**: Document the stacking explicitly in `19-sample-rules-cookbook.md`

### F-018 🟢 — LocalLRUCache.get() uses WRITE lock (deliberate, not bug)
- **Severity**: Informational
- **What code does**: `get()` uses write lock at [LocalLRUCache.java:73](src/main/java/com/company/drools/cache/LocalLRUCache.java#L73). Comment explains: access-ordered LinkedHashMap mutates internal structure on `get()`.
- **Action**: Document in `04-architecture.md` and ADR-004 in `36-architecture-decision-records.md`

### F-019 🟢 — `maxRuleFirings` cap of 10000
- **Severity**: Informational (good defense, undocumented)
- **What code does**: [RuleExecutor.java:22](src/main/java/com/company/drools/core/engine/RuleExecutor.java#L22) caps `fireAllRules` at 10000 to prevent runaway loops. Configurable via constructor for tests.
- **Action**: Document in `14-security-architecture.md` and `17-rule-development.md`

### F-020 🟢 — Path traversal protection in two places
- **Severity**: Informational
- **What code does**:
  - `S3RuleStorage.java:339-341` rejects `../` and leading `/`
  - `LocalFileStorage.java:161` uses `normalize()` + `startsWith()` check
- **Action**: Document both in `14-security-architecture.md` and `18-rule-id-and-storage-layout.md`

### F-021 🟢 — Atomic-swap rule loading pattern
- **Severity**: Informational (architectural strength, undocumented)
- **What code does**: `RuleCompiler.compileRules()` and `DroolsEngineService.loadRules()` compile **outside** the write lock, then atomic-swap inside. Old KieContainer disposed. Reads remain non-blocking during compilation.
- **Action**: Document in `04-architecture.md` and ADR-003 in `36-architecture-decision-records.md`

### F-022 🟢 — Circuit breaker thresholds vary by profile (stricter in prod)
- **Severity**: Informational
- **What code does**:
  - dev: S3 60% / Redis 70% failure threshold
  - prod: S3 40% / Redis 50% failure threshold (stricter)
- **Action**: Document in `29-circuit-breakers-and-resilience.md` and `05-environments-and-profiles.md`

### F-023 🟢 — RedisRuleCache uses SCAN, not KEYS
- **Severity**: Informational (good practice, undocumented)
- **What code does**: [RedisRuleCache.java:284-299](src/main/java/com/company/drools/cache/RedisRuleCache.java#L284-L299) uses cursor-based SCAN to avoid blocking Redis on large keysets
- **Action**: Document in `29-circuit-breakers-and-resilience.md` or `04-architecture.md` cache section

### F-024 🟢 — `BaseIntegrationTest` provides Testcontainers + LocalStack + Redis fixtures
- **Severity**: Informational (testing infrastructure)
- **What code does**: Test base class wires up real LocalStack and Redis containers for integration tests
- **Action**: Document in `28-testing-guide.md`

### F-025 🟢 — `setup-dev-environment.sh` includes a rule-execution test that uses wrong field name
- **Severity**: Low (silent test failure)
- **What's wrong**: `setup-dev-environment.sh` and `test-localstack.sh` use `ruleId` (camelCase) in their inline curl tests. The DTO requires `rule_id` (snake_case). The tests' rule-execution check thus fails silently and prints "rule may not be loaded yet" instead of a real failure.
- **Severity if fixed**: Code/script fix (not a doc fix). The fix is one-character in two scripts.
- **Action**: Document the issue here for triage. **Not fixed during docs work.** Note in `06-deployment.md` and `33-simple-start.md` to use `rule_id` and warn about this script bug.

### F-026 🟢 — `ADMIN_API_KEY` empty disables auth (warning logged at startup)
- **Severity**: Operational (security-relevant)
- **What code does**: [AdminAuthFilter.java:36-42](src/main/java/com/company/drools/api/filter/AdminAuthFilter.java#L36-L42) — when env var is empty/null, auth is **skipped** and a warning is logged. Existing docs hint at this but don't emphasize the operational risk.
- **Action**: Highlight prominently in `15-admin-authentication.md` with a "production checklist" callout

### F-027 🟢 — Storage factory has fallback to InMemory when RULE_SOURCE invalid
- **Severity**: Operational
- **What code does**: [StorageFactory.java:23-44](src/main/java/com/company/drools/storage/StorageFactory.java#L23-L44) — unrecognized RULE_SOURCE values fall through to InMemoryRuleStorageAdapter with a warning. Easy to miss in production.
- **Action**: Document in `18-rule-id-and-storage-layout.md` with a "watch out" callout

### F-028 🟢 — Tests prove rate-limiting admin exemption
- **Severity**: Informational (test-backed claim)
- **What code does**: [RateLimitingFilterTest.java:120-127](src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java#L120-L127) test `testFilter_AdminEndpoint_SkipsRateLimit()` proves admin endpoints are exempt
- **Action**: Cite this test in `13-rate-limiting-and-throttling.md` and `28-testing-guide.md`

### F-029 🟢 — JaCoCo has no coverage threshold configured
- **Severity**: Low (CI gate gap)
- **What code does**: `pom.xml` runs `prepare-agent` and `report` but no `check` goal with threshold
- **Action**: Note in `28-testing-guide.md` as recommended improvement (not a fix during this work)

### F-030 🟢 — `parameters: true` compiler flag preserves method names
- **Severity**: Informational
- **What code does**: `pom.xml` maven-compiler-plugin sets `parameters: true` for runtime parameter name retention
- **Action**: Document in `27-development-setup.md` build section

---

## Triage summary

- **🟢 Doc-fix-only items**: 30
- **🟡 Consider code-change items**: 0 (all current findings are doc-fix-only)
- **🔴 Potential bugs**: 0

The script-bug noted in F-025 (using `ruleId` instead of `rule_id` in setup/test scripts) is a code-side issue but cosmetic — the surrounding script checks still produce correct overall pass/fail; only the embedded rule-execution test step fails silently. Mark for separate triage.

---

## Findings discovered during Phase 1+ (appended as work proceeds)

### F-032 🟡 — `RuleIdValidator` silently trims whitespace instead of rejecting it
- **Severity**: Low (cosmetic; user gets the wrong error code but is rejected)
- **Discovered during**: Phase 3 review of `18-rule-id-and-storage-layout.md`
- **Where**: [`RuleIdValidator.java:29`](../src/main/java/com/company/drools/api/validation/RuleIdValidator.java#L29)
- **What's wrong**: `String trimmedRuleId = ruleId.trim();` — the validator trims for its own checks, but the controller still receives the original (untrimmed) value. So `"pricing.discount.simple "` (trailing space) **passes validation** but then fails as `RULE_NOT_FOUND` (404) at storage lookup, because the S3 key `pricing/discount/simple .drl` doesn't exist.
- **Why it matters**: Users get 404 instead of 400 with a clear validation message. Slight UX paper cut. No security impact (storage layer rejects properly).
- **Action**: Documented in `18-rule-id-and-storage-layout.md` with a ⚠️ note. **Code fix recommended** (separate from this docs work) — the validator should reject leading/trailing whitespace and return INVALID_INPUT.

### F-031 🟢 — `@Value` default mismatch with `application.yml` for max-number-value
- **Severity**: Low (cosmetic; YAML wins at runtime)
- **Discovered during**: Phase 2 writing of `09-environment-variables-reference.md`
- **Where**: validation config
- **What's inconsistent**:
  - `@Value("${drools.validation.data.max-number-value:1000000}")` in code → default 1,000,000 (1M)
  - [`application.yml:109`](../src/main/resources/application.yml#L109) sets `max-number-value: ${DROOLS_VALIDATION_DATA_MAX_NUMBER_VALUE:1000000000}` → default 1,000,000,000 (1B)
- **What runs**: YAML wins because it's loaded into the property source list before `@Value` resolution. The `@Value` default is unreachable unless YAML is removed.
- **Action**: Doc-level note added to `09-environment-variables-reference.md`. **No code fix recommended** — make sure people reading code know YAML is authoritative; consider aligning the `@Value` default to 1B as a tidy-up in a future commit.
