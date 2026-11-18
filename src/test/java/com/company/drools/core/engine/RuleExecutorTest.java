package com.company.drools.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.company.drools.api.exception.RuleLimitExceededException;
import com.company.drools.api.exception.ServiceUnavailableException;
import com.company.drools.api.exception.TimeoutException;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.testutil.RuleTestUtils;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  /** Compile one DRL body into its own KieBase under {@code ruleId}. */
  private KieBase compileDrl(String ruleId, String drl) {
    return compileRule(new Rule(ruleId, drl, RuleMetadata.createNew()));
  }

  /** Counter loop: increments "n" and re-fires itself via update(); {@code guard} bounds it. */
  private static String counterLoop(String guard) {
    return """
        package com.company.rules.loop

        import java.util.Map

        rule "loop"
        when
            $d : Map(this["n"] != null%s)
        then
            $d.put("n", ((Number) $d.get("n")).intValue() + 1);
            update($d);
        end
        """
        .formatted(guard);
  }

  /** A rule that runs a {@link Runnable} supplied in the input map under key "hook". */
  private static final String HOOK_DRL =
      """
      package com.company.rules.hook

      import java.util.Map

      rule "hook"
      when
          $d : Map(this["hook"] != null)
      then
          ((Runnable) $d.get("hook")).run();
          $d.put("done", true);
      end
      """;

  private static void awaitStuck(RuleExecutor executor, int expected) {
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(executor.getStuckWorkers()).isEqualTo(expected));
  }

  @Nested
  @DisplayName("Rule Firing Limit")
  class RuleFiringLimit {

    @Test
    @DisplayName("the loop rule really re-fires (sanity: bounded loop reaches its bound)")
    void loopRuleReFires() {
      KieBase kieBase =
          compileDrl("loop.bounded", counterLoop(", ((Number) this[\"n\"]).intValue() < 50"));
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor, 1000);

      Map<String, Object> input = new HashMap<>(Map.of("n", 0));
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(kieBase, "loop.bounded", input);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("n", 50);
      executor.shutdown();
    }

    @Test
    @DisplayName("an unbounded loop exceeds the cap → RuleLimitExceededException, no result")
    void loopExceedsCap() {
      KieBase kieBase = compileDrl("loop.forever", counterLoop(""));
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor, 5);
      assertThat(ruleExecutor.getMaxRuleFirings()).isEqualTo(5);

      Map<String, Object> input = new HashMap<>(Map.of("n", 0));
      assertThatThrownBy(() -> ruleExecutor.executeRule(kieBase, "loop.forever", input, 10))
          .isInstanceOfSatisfying(
              RuleLimitExceededException.class,
              e -> {
                assertThat(e.getLimit()).isEqualTo(5);
                assertThat(e.getRuleId()).isEqualTo("loop.forever");
              });
      // The cap stopped the agenda just past the limit (cap + 1 firings), not at timeout.
      assertThat(input).containsEntry("n", 6);
      executor.shutdown();
    }

    @Test
    @DisplayName("firing exactly the cap is not a false positive")
    void exactlyCapSucceeds() {
      KieBase kieBase =
          compileDrl("loop.five", counterLoop(", ((Number) this[\"n\"]).intValue() < 5"));
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor, 5);

      Map<String, Object> input = new HashMap<>(Map.of("n", 0));
      RuleExecutor.ExecutionResult result = ruleExecutor.executeRule(kieBase, "loop.five", input);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("n", 5);
      executor.shutdown();
    }

    @Test
    @DisplayName("default constructor uses the 10000 cap")
    void defaultCap() {
      assertThat(new RuleExecutor(Runnable::run).getMaxRuleFirings()).isEqualTo(10000);
    }

    @ParameterizedTest(name = "rejects cap {0}")
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    @DisplayName("constructor rejects a cap outside 1..Integer.MAX_VALUE-1")
    void rejectsInvalidCap(int cap) {
      Executor direct = Runnable::run;
      assertThatThrownBy(() -> new RuleExecutor(direct, cap))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("max-rule-firings");
    }

    @Test
    @DisplayName("accepts the extreme valid caps 1 and Integer.MAX_VALUE - 1")
    void acceptsBoundaryCaps() {
      assertThat(new RuleExecutor(Runnable::run, 1).getMaxRuleFirings()).isEqualTo(1);
      assertThat(new RuleExecutor(Runnable::run, Integer.MAX_VALUE - 1).getMaxRuleFirings())
          .isEqualTo(Integer.MAX_VALUE - 1);
    }
  }

  @Nested
  @DisplayName("Timeout Interruption and Stuck Workers")
  class TimeoutInterruption {

    @Test
    @SuppressWarnings("java:S2925")
    @DisplayName("timeout interrupts interruptible rule work; stuck count returns to 0")
    void timeoutInterruptsWorker() throws Exception {
      KieBase kieBase = compileDrl("hook.sleep", HOOK_DRL);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor, 100);
      CountDownLatch started = new CountDownLatch(1);
      CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
      Runnable hook =
          () -> {
            started.countDown();
            try {
              Thread.sleep(30_000);
              interrupted.complete(false);
            } catch (InterruptedException _) {
              interrupted.complete(true);
              Thread.currentThread().interrupt();
            }
          };

      Map<String, Object> input = new HashMap<>(Map.of("hook", hook));
      long start = System.nanoTime();
      assertThatThrownBy(() -> ruleExecutor.executeRule(kieBase, "hook.sleep", input, 1))
          .isInstanceOf(TimeoutException.class);

      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(interrupted.get(5, TimeUnit.SECONDS)).as("hook saw InterruptedException").isTrue();
      assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(10);
      awaitStuck(ruleExecutor, 0);
      executor.shutdownNow();
    }

    @Test
    @SuppressWarnings("java:S2925")
    @DisplayName("work that ignores interrupts is counted as stuck until it finally ends")
    void nonInterruptibleWorkCountedAsStuck() throws Exception {
      KieBase kieBase = compileDrl("hook.spin", HOOK_DRL);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      RuleExecutor ruleExecutor = new RuleExecutor(executor, 100);
      AtomicBoolean release = new AtomicBoolean(false);
      CountDownLatch finished = new CountDownLatch(1);
      Runnable hook =
          () -> {
            while (!release.get()) {
              Thread.onSpinWait(); // ignores interrupts, like a CPU-bound rule body
            }
            finished.countDown();
          };

      Map<String, Object> input = new HashMap<>(Map.of("hook", hook));
      try {
        assertThatThrownBy(() -> ruleExecutor.executeRule(kieBase, "hook.spin", input, 1))
            .isInstanceOf(TimeoutException.class);

        // Still spinning well after the timeout fired → counted as stuck.
        Thread.sleep(300);
        assertThat(finished.getCount()).isEqualTo(1);
        assertThat(ruleExecutor.getStuckWorkers()).isEqualTo(1);
      } finally {
        release.set(true);
      }

      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
      awaitStuck(ruleExecutor, 0);
      executor.shutdownNow();
    }

    @Test
    @DisplayName("the Spring constructor registers the drools.rule.execution.stuck gauge")
    void registersStuckGauge() {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      RuleExecutor ruleExecutor = new RuleExecutor(Runnable::run, 7, registry);

      Gauge gauge = registry.find("drools.rule.execution.stuck").gauge();
      assertThat(gauge).isNotNull();
      assertThat(gauge.value()).isZero();
      assertThat(ruleExecutor.getMaxRuleFirings()).isEqualTo(7);
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
