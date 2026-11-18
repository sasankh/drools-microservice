package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.testutil.RuleTestUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kie.api.KieBase;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Service-logic tests for {@link DroolsEngineService} (ADR-025 per-rule-ID isolation). Rules are
 * compiled by a REAL {@link RuleCompiler} (spied, so calls can be verified or delayed) into a REAL
 * {@link CompiledRulesetRegistry}; {@link RuleExecutor} is mocked so these tests stay about the
 * service's bookkeeping rather than Drools execution (covered by RuleExecutorTest and the
 * integration tests).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DroolsEngineServiceTest {

  @Mock private RuleExecutor ruleExecutor;
  @Mock private RuleStorage ruleStorage;
  @Mock private TimeoutConfig timeoutConfig;

  private RuleCompiler ruleCompiler;
  private CompiledRulesetRegistry registry;
  private MeterRegistry meterRegistry;
  private DroolsEngineService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    ruleCompiler = spy(new RuleCompiler(new DrlSanitizer()));
    registry = new CompiledRulesetRegistry();
    when(timeoutConfig.getRuleExecutionTimeoutSeconds()).thenReturn(30);
    service =
        new DroolsEngineService(
            ruleCompiler, ruleExecutor, registry, ruleStorage, timeoutConfig, meterRegistry);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void loadRuleSuccessfully(Rule rule) {
    loadRulesSuccessfully(List.of(rule));
  }

  private void loadRulesSuccessfully(List<Rule> rules) {
    assertThat(service.loadRules(rules)).as("seed load must compile").isTrue();
  }

  /** A valid one-rule DRL that puts {@code key=true}; different keys give different content. */
  private static Rule ruleWithKey(String ruleId, String key) {
    String content =
        String.format(
            """
            package com.company.rules.test

            import java.util.Map

            rule "%s"
            when
                $data : Map()
            then
                $data.put("%s", true);
            end
            """,
            ruleId, key);
    return new Rule(ruleId, content, RuleMetadata.createNew());
  }

  /** DRL that fails to compile (unresolvable type, missing import). */
  private static Rule brokenRule(String ruleId) {
    return RuleTestUtils.createInvalidRule(ruleId);
  }

  private CompiledRuleset handle(String ruleId) {
    return registry.get(ruleId).orElseThrow();
  }

  private void stubExecutorSuccess() {
    when(ruleExecutor.executeRule(any(KieBase.class), anyString(), any(), anyLong()))
        .thenAnswer(
            inv ->
                RuleExecutor.ExecutionResult.success(
                    new HashMap<String, Object>(inv.getArgument(2)), 5L));
  }

  // =========================================================================
  // Happy Path Tests
  // =========================================================================

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("executeRule succeeds and runs on that rule ID's own KieBase")
    void testExecuteRule_Success_WithValidRuleAndData() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      loadRuleSuccessfully(rule);
      KieBase ownBase = handle("pricing.discount.simple").kieBase();

      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 100.0);
      Map<String, Object> expectedResult = new HashMap<>(inputData);
      expectedResult.put("executed", true);

      when(ruleExecutor.executeRule(
              any(KieBase.class), eq("pricing.discount.simple"), eq(inputData), eq(30L)))
          .thenReturn(RuleExecutor.ExecutionResult.success(expectedResult, 15L));

      RuleExecutor.ExecutionResult result =
          service.executeRule("pricing.discount.simple", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("executed", true);
      assertThat(result.getResult()).containsEntry("amount", 100.0);
      verify(ruleExecutor)
          .executeRule(same(ownBase), eq("pricing.discount.simple"), eq(inputData), eq(30L));
    }

    @Test
    @DisplayName("loadRules succeeds with a single rule")
    void testLoadRules_Success_SingleRule() {
      Rule rule = RuleTestUtils.createSimpleRule("test.rule.one");

      boolean result = service.loadRules(List.of(rule));

      assertThat(result).isTrue();
      assertThat(service.hasRule("test.rule.one")).isTrue();
      assertThat(service.getLoadedRulesCount()).isEqualTo(1);
      assertThat(registry.contains("test.rule.one")).isTrue();

      RuleMetadata metadata = service.getRuleMetadata("test.rule.one");
      assertThat(metadata).isNotNull();
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      verify(ruleCompiler).compileRule(rule);
    }

    @Test
    @DisplayName("loadRules succeeds with multiple rules, one KieBase per rule ID")
    void testLoadRules_Success_MultipleRules() {
      Rule rule1 = RuleTestUtils.createSimpleRule("test.rule.one");
      Rule rule2 = RuleTestUtils.createSimpleRule("test.rule.two");
      Rule rule3 = RuleTestUtils.createSimpleRule("test.rule.three");

      boolean result = service.loadRules(List.of(rule1, rule2, rule3));

      assertThat(result).isTrue();
      assertThat(service.getLoadedRulesCount()).isEqualTo(3);
      assertThat(service.hasRule("test.rule.one")).isTrue();
      assertThat(service.hasRule("test.rule.two")).isTrue();
      assertThat(service.hasRule("test.rule.three")).isTrue();
      assertThat(service.getActiveRulesCount()).isEqualTo(3);
      assertThat(handle("test.rule.one").kieBase())
          .isNotSameAs(handle("test.rule.two").kieBase())
          .isNotSameAs(handle("test.rule.three").kieBase());
    }

    @Test
    @DisplayName("getRuleMetadata returns correct metadata")
    void testGetRuleMetadata_ReturnsCorrectMetadata() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.vip");
      loadRuleSuccessfully(rule);

      RuleMetadata metadata = service.getRuleMetadata("pricing.discount.vip");

      assertThat(metadata).isNotNull();
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(metadata.getVersion()).isEqualTo("1.0");
      assertThat(metadata.getLoadedAt()).isNotNull();
      assertThat(metadata.getExecutionCount()).isZero();
      assertThat(metadata.getAverageExecutionTimeMs()).isEqualTo(0.0);
      assertThat(metadata.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("hasRule returns true for loaded rule")
    void testHasRule_ReturnsTrueForLoadedRule() {
      Rule rule = RuleTestUtils.createSimpleRule("test.loaded.rule");
      loadRuleSuccessfully(rule);

      assertThat(service.hasRule("test.loaded.rule")).isTrue();
      assertThat(service.hasRule("test.nonexistent.rule")).isFalse();
    }
  }

  // =========================================================================
  // Concurrency & Thread Safety Tests
  // =========================================================================

  @Nested
  @DisplayName("Concurrency & Thread Safety")
  class ConcurrencyTests {

    @Test
    @DisplayName("concurrent executeRule calls complete without errors")
    void testExecuteRule_ConcurrentAccess() throws Exception {
      Rule rule = RuleTestUtils.createSimpleRule("concurrent.rule");
      loadRuleSuccessfully(rule);

      Map<String, Object> inputData = RuleTestUtils.createTestData("value", 42);
      RuleExecutor.ExecutionResult execResult =
          RuleExecutor.ExecutionResult.success(Map.of("value", 42, "executed", true), 5L);
      when(ruleExecutor.executeRule(any(KieBase.class), eq("concurrent.rule"), any(), anyLong()))
          .thenReturn(execResult);

      int threadCount = 10;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);
      List<RuleExecutor.ExecutionResult> results = Collections.synchronizedList(new ArrayList<>());
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              try {
                startLatch.await();
                RuleExecutor.ExecutionResult r =
                    service.executeRule("concurrent.rule", new HashMap<>(inputData));
                results.add(r);
              } catch (Exception _) {
                errorCount.incrementAndGet();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
      executor.shutdown();

      assertThat(completed).isTrue();
      assertThat(errorCount.get()).isZero();
      assertThat(results).hasSize(threadCount);
      results.forEach(r -> assertThat(r.isSuccess()).isTrue());
      assertThat(service.getRuleMetadata("concurrent.rule").getExecutionCount())
          .as("no lost execution-count updates")
          .isEqualTo(threadCount);
    }

    @Test
    @DisplayName("concurrent loadOrReplaceRule calls for different IDs all end up installed")
    void testLoadOrReplaceRule_ConcurrentAccess_AllInstalled() throws Exception {
      int threadCount = 5;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);
      List<Boolean> outcomes = Collections.synchronizedList(new ArrayList<>());

      for (int i = 0; i < threadCount; i++) {
        final int idx = i;
        executor.submit(
            () -> {
              try {
                startLatch.await();
                outcomes.add(
                    service.loadOrReplaceRule(
                        RuleTestUtils.createSimpleRule("write.lock.rule." + idx)));
              } catch (Exception _) {
                outcomes.add(false);
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
      executor.shutdown();

      assertThat(completed).isTrue();
      assertThat(outcomes).hasSize(threadCount).containsOnly(true);
      assertThat(service.getLoadedRulesCount()).isEqualTo(threadCount);
      assertThat(service.getActiveRulesCount()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("executeRule proceeds while a refresh is compiling (no lock on execution)")
    void testExecuteRule_WhileRefreshCompiles_NotBlocked() throws Exception {
      String id = "locking.test.rule";
      loadRuleSuccessfully(ruleWithKey(id, "v1"));
      KieBase v1Base = handle(id).kieBase();
      stubExecutorSuccess();

      // Hold the refresh inside compileRule (i.e. while it owns refreshLock).
      CountDownLatch compileStarted = new CountDownLatch(1);
      CountDownLatch releaseCompile = new CountDownLatch(1);
      doAnswer(
              inv -> {
                compileStarted.countDown();
                releaseCompile.await(10, TimeUnit.SECONDS);
                return inv.callRealMethod();
              })
          .when(ruleCompiler)
          .compileRule(any(Rule.class));

      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Boolean> refresh =
            pool.submit(() -> service.loadRules(List.of(ruleWithKey(id, "v2"))));
        assertThat(compileStarted.await(5, TimeUnit.SECONDS)).isTrue();

        Future<RuleExecutor.ExecutionResult> exec =
            pool.submit(() -> service.executeRule(id, new HashMap<>()));
        assertThat(exec.get(5, TimeUnit.SECONDS).isSuccess())
            .as("execution must not wait for the in-progress refresh")
            .isTrue();
        assertThat(refresh).isNotDone();
        // It ran on the version that was serving when it started.
        verify(ruleExecutor).executeRule(same(v1Base), eq(id), any(), anyLong());

        releaseCompile.countDown();
        assertThat(refresh.get(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        releaseCompile.countDown();
        pool.shutdownNow();
      }

      KieBase v2Base = handle(id).kieBase();
      assertThat(v2Base).isNotSameAs(v1Base);
      service.executeRule(id, new HashMap<>());
      verify(ruleExecutor).executeRule(same(v2Base), eq(id), any(), anyLong());
    }

    @Test
    @DisplayName("an in-flight request finishes on the handle it took while a swap completes")
    void testExecuteRule_InFlightDuringSwap_FinishesOnItsHandle() throws Exception {
      String id = "inflight.rule";
      loadRuleSuccessfully(ruleWithKey(id, "v1"));
      KieBase v1Base = handle(id).kieBase();

      CountDownLatch execStarted = new CountDownLatch(1);
      CountDownLatch releaseExec = new CountDownLatch(1);
      List<KieBase> seen = Collections.synchronizedList(new ArrayList<>());
      when(ruleExecutor.executeRule(any(KieBase.class), eq(id), any(), anyLong()))
          .thenAnswer(
              inv -> {
                seen.add(inv.getArgument(0));
                execStarted.countDown();
                releaseExec.await(10, TimeUnit.SECONDS);
                return RuleExecutor.ExecutionResult.success(Map.of("ok", true), 3L);
              });

      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<RuleExecutor.ExecutionResult> inFlight =
            pool.submit(() -> service.executeRule(id, new HashMap<>()));
        assertThat(execStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // The swap must not wait for the in-flight request.
        Future<Boolean> swap = pool.submit(() -> service.loadOrReplaceRule(ruleWithKey(id, "v2")));
        assertThat(swap.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handle(id).kieBase()).isNotSameAs(v1Base);
        assertThat(inFlight).isNotDone();

        releaseExec.countDown();
        assertThat(inFlight.get(5, TimeUnit.SECONDS).isSuccess()).isTrue();
      } finally {
        releaseExec.countDown();
        pool.shutdownNow();
      }

      assertThat(seen).hasSize(1);
      assertThat(seen.get(0)).isSameAs(v1Base);
      // Stats recorded after the swap are kept on the new version's metadata.
      assertThat(service.getRuleMetadata(id).getExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("multiple threads execute without data corruption")
    void testExecuteRule_MultipleThreads_NoDataCorruption() throws Exception {
      Rule rule = RuleTestUtils.createSimpleRule("corruption.test.rule");
      loadRuleSuccessfully(rule);

      when(ruleExecutor.executeRule(
              any(KieBase.class), eq("corruption.test.rule"), any(), anyLong()))
          .thenAnswer(
              invocation -> {
                Map<String, Object> data = invocation.getArgument(2);
                Map<String, Object> result = new HashMap<>(data);
                result.put("executed", true);
                return RuleExecutor.ExecutionResult.success(result, 5L);
              });

      int threadCount = 20;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);
      List<RuleExecutor.ExecutionResult> results = Collections.synchronizedList(new ArrayList<>());

      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(
            () -> {
              try {
                startLatch.await();
                Map<String, Object> data = new HashMap<>();
                data.put("threadId", threadId);
                data.put("value", threadId * 10);
                RuleExecutor.ExecutionResult r = service.executeRule("corruption.test.rule", data);
                results.add(r);
              } catch (Exception _) {
                // ignore
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
      executor.shutdown();

      assertThat(completed).isTrue();
      assertThat(results).hasSize(threadCount);
      results.forEach(
          r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getResult()).containsKey("executed");
            assertThat(r.getResult()).containsKey("threadId");
          });
    }
  }

  // =========================================================================
  // Error Handling Tests
  // =========================================================================

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("executeRule returns failure for non-existent rule")
    void testExecuteRule_RuleNotFound_ReturnsFailure() {
      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 50.0);

      RuleExecutor.ExecutionResult result = service.executeRule("nonexistent.rule", inputData);

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).isEqualTo("Rule not found: nonexistent.rule");
      verify(ruleExecutor, never())
          .executeRule(any(KieBase.class), anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("loadRules with compile failure keeps the prior version ACTIVE and serving")
    void testLoadRules_CompilationFailure_PreservesPriorActive() {
      loadRuleSuccessfully(RuleTestUtils.createSimpleRule("preserved.rule"));
      CompiledRuleset previous = handle("preserved.rule");
      stubExecutorSuccess();

      boolean ok = service.loadRules(List.of(brokenRule("preserved.rule")));

      assertThat(ok).isFalse();
      RuleMetadata metadata = service.getRuleMetadata("preserved.rule");
      assertThat(metadata.getStatus())
          .as("previously-ACTIVE rule must stay ACTIVE after a failed recompile")
          .isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(metadata.getErrorMessage())
          .as("the rejected update is recorded for operators")
          .startsWith("Compilation errors:");
      assertThat(registry.get("preserved.rule")).containsSame(previous);

      assertThat(service.executeRule("preserved.rule", new HashMap<>()).isSuccess()).isTrue();
      verify(ruleExecutor)
          .executeRule(same(previous.kieBase()), eq("preserved.rule"), any(), anyLong());
    }

    @Test
    @DisplayName("a brand-new rule that fails to compile is ERROR and not executable")
    void testLoadRules_CompilationFailure_NewRuleIsErrorAndNotExecutable() {
      boolean result = service.loadRules(List.of(brokenRule("brand.new.broken.rule")));

      assertThat(result).isFalse();
      RuleMetadata metadata = service.getRuleMetadata("brand.new.broken.rule");
      assertThat(metadata).isNotNull();
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(metadata.getErrorMessage()).isNotBlank();
      assertThat(service.hasRule("brand.new.broken.rule")).isTrue();
      assertThat(service.getLoadedRulesCount()).isZero();

      RuleExecutor.ExecutionResult exec =
          service.executeRule("brand.new.broken.rule", new HashMap<>());
      assertThat(exec.isSuccess()).isFalse();
      assertThat(exec.getErrorMessage()).isEqualTo("Rule is not active: brand.new.broken.rule");
      verify(ruleExecutor, never()).executeRule(any(KieBase.class), anyString(), any(), anyLong());
      assertThat(
              meterRegistry
                  .counter(
                      "drools.rule.execution.error",
                      "rule_id",
                      "brand.new.broken.rule",
                      "error",
                      "rule_not_active")
                  .count())
          .isEqualTo(1.0);
    }

    @Test
    @DisplayName("loadRules handles empty list gracefully")
    void testLoadRules_EmptyList_HandlesGracefully() {
      boolean result = service.loadRules(Collections.emptyList());

      assertThat(result).isTrue();
      assertThat(service.getLoadedRulesCount()).isZero();
      verify(ruleCompiler, never()).compileRule(any());
    }

    @Test
    @DisplayName("loadRules with one invalid DRL still installs the valid rule IDs")
    void testLoadRules_InvalidDRL_OthersStillLoad() {
      Rule invalidRule = brokenRule("invalid.drl.rule");
      Rule validRule = RuleTestUtils.createSimpleRule("valid.drl.rule");

      boolean result = service.loadRules(List.of(invalidRule, validRule));

      assertThat(result).isFalse();
      assertThat(registry.contains("valid.drl.rule")).isTrue();
      assertThat(service.getRuleMetadata("valid.drl.rule").getStatus())
          .isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(registry.contains("invalid.drl.rule")).isFalse();
      assertThat(service.getRuleMetadata("invalid.drl.rule").getStatus())
          .isEqualTo(RuleMetadata.RuleStatus.ERROR);
    }

    @Test
    @DisplayName("an ERROR rule becomes ACTIVE with its error cleared once a fix compiles")
    void testLoadOrReplaceRule_FixesErrorRule() {
      service.loadRules(List.of(brokenRule("fixable.rule")));
      assertThat(service.getRuleMetadata("fixable.rule").getStatus())
          .isEqualTo(RuleMetadata.RuleStatus.ERROR);

      assertThat(service.loadOrReplaceRule(RuleTestUtils.createSimpleRule("fixable.rule")))
          .isTrue();

      RuleMetadata metadata = service.getRuleMetadata("fixable.rule");
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(metadata.getErrorMessage()).isNull();
      assertThat(registry.contains("fixable.rule")).isTrue();
    }

    @Test
    @DisplayName("metadata status gates execution even when a compiled handle exists")
    void testExecuteRule_NonActiveStatusWithHandle_NotActive() {
      service.loadRules(List.of(brokenRule("gated.rule")));
      // Simulate a handle present for an ID whose metadata is not ACTIVE.
      registry.install(
          ruleCompiler.compileRule(RuleTestUtils.createSimpleRule("gated.rule")).ruleset());

      RuleExecutor.ExecutionResult result = service.executeRule("gated.rule", new HashMap<>());

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).isEqualTo("Rule is not active: gated.rule");
      assertThat(service.getActiveRulesCount()).isZero();
    }

    @Test
    @DisplayName("ACTIVE metadata without a compiled handle is not executable or counted")
    void testExecuteRule_ActiveWithoutHandle_NotActive() {
      loadRuleSuccessfully(RuleTestUtils.createSimpleRule("orphan.rule"));
      registry.remove("orphan.rule");

      RuleExecutor.ExecutionResult result = service.executeRule("orphan.rule", new HashMap<>());

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).isEqualTo("Rule is not active: orphan.rule");
      assertThat(service.getActiveRulesCount()).isZero();
      assertThat(service.hasRule("orphan.rule")).isTrue();
    }
  }

  // =========================================================================
  // Per-rule-ID isolation (ADR-025) — replaces the old shared-container tests
  // =========================================================================

  @Nested
  @DisplayName("Per-ID isolation")
  class PerIdIsolation {

    @Test
    @DisplayName("loadOrReplaceRule recompiles only that ID; other handles are untouched")
    void testLoadOrReplaceRule_OnlyRecompilesThatId() {
      Rule ruleA = RuleTestUtils.createSimpleRule("preserve.test.ruleA");
      Rule ruleB = RuleTestUtils.createSimpleRule("preserve.test.ruleB");
      Rule ruleC = RuleTestUtils.createSimpleRule("preserve.test.ruleC");
      loadRulesSuccessfully(List.of(ruleA, ruleB, ruleC));
      CompiledRuleset handleA = handle("preserve.test.ruleA");
      CompiledRuleset handleB = handle("preserve.test.ruleB");
      CompiledRuleset handleC = handle("preserve.test.ruleC");
      clearInvocations(ruleCompiler);

      Rule ruleBv2 = ruleWithKey("preserve.test.ruleB", "v2");
      boolean result = service.loadOrReplaceRule(ruleBv2);

      assertThat(result).isTrue();
      verify(ruleCompiler).compileRule(ruleBv2);
      verifyNoMoreInteractions(ruleCompiler);
      assertThat(service.getLoadedRulesCount()).isEqualTo(3);
      assertThat(registry.get("preserve.test.ruleA")).containsSame(handleA);
      assertThat(registry.get("preserve.test.ruleC")).containsSame(handleC);
      CompiledRuleset newB = handle("preserve.test.ruleB");
      assertThat(newB).isNotSameAs(handleB);
      assertThat(newB.contentDigest())
          .isNotEqualTo(handleB.contentDigest())
          .isEqualTo(RuleCompiler.sha256(ruleBv2.getContent()));
    }

    @Test
    @DisplayName("a bad update keeps the previous version serving and records the error")
    void testLoadOrReplaceRule_BadUpdate_PreviousVersionKeepsServing() {
      loadRulesSuccessfully(
          List.of(
              RuleTestUtils.createSimpleRule("bad.update.rule"),
              RuleTestUtils.createSimpleRule("bystander.rule")));
      CompiledRuleset previous = handle("bad.update.rule");
      CompiledRuleset bystander = handle("bystander.rule");
      stubExecutorSuccess();

      boolean result = service.loadOrReplaceRule(brokenRule("bad.update.rule"));

      assertThat(result).isFalse();
      assertThat(registry.get("bad.update.rule")).containsSame(previous);
      assertThat(registry.get("bystander.rule")).containsSame(bystander);
      RuleMetadata metadata = service.getRuleMetadata("bad.update.rule");
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThat(metadata.getErrorMessage()).isNotBlank();

      assertThat(service.executeRule("bad.update.rule", new HashMap<>()).isSuccess()).isTrue();
      verify(ruleExecutor)
          .executeRule(same(previous.kieBase()), eq("bad.update.rule"), any(), anyLong());

      // A later good update clears the recorded error.
      assertThat(service.loadOrReplaceRule(ruleWithKey("bad.update.rule", "v3"))).isTrue();
      assertThat(service.getRuleMetadata("bad.update.rule").getErrorMessage()).isNull();
      assertThat(handle("bad.update.rule")).isNotSameAs(previous);
    }

    @Test
    @DisplayName("a new rule that fails via loadOrReplaceRule is ERROR; other rules unaffected")
    void testLoadOrReplaceRule_NewRuleFails_ErrorAndIsolated() {
      loadRuleSuccessfully(RuleTestUtils.createSimpleRule("healthy.rule"));
      CompiledRuleset healthy = handle("healthy.rule");

      boolean result = service.loadOrReplaceRule(brokenRule("new.broken.rule"));

      assertThat(result).isFalse();
      assertThat(service.getRuleMetadata("new.broken.rule").getStatus())
          .isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(registry.contains("new.broken.rule")).isFalse();
      assertThat(service.executeRule("new.broken.rule", new HashMap<>()).getErrorMessage())
          .isEqualTo("Rule is not active: new.broken.rule");
      assertThat(registry.get("healthy.rule")).containsSame(healthy);
      assertThat(service.getActiveRulesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("removeRule removes only that ID and always returns true")
    void testRemoveRule_OnlyThatId() {
      loadRulesSuccessfully(
          List.of(
              RuleTestUtils.createSimpleRule("remove.keep"),
              RuleTestUtils.createSimpleRule("remove.drop")));
      CompiledRuleset keep = handle("remove.keep");

      assertThat(service.removeRule("remove.drop")).isTrue();

      assertThat(service.hasRule("remove.drop")).isFalse();
      assertThat(registry.contains("remove.drop")).isFalse();
      assertThat(service.executeRule("remove.drop", new HashMap<>()).getErrorMessage())
          .isEqualTo("Rule not found: remove.drop");
      assertThat(registry.get("remove.keep")).containsSame(keep);
      assertThat(service.getLoadedRulesCount()).isEqualTo(1);

      // Absent rule: no-op, still true.
      assertThat(service.removeRule("never.loaded")).isTrue();
      assertThat(service.getLoadedRulesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("removeRule clears a metadata-only (ERROR) rule ID")
    void testRemoveRule_ErrorOnlyId() {
      service.loadRules(List.of(brokenRule("error.only.rule")));
      assertThat(service.hasRule("error.only.rule")).isTrue();

      assertThat(service.removeRule("error.only.rule")).isTrue();

      assertThat(service.hasRule("error.only.rule")).isFalse();
    }

    @Test
    @DisplayName("loadRules removes IDs absent from the new list (compiled and ERROR alike)")
    void testLoadRules_RemovesAbsentIds() {
      Rule keep = RuleTestUtils.createSimpleRule("set.keep");
      Rule gone = RuleTestUtils.createSimpleRule("set.gone");
      Rule broken = brokenRule("set.broken");
      service.loadRules(List.of(keep, gone, broken));
      assertThat(service.hasRule("set.broken")).isTrue();

      DroolsEngineService.LoadReport report = service.loadRulesReport(List.of(keep));

      assertThat(report.allSucceeded()).isTrue();
      assertThat(report.loaded()).containsExactly("set.keep");
      assertThat(report.removed()).containsExactlyInAnyOrder("set.gone", "set.broken");
      assertThat(registry.ruleIds()).containsExactly("set.keep");
      assertThat(service.getAllRuleMetadata()).containsOnlyKeys("set.keep");
    }

    @Test
    @DisplayName("LoadReport lists loaded, failed (with message) and removed IDs")
    void testLoadRulesReport_Contents() {
      loadRuleSuccessfully(RuleTestUtils.createSimpleRule("report.old"));

      DroolsEngineService.LoadReport report =
          service.loadRulesReport(
              List.of(
                  RuleTestUtils.createSimpleRule("report.ok1"),
                  brokenRule("report.bad"),
                  RuleTestUtils.createSimpleRule("report.ok2")));

      assertThat(report.allSucceeded()).isFalse();
      assertThat(report.loaded()).containsExactly("report.ok1", "report.ok2");
      assertThat(report.failed()).containsOnlyKeys("report.bad");
      assertThat(report.failed().get("report.bad")).startsWith("Compilation errors:");
      assertThat(report.removed()).containsExactly("report.old");
      // Defensive copies: the report is immutable.
      assertThatThrownBy(() -> report.loaded().add("x"))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> report.failed().put("x", "y"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a duplicate rule ID in one load keeps the later definition (last wins)")
    void testLoadRules_DuplicateRuleId_LastWins() {
      Rule first = ruleWithKey("dup.rule", "first");
      Rule second = ruleWithKey("dup.rule", "second");

      assertThat(service.loadRules(List.of(first, second))).isTrue();

      assertThat(service.getLoadedRulesCount()).isEqualTo(1);
      assertThat(handle("dup.rule").contentDigest())
          .isEqualTo(RuleCompiler.sha256(second.getContent()));
      // Rule.equals is by rule ID, so check the count: only one compile for the duplicated ID.
      verify(ruleCompiler, times(1)).compileRule(any(Rule.class));
    }

    @Test
    @DisplayName("compile time and compile-error metrics are recorded per outcome")
    void testCompileMetrics_Recorded() {
      service.loadRules(
          List.of(
              RuleTestUtils.createSimpleRule("metric.ok1"),
              RuleTestUtils.createSimpleRule("metric.ok2"),
              brokenRule("metric.bad")));

      Timer success =
          meterRegistry.find("drools.rule.compile.time").tag("result", "success").timer();
      Timer error = meterRegistry.find("drools.rule.compile.time").tag("result", "error").timer();
      assertThat(success).isNotNull();
      assertThat(success.count()).isEqualTo(2);
      assertThat(error).isNotNull();
      assertThat(error.count()).isEqualTo(1);
      assertThat(
              meterRegistry.counter("drools.rule.compile.error", "rule_id", "metric.bad").count())
          .isEqualTo(1.0);
    }
  }

  // =========================================================================
  // Metrics & Statistics Tests
  // =========================================================================

  @Nested
  @DisplayName("Metrics & Statistics")
  class MetricsTests {

    @Test
    @DisplayName("successful execution records success metrics")
    void testExecuteRule_Success_RecordsMetrics() {
      Rule rule = RuleTestUtils.createSimpleRule("metrics.success.rule");
      loadRuleSuccessfully(rule);

      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 100.0);
      RuleExecutor.ExecutionResult execResult =
          RuleExecutor.ExecutionResult.success(Map.of("amount", 100.0, "executed", true), 25L);
      when(ruleExecutor.executeRule(
              any(KieBase.class), eq("metrics.success.rule"), eq(inputData), eq(30L)))
          .thenReturn(execResult);

      service.executeRule("metrics.success.rule", inputData);

      assertThat(
              meterRegistry
                  .counter("drools.rule.execution.success", "rule_id", "metrics.success.rule")
                  .count())
          .isEqualTo(1.0);

      assertThat(
              meterRegistry
                  .find("drools.rule.execution.time")
                  .tag("rule_id", "metrics.success.rule")
                  .tag("status", "success")
                  .timer())
          .isNotNull();

      RuleMetadata metadata = service.getRuleMetadata("metrics.success.rule");
      assertThat(metadata.getExecutionCount()).isEqualTo(1);
      assertThat(metadata.getAverageExecutionTimeMs()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("failed execution records error metrics")
    void testExecuteRule_Failure_RecordsErrorMetrics() {
      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 100.0);

      service.executeRule("missing.rule", inputData);

      assertThat(
              meterRegistry
                  .counter(
                      "drools.rule.execution.error",
                      "rule_id",
                      "unknown",
                      "error",
                      "rule_not_found")
                  .count())
          .isEqualTo(1.0);

      // Error timer uses the "unknown" tag (prevents cardinality explosion)
      assertThat(
              meterRegistry
                  .find("drools.rule.execution.time")
                  .tag("rule_id", "unknown")
                  .tag("status", "error")
                  .timer())
          .isNotNull();

      Rule rule = RuleTestUtils.createSimpleRule("failing.rule");
      loadRuleSuccessfully(rule);

      when(ruleExecutor.executeRule(any(KieBase.class), eq("failing.rule"), any(), anyLong()))
          .thenReturn(RuleExecutor.ExecutionResult.failure("Runtime error in rule"));

      service.executeRule("failing.rule", inputData);

      assertThat(
              meterRegistry
                  .counter(
                      "drools.rule.execution.error",
                      "rule_id",
                      "failing.rule",
                      "error",
                      "execution_failed")
                  .count())
          .isEqualTo(1.0);
    }
  }

  // =========================================================================
  // Additional Branch Coverage Tests
  // =========================================================================

  @Nested
  @DisplayName("Additional Coverage")
  class AdditionalCoverage {

    @Test
    @DisplayName("getAllRuleMetadata returns immutable copy of all metadata")
    void testGetAllRuleMetadata() {
      Rule rule1 = RuleTestUtils.createSimpleRule("meta.rule.one");
      Rule rule2 = RuleTestUtils.createSimpleRule("meta.rule.two");
      loadRulesSuccessfully(List.of(rule1, rule2));

      Map<String, RuleMetadata> allMetadata = service.getAllRuleMetadata();

      assertThat(allMetadata).hasSize(2).containsKey("meta.rule.one").containsKey("meta.rule.two");
      assertThat(allMetadata.get("meta.rule.one").getStatus())
          .isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      assertThatThrownBy(() -> allMetadata.remove("meta.rule.one"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("getActiveRulesCount returns 0 when all rules are in error state")
    void testGetActiveRulesCount_AllError() {
      service.loadRules(List.of(brokenRule("error.rule")));

      assertThat(service.getActiveRulesCount()).isZero();
      assertThat(service.getLoadedRulesCount()).isZero();
    }

    @Test
    @DisplayName("executeRule with execution failure records error metrics and timer")
    void testExecuteRule_ExecutionFailure_RecordsMetricsAndTimer() {
      Rule rule = RuleTestUtils.createSimpleRule("fail.exec.rule");
      loadRuleSuccessfully(rule);

      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 100.0);
      when(ruleExecutor.executeRule(
              any(KieBase.class), eq("fail.exec.rule"), eq(inputData), eq(30L)))
          .thenReturn(RuleExecutor.ExecutionResult.failure("Rule threw exception"));

      RuleExecutor.ExecutionResult result = service.executeRule("fail.exec.rule", inputData);

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).contains("Rule threw exception");
      assertThat(service.getRuleMetadata("fail.exec.rule").getExecutionCount())
          .as("failed executions don't count toward stats")
          .isZero();

      assertThat(
              meterRegistry
                  .find("drools.rule.execution.time")
                  .tag("rule_id", "fail.exec.rule")
                  .tag("status", "error")
                  .timer())
          .isNotNull();
    }
  }
}
