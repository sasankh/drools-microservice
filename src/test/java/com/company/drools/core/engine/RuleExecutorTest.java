package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.company.drools.api.exception.ServiceUnavailableException;
import com.company.drools.api.exception.TimeoutException;
import com.company.drools.core.model.Rule;
import com.company.drools.testutil.RuleTestUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

@DisplayName("RuleExecutor")
class RuleExecutorTest {

  /** Helper: compile a single rule ID into its own KieBase (ADR-025). */
  private KieBase compileRule(Rule rule) {
    RuleCompiler compiler = new RuleCompiler(new DrlSanitizer());
    RuleCompiler.RuleCompilation result = compiler.compileRule(rule);
    assertThat(result.isSuccess())
        .as("Rule compilation should succeed for: " + rule.getRuleId())
        .isTrue();
    return result.ruleset().kieBase();
  }

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("executes a valid rule and returns modified data")
    void testExecuteRule_ValidRule_ReturnsModifiedData() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      KieBase kieBase = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("amount", 100);

      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieBase, "pricing.discount.simple", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("executed", true);
      assertThat(result.getResult()).containsEntry("amount", 100);
      assertThat(result.getExecutionTimeMs()).isGreaterThanOrEqualTo(0);
      assertThat(result.getErrorMessage()).isNull();

      executor.shutdown();
    }

    @Test
    @DisplayName("completes within the specified timeout")
    void testExecuteRule_WithTimeout_CompletesSuccessfully() {
      Rule rule = RuleTestUtils.createSimpleRule("test.timeout.fast");
      KieBase kieBase = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("value", "test");

      // Set a generous 5-second timeout for a rule that completes instantly
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieBase, "test.timeout.fast", inputData, 5);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getExecutionTimeMs()).isLessThan(5000);

      executor.shutdown();
    }

    @Test
    @DisplayName("fires multiple rules and returns correct result")
    void testExecuteRule_MultipleRuleFirings_ReturnsCorrectResult() {
      String content =
          """
          package com.company.rules.multi

          import java.util.Map

          rule "rule-one"
          when
              $data : Map(this["step1"] == null)
          then
              $data.put("step1", true);
          end

          rule "rule-two"
          when
              $data : Map(this["step2"] == null)
          then
              $data.put("step2", true);
          end

          rule "rule-three"
          when
              $data : Map(this["step3"] == null)
          then
              $data.put("step3", true);
          end
          """;
      Rule rule =
          new Rule("multi.rules", content, com.company.drools.core.model.RuleMetadata.createNew());
      KieBase kieBase = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieBase, "multi.rules", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("step1", true);
      assertThat(result.getResult()).containsEntry("step2", true);
      assertThat(result.getResult()).containsEntry("step3", true);

      executor.shutdown();
    }

    @Test
    @DisplayName("fires only the rules of the KieBase it is given (ADR-025 isolation)")
    void testExecuteRule_OnlyGivenKieBaseFires() {
      KieBase simpleBase = compileRule(RuleTestUtils.createSimpleRule("iso.simple"));
      String otherContent =
          """
          package com.company.rules.iso

          import java.util.Map

          rule "other"
          when
              $data : Map()
          then
              $data.put("other", true);
          end
          """;
      KieBase otherBase =
          compileRule(
              new Rule(
                  "iso.other",
                  otherContent,
                  com.company.drools.core.model.RuleMetadata.createNew()));
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      RuleExecutor.ExecutionResult simple =
          ruleExecutor.executeRule(simpleBase, "iso.simple", new HashMap<>());
      RuleExecutor.ExecutionResult other =
          ruleExecutor.executeRule(otherBase, "iso.other", new HashMap<>());

      assertThat(simple.getResult()).containsEntry("executed", true).doesNotContainKey("other");
      assertThat(other.getResult()).containsEntry("other", true).doesNotContainKey("executed");

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Timeout Handling")
  class TimeoutHandling {

    @Test
    @SuppressWarnings("java:S2925")
    @DisplayName("throws TimeoutException when execution exceeds timeout")
    void testExecuteRule_ExceedsTimeout_ThrowsTimeoutException() {
      // Use a mock KieBase whose session blocks the session thread
      KieBase mockKieBase = mock(KieBase.class);
      KieSession mockSession = mock(KieSession.class);
      when(mockKieBase.newKieSession()).thenReturn(mockSession);
      when(mockSession.fireAllRules(anyInt()))
          .thenAnswer(
              invocation -> {
                // Simulate a long-running rule execution
                Thread.sleep(10_000);
                return 1;
              });

      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      assertThatThrownBy(() -> ruleExecutor.executeRule(mockKieBase, "slow.rule", inputData, 1))
          .isInstanceOf(TimeoutException.class)
          .hasMessageContaining("slow.rule");

      executor.shutdownNow();
    }

    @Test
    @DisplayName("completes successfully when finishing just before timeout")
    void testExecuteRule_NearTimeout_CompletesSuccessfully() {
      Rule rule = RuleTestUtils.createSimpleRule("test.neartimeout");
      KieBase kieBase = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      // Simple rule completes almost instantly; 2-second timeout is generous
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieBase, "test.neartimeout", inputData, 2);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getExecutionTimeMs()).isLessThan(2000);

      executor.shutdown();
    }

    @Test
    @SuppressWarnings("java:S2925")
    @DisplayName("respects custom timeout configuration")
    void testExecuteRule_CustomTimeout_RespectsConfiguration() {
      KieBase mockKieBase = mock(KieBase.class);
      KieSession mockSession = mock(KieSession.class);
      when(mockKieBase.newKieSession()).thenReturn(mockSession);
      when(mockSession.fireAllRules(anyInt()))
          .thenAnswer(
              invocation -> {
                Thread.sleep(5_000);
                return 1;
              });

      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      // Custom timeout of 1 second should trigger before the 5s sleep
      assertThatThrownBy(
              () -> ruleExecutor.executeRule(mockKieBase, "custom.timeout", inputData, 1))
          .isInstanceOf(TimeoutException.class);

      // Verify the timeout value is in the exception
      try {
        ruleExecutor.executeRule(mockKieBase, "custom.timeout", inputData, 1);
      } catch (TimeoutException e) {
        assertThat(e.getTimeoutSeconds()).isEqualTo(1);
      } catch (Exception _) {
        // May get a different error on second call since thread pool is busy
      }

      executor.shutdownNow();
    }

    @Test
    @SuppressWarnings("java:S2925")
    @DisplayName("halts the running session when execution times out (P3)")
    void testExecuteRule_Timeout_HaltsSession() {
      KieBase mockKieBase = mock(KieBase.class);
      KieSession mockSession = mock(KieSession.class);
      when(mockKieBase.newKieSession()).thenReturn(mockSession);
      when(mockSession.fireAllRules(anyInt()))
          .thenAnswer(
              invocation -> {
                Thread.sleep(10_000);
                return 1;
              });

      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> data = new HashMap<>();
      assertThatThrownBy(() -> ruleExecutor.executeRule(mockKieBase, "runaway.rule", data, 1))
          .isInstanceOf(TimeoutException.class);

      // The timeout path must call halt() so the runaway firing is stopped, not leaked.
      verify(mockSession, timeout(3000)).halt();

      executor.shutdownNow();
    }
  }

  @Nested
  @DisplayName("Load Shedding")
  class LoadShedding {

    @Test
    @DisplayName("throws ServiceUnavailableException when the pool rejects the task (P3)")
    void testExecuteRule_PoolSaturated_ThrowsServiceUnavailable() {
      KieBase mockKieBase = mock(KieBase.class);
      // Executor that always rejects — simulates a saturated pool under AbortPolicy.
      Executor rejecting =
          command -> {
            throw new RejectedExecutionException("pool saturated");
          };
      RuleExecutor ruleExecutor = new RuleExecutor(rejecting);

      Map<String, Object> data = new HashMap<>();
      assertThatThrownBy(() -> ruleExecutor.executeRule(mockKieBase, "rejected.rule", data, 5))
          .isInstanceOf(ServiceUnavailableException.class);
    }
  }

  @Nested
  @DisplayName("Thread Pool and Concurrency")
  class ThreadPoolAndConcurrency {

    @Test
    @DisplayName("uses the provided custom thread pool executor")
    void testExecuteRule_UsesCustomThreadPool() {
      Rule rule = RuleTestUtils.createSimpleRule("test.threadpool");
      KieBase kieBase = compileRule(rule);

      // Track which thread runs the rule
      List<String> threadNames = new CopyOnWriteArrayList<>();
      ExecutorService customExecutor =
          Executors.newSingleThreadExecutor(r -> new Thread(r, "custom-rule-thread"));

      // Wrap to capture thread name during execution
      Executor trackingExecutor =
          command -> {
            customExecutor.execute(
                () -> {
                  threadNames.add(Thread.currentThread().getName());
                  command.run();
                });
          };

      RuleExecutor ruleExecutor = new RuleExecutor(trackingExecutor);

      Map<String, Object> inputData = new HashMap<>();

      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieBase, "test.threadpool", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(threadNames).isNotEmpty();
      assertThat(threadNames.get(0)).isEqualTo("custom-rule-thread");

      customExecutor.shutdown();
    }

    @Test
    @DisplayName("parallel executions use independent sessions")
    void testExecuteRule_ParallelExecutions_IndependentSessions() throws Exception {
      Rule rule = RuleTestUtils.createSimpleRule("test.parallel");
      KieBase kieBase = compileRule(rule);
      ExecutorService executor = Executors.newFixedThreadPool(5);
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      int parallelCount = 5;
      CountDownLatch latch = new CountDownLatch(parallelCount);
      List<RuleExecutor.ExecutionResult> results = new CopyOnWriteArrayList<>();
      List<Throwable> errors = new CopyOnWriteArrayList<>();

      for (int i = 0; i < parallelCount; i++) {
        final int index = i;
        new Thread(
                () -> {
                  try {
                    Map<String, Object> inputData = new HashMap<>();
                    inputData.put("index", index);

                    RuleExecutor.ExecutionResult result =
                        ruleExecutor.executeRule(kieBase, "test.parallel", inputData);
                    results.add(result);
                  } catch (Exception e) {
                    errors.add(e);
                  } finally {
                    latch.countDown();
                  }
                })
            .start();
      }

      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertThat(completed).as("All parallel executions should complete").isTrue();
      assertThat(errors).as("No errors in parallel execution").isEmpty();
      assertThat(results).hasSize(parallelCount);

      // Each result should be successful and independent
      for (RuleExecutor.ExecutionResult result : results) {
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).containsEntry("executed", true);
      }

      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("returns failure when rule throws an exception")
    void testExecuteRule_RuleThrowsException_ReturnsFailure() {
      KieBase mockKieBase = mock(KieBase.class);
      when(mockKieBase.newKieSession()).thenThrow(new RuntimeException("Session creation failed"));

      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(mockKieBase, "error.rule", inputData);

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).contains("Session creation failed");

      executor.shutdown();
    }

    @Test
    @DisplayName("handles null input data gracefully")
    void testExecuteRule_InvalidInputData_HandlesGracefully() {
      KieBase mockKieBase = mock(KieBase.class);
      KieSession mockSession = mock(KieSession.class);
      when(mockKieBase.newKieSession()).thenReturn(mockSession);
      when(mockSession.fireAllRules(anyInt())).thenReturn(0);

      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      // Passing null data -- the executor should either handle it or report an error
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(mockKieBase, "null.data", null);

      // The result should not be an unhandled exception -- it should either succeed or fail
      assertThat(result).isNotNull();
      verify(mockSession).close();

      executor.shutdown();
    }
  }
}
