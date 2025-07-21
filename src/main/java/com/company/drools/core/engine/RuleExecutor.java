package com.company.drools.core.engine;

import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RuleExecutor {

  private static final Logger log = LoggerFactory.getLogger(RuleExecutor.class);
  
  private static final long DEFAULT_TIMEOUT_SECONDS = 30;

  public ExecutionResult executeRule(KieContainer kieContainer, String ruleId, Map<String, Object> inputData) {
    return executeRule(kieContainer, ruleId, inputData, DEFAULT_TIMEOUT_SECONDS);
  }

  public ExecutionResult executeRule(KieContainer kieContainer, String ruleId, 
                                   Map<String, Object> inputData, long timeoutSeconds) {
    log.debug("Executing rule {} with timeout {}s", ruleId, timeoutSeconds);
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Execute rule in a separate thread to handle timeout
      CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
        return executeRuleInternal(kieContainer, ruleId, inputData);
      });
      
      Map<String, Object> result = future.get(timeoutSeconds, TimeUnit.SECONDS);
      long executionTime = System.currentTimeMillis() - startTime;
      
      log.debug("Rule {} executed successfully in {}ms", ruleId, executionTime);
      return ExecutionResult.success(result, executionTime);
      
    } catch (TimeoutException e) {
      log.error("Rule {} execution timed out after {}s", ruleId, timeoutSeconds);
      return ExecutionResult.failure("Rule execution timed out after " + timeoutSeconds + " seconds");
      
    } catch (Exception e) {
      long executionTime = System.currentTimeMillis() - startTime;
      log.error("Rule {} execution failed after {}ms", ruleId, executionTime, e);
      return ExecutionResult.failure("Rule execution failed: " + e.getMessage());
    }
  }

  private Map<String, Object> executeRuleInternal(KieContainer kieContainer, String ruleId, 
                                                 Map<String, Object> inputData) {
    // Create a new stateless session for thread safety
    KieSession kieSession = kieContainer.newKieSession();
    
    try {
      // Insert input data as facts
      kieSession.insert(inputData);
      
      // Fire all rules
      int rulesFired = kieSession.fireAllRules();
      log.debug("Fired {} rules for rule ID {}", rulesFired, ruleId);
      
      // Extract results from the modified input data
      // The rules should modify the input map to add results
      return inputData;
      
    } finally {
      // Always dispose of the session to prevent memory leaks
      kieSession.dispose();
    }
  }

  public static class ExecutionResult {
    private final boolean success;
    private final Map<String, Object> result;
    private final String errorMessage;
    private final long executionTimeMs;

    private ExecutionResult(boolean success, Map<String, Object> result, 
                           String errorMessage, long executionTimeMs) {
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