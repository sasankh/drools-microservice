package com.company.drools.core.engine;

import com.company.drools.api.exception.ServiceUnavailableException;
import com.company.drools.api.exception.TimeoutException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RuleExecutor {

  private static final Logger log = LoggerFactory.getLogger(RuleExecutor.class);

  private static final long DEFAULT_TIMEOUT_SECONDS = 30;
  private static final int DEFAULT_MAX_RULE_FIRINGS = 10000;

  private final Executor ruleExecutionExecutor;
  private final int maxRuleFirings;

  @Autowired
  public RuleExecutor(@Qualifier("ruleExecutionExecutor") Executor ruleExecutionExecutor) {
    this(ruleExecutionExecutor, DEFAULT_MAX_RULE_FIRINGS);
  }

  public RuleExecutor(Executor ruleExecutionExecutor, int maxRuleFirings) {
    this.ruleExecutionExecutor = ruleExecutionExecutor;
    this.maxRuleFirings = maxRuleFirings;
    log.info("RuleExecutor initialized with custom thread pool, maxRuleFirings={}", maxRuleFirings);
  }

  public ExecutionResult executeRule(
      KieContainer kieContainer, String ruleId, Map<String, Object> inputData) {
    return executeRule(kieContainer, ruleId, inputData, DEFAULT_TIMEOUT_SECONDS);
  }

  public ExecutionResult executeRule(
      KieContainer kieContainer,
      String ruleId,
      Map<String, Object> inputData,
      long timeoutSeconds) {
    log.debug("Executing rule {} with timeout {}s", ruleId, timeoutSeconds);

    long startTime = System.currentTimeMillis();
    CompletableFuture<Map<String, Object>> future = null;
    // Shared handle to the running session so the timeout path can halt() fireAllRules
    // cooperatively.
    AtomicReference<KieSession> sessionRef = new AtomicReference<>();

    try {
      // Execute rule in custom thread pool to handle timeout and provide better concurrency control
      future =
          CompletableFuture.supplyAsync(
              () -> executeRuleInternal(kieContainer, ruleId, inputData, sessionRef),
              ruleExecutionExecutor);

      Map<String, Object> result = future.get(timeoutSeconds, TimeUnit.SECONDS);
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
      // Cooperatively stop the running rule firing so its worker thread is not leaked, then cancel.
      haltQuietly(sessionRef);
      future.cancel(true);
      log.error("Rule {} execution timed out after {}s", ruleId, timeoutSeconds);
      throw new TimeoutException("Rule execution: " + ruleId, timeoutSeconds, e);

    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      haltQuietly(sessionRef);
      future.cancel(true);
      log.warn("Rule {} execution interrupted", ruleId);
      return ExecutionResult.failure("Rule execution interrupted: " + ruleId);

    } catch (Exception e) {
      long executionTime = System.currentTimeMillis() - startTime;
      log.error("Rule {} execution failed after {}ms", ruleId, executionTime, e);
      return ExecutionResult.failure("Rule execution failed: " + e.getMessage());
    }
  }

  /**
   * Halt the running {@link KieSession} if one is set. {@code halt()} is designed to be called from
   * another thread and stops {@code fireAllRules} at the next rule boundary. Swallows any error —
   * the session may already have completed and closed by the time we get here.
   *
   * <p>Limitation: this stops the agenda between rule firings, so it CANNOT interrupt a single
   * consequence that never yields (e.g. {@code then while(true){} end}). Such a thread stays busy
   * until it is recycled; the request still returns 408 promptly. Fully sandboxing rule bodies is
   * tracked with the DRL-sandbox hardening (finding B1, deferred).
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
      KieContainer kieContainer,
      String ruleId,
      Map<String, Object> inputData,
      AtomicReference<KieSession> sessionRef) {
    try (KieSession kieSession = kieContainer.newKieSession()) {
      sessionRef.set(kieSession);
      kieSession.insert(inputData);
      int rulesFired = kieSession.fireAllRules(maxRuleFirings);
      log.debug("Fired {} rules for rule ID {} (limit: {})", rulesFired, ruleId, maxRuleFirings);
      return inputData;
    } finally {
      // Clear the handle so a late timeout can't halt a session that's already closed.
      sessionRef.set(null);
    }
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
