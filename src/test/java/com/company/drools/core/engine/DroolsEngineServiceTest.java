package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.testutil.RuleTestUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DroolsEngineServiceTest {

  @Mock private RuleCompiler ruleCompiler;
  @Mock private RuleExecutor ruleExecutor;
  @Mock private KieContainer initialKieContainer;
  @Mock private RuleStorage ruleStorage;
  @Mock private TimeoutConfig timeoutConfig;

  private MeterRegistry meterRegistry;
  private DroolsEngineService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    when(timeoutConfig.getRuleExecutionTimeoutSeconds()).thenReturn(30);
    // Default stub: updateToVersion returns a no-error Results so loadRules succeeds.
    Results successResults = mock(Results.class);
    when(successResults.hasMessages(Message.Level.ERROR)).thenReturn(false);
    when(initialKieContainer.updateToVersion(any(ReleaseId.class))).thenReturn(successResults);
    service =
        new DroolsEngineService(
            ruleCompiler,
            ruleExecutor,
            initialKieContainer,
            ruleStorage,
            timeoutConfig,
            meterRegistry);
  }

  // ---------------------------------------------------------------------------
  // Helper: load a rule into the service with successful compilation
  // ---------------------------------------------------------------------------
  private void loadRuleSuccessfully(Rule rule) {
    loadRulesSuccessfully(List.of(rule));
  }

  private void loadRulesSuccessfully(List<Rule> rules) {
    ReleaseId newReleaseId = mock(ReleaseId.class);
    RuleCompiler.CompilationResult result = RuleCompiler.CompilationResult.success(newReleaseId);
    when(ruleCompiler.compileRules(rules)).thenReturn(result);
    service.loadRules(rules);
  }

  // =========================================================================
  // Happy Path Tests
  // =========================================================================

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("executeRule succeeds with valid rule and data")
    void testExecuteRule_Success_WithValidRuleAndData() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      loadRuleSuccessfully(rule);

      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 100.0);
      Map<String, Object> expectedResult = new HashMap<>(inputData);
      expectedResult.put("executed", true);

      RuleExecutor.ExecutionResult execResult =
          RuleExecutor.ExecutionResult.success(expectedResult, 15L);
      when(ruleExecutor.executeRule(
              any(KieContainer.class), eq("pricing.discount.simple"), eq(inputData), eq(30L)))
          .thenReturn(execResult);

      RuleExecutor.ExecutionResult result =
          service.executeRule("pricing.discount.simple", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("executed", true);
      assertThat(result.getResult()).containsEntry("amount", 100.0);
      verify(ruleExecutor)
          .executeRule(
              any(KieContainer.class), eq("pricing.discount.simple"), eq(inputData), eq(30L));
    }

    @Test
    @DisplayName("loadRules succeeds with a single rule")
    void testLoadRules_Success_SingleRule() {
      Rule rule = RuleTestUtils.createSimpleRule("test.rule.one");
      ReleaseId newReleaseId = mock(ReleaseId.class);
      RuleCompiler.CompilationResult compilationResult =
          RuleCompiler.CompilationResult.success(newReleaseId);
      when(ruleCompiler.compileRules(List.of(rule))).thenReturn(compilationResult);

      boolean result = service.loadRules(List.of(rule));

      assertThat(result).isTrue();
      assertThat(service.hasRule("test.rule.one")).isTrue();
      assertThat(service.getLoadedRulesCount()).isEqualTo(1);

      RuleMetadata metadata = service.getRuleMetadata("test.rule.one");
      assertThat(metadata).isNotNull();
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
      verify(ruleCompiler).compileRules(List.of(rule));
    }

    @Test
    @DisplayName("loadRules succeeds with multiple rules")
    void testLoadRules_Success_MultipleRules() {
      Rule rule1 = RuleTestUtils.createSimpleRule("test.rule.one");
      Rule rule2 = RuleTestUtils.createSimpleRule("test.rule.two");
      Rule rule3 = RuleTestUtils.createSimpleRule("test.rule.three");
      List<Rule> rules = List.of(rule1, rule2, rule3);

      ReleaseId newReleaseId = mock(ReleaseId.class);
      RuleCompiler.CompilationResult compilationResult =
          RuleCompiler.CompilationResult.success(newReleaseId);
      when(ruleCompiler.compileRules(rules)).thenReturn(compilationResult);

      boolean result = service.loadRules(rules);

      assertThat(result).isTrue();
      assertThat(service.getLoadedRulesCount()).isEqualTo(3);
      assertThat(service.hasRule("test.rule.one")).isTrue();
      assertThat(service.hasRule("test.rule.two")).isTrue();
      assertThat(service.hasRule("test.rule.three")).isTrue();
      assertThat(service.getActiveRulesCount()).isEqualTo(3);
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
      assertThat(metadata.getExecutionCount()).isEqualTo(0);
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
    void testExecuteRule_ConcurrentAccess_ReadLockBehavior() throws Exception {
      Rule rule = RuleTestUtils.createSimpleRule("concurrent.rule");
      loadRuleSuccessfully(rule);

      Map<String, Object> inputData = RuleTestUtils.createTestData("value", 42);
      RuleExecutor.ExecutionResult execResult =
          RuleExecutor.ExecutionResult.success(Map.of("value", 42, "executed", true), 5L);
      when(ruleExecutor.executeRule(
              any(KieContainer.class), eq("concurrent.rule"), any(), anyLong()))
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
              } catch (Exception e) {
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
      assertThat(errorCount.get()).isEqualTo(0);
      assertThat(results).hasSize(threadCount);
      results.forEach(r -> assertThat(r.isSuccess()).isTrue());
    }

    @Test
    @DisplayName("loadOrReplaceRule allows concurrent compilation but serialized swap")
    void testLoadOrReplaceRule_ConcurrentAccess_WriteLockBehavior() throws Exception {
      when(ruleCompiler.compileRules(anyList()))
          .thenAnswer(
              invocation -> {
                Thread.sleep(50); // simulate compilation work
                ReleaseId releaseId = mock(ReleaseId.class);
                return RuleCompiler.CompilationResult.success(releaseId);
              });

      int threadCount = 5;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);

      for (int i = 0; i < threadCount; i++) {
        final int idx = i;
        executor.submit(
            () -> {
              try {
                startLatch.await();
                Rule rule = RuleTestUtils.createSimpleRule("write.lock.rule." + idx);
                // loadOrReplaceRule is the merge primitive — concurrent calls each add their
                // own rule on top of whatever is already loaded, exercising both the read-lock
                // (snapshot of current set) and write-lock (atomic update) paths.
                service.loadOrReplaceRule(rule);
              } catch (Exception e) {
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
      // All 5 rules should be loaded — concurrent merges preserve other rules on each call.
      assertThat(service.getLoadedRulesCount()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("executeRule and loadRules coordinate via read/write locks")
    void testExecuteRule_WhileLoadingRules_ProperLocking() throws Exception {
      Rule rule = RuleTestUtils.createSimpleRule("locking.test.rule");
      loadRuleSuccessfully(rule);

      AtomicBoolean loadInProgress = new AtomicBoolean(false);
      AtomicBoolean executionDuringLoad = new AtomicBoolean(false);

      // Make compileRules slow so we can detect overlap
      when(ruleCompiler.compileRules(anyList()))
          .thenAnswer(
              invocation -> {
                loadInProgress.set(true);
                Thread.sleep(200);
                loadInProgress.set(false);
                ReleaseId releaseId = mock(ReleaseId.class);
                return RuleCompiler.CompilationResult.success(releaseId);
              });

      RuleExecutor.ExecutionResult execResult =
          RuleExecutor.ExecutionResult.success(Map.of("executed", true), 5L);
      when(ruleExecutor.executeRule(any(KieContainer.class), anyString(), any(), anyLong()))
          .thenAnswer(
              invocation -> {
                if (loadInProgress.get()) {
                  executionDuringLoad.set(true);
                }
                return execResult;
              });

      ExecutorService executor = Executors.newFixedThreadPool(2);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(2);

      // Thread 1: load rules (write lock)
      executor.submit(
          () -> {
            try {
              startLatch.await();
              Rule newRule = RuleTestUtils.createSimpleRule("locking.test.rule");
              service.loadRules(List.of(newRule));
            } catch (Exception e) {
              // ignore
            } finally {
              doneLatch.countDown();
            }
          });

      // Thread 2: execute rule (read lock) - starts slightly after
      executor.submit(
          () -> {
            try {
              startLatch.await();
              Thread.sleep(50); // give write lock time to acquire
              service.executeRule("locking.test.rule", Map.of("key", "value"));
            } catch (Exception e) {
              // ignore
            } finally {
              doneLatch.countDown();
            }
          });

      startLatch.countDown();
      boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
      executor.shutdown();

      assertThat(completed).isTrue();
      // Execution should NOT happen while load is in progress (write lock blocks read)
      assertThat(executionDuringLoad.get()).isFalse();
    }

    @Test
    @DisplayName("multiple threads execute without data corruption")
    void testExecuteRule_MultipleThreads_NoDataCorruption() throws Exception {
      Rule rule = RuleTestUtils.createSimpleRule("corruption.test.rule");
      loadRuleSuccessfully(rule);

      when(ruleExecutor.executeRule(
              any(KieContainer.class), eq("corruption.test.rule"), any(), anyLong()))
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
              } catch (Exception e) {
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
      assertThat(result.getErrorMessage()).contains("Rule not found");
      assertThat(result.getErrorMessage()).contains("nonexistent.rule");
      verify(ruleExecutor, never())
          .executeRule(any(KieContainer.class), anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("executeRule returns failure for inactive rule")
    void testExecuteRule_InactiveRule_ReturnsFailure() {
      // Load rule successfully first so it's in loadedRules
      Rule rule = RuleTestUtils.createSimpleRule("inactive.rule");
      loadRuleSuccessfully(rule);

      // Now reload with failure - this marks metadata as ERROR but keeps rule in loadedRules
      RuleCompiler.CompilationResult failResult =
          RuleCompiler.CompilationResult.failure("Compilation error on refresh");
      when(ruleCompiler.compileRules(List.of(rule))).thenReturn(failResult);
      service.loadRules(List.of(rule));

      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 100.0);
      RuleExecutor.ExecutionResult result = service.executeRule("inactive.rule", inputData);

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).contains("not active");
      verify(ruleExecutor, never())
          .executeRule(any(KieContainer.class), anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("loadRules marks rules as ERROR on compilation failure")
    void testLoadRules_CompilationFailure_RulesMarkedAsError() {
      Rule rule = RuleTestUtils.createSimpleRule("broken.rule");
      String errorMsg = "Syntax error in DRL file";
      RuleCompiler.CompilationResult failResult = RuleCompiler.CompilationResult.failure(errorMsg);
      when(ruleCompiler.compileRules(List.of(rule))).thenReturn(failResult);

      boolean result = service.loadRules(List.of(rule));

      assertThat(result).isFalse();
      RuleMetadata metadata = service.getRuleMetadata("broken.rule");
      assertThat(metadata).isNotNull();
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(metadata.getErrorMessage()).isEqualTo(errorMsg);
    }

    @Test
    @DisplayName("loadRules handles empty list gracefully")
    void testLoadRules_EmptyList_HandlesGracefully() {
      ReleaseId newReleaseId = mock(ReleaseId.class);
      RuleCompiler.CompilationResult compilationResult =
          RuleCompiler.CompilationResult.success(newReleaseId);
      when(ruleCompiler.compileRules(Collections.emptyList())).thenReturn(compilationResult);

      boolean result = service.loadRules(Collections.emptyList());

      assertThat(result).isTrue();
      assertThat(service.getLoadedRulesCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("loadRules handles invalid DRL gracefully")
    void testLoadRules_InvalidDRL_FailsGracefully() {
      Rule invalidRule = RuleTestUtils.createInvalidRule("invalid.drl.rule");
      String errorMsg = "Compilation errors: missing package declaration";
      RuleCompiler.CompilationResult failResult = RuleCompiler.CompilationResult.failure(errorMsg);
      when(ruleCompiler.compileRules(List.of(invalidRule))).thenReturn(failResult);

      boolean result = service.loadRules(List.of(invalidRule));

      assertThat(result).isFalse();
      RuleMetadata metadata = service.getRuleMetadata("invalid.drl.rule");
      assertThat(metadata).isNotNull();
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
      assertThat(metadata.getErrorMessage()).contains("Compilation errors");
    }
  }

  // =========================================================================
  // Container Update Pattern Tests (Drools 10 updateToVersion)
  // =========================================================================

  @Nested
  @DisplayName("Container Update Pattern")
  class ContainerUpdatePattern {

    @Test
    @DisplayName("loadRules updates the long-lived container in place rather than swapping")
    void testLoadRules_UpdatesContainerInPlace_NoSwap() {
      Rule rule = RuleTestUtils.createSimpleRule("update.test.rule");
      ReleaseId newReleaseId = mock(ReleaseId.class);
      RuleCompiler.CompilationResult result =
          RuleCompiler.CompilationResult.success(newReleaseId);
      when(ruleCompiler.compileRules(List.of(rule))).thenReturn(result);

      service.loadRules(List.of(rule));

      // Verify updateToVersion was called with the new ReleaseId on the long-lived container.
      verify(initialKieContainer).updateToVersion(newReleaseId);
      // Verify the long-lived container is NOT disposed — it lives across all rule updates.
      verify(initialKieContainer, never()).dispose();
    }

    @Test
    @DisplayName("multiple refreshes call updateToVersion each time on the same container")
    void testLoadRules_MultipleRefreshes_SameContainerInstance() {
      List<ReleaseId> releaseIds = new ArrayList<>();

      for (int i = 0; i < 5; i++) {
        Rule rule = RuleTestUtils.createSimpleRule("refresh.rule." + i);
        ReleaseId releaseId = mock(ReleaseId.class, "release-" + i);
        releaseIds.add(releaseId);
        RuleCompiler.CompilationResult result =
            RuleCompiler.CompilationResult.success(releaseId);
        when(ruleCompiler.compileRules(List.of(rule))).thenReturn(result);

        service.loadRules(List.of(rule));
      }

      // updateToVersion was called once per refresh on the same long-lived container.
      for (ReleaseId releaseId : releaseIds) {
        verify(initialKieContainer).updateToVersion(releaseId);
      }
      // The long-lived container is never disposed.
      verify(initialKieContainer, never()).dispose();
    }

    @Test
    @DisplayName("loadOrReplaceRule preserves other loaded rules (single-rule refresh fix)")
    void testLoadOrReplaceRule_PreservesOtherRules() {
      // Seed with 3 rules.
      Rule ruleA = RuleTestUtils.createSimpleRule("preserve.test.ruleA");
      Rule ruleB = RuleTestUtils.createSimpleRule("preserve.test.ruleB");
      Rule ruleC = RuleTestUtils.createSimpleRule("preserve.test.ruleC");
      loadRulesSuccessfully(List.of(ruleA, ruleB, ruleC));
      assertThat(service.getLoadedRulesCount()).isEqualTo(3);

      // Replace just ruleB. The compiler must be invoked with the FULL combined set
      // (so updateToVersion sees a KieModule containing all three rules).
      Rule ruleBv2 = RuleTestUtils.createSimpleRule("preserve.test.ruleB");
      ReleaseId mergedReleaseId = mock(ReleaseId.class);
      when(ruleCompiler.compileRules(argThat(list -> list != null && list.size() == 3)))
          .thenReturn(RuleCompiler.CompilationResult.success(mergedReleaseId));

      boolean result = service.loadOrReplaceRule(ruleBv2);

      assertThat(result).isTrue();
      assertThat(service.getLoadedRulesCount()).isEqualTo(3);
      assertThat(service.hasRule("preserve.test.ruleA")).isTrue();
      assertThat(service.hasRule("preserve.test.ruleB")).isTrue();
      assertThat(service.hasRule("preserve.test.ruleC")).isTrue();
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
              any(KieContainer.class), eq("metrics.success.rule"), eq(inputData), eq(30L)))
          .thenReturn(execResult);

      service.executeRule("metrics.success.rule", inputData);

      // Verify success counter was recorded via SimpleMeterRegistry
      assertThat(
              meterRegistry
                  .counter("drools.rule.execution.success", "rule_id", "metrics.success.rule")
                  .count())
          .isEqualTo(1.0);

      // Verify timer was recorded
      assertThat(
              meterRegistry
                  .find("drools.rule.execution.time")
                  .tag("rule_id", "metrics.success.rule")
                  .tag("status", "success")
                  .timer())
          .isNotNull();

      // Verify metadata was updated with execution stats
      RuleMetadata metadata = service.getRuleMetadata("metrics.success.rule");
      assertThat(metadata.getExecutionCount()).isEqualTo(1);
      assertThat(metadata.getAverageExecutionTimeMs()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("failed execution records error metrics")
    void testExecuteRule_Failure_RecordsErrorMetrics() {
      // Test rule-not-found error metrics
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

      // Verify error timer was recorded with "unknown" tag (prevents cardinality explosion)
      assertThat(
              meterRegistry
                  .find("drools.rule.execution.time")
                  .tag("rule_id", "unknown")
                  .tag("status", "error")
                  .timer())
          .isNotNull();

      // Test execution-failed error metrics
      Rule rule = RuleTestUtils.createSimpleRule("failing.rule");
      loadRuleSuccessfully(rule);

      RuleExecutor.ExecutionResult failResult =
          RuleExecutor.ExecutionResult.failure("Runtime error in rule");
      when(ruleExecutor.executeRule(any(KieContainer.class), eq("failing.rule"), any(), anyLong()))
          .thenReturn(failResult);

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

      assertThat(allMetadata).hasSize(2);
      assertThat(allMetadata).containsKey("meta.rule.one");
      assertThat(allMetadata).containsKey("meta.rule.two");
      assertThat(allMetadata.get("meta.rule.one").getStatus())
          .isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
    }

    @Test
    @DisplayName("getActiveRulesCount returns 0 when all rules are in error state")
    void testGetActiveRulesCount_AllError() {
      Rule rule = RuleTestUtils.createSimpleRule("error.rule");
      RuleCompiler.CompilationResult failResult =
          RuleCompiler.CompilationResult.failure("Syntax error");
      when(ruleCompiler.compileRules(List.of(rule))).thenReturn(failResult);

      service.loadRules(List.of(rule));

      assertThat(service.getActiveRulesCount()).isEqualTo(0);
      assertThat(service.getLoadedRulesCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("loadRules returns false when updateToVersion reports compilation errors")
    void testLoadRules_UpdateToVersionError_ReturnsFalse() {
      Rule rule = RuleTestUtils.createSimpleRule("update.error.rule");
      ReleaseId newReleaseId = mock(ReleaseId.class);
      RuleCompiler.CompilationResult result =
          RuleCompiler.CompilationResult.success(newReleaseId);
      when(ruleCompiler.compileRules(List.of(rule))).thenReturn(result);

      // Override the default no-error stub for this test only.
      Results errorResults = mock(Results.class);
      when(errorResults.hasMessages(Message.Level.ERROR)).thenReturn(true);
      when(errorResults.getMessages(Message.Level.ERROR)).thenReturn(List.of());
      when(initialKieContainer.updateToVersion(newReleaseId)).thenReturn(errorResults);

      boolean loaded = service.loadRules(List.of(rule));

      assertThat(loaded).isFalse();
      // Rule must NOT be marked active when the update failed (KieBase remains at prior version).
      RuleMetadata metadata = service.getRuleMetadata("update.error.rule");
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ERROR);
    }

    @Test
    @DisplayName("executeRule with execution failure records error metrics and timer")
    void testExecuteRule_ExecutionFailure_RecordsMetricsAndTimer() {
      Rule rule = RuleTestUtils.createSimpleRule("fail.exec.rule");
      loadRuleSuccessfully(rule);

      Map<String, Object> inputData = RuleTestUtils.createTestData("amount", 100.0);
      RuleExecutor.ExecutionResult failResult =
          RuleExecutor.ExecutionResult.failure("Rule threw exception");
      when(ruleExecutor.executeRule(
              any(KieContainer.class), eq("fail.exec.rule"), eq(inputData), eq(30L)))
          .thenReturn(failResult);

      RuleExecutor.ExecutionResult result = service.executeRule("fail.exec.rule", inputData);

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).contains("Rule threw exception");

      // Verify error timer was recorded
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
