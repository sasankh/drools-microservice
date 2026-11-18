# 28 · Testing Guide

| | |
|---|---|
| **Audience** | Developers, AI agents inspecting test coverage |
| **Purpose** | Test suite map, how to run tests, how to add new ones, and the test cases that prove the most important behaviors |
| **Last verified against** | All `src/test/java/com/company/drools/**/*.java` on 2026-11-18 (55 test files; 766 tests in `mvn test` after `@ParameterizedTest` expansion, incl. the plain integration tests; 22 Testcontainers integration tests, surefire-excluded by default) |
| **Related docs** | [27-development-setup.md](27-development-setup.md), [16-drl-sandboxing.md](16-drl-sandboxing.md), [14-security-architecture.md](14-security-architecture.md) |

---

## TL;DR

- **55 test files**, **766 tests in `mvn test`** (unit tests plus plain integration tests such as `RuleExecutionIntegrationTest`, `RuleIsolationIntegrationTest`, `RuleRefreshIntegrationTest`, `ReadinessProbeIntegrationTest` and `PathBypassIntegrationTest`) and **22 Testcontainers integration tests** (S3 6, Redis cache 12, pub/sub 4). Coverage is **93.9% instruction / 86.1% branch** (JaCoCo, 2026-11-18).
  - Count history: 597 → 545 (dead `RuleCache` layer deleted, 2026-05-20) → 548 (SCAN-CB-wrap, 2026-05-24) → 536 (2026-08-20) → 591 (+55 path-bypass tests, 2026-11-17) → 620 (per-rule-ID isolation) → 695 (refresh/storage/startup) → 766 (limits/security) (engine isolation & hardening, 2026-11-18).
  - A coverage floor is enforced at `verify`: INSTRUCTION ≥ 0.88, BRANCH ≥ 0.74.
- **End-to-end:** `scripts/e2e-load-test.sh` (keep-alive load test with value checks) and `scripts/run-docker-test-plan.sh` (automated `full-docker-test-plan.md`). See [scripts/README.md](../scripts/README.md).
- Run all: `mvn test`
- Run one: `mvn test -Dtest=DroolsEngineServiceTest`
- Coverage report: `mvn test jacoco:report` → `target/site/jacoco/index.html`
- **Two base classes**: `BaseUnitTest` (Mockito) and `BaseIntegrationTest` (Testcontainers + Spring Boot).
- Tests are the **most accurate behavior spec** in the project. When docs and tests disagree, the tests win.

---

## Test inventory by package

Counts are `@Test` + `@ParameterizedTest` annotations per file (`grep`, verified 2026-11-18). A parameterized method counts once here but runs once per argument in `mvn test`, so these numbers add up to 678, not 766.

### `api/controller/` — 72 tests across 3 test files (+1 config helper)

| File | Tests | What it covers |
|---|---:|---|
| [`AdminControllerTest.java`](../src/test/java/com/company/drools/api/controller/AdminControllerTest.java) | 44 | `/admin/health` UP / DEGRADED / DOWN (503), `/admin/info`, `/admin/rules`, bulk refresh 200 / 500 `failed` / 409 `rejected_empty` with per-ID errors and counts, single refresh `success` / `unchanged` / `removed` / 404 / 500, always-publish to siblings, caller address passed to the audit log |
| [`MemoryControllerTest.java`](../src/test/java/com/company/drools/api/controller/MemoryControllerTest.java) | 12 | `/admin/memory/info`, `/admin/memory/snapshot`, `/admin/memory/gc` |
| [`RuleExecutionControllerTest.java`](../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java) | 16 | `POST /execute-rule` happy path + error envelope shapes, incl. 422 passthrough |
| `TestValidationConfig.java` | 0 | Test-only config class (provides validators with mocked `ValidationConfig`) |

### `api/dto/` — 37 tests

| File | Tests |
|---|---:|
| [`DtoTest.java`](../src/test/java/com/company/drools/api/dto/DtoTest.java) | 37 — DTO equality, JSON roundtripping (`@JsonProperty("rule_id")` mapping verified here) |

### `api/exception/` — 20 tests

| File | Tests | What it covers |
|---|---:|---|
| [`ExceptionTest.java`](../src/test/java/com/company/drools/api/exception/ExceptionTest.java) | 9 | Custom exception types (RuleNotFoundException, TimeoutException, etc.) |
| [`GlobalExceptionHandlerTest.java`](../src/test/java/com/company/drools/api/exception/GlobalExceptionHandlerTest.java) | 11 | HTTP status mapping for every handled exception type, incl. `RuleLimitExceededException` → 422 — see [12-error-code-catalog.md](12-error-code-catalog.md) |

### `api/filter/` — 43 tests across 5 files

| File | Tests | Notable |
|---|---:|---|
| [`AdminAuthFilterTest.java`](../src/test/java/com/company/drools/api/filter/AdminAuthFilterTest.java) | 14 | Verifies local/dev bypass when `ADMIN_API_KEY` empty; fail-closed startup under `prod`/`docker`; 401 when wrong/missing key; non-admin paths skip filter; **normalized-path matching** (`/admin;x/…`, `/%61dmin/…`, `//admin/…`, `/admin` → key required; `/administrator` not) |
| [`StrictRequestPathFilterTest.java`](../src/test/java/com/company/drools/api/filter/StrictRequestPathFilterTest.java) | 4 (parameterized) | 400 `INVALID_INPUT` for raw paths with `;` `%` `\` `//` `.`/`..`; normal paths (incl. dotted rule IDs) pass through (ADR-024) |
| [`RateLimitingFilterTest.java`](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java) | 13 | Verifies `/admin/*` exemption, per-client (IP-keyed) buckets, `X-Forwarded-For` ignored unless `trust-proxy=true` |
| [`RequestSizeValidationFilterTest.java`](../src/test/java/com/company/drools/api/filter/RequestSizeValidationFilterTest.java) | 11 | Content-Length check + chunked stream wrapping |
| [`SecurityHeadersFilterTest.java`](../src/test/java/com/company/drools/api/filter/SecurityHeadersFilterTest.java) | 1 | All 7 headers verified present with exact values |

### `api/validation/` — 45 tests

| File | Tests | What it covers |
|---|---:|---|
| [`RuleIdValidatorTest.java`](../src/test/java/com/company/drools/api/validation/RuleIdValidatorTest.java) | 17 | Pattern check, length check, path traversal rejection |
| [`RuleDataValidatorTest.java`](../src/test/java/com/company/drools/api/validation/RuleDataValidatorTest.java) | 28 | Field count, string length, number magnitude, dangerous patterns |

### `cache/` — 22 tests (pub/sub fan-out)

| File | Tests | What it covers |
|---|---:|---|
| [`RefreshEventTest.java`](../src/test/java/com/company/drools/cache/RefreshEventTest.java) | 3 | Wire-format serde for `RefreshEvent` (event_type / rule_id / source_instance_id / timestamp) |
| [`RuleRefreshPublisherTest.java`](../src/test/java/com/company/drools/cache/RuleRefreshPublisherTest.java) | 6 | Channel + payload assertions; circuit-breaker fall-through; failure → `drools.refresh.failed{layer=publisher}` |
| [`RuleRefreshSubscriberTest.java`](../src/test/java/com/company/drools/cache/RuleRefreshSubscriberTest.java) | 13 | Self-dedup via `source_instance_id`; dispatch to `RuleRefreshService`; every bulk event refreshes (no coalescing); compile failures and failed refreshes counted in `drools.refresh.failed{layer=subscriber}`; `RULE_DELETED` removes the rule; unsafe rule IDs and malformed JSON rejected |

The dead `LocalLRUCacheTest` / `RedisRuleCacheTest` / `CacheStatisticsTest` files were deleted on 2026-05-20 along with the legacy cache layer. The Redis decorator itself is covered under `storage/`:

| File | Tests |
|---|---:|
| [`RedisCachedRuleStorageTest.java`](../src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java) | 23 — read-through, write-through, bulk SCAN+MGET, circuit-breaker fall-through, delegate failures propagate (never a partial set), `getAllRulesFromSource` bypasses the cache (Mockito) |
| [`RedisCachedStorageIntegrationTest.java`](../src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java) | 12 — Testcontainers — cold→warm cache, TTL, bulk SCAN+MGET (hot/cold), refreshCache, refreshRule, write-through, Redis-kill CB fallback. **Excluded from default `mvn test`** (see `pom.xml` surefire `<excludes>`); requires host-side Docker daemon. |
| [`RedisPubSubIntegrationTest.java`](../src/test/java/com/company/drools/integration/RedisPubSubIntegrationTest.java) | 4 — Testcontainers — two-instance fan-out (single + bulk + delete), wire-format end-to-end. **Excluded from default `mvn test`**. |

### `common/` — 29 tests across 2 files

| File | Tests |
|---|---:|
| [`LogSanitizerTest.java`](../src/test/java/com/company/drools/common/LogSanitizerTest.java) | 24 — credit card masking, SSN masking, nested map recursion, false-positive avoidance |
| [`RequestPathsTest.java`](../src/test/java/com/company/drools/common/RequestPathsTest.java) | 5 (parameterized) — normalization used by all filters: percent-decoding, `;` stripping, `//` collapse, context path (ADR-024) |

### `config/` — 88 tests across 12 files

| File | Tests |
|---|---:|
| `CircuitBreakerConfigTest.java` | 6 |
| `InstanceIdConfigTest.java` | 2 (per-instance UUID bean used by pub/sub layer, added 2026-05-20) |
| `LoggingConfigTest.java` | 9 |
| `MetricsConfigTest.java` | 12 (incl. `drools.rules.loaded` bound to the registry size) |
| `RateLimitingConfigTest.java` | 18 |
| `RedisConfigTest.java` | 7 |
| `RequestTimeoutConfigTest.java` | 4 |
| `RuleLoadingConfigTest.java` | 7 (startup load outcomes; `fail-on-load-error=true` exits on a storage failure) |
| [`RuleReconcileSchedulerTest.java`](../src/test/java/com/company/drools/config/RuleReconcileSchedulerTest.java) | 4 (no drift → DEBUG; drift or failures → INFO with counts; storage failed / rejected empty → WARN) |
| [`RulesHealthIndicatorTest.java`](../src/test/java/com/company/drools/config/RulesHealthIndicatorTest.java) | 7 (DOWN until the startup load succeeds; DOWN with 0 rules unless `allow-empty`; per-ID errors listed, max 20, but still UP) |
| `S3ConfigTest.java` | 8 |
| `ThreadPoolConfigTest.java` | 4 |

### `core/engine/` — 134 tests across 5 files

| File | Tests | What it proves |
|---|---:|---|
| [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) | 22 | Import allowlist/blocklist, blocked classes, blocked methods, `eval()` rejection, static imports rejected. New nested groups (2026-11-18): `NormalizationResistant` (comment-hidden imports, `System .exit`, fully-qualified names, narrowed `java.util.`; all 17 samples accepted), `Normalize` (comment/string stripping, whitespace around `.`), `ReviewRegressions` (unicode escapes rejected; an unterminated quote can't hide an import). 9 `@Nested` groups; parameterized methods expand over every blocked entry |
| [`DroolsEngineServiceTest.java`](../src/test/java/com/company/drools/core/engine/DroolsEngineServiceTest.java) | 32 | Rule lookup, per-rule-ID install/replace/remove (`PerIdIsolation`), failed compile keeps last good version, digest skip, `removeRule`, TOCTOU-safe lookup, metrics |
| [`RuleCompilerTest.java`](../src/test/java/com/company/drools/core/engine/RuleCompilerTest.java) | 18 | Per-rule-ID DRL compilation into its own KieBase, policy-check integration, error reporting, duplicate rule name inside one file |
| [`RuleExecutorTest.java`](../src/test/java/com/company/drools/core/engine/RuleExecutorTest.java) | 22 | Async execution, timeout → `KieSession.halt()` + 408, pool saturation → `ServiceUnavailableException` (503). New nested groups (2026-11-18): `RuleFiringLimit` (a loop rule over the cap → `RuleLimitExceededException`; exactly `cap` firings succeeds; cap bounds 1 … `Integer.MAX_VALUE-1`) and `TimeoutInterruption` (`FutureTask.cancel(true)` interrupts a sleeping worker; a non-interruptible worker is counted in `drools.rule.execution.stuck`, which returns to 0 when it ends) |
| [`RuleRefreshServiceTest.java`](../src/test/java/com/company/drools/core/engine/RuleRefreshServiceTest.java) | 40 | New 2026-11-18. `refreshAll`: a storage failure changes nothing; empty set refused unless `allow-empty`; invalidate-then-read vs cached vs source reads; identical DRL → `unchanged`; only the changed rule recompiles; partial failure. `RuleLoadState` phase transitions. `refreshOne`: installed / unchanged / failed keeps previous / removed / not found / storage failed. `reconcile`: drift counted in `drools.reconcile.changed`. `AuditLog`: one `AUDIT` line per action, IDs capped at 50, no DRL text |

### `core/model/` — 38 tests

| File | Tests |
|---|---:|
| `RuleTest.java` | 12 |
| `RuleMetadataTest.java` | 26 — incremental averaging (Welford's method), no-arg constructor for Jackson |

### `storage/` — 90 tests across 5 files

| File | Tests | Notable |
|---|---:|---|
| [`S3RuleStorageTest.java`](../src/test/java/com/company/drools/storage/S3RuleStorageTest.java) | 30 | One unreadable object aborts the whole read (`testThrowsOnIndividualFailure`); an object deleted mid-listing is skipped; listing errors throw |
| [`LocalFileStorageTest.java`](../src/test/java/com/company/drools/storage/LocalFileStorageTest.java) | 12 | Missing directory, a path that is a file, and an IO error on one file all throw; an empty directory returns `[]` |
| [`InMemoryRuleStorageTest.java`](../src/test/java/com/company/drools/storage/InMemoryRuleStorageTest.java) | 19 | |
| [`RedisCachedRuleStorageTest.java`](../src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java) | 23 | See `cache/` above |
| [`StorageFactoryTest.java`](../src/test/java/com/company/drools/storage/StorageFactoryTest.java) | 6 | `local` / `file` / `s3`; an unknown source throws; the storage is built once |

### `integration/` — 60 tests across 8 files (5 plain ITs, 38 tests, run by default; 3 Testcontainers ITs, 22 tests, surefire-excluded, run in CI)

> **Pinned container images.** The Testcontainers ITs pin their images: **`localstack/localstack:2.3`** and **`redis:7.4-alpine`** (`DockerImageName.parse(...)`). `S3Config` also disables SDK request/response checksums for LocalStack endpoints, since LocalStack 2.3 rejects the SDK's default CRC32 request checksum.

| File | Tests | Stack |
|---|---:|---|
| [`RuleExecutionIntegrationTest.java`](../src/test/java/com/company/drools/integration/RuleExecutionIntegrationTest.java) | 15 | Real Drools KieBase + sample rules from filesystem; includes the `SampleRulesExecution` nested class that pin-tests outputs for the sample rules |
| [`RuleIsolationIntegrationTest.java`](../src/test/java/com/company/drools/integration/RuleIsolationIntegrationTest.java) | 14 | Rule-ID isolation with all 17 sample rules loaded ([ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)): VIP $100 → 80 and simple with VIP fields → 90 (no stacking), forall without `price` succeeds, same-package files isolate, salience order inside one file, unknown IDs never fall back, broken refresh keeps last good version, replace recompiles only that ID, in-flight request finishes on the old version, concurrent IDs don't cross-contaminate |
| [`RuleRefreshIntegrationTest.java`](../src/test/java/com/company/drools/integration/RuleRefreshIntegrationTest.java) | 4 | Refresh-path integration: a single-rule refresh doesn't degrade others; a content change swaps handle and digest; a broken update keeps the previous version; removal affects only that ID |
| [`ReadinessProbeIntegrationTest.java`](../src/test/java/com/company/drools/integration/ReadinessProbeIntegrationTest.java) | 2 | **Real server** (`@SpringBootTest(RANDOM_PORT)`, `local` profile): `/actuator/health/readiness` is 200 UP and includes the `rules` indicator; `/actuator/health/liveness` is 200 and doesn't depend on rules. Runs in default `mvn test` (no Docker). |
| [`PathBypassIntegrationTest.java`](../src/test/java/com/company/drools/integration/PathBypassIntegrationTest.java) | 3 (parameterized) | **Real embedded Tomcat** (`@SpringBootTest(RANDOM_PORT)`, `local` profile) + `java.net.http.HttpClient` sending raw paths: `/admin;x/health`, `/%61dmin/health`, `/%65xecute-rule`, … → 400 (with security headers); `/admin/health` → 401 / 200. Regression guard for ADR-024. Runs in default `mvn test` (no Docker). |
| [`S3StorageIntegrationTest.java`](../src/test/java/com/company/drools/integration/S3StorageIntegrationTest.java) | 6 | Real LocalStack S3 via Testcontainers |
| [`RedisCachedStorageIntegrationTest.java`](../src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java) | 12 | Redis decorator end-to-end (cold→warm cache, TTL, bulk SCAN+MGET, refreshCache, refreshRule, write-through, Redis-kill CB fallback) |
| [`RedisPubSubIntegrationTest.java`](../src/test/java/com/company/drools/integration/RedisPubSubIntegrationTest.java) | 4 | Two-instance pub/sub fan-out (single + bulk + delete events, wire-format end-to-end, source-instance dedup) |

### `testutil/` — helpers

| File | Purpose |
|---|---|
| [`RuleTestUtils.java`](../src/test/java/com/company/drools/testutil/RuleTestUtils.java) | `createSimpleRule()`, `createTestData()` |
| [`ValidationConfigTestHelper.java`](../src/test/java/com/company/drools/testutil/ValidationConfigTestHelper.java) | Mock `ValidationConfig` for validator tests |

---

## Two base classes

### `BaseUnitTest` ([source](../src/test/java/com/company/drools/BaseUnitTest.java))

Standard unit test harness:
- `@ExtendWith(MockitoExtension.class)` — Mockito @Mock auto-injection
- `@ActiveProfiles("test")` — test profile
- `@Mock protected MeterRegistry meterRegistry` — pre-mocked. The `@BeforeEach` configures it to return mocked `Counter` and `Timer` for any name + tags. This avoids NPEs in code that calls `meterRegistry.counter("foo", "tag", "value").increment()`.

Use this for any test where you mock collaborators. ~90% of tests extend it.

### `BaseIntegrationTest` ([source](../src/test/java/com/company/drools/BaseIntegrationTest.java))

For tests that need a real Spring context:
- `@SpringBootTest` — full app context boot
- `@ActiveProfiles("test")` — test profile
- `@Testcontainers` — manages container lifecycle
- Helpers: `loadSampleRules()` walks `sample-rules/` and returns `List<Rule>`; `loadSampleRule(ruleId)` loads one.

Use this for tests that exercise the real Drools engine, real storage layer, or real Spring beans.

---

## Running tests

### All tests

```bash
mvn test
```

Takes ~30-60 seconds. Output ends with `BUILD SUCCESS` and a summary like `Tests run: 766, Failures: 0, Errors: 0, Skipped: 0`. Plain integration tests (`RuleExecutionIntegrationTest`, `RuleIsolationIntegrationTest`, `RuleRefreshIntegrationTest`, and `ReadinessProbeIntegrationTest` / `PathBypassIntegrationTest`, which use a real embedded Tomcat) run by default. The 3 Testcontainers classes are surefire-excluded; run them with `mvn test -Dtest='S3StorageIntegrationTest,RedisCachedStorageIntegrationTest,RedisPubSubIntegrationTest'`. This needs a Docker daemon, and Testcontainers ≥ 1.21.4 for Docker Engine 29.

### Single class

```bash
mvn test -Dtest=DroolsEngineServiceTest
```

### Single method

```bash
mvn test -Dtest=DroolsEngineServiceTest#testExecuteRule
```

### All tests matching a pattern

```bash
mvn test -Dtest='*FilterTest'        # all filter tests
mvn test -Dtest='*IntegrationTest'   # all 8 integration classes (-Dtest overrides the excludes; the 3 Testcontainers ones need Docker)
```

### With coverage report

```bash
mvn test jacoco:report
open target/site/jacoco/index.html
```

The HTML report gives line-by-line coverage visualization. Drill in by package → class → method.

### Skip tests

```bash
mvn package -DskipTests   # build the jar without testing
```

---

## Behavior verified by tests (the implicit spec)

These are claims that are **proven by tests** — when this doc says "X is enforced", point to the named test. If you want to change a behavior, find the test that locks it in.

| Claim | Test class | Test method (representative) |
|---|---|---|
| Rate limiting uses per-client identity, not global | `RateLimitingFilterTest` | `testFilter_PerClient_IndependentLimits` |
| `/admin/*` exempt from rate limiting | `RateLimitingFilterTest` | `testFilter_AdminEndpoint_SkipsRateLimit` |
| `X-Forwarded-For` ignored by default (used only when `trust-proxy=true`) | `RateLimitingFilterTest` | client-id determination cases |
| AdminAuth open with WARN when `ADMIN_API_KEY` empty in `local`/`dev` | `AdminAuthFilterTest` | `testAllowsAdminWhenKeyEmpty` / `testAllowsAdminWhenKeyNull` |
| AdminAuth fails startup when key blank in `prod`/`docker` | `AdminAuthFilterTest` | `testFailsClosedProd` / `testFailsClosedDocker` |
| AdminAuth requires header when key configured (constant-time compare) | `AdminAuthFilterTest` | `testRejectsMissingApiKey` / `testRejectsInvalidApiKey` |
| All 7 security headers set | `SecurityHeadersFilterTest` | single test verifies all 7 |
| Request size limit blocks oversized bodies | `RequestSizeValidationFilterTest` | content-length and chunked variants |
| `eval()` blocked in DRL | `DrlSanitizerTest` (`EvalBlocking` nested class) | `testRejectsEvalInWhen` |
| Blocked imports rejected | `DrlSanitizerTest` (`BlockedImports` nested) | various |
| Comments, whitespace and fully-qualified names can't bypass the policy check; unicode escapes rejected | `DrlSanitizerTest` (`NormalizationResistant`, `ReviewRegressions`) | `rejects`, `unicodeEscapeRejected`, `unterminatedQuoteDoesNotHideImport` |
| All 17 sample rules pass the policy check | `DrlSanitizerTest` (`NormalizationResistant`) | `acceptsAllSampleRules` |
| Allowed imports accepted | `DrlSanitizerTest` (`AllowedRules` nested) | various |
| Static imports rejected | `DrlSanitizerTest` (`BlockedImports` nested) | dedicated test |
| A request runs only its own rule ID's rules (no stacking) | `RuleIsolationIntegrationTest` | `simpleDoesNotRunVip`, `vipGivesEighty` |
| Per-rule-ID refresh recompiles only that ID | `RuleIsolationIntegrationTest`, `DroolsEngineServiceTest` (`PerIdIsolation`) | `replaceOnlyRecompilesThatId` |
| TOCTOU-safe lookup | `DroolsEngineServiceTest` | tested via concurrent access |
| Over the firing cap → `RuleLimitExceededException` (422); exactly `cap` firings succeeds | `RuleExecutorTest` (`RuleFiringLimit`) | `loopExceedsCap`, `exactlyCapSucceeds` |
| `RuleLimitExceededException` → 422 `RULE_LIMIT_EXCEEDED` | `GlobalExceptionHandlerTest` | `testHandleRuleLimitExceeded` |
| Timeout halts the session and interrupts the worker; a non-yielding worker is counted as stuck | `RuleExecutorTest` (`TimeoutInterruption`) | `timeoutInterruptsWorker`, `nonInterruptibleWorkCountedAsStuck` |
| A failed storage read changes nothing | `RuleRefreshServiceTest` | `storageFailureLeavesEngineUntouched` |
| An empty read can't remove loaded rules unless `allow-empty` | `RuleRefreshServiceTest` | `emptySetRejected`, `allowEmptyRemovesAll` |
| Identical DRL is not recompiled | `RuleRefreshServiceTest` | `identicalRefreshIsUnchanged`, `onlyChangedRuleRecompiled` |
| One unreadable S3 object aborts the whole read | `S3RuleStorageTest` | `testThrowsOnIndividualFailure` |
| A missing rules directory throws (not `[]`) | `LocalFileStorageTest` | `testGetAllRules_MissingDirectory_Throws` |
| Reconcile counts drift | `RuleRefreshServiceTest` (`Reconcile`) | `countsDrift`, `noDrift` |
| Readiness DOWN until the startup load succeeds and with 0 rules | `RulesHealthIndicatorTest`, `ReadinessProbeIntegrationTest` | `downUntilLoaded`, `downWithZeroRules`, `readinessUpWithRulesComponent` |
| `/admin/health` DEGRADED (200) when storage is down, DOWN (503) when rules can't serve | `AdminControllerTest` | `testHealth_StorageDown_ReturnsDegraded`, `testHealth_StartupLoadNotSucceeded_ReturnsDown` |
| Bulk refresh 500 / 409, single refresh 404 with body | `AdminControllerTest` | `testRefreshAllRules_StorageFailed_Returns500`, `testRefreshAllRules_RejectedEmpty_Returns409`, `testRefreshRule_NotFound_Returns404` |
| Every refresh writes an `AUDIT` line without DRL text | `RuleRefreshServiceTest` (`AuditLog`) | `bulkAdminRefresh`, `refreshRuleCompileFailed` |
| Pool saturation → 503 `ServiceUnavailableException` | `RuleExecutorTest` | AbortPolicy rejection test |
| Path traversal rejected (S3) | `S3RuleStorageTest` | path validation tests |
| Path traversal rejected (LocalFile) | `LocalFileStorageTest` | normalize() check |
| `StorageFactory` recognizes `local`/`file`/`s3`; an unknown value fails | `StorageFactoryTest` | one test per case, `testUnknownSource` |
| Circuit breaker `ignoreExceptions(NoSuchKeyException)` | `CircuitBreakerConfigTest` / `S3RuleStorageTest` | NoSuchKey doesn't trip breaker |
| LogSanitizer masks credit cards | `LogSanitizerTest` | `testCreditCardMasking` |
| LogSanitizer masks SSN | `LogSanitizerTest` | `testSsnMasking` |
| LogSanitizer recurses nested maps | `LogSanitizerTest` | `testNestedMapSanitization` |
| Rule ID pattern `^[a-zA-Z0-9._-]+$` | `RuleIdValidatorTest` | various |
| `@JsonProperty("rule_id")` on RuleExecutionRequest | `DtoTest` | JSON roundtrip test |

If you change a behavior covered by a test, you'll get a failing test until you update both. That's by design — tests are the contract.

---

## Coverage

JaCoCo report (run `mvn test jacoco:report`):

| | Instruction | Branch |
|---|---:|---:|
| **Total (2026-11-18, `mvn clean verify`, Testcontainers ITs excluded)** | **93.9%** | **86.1%** |

> The old per-package breakdown (`api/validation` 100%, `core/model` 100%, etc., totalling 96.2%/89.7%) was the pre-modernization baseline and is no longer accurate; the numbers above are the current remeasured totals.

**A coverage threshold IS now enforced in `pom.xml`.** The `jacoco-check` execution is bound to the `verify` phase and fails the build if coverage drops below the floor:

```xml
<execution>
  <id>jacoco-check</id>
  <phase>verify</phase>
  <goals><goal>check</goal></goals>
  <configuration>
    <rules>
      <rule>
        <element>BUNDLE</element>
        <limits>
          <limit><counter>INSTRUCTION</counter><value>COVEREDRATIO</value><minimum>0.88</minimum></limit>
          <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.74</minimum></limit>
        </limits>
      </rule>
    </rules>
  </configuration>
</execution>
```

So `mvn verify` fails if instruction coverage falls below 0.88 or branch coverage below 0.74. (The floor sits below the current 93.9%/86.1% to catch regressions without being brittle.)

---

## Adding a new test

### Unit test

```java
// File: src/test/java/com/company/drools/<package>/MyClassTest.java
package com.company.drools.<package>;

import com.company.drools.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class MyClassTest extends BaseUnitTest {

  @Mock private SomeDependency dependency;

  @Test
  @DisplayName("happy path: returns expected result")
  void happyPath() {
    // Arrange
    when(dependency.fetch("foo")).thenReturn("bar");
    MyClass sut = new MyClass(dependency);

    // Act
    String result = sut.doThing("foo");

    // Assert
    assertThat(result).isEqualTo("bar");
  }

  @Test
  @DisplayName("when dependency throws, error is wrapped")
  void wrapsErrors() {
    when(dependency.fetch(any())).thenThrow(new RuntimeException("boom"));
    MyClass sut = new MyClass(dependency);

    assertThatThrownBy(() -> sut.doThing("foo"))
        .isInstanceOf(MyException.class)
        .hasMessageContaining("boom");
  }
}
```

### Integration test (Testcontainers)

```java
// File: src/test/java/com/company/drools/integration/MyIntegrationTest.java
package com.company.drools.integration;

import com.company.drools.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class MyIntegrationTest extends BaseIntegrationTest {

  @Autowired private SomeRealService service;

  @Test
  void endToEndFlow() throws Exception {
    var rules = loadSampleRules();
    var result = service.executeAgainst(rules.get(0), Map.of("amount", 100));
    assertThat(result).containsKey("discount");
  }
}
```

Integration tests are slower because they boot a Spring context. Use them when a unit test cannot prove the behavior (e.g., proving the real S3 client integrates correctly).

### Parameterized tests

```java
@ParameterizedTest
@ValueSource(strings = {"pricing.discount.simple", "pricing.discount.vip", "pricing.shipping.standard"})
void allRuleIdsHaveValidFormat(String ruleId) {
  assertThat(ruleId).matches("^[a-zA-Z0-9._-]+$");
}
```

Counts as one entry in our `@Test`/`@ParameterizedTest` annotation count, but multiple in `mvn test` output (one per parameter set). The raw annotation count is 678 (656 outside the 3 Testcontainers classes); surefire reports 766 after parameterized expansion.

---

## Test patterns to follow

### Use AssertJ, not Hamcrest

The project uses `org.assertj` exclusively. New tests should match.

```java
assertThat(result).isEqualTo(expected);
assertThat(list).hasSize(3).contains("foo");
assertThatThrownBy(() -> ...).isInstanceOf(MyException.class);
```

### Use `@DisplayName` for readability

```java
@Test
@DisplayName("rule_id with trailing space is silently trimmed by validator")
void validatorTrimsTrailingSpace() { ... }
```

The display name shows in test output; useful for non-trivial scenarios.

### Group with `@Nested`

When testing a class with many distinct concerns, group with `@Nested`:

```java
class MyServiceTest extends BaseUnitTest {

  @Nested
  @DisplayName("happy path scenarios")
  class HappyPath {
    @Test void scenarioA() { ... }
    @Test void scenarioB() { ... }
  }

  @Nested
  @DisplayName("error handling")
  class Errors {
    @Test void networkFailure() { ... }
    @Test void timeout() { ... }
  }
}
```

The `DrlSanitizerTest` is a good example — 9 nested groups (one per kind of check).

### Don't test the framework

Tests should verify *our* logic, not Spring/Mockito/Drools. If a test is just verifying that `@Autowired` works or `Map.put` puts, delete it.

---

## Common test gotchas

| Gotcha | Symptom | Fix |
|---|---|---|
| Forgetting to extend `BaseUnitTest` | NPEs in code that calls `meterRegistry.counter(...)` | Extend `BaseUnitTest` |
| Mocking final classes | "Cannot mock/spy class ..." | Mockito 5+ handles this; ensure `mockito-inline` is on classpath (it is, via spring-boot-starter-test) |
| Testcontainers fails to start | First-run delay; check Docker is running | `docker info` to verify |
| Tests pass locally, fail in CI | Probably timezone / locale / file separators | Always use `Paths.get(...)` not string concatenation; set `-Duser.timezone=UTC` |
| Flaky test on slow machine | Timeout-based assertions | Use Awaitility (`await().atMost(...).until(...)`) instead of `Thread.sleep` |
| Test pollutes shared state | Subsequent test fails when run together | Reset state in `@AfterEach`; tests must be order-independent |

---

## Docker test plan (end-to-end)

[`scripts/run-docker-test-plan.sh`](../scripts/run-docker-test-plan.sh) automates [`full-docker-test-plan.md`](../full-docker-test-plan.md) against the real compose stack and prints a PASS/FAIL table. Result on 2026-11-18: **72 PASS / 0 FAIL** (Steps 1–13). Checks added by the engine-isolation work:

| Step | What it proves |
|---|---|
| 12.5b | After the runaway rule (12.5, 408), `drools.rule.execution.stuck` ≥ 1 |
| 12.8 | A rule that re-triggers itself → 422 `RULE_LIMIT_EXCEEDED`, `result` is `null`, and the service still answers 200 afterwards |
| 12.9 | `AUDIT` lines for bulk and single refreshes, with the caller's address as actor and no DRL text |
| 13.1 | `/actuator/health/readiness` is 200 |
| 13.2–13.3 | LocalStack stopped → bulk refresh 500 `failed`, all 17 rules still serve; LocalStack back → 200, 17 `unchanged` |
| 13.4 | Empty bucket → 409 `rejected_empty`, 17 kept |
| 13.5–13.6 | App started against an empty bucket → readiness and `/admin/health` 503; rules restored → the scheduled reconcile loads 17 with no admin call |
| 13.7 | A direct S3 edit is picked up by reconcile (reads S3, bypassing Redis) |
| 13.8–13.9 | Deleted in S3 → single refresh `removed`; the same DRL again → `unchanged` |

---

## Load test orchestrator

The repo ships a full load-test orchestrator at [`scripts/run-load-test.sh`](../scripts/run-load-test.sh) (added 2026-05-10, extended with Phase 9 sub-tests on 2026-05-23). It runs:

- **Phase 0–8**: 1000-rule baseline, JMeter mixed-workload soak, refresh-storm
- **Phase 9.1**: single-instance regression (~520 RPS, P99 9ms, 0% errors)
- **Phase 9.2**: cache-only 3-replica mode (P99 10ms, 0% errors; per-replica Redis activity confirmed)
- **Phase 9.3**: full mode + pub/sub convergence (single-rule max 47ms / bulk 45ms / under-load 42ms across 3 sibling instances — all under 2000ms deadline)
- **Phase 9.4**: failure-mode (Redis kill / restart) — graceful-degradation ✅; 2 deferred follow-ups documented in [39-load-test-findings.md](39-load-test-findings.md) Phase 9.4 addendum

```bash
# Run individual phases (faster, ~2-5 min each)
./scripts/run-load-test.sh --quick --phase 9.1
./scripts/run-load-test.sh --quick --phase 9.3

# Run the full suite
./scripts/run-load-test.sh
```

Multi-container Phase 9 harness uses [`scripts/docker-compose.loadtest-multi.yml`](../scripts/docker-compose.loadtest-multi.yml) (3 replicas + nginx LB + named Redis). See [39-load-test-findings.md](39-load-test-findings.md) for the full results catalog and [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) for what to watch during a test.

For ad-hoc smoke load:

```bash
seq 1 1000 | xargs -P 50 -I {} curl -s -o /dev/null \
  -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}'
```

---

## Where to look when stuck

| Question | Answer |
|---|---|
| What does this method actually do? | Find its test. Tests are spec. |
| How is X called in production? | Find the integration test. |
| Why is this test failing? | Run with `-X` for full Maven log; compare diff before/after. |
| How do I mock Spring beans? | `@MockBean` in `@SpringBootTest` classes; `@Mock` in unit tests. |
| Where do I put fixtures? | `src/test/resources/` — but this project uses helper methods (`loadSampleRules()`) instead. |
