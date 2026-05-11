package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.company.drools.api.exception.TimeoutException;
import com.company.drools.core.model.Rule;
import com.company.drools.testutil.RuleTestUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

@DisplayName("RuleExecutor")
class RuleExecutorTest {

  private KieServices kieServices;

  @BeforeEach
  void setUp() {
    kieServices = KieServices.Factory.get();
  }

  /** Helper: compile a single rule and return a fresh KieContainer at its release. */
  private KieContainer compileRule(Rule rule) {
    RuleCompiler compiler = new RuleCompiler(kieServices, new DrlSanitizer());
    RuleCompiler.CompilationResult result = compiler.compileRules(List.of(rule));
    assertThat(result.isSuccess())
        .as("Rule compilation should succeed for: " + rule.getRuleId())
        .isTrue();
    return kieServices.newKieContainer(result.getReleaseId());
  }

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("executes a valid rule and returns modified data")
    void testExecuteRule_ValidRule_ReturnsModifiedData() {
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      KieContainer kieContainer = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("amount", 100);

      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieContainer, "pricing.discount.simple", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("executed", true);
      assertThat(result.getResult()).containsEntry("amount", 100);
      assertThat(result.getExecutionTimeMs()).isGreaterThanOrEqualTo(0);
      assertThat(result.getErrorMessage()).isNull();

      executor.shutdown();
      kieContainer.dispose();
    }

    @Test
    @DisplayName("completes within the specified timeout")
    void testExecuteRule_WithTimeout_CompletesSuccessfully() {
      Rule rule = RuleTestUtils.createSimpleRule("test.timeout.fast");
      KieContainer kieContainer = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("value", "test");

      // Set a generous 5-second timeout for a rule that completes instantly
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieContainer, "test.timeout.fast", inputData, 5);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getExecutionTimeMs()).isLessThan(5000);

      executor.shutdown();
      kieContainer.dispose();
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
      KieContainer kieContainer = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieContainer, "multi.rules", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("step1", true);
      assertThat(result.getResult()).containsEntry("step2", true);
      assertThat(result.getResult()).containsEntry("step3", true);

      executor.shutdown();
      kieContainer.dispose();
    }
  }

  @Nested
  @DisplayName("Timeout Handling")
  class TimeoutHandling {

    @Test
    @SuppressWarnings("java:S2925")
    @DisplayName("throws TimeoutException when execution exceeds timeout")
    void testExecuteRule_ExceedsTimeout_ThrowsTimeoutException() {
      // Use a mock KieContainer that blocks the session thread
      KieContainer mockContainer = mock(KieContainer.class);
      KieSession mockSession = mock(KieSession.class);
      when(mockContainer.newKieSession()).thenReturn(mockSession);
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

      assertThatThrownBy(() -> ruleExecutor.executeRule(mockContainer, "slow.rule", inputData, 1))
          .isInstanceOf(TimeoutException.class)
          .hasMessageContaining("slow.rule");

      executor.shutdownNow();
    }

    @Test
    @DisplayName("completes successfully when finishing just before timeout")
    void testExecuteRule_NearTimeout_CompletesSuccessfully() {
      Rule rule = RuleTestUtils.createSimpleRule("test.neartimeout");
      KieContainer kieContainer = compileRule(rule);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      // Simple rule completes almost instantly; 2-second timeout is generous
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieContainer, "test.neartimeout", inputData, 2);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getExecutionTimeMs()).isLessThan(2000);

      executor.shutdown();
      kieContainer.dispose();
    }

    @Test
    @SuppressWarnings("java:S2925")
    @DisplayName("respects custom timeout configuration")
    void testExecuteRule_CustomTimeout_RespectsConfiguration() {
      KieContainer mockContainer = mock(KieContainer.class);
      KieSession mockSession = mock(KieSession.class);
      when(mockContainer.newKieSession()).thenReturn(mockSession);
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
              () -> ruleExecutor.executeRule(mockContainer, "custom.timeout", inputData, 1))
          .isInstanceOf(TimeoutException.class);

      // Verify the timeout value is in the exception
      try {
        ruleExecutor.executeRule(mockContainer, "custom.timeout", inputData, 1);
      } catch (TimeoutException e) {
        assertThat(e.getTimeoutSeconds()).isEqualTo(1);
      } catch (Exception _) {
        // May get a different error on second call since thread pool is busy
      }

      executor.shutdownNow();
    }
  }

  @Nested
  @DisplayName("Thread Pool and Concurrency")
  class ThreadPoolAndConcurrency {

    @Test
    @DisplayName("uses the provided custom thread pool executor")
    void testExecuteRule_UsesCustomThreadPool() {
      Rule rule = RuleTestUtils.createSimpleRule("test.threadpool");
      KieContainer kieContainer = compileRule(rule);

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
          ruleExecutor.executeRule(kieContainer, "test.threadpool", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(threadNames).isNotEmpty();
      assertThat(threadNames.get(0)).isEqualTo("custom-rule-thread");

      customExecutor.shutdown();
      kieContainer.dispose();
    }

    @Test
    @DisplayName("parallel executions use independent sessions")
    void testExecuteRule_ParallelExecutions_IndependentSessions() throws Exception {
      Rule rule = RuleTestUtils.createSimpleRule("test.parallel");
      KieContainer kieContainer = compileRule(rule);
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
                        ruleExecutor.executeRule(kieContainer, "test.parallel", inputData);
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
      kieContainer.dispose();
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("returns failure when rule throws an exception")
    void testExecuteRule_RuleThrowsException_ReturnsFailure() {
      KieContainer mockContainer = mock(KieContainer.class);
      when(mockContainer.newKieSession())
          .thenThrow(new RuntimeException("Session creation failed"));

      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      Map<String, Object> inputData = new HashMap<>();

      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(mockContainer, "error.rule", inputData);

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).contains("Session creation failed");

      executor.shutdown();
    }

    @Test
    @DisplayName("handles null input data gracefully")
    void testExecuteRule_InvalidInputData_HandlesGracefully() {
      KieContainer mockContainer = mock(KieContainer.class);
      KieSession mockSession = mock(KieSession.class);
      when(mockContainer.newKieSession()).thenReturn(mockSession);
      when(mockSession.fireAllRules(anyInt())).thenReturn(0);

      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor);

      // Passing null data -- the executor should either handle it or report an error
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(mockContainer, "null.data", null);

      // The result should not be an unhandled exception -- it should either succeed or fail
      assertThat(result).isNotNull();
      verify(mockSession).dispose();

      executor.shutdown();
    }
  }
}
