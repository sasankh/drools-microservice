package com.company.drools.core.engine;

import com.company.drools.api.exception.RuleLimitExceededException;
import com.company.drools.api.exception.ServiceUnavailableException;
import com.company.drools.api.exception.TimeoutException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RuleExecutor {

  private static final Logger log = LoggerFactory.getLogger(RuleExecutor.class);

  private static final long DEFAULT_TIMEOUT_SECONDS = 30;
  private static final int DEFAULT_MAX_RULE_FIRINGS = 10000;

  /** Worker states for stuck-worker accounting. */
  private static final int RUNNING = 0;

  private static final int DONE = 1;
  private static final int ABANDONED = 2;

  private final Executor ruleExecutionExecutor;
  private final int maxRuleFirings;

  /** Workers still running after their request timed out (a rule body that never yields). */
  private final AtomicInteger stuckWorkers = new AtomicInteger();

  @Autowired
  public RuleExecutor(
      @Qualifier("ruleExecutionExecutor") Executor ruleExecutionExecutor,
      @Value("${drools.execution.max-rule-firings:10000}") int maxRuleFirings,
      MeterRegistry meterRegistry) {
    this(ruleExecutionExecutor, maxRuleFirings);
    Gauge.builder("drools.rule.execution.stuck", stuckWorkers, AtomicInteger::get)
        .description(
            "Rule-execution workers still running after their request timed out (a rule body that"
                + " never yields holds its thread until restart)")
        .register(meterRegistry);
  }

  public RuleExecutor(Executor ruleExecutionExecutor) {
    this(ruleExecutionExecutor, DEFAULT_MAX_RULE_FIRINGS);
  }

  public RuleExecutor(Executor ruleExecutionExecutor, int maxRuleFirings) {
    if (maxRuleFirings < 1 || maxRuleFirings == Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "drools.execution.max-rule-firings must be between 1 and " + (Integer.MAX_VALUE - 1));
    }
    this.ruleExecutionExecutor = ruleExecutionExecutor;
    this.maxRuleFirings = maxRuleFirings;
    log.info("RuleExecutor initialized with custom thread pool, maxRuleFirings={}", maxRuleFirings);
  }

  public ExecutionResult executeRule(
      KieBase kieBase, String ruleId, Map<String, Object> inputData) {
    return executeRule(kieBase, ruleId, inputData, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Execute {@code inputData} against ONE rule ID's own compiled base (ADR-025): a fresh session on
   * {@code kieBase}, which contains only that rule ID's DRL rules, so unrelated rules cannot fire.
   *
   * @throws RuleLimitExceededException if the rules would fire more than {@code
   *     drools.execution.max-rule-firings} times (usually a rule loop); no partial result
   * @throws TimeoutException if execution exceeds {@code timeoutSeconds}
   * @throws ServiceUnavailableException if the execution pool is saturated
   */
  public ExecutionResult executeRule(
      KieBase kieBase, String ruleId, Map<String, Object> inputData, long timeoutSeconds) {
    log.debug("Executing rule {} with timeout {}s", ruleId, timeoutSeconds);

    long startTime = System.currentTimeMillis();
    // Shared handle to the running session so the timeout path can halt() fireAllRules.
    AtomicReference<KieSession> sessionRef = new AtomicReference<>();
    AtomicInteger state = new AtomicInteger(RUNNING);
    // A FutureTask (not CompletableFuture) so cancel(true) really interrupts the worker thread:
    // CompletableFuture.cancel ignores mayInterruptIfRunning.
    FutureTask<Map<String, Object>> task =
        new FutureTask<>(
            () -> {
              try {
                return executeRuleInternal(kieBase, ruleId, inputData, sessionRef);
              } finally {
                if (state.getAndSet(DONE) == ABANDONED) {
                  stuckWorkers.decrementAndGet();
                  log.warn("Worker for timed-out rule {} has finally finished", ruleId);
                }
              }
            });

    try {
      ruleExecutionExecutor.execute(task);
      Map<String, Object> result = task.get(timeoutSeconds, TimeUnit.SECONDS);
      long executionTime = System.currentTimeMillis() - startTime;
      log.debug("Rule {} executed successfully in {}ms", ruleId, executionTime);
      return ExecutionResult.success(result, executionTime);

    } catch (RejectedExecutionException e) {
      // Pool saturated (queue full + all threads busy). Shed load with 503 instead of running the
      // rule body on the Tomcat request thread (which would silently bypass the timeout).
      log.warn("Rule execution pool saturated, rejecting rule {}", ruleId);
      throw new ServiceUnavailableException(
          "Rule execution capacity exceeded; please retry shortly", e);

    } catch (java.util.concurrent.TimeoutException e) {
      // Stop the agenda at the next rule boundary and interrupt the worker. A rule body that
      // never yields (e.g. while(true){}) ignores both and keeps its thread: counted as stuck.
      haltQuietly(sessionRef);
      task.cancel(true);
      awaitBriefly(state);
      if (state.compareAndSet(RUNNING, ABANDONED)) {
        int stuck = stuckWorkers.incrementAndGet();
        log.error(
            "Rule {} execution timed out after {}s; worker did not stop ({} stuck)",
            ruleId,
            timeoutSeconds,
            stuck);
      } else {
        log.error("Rule {} execution timed out after {}s", ruleId, timeoutSeconds);
      }
      throw new TimeoutException("Rule execution: " + ruleId, timeoutSeconds, e);

    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      haltQuietly(sessionRef);
      task.cancel(true);
      log.warn("Rule {} execution interrupted", ruleId);
      return ExecutionResult.failure("Rule execution interrupted: " + ruleId);

    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuleLimitExceededException limit) {
        throw limit;
      }
      long executionTime = System.currentTimeMillis() - startTime;
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      log.error("Rule {} execution failed after {}ms", ruleId, executionTime, cause);
      return ExecutionResult.failure("Rule execution failed: " + cause.getMessage());
    }
  }

  /**
   * Give an interrupted worker a moment (up to ~200 ms) to finish, so only workers that really
   * ignore the interrupt are counted as stuck.
   */
  private static void awaitBriefly(AtomicInteger state) {
    for (int i = 0; i < 20 && state.get() == RUNNING; i++) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * Halt the running {@link KieSession} if one is set. {@code halt()} is designed to be called from
   * another thread and stops {@code fireAllRules} at the next rule boundary. Swallows any error —
   * the session may already have completed and closed by the time we get here.
   *
   * <p>Limitation: this stops the agenda between rule firings, and the interrupt only stops code
   * that checks for it (sleep, wait, blocking IO). A consequence that never yields (e.g. {@code
   * then while(true){} end}) keeps its thread until restart; the request still returns 408 and the
   * {@code drools.rule.execution.stuck} gauge counts it. Only process isolation can kill it (see
   * SECURITY.md, threat model).
   */
  private static void haltQuietly(AtomicReference<KieSession> sessionRef) {
    KieSession session = sessionRef.get();
    if (session != null) {
      try {
        session.halt();
      } catch (RuntimeException _) {
        // Session already disposed/closed — nothing to halt.
      }
    }
  }

  private Map<String, Object> executeRuleInternal(
      KieBase kieBase,
      String ruleId,
      Map<String, Object> inputData,
      AtomicReference<KieSession> sessionRef) {
    try (KieSession kieSession = kieBase.newKieSession()) {
      sessionRef.set(kieSession);
      kieSession.insert(inputData);
      // Allow one firing beyond the cap: reaching cap + 1 proves more activations were pending
      // than allowed, so the limit is detected exactly (no false positive at exactly the cap).
      int rulesFired = kieSession.fireAllRules(maxRuleFirings + 1);
      if (rulesFired > maxRuleFirings) {
        log.warn(
            "Rule {} exceeded the firing limit of {} (likely a rule loop)", ruleId, maxRuleFirings);
        throw new RuleLimitExceededException(ruleId, maxRuleFirings);
      }
      log.debug("Fired {} rules for rule ID {} (limit: {})", rulesFired, ruleId, maxRuleFirings);
      return inputData;
    } finally {
      // Clear the handle so a late timeout can't halt a session that's already closed.
      sessionRef.set(null);
    }
  }

  /** Number of workers still running after their request timed out. */
  public int getStuckWorkers() {
    return stuckWorkers.get();
  }

  public int getMaxRuleFirings() {
    return maxRuleFirings;
  }

  public static class ExecutionResult {
    private final boolean success;
    private final Map<String, Object> result;
    private final String errorMessage;
    private final long executionTimeMs;

    private ExecutionResult(
        boolean success, Map<String, Object> result, String errorMessage, long executionTimeMs) {
      this.success = success;
      this.result = result;
      this.errorMessage = errorMessage;
      this.executionTimeMs = executionTimeMs;
    }

    public static ExecutionResult success(Map<String, Object> result, long executionTimeMs) {
      return new ExecutionResult(true, result, null, executionTimeMs);
    }

    public static ExecutionResult failure(String errorMessage) {
      return new ExecutionResult(false, null, errorMessage, 0);
    }

    public boolean isSuccess() {
      return success;
    }

    public Map<String, Object> getResult() {
      return result;
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    public long getExecutionTimeMs() {
      return executionTimeMs;
    }
  }
}
