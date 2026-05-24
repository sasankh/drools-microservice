# 28 · Testing Guide

| | |
|---|---|
| **Audience** | Developers, AI agents inspecting test coverage |
| **Purpose** | Test suite map, how to run tests, how to add new ones, and the test cases that prove the most important behaviors |
| **Last verified against** | All `src/test/java/com/company/drools/**/*.java` on 2026-05-11 (45 test files, 597 `@Test`/`@ParameterizedTest` annotations) |
| **Related docs** | [27-development-setup.md](27-development-setup.md), [16-drl-sandboxing.md](16-drl-sandboxing.md), [14-security-architecture.md](14-security-architecture.md) |

---

## TL;DR

- **45 test files**, **548 unit tests + 14 Testcontainers integration tests**, ~96.2% instruction / ~89.7% branch coverage (JaCoCo, pre-modernization baseline; coverage roughly preserved post-modernization but JaCoCo not yet re-run). Unit count went 597 → 545 with the deletion of the dead `RuleCache` layer on 2026-05-20, then 545 → 548 with the SCAN-CB-wrap tests on 2026-05-24.
- Run all: `mvn test`
- Run one: `mvn test -Dtest=DroolsEngineServiceTest`
- Coverage report: `mvn test jacoco:report` → `target/site/jacoco/index.html`
- **Two base classes**: `BaseUnitTest` (Mockito) and `BaseIntegrationTest` (Testcontainers + Spring Boot).
- Tests are the **most accurate behavior spec** in the project. When docs and tests disagree, the tests win.

---

## Test inventory by package

Counts are `@Test` + `@ParameterizedTest` annotations per file (verified 2026-05-10).

### `api/controller/` — 64 tests across 4 files

| File | Tests | What it covers |
|---|---:|---|
| [`AdminControllerTest.java`](../src/test/java/com/company/drools/api/controller/AdminControllerTest.java) | 36 | `/admin/health`, `/admin/info`, `/admin/rules`, `/admin/refresh-rules`, `/admin/refresh-rules/{id}`, `/admin/thread-pools` — full controller behavior including error paths |
| [`MemoryControllerTest.java`](../src/test/java/com/company/drools/api/controller/MemoryControllerTest.java) | 15 | `/admin/memory/info`, `/admin/memory/snapshot`, `/admin/memory/gc` |
| [`RuleExecutionControllerTest.java`](../src/test/java/com/company/drools/api/controller/RuleExecutionControllerTest.java) | 12 | `POST /execute-rule` happy path + error envelope shapes |
| `TestValidationConfig.java` | 1 | Test-only config class (provides validators with mocked `ValidationConfig`) |

### `api/dto/` — 37 tests

| File | Tests |
|---|---:|
| [`DtoTest.java`](../src/test/java/com/company/drools/api/dto/DtoTest.java) | 37 — DTO equality, JSON roundtripping (`@JsonProperty("rule_id")` mapping verified here) |

### `api/exception/` — 18 tests

| File | Tests | What it covers |
|---|---:|---|
| [`ExceptionTest.java`](../src/test/java/com/company/drools/api/exception/ExceptionTest.java) | 9 | Custom exception types (RuleNotFoundException, TimeoutException, etc.) |
| [`GlobalExceptionHandlerTest.java`](../src/test/java/com/company/drools/api/exception/GlobalExceptionHandlerTest.java) | 9 | HTTP status mapping for every handled exception type — see [12-error-code-catalog.md](12-error-code-catalog.md) |

### `api/filter/` — 40 tests across 4 files

| File | Tests | Notable |
|---|---:|---|
| [`AdminAuthFilterTest.java`](../src/test/java/com/company/drools/api/filter/AdminAuthFilterTest.java) | 9 | Verifies dev-mode bypass when `ADMIN_API_KEY` empty; 401 when wrong key; non-admin paths skip filter |
| [`RateLimitingFilterTest.java`](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java) | 18 | Verifies `/admin/*` exemption (line 120-127), per-client buckets, X-Forwarded-For ignored, multi-tier client identification |
| [`RequestSizeValidationFilterTest.java`](../src/test/java/com/company/drools/api/filter/RequestSizeValidationFilterTest.java) | 12 | Content-Length check + chunked stream wrapping |
| [`SecurityHeadersFilterTest.java`](../src/test/java/com/company/drools/api/filter/SecurityHeadersFilterTest.java) | 1 | All 7 headers verified present with exact values |

### `api/validation/` — 50 tests

| File | Tests | What it covers |
|---|---:|---|
| [`RuleIdValidatorTest.java`](../src/test/java/com/company/drools/api/validation/RuleIdValidatorTest.java) | 17 | Pattern check, length check, path traversal rejection |
| [`RuleDataValidatorTest.java`](../src/test/java/com/company/drools/api/validation/RuleDataValidatorTest.java) | 33 | Field count, string length, number magnitude, dangerous patterns |

### `cache/` — pub/sub fan-out tests

| File | Tests |
|---|---:|
| [`RefreshEventTest.java`](../src/test/java/com/company/drools/cache/RefreshEventTest.java) | Wire-format serde for `RefreshEvent` (event_type / rule_id / source_instance_id / timestamp) |
| [`RuleRefreshPublisherTest.java`](../src/test/java/com/company/drools/cache/RuleRefreshPublisherTest.java) | Channel + payload assertions; circuit-breaker fall-through; failure → `drools.refresh.failed{layer=publisher}` |
| [`RuleRefreshSubscriberTest.java`](../src/test/java/com/company/drools/cache/RuleRefreshSubscriberTest.java) | Self-dedup via `source_instance_id`; dispatch to `engine.loadOrReplaceRule` / `loadRules`; malformed JSON tolerance |

The dead `LocalLRUCacheTest` / `RedisRuleCacheTest` / `CacheStatisticsTest` files were deleted on 2026-05-20 along with the legacy cache layer. The Redis decorator itself is covered under `storage/`:

| File | Tests |
|---|---:|
| [`RedisCachedRuleStorageTest.java`](../src/test/java/com/company/drools/storage/RedisCachedRuleStorageTest.java) | Read-through, write-through, bulk SCAN+MGET, circuit-breaker fall-through (Mockito) |
| [`RedisCachedStorageIntegrationTest.java`](../src/test/java/com/company/drools/integration/RedisCachedStorageIntegrationTest.java) | Testcontainers — cold→warm cache, TTL, bulk SCAN+MGET (hot/cold), refreshCache, refreshRule, write-through, Redis-kill CB fallback. **Excluded from default `mvn test`** (see `pom.xml` surefire `<excludes>`); requires host-side Docker daemon. |
| [`RedisPubSubIntegrationTest.java`](../src/test/java/com/company/drools/integration/RedisPubSubIntegrationTest.java) | Testcontainers — two-instance fan-out (single + bulk + delete), wire-format end-to-end. **Excluded from default `mvn test`**. |

### `common/` — 24 tests

| File | Tests |
|---|---:|
| [`LogSanitizerTest.java`](../src/test/java/com/company/drools/common/LogSanitizerTest.java) | 24 — credit card masking, SSN masking, nested map recursion, false-positive avoidance |

### `config/` — 93 tests across 11 files

| File | Tests |
|---|---:|
| `CircuitBreakerConfigTest.java` | 5 |
| `DroolsConfigTest.java` | 4 |
| `LoggingConfigTest.java` | 9 |
| `MetricsConfigTest.java` | 13 |
| `RateLimitingConfigTest.java` | 18 |
| `RedisConfigTest.java` | 10 |
| `RequestTimeoutConfigTest.java` | 4 |
| `RuleLoadingConfigTest.java` | 7 |
| `S3ConfigTest.java` | 10 |
| `StorageConfigTest.java` | 14 |
| `ThreadPoolConfigTest.java` | 4 |

### `core/engine/` — 63 tests across 4 files

| File | Tests | What it proves |
|---|---:|---|
| [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) | 23 | Every sandbox rule: import allowlist/blocklist, blocked classes, blocked methods, eval() rejection, static imports rejected |
| [`DroolsEngineServiceTest.java`](../src/test/java/com/company/drools/core/engine/DroolsEngineServiceTest.java) | 22 | Rule lookup, `KieContainer.updateToVersion` swap (post-2026-05-10 pattern), `KieRepository.removeKieModule` cleanup, TOCTOU-safe lookup |
| [`RuleCompilerTest.java`](../src/test/java/com/company/drools/core/engine/RuleCompilerTest.java) | 8 | DRL compilation, sanitizer integration, error reporting |
| [`RuleExecutorTest.java`](../src/test/java/com/company/drools/core/engine/RuleExecutorTest.java) | 10 | Async execution, timeout + future.cancel(true), maxRuleFirings cap |

### `core/model/` — 38 tests

| File | Tests |
|---|---:|
| `RuleTest.java` | 12 |
| `RuleMetadataTest.java` | 26 — incremental averaging (Welford's method), no-arg constructor for Jackson |

### `storage/` — 61 tests

| File | Tests |
|---|---:|
| [`S3RuleStorageTest.java`](../src/test/java/com/company/drools/storage/S3RuleStorageTest.java) | 29 |
| [`LocalFileStorageTest.java`](../src/test/java/com/company/drools/storage/LocalFileStorageTest.java) | 8 |
| [`InMemoryRuleStorageTest.java`](../src/test/java/com/company/drools/storage/InMemoryRuleStorageTest.java) | 19 |
| [`StorageFactoryTest.java`](../src/test/java/com/company/drools/storage/StorageFactoryTest.java) | 5 |

### `integration/` — 15 tests (Testcontainers, slower)

| File | Tests | Stack |
|---|---:|---|
| [`RuleExecutionIntegrationTest.java`](../src/test/java/com/company/drools/integration/RuleExecutionIntegrationTest.java) | 8 | Real Drools KieBase + sample rules from filesystem |
| [`S3StorageIntegrationTest.java`](../src/test/java/com/company/drools/integration/S3StorageIntegrationTest.java) | 7 | Real LocalStack S3 via Testcontainers |

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

Takes ~30-60 seconds. Output ends with `BUILD SUCCESS` and a summary like `Tests run: 597, Failures: 0, Errors: 0, Skipped: 0`.

### Single class

```bash
mvn test -Dtest=DroolsEngineServiceTest
```

### Single method

```bash
mvn test -Dtest=DroolsEngineServiceTest#testAtomicSwapDisposesOldContainer
```

### All tests matching a pattern

```bash
mvn test -Dtest='*FilterTest'        # all filter tests
mvn test -Dtest='*IntegrationTest'   # only Testcontainers tests (slower)
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
| `X-Forwarded-For` ignored | `RateLimitingFilterTest` | tested in client-id determination cases |
| AdminAuth bypassed when `ADMIN_API_KEY` empty | `AdminAuthFilterTest` | `testFilter_NoApiKeyConfigured_AllowsAccess` (representative) |
| AdminAuth requires header when key configured | `AdminAuthFilterTest` | `testFilter_MissingApiKey_Returns401` |
| All 7 security headers set | `SecurityHeadersFilterTest` | single test verifies all 7 |
| Request size limit blocks oversized bodies | `RequestSizeValidationFilterTest` | content-length and chunked variants |
| `eval()` blocked in DRL | `DrlSanitizerTest` (`EvalBlocking` nested class) | `testRejectsEvalInWhen` |
| Blocked imports rejected | `DrlSanitizerTest` (`ImportBlocking` nested) | various |
| Allowed imports accepted | `DrlSanitizerTest` (`ImportAllowlist` nested) | various |
| Static imports rejected | `DrlSanitizerTest` | dedicated test |
| Old `KieModule` removed from `KieRepository` on refresh | `DroolsEngineServiceTest` | post-2026-05-10 cleanup test (representative) |
| `KieContainer.updateToVersion` rule loading | `DroolsEngineServiceTest` | tested in load/refresh paths |
| TOCTOU-safe lookup | `DroolsEngineServiceTest` | tested via concurrent access |
| `maxRuleFirings = 10000` cap | `RuleExecutorTest` | bounded execution test |
| Timeout calls `future.cancel(true)` | `RuleExecutorTest` | timeout test |
| Path traversal rejected (S3) | `S3RuleStorageTest` | path validation tests |
| Path traversal rejected (LocalFile) | `LocalFileStorageTest` | normalize() check |
| `StorageFactory` recognizes `local`/`file`/`s3` | `StorageFactoryTest` | one test per case |
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

| Package | Instruction | Branch |
|---|---:|---:|
| `api/validation` | 100.0% | 94.6% |
| `core/model` | 100.0% | 100.0% |
| `common` | 98.6% | 90.5% |
| `api/filter` | 98.6% | 94.1% |
| `cache` | 98.3% | 90.0% |
| `api/controller` | 97.4% | 91.2% |
| `api/dto` | 96.0% | 86.8% |
| `core/engine` | 95.7% | 83.3% |
| `storage` | 94.1% | 89.5% |
| `config` | 93.1% | 83.3% |
| **Total** | **96.2%** | **89.7%** |

**No coverage threshold is configured in `pom.xml`.** A future addition could be:

```xml
<execution>
  <id>jacoco-check</id>
  <goals><goal>check</goal></goals>
  <configuration>
    <rules>
      <rule>
        <element>BUNDLE</element>
        <limits>
          <limit><counter>INSTRUCTION</counter><value>COVEREDRATIO</value><minimum>0.95</minimum></limit>
          <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.85</minimum></limit>
        </limits>
      </rule>
    </rules>
  </configuration>
</execution>
```

This would fail the build if coverage drops below thresholds. Logged as recommendation in `CODE_FINDINGS.md` F-029.

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

Counts as one entry in `mvn test` output but one `@Test` annotation in our count. (Why our count is 597 — many tests are parameterized and run multiple cases each.)

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

The `DrlSanitizerTest` is a good example — 6 nested groups (one per check).

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

## Performance test gap

There is **no JMeter / k6 / wrk load test suite** committed to the repo. Phase 4.3 of the original project plan was deferred. If you need to load-test:

```bash
# Crude single-host load test
seq 1 1000 | xargs -P 50 -I {} curl -s -o /dev/null \
  -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}'
```

For real load testing, use [k6](https://k6.io) or [JMeter](https://jmeter.apache.org). See [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) for what to watch during the test.

---

## Where to look when stuck

| Question | Answer |
|---|---|
| What does this method actually do? | Find its test. Tests are spec. |
| How is X called in production? | Find the integration test. |
| Why is this test failing? | Run with `-X` for full Maven log; compare diff before/after. |
| How do I mock Spring beans? | `@MockBean` in `@SpringBootTest` classes; `@Mock` in unit tests. |
| Where do I put fixtures? | `src/test/resources/` — but this project uses helper methods (`loadSampleRules()`) instead. |
