package com.company.drools.core.engine;

import com.company.drools.api.exception.TimeoutException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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

    try {
      // Execute rule in custom thread pool to handle timeout and provide better concurrency control
      future =
          CompletableFuture.supplyAsync(
              () -> executeRuleInternal(kieContainer, ruleId, inputData), ruleExecutionExecutor);

      Map<String, Object> result = future.get(timeoutSeconds, TimeUnit.SECONDS);
      long executionTime = System.currentTimeMillis() - startTime;

      log.debug("Rule {} executed successfully in {}ms", ruleId, executionTime);
      return ExecutionResult.success(result, executionTime);

    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      log.error("Rule {} execution timed out after {}s", ruleId, timeoutSeconds);
      throw new TimeoutException("Rule execution: " + ruleId, timeoutSeconds, e);

    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      log.warn("Rule {} execution interrupted", ruleId);
      return ExecutionResult.failure("Rule execution interrupted: " + ruleId);

    } catch (Exception e) {
      long executionTime = System.currentTimeMillis() - startTime;
      log.error("Rule {} execution failed after {}ms", ruleId, executionTime, e);
      return ExecutionResult.failure("Rule execution failed: " + e.getMessage());
    }
  }

  private Map<String, Object> executeRuleInternal(
      KieContainer kieContainer, String ruleId, Map<String, Object> inputData) {
    try (KieSession kieSession = kieContainer.newKieSession()) {
      kieSession.insert(inputData);
      int rulesFired = kieSession.fireAllRules(maxRuleFirings);
      log.debug("Fired {} rules for rule ID {} (limit: {})", rulesFired, ruleId, maxRuleFirings);
      return inputData;
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
