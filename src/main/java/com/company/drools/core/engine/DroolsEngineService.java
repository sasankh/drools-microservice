package com.company.drools.core.engine;

import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.kie.api.runtime.KieContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DroolsEngineService {

  private static final Logger log = LoggerFactory.getLogger(DroolsEngineService.class);

  private final RuleCompiler ruleCompiler;
  private final RuleExecutor ruleExecutor;
  private final RuleStorage ruleStorage;
  private final TimeoutConfig timeoutConfig;

  // Metrics
  private final MeterRegistry meterRegistry;

  // Thread-safe storage for rules and their metadata
  private final Map<String, Rule> loadedRules = new ConcurrentHashMap<>();
  private final Map<String, RuleMetadata> ruleMetadata = new ConcurrentHashMap<>();

  // Current KieContainer with compiled rules
  private volatile KieContainer currentKieContainer;

  // Lock for managing rule updates
  private final ReentrantReadWriteLock rulesLock = new ReentrantReadWriteLock();

  public DroolsEngineService(
      RuleCompiler ruleCompiler,
      RuleExecutor ruleExecutor,
      KieContainer kieContainer,
      RuleStorage ruleStorage,
      TimeoutConfig timeoutConfig,
      MeterRegistry meterRegistry) {
    this.ruleCompiler = ruleCompiler;
    this.ruleExecutor = ruleExecutor;
    this.currentKieContainer = kieContainer;
    this.ruleStorage = ruleStorage;
    this.timeoutConfig = timeoutConfig;
    this.meterRegistry = meterRegistry;
    log.info(
        "DroolsEngineService initialized with rule storage: {}",
        ruleStorage.getClass().getSimpleName());
  }

  public RuleExecutor.ExecutionResult executeRule(String ruleId, Map<String, Object> inputData) {
    log.debug("Executing rule: {}", ruleId);

    // Start timing the execution
    Timer.Sample sample = Timer.start();

    rulesLock.readLock().lock();
    try {
      // Check if rule exists
      if (!loadedRules.containsKey(ruleId)) {
        log.warn("Rule not found: {}", ruleId);
        meterRegistry
            .counter("drools.rule.execution.error", "rule_id", ruleId, "error", "rule_not_found")
            .increment();
        sample.stop(
            Timer.builder("drools.rule.execution.time")
                .tag("rule_id", ruleId)
                .tag("status", "error")
                .register(meterRegistry));
        return RuleExecutor.ExecutionResult.failure("Rule not found: " + ruleId);
      }

      Rule rule = loadedRules.get(ruleId);
      RuleMetadata metadata = ruleMetadata.get(ruleId);

      // Check if rule is active
      if (metadata.getStatus() != RuleMetadata.RuleStatus.ACTIVE) {
        log.warn("Rule is not active: {} (status: {})", ruleId, metadata.getStatus());
        meterRegistry
            .counter("drools.rule.execution.error", "rule_id", ruleId, "error", "rule_not_active")
            .increment();
        sample.stop(
            Timer.builder("drools.rule.execution.time")
                .tag("rule_id", ruleId)
                .tag("status", "error")
                .register(meterRegistry));
        return RuleExecutor.ExecutionResult.failure("Rule is not active: " + ruleId);
      }

      // Execute the rule with configured timeout
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(
              currentKieContainer,
              ruleId,
              inputData,
              timeoutConfig.getRuleExecutionTimeoutSeconds());

      // Update execution statistics and metrics
      if (result.isSuccess()) {
        RuleMetadata updatedMetadata = metadata.withExecution(result.getExecutionTimeMs());
        ruleMetadata.put(ruleId, updatedMetadata);

        // Record successful execution metrics
        meterRegistry.counter("drools.rule.execution.success", "rule_id", ruleId).increment();
        sample.stop(
            Timer.builder("drools.rule.execution.time")
                .tag("rule_id", ruleId)
                .tag("status", "success")
                .register(meterRegistry));
      } else {
        // Record failed execution metrics
        meterRegistry
            .counter("drools.rule.execution.error", "rule_id", ruleId, "error", "execution_failed")
            .increment();
        sample.stop(
            Timer.builder("drools.rule.execution.time")
                .tag("rule_id", ruleId)
                .tag("status", "error")
                .register(meterRegistry));
      }

      return result;

    } finally {
      rulesLock.readLock().unlock();
    }
  }

  public boolean loadRules(List<Rule> rules) {
    log.info("Loading {} rules", rules.size());

    rulesLock.writeLock().lock();
    try {
      // Mark all rules as loading
      for (Rule rule : rules) {
        RuleMetadata metadata = RuleMetadata.createNew();
        ruleMetadata.put(rule.getRuleId(), metadata);
      }

      // Compile rules
      RuleCompiler.CompilationResult compilationResult = ruleCompiler.compileRules(rules);

      if (!compilationResult.isSuccess()) {
        log.error("Failed to compile rules: {}", compilationResult.getErrorMessage());

        // Mark all rules as error
        for (Rule rule : rules) {
          RuleMetadata errorMetadata =
              ruleMetadata.get(rule.getRuleId()).withError(compilationResult.getErrorMessage());
          ruleMetadata.put(rule.getRuleId(), errorMetadata);
        }

        return false;
      }

      // Update the container and mark rules as active
      currentKieContainer = compilationResult.getKieContainer();

      for (Rule rule : rules) {
        loadedRules.put(rule.getRuleId(), rule);
        RuleMetadata activeMetadata =
            ruleMetadata.get(rule.getRuleId()).withStatus(RuleMetadata.RuleStatus.ACTIVE);
        ruleMetadata.put(rule.getRuleId(), activeMetadata);
      }

      log.info("Successfully loaded {} rules", rules.size());
      return true;

    } finally {
      rulesLock.writeLock().unlock();
    }
  }

  public Map<String, RuleMetadata> getAllRuleMetadata() {
    return Map.copyOf(ruleMetadata);
  }

  public RuleMetadata getRuleMetadata(String ruleId) {
    return ruleMetadata.get(ruleId);
  }

  public boolean hasRule(String ruleId) {
    return loadedRules.containsKey(ruleId);
  }

  public int getLoadedRulesCount() {
    return loadedRules.size();
  }

  public long getActiveRulesCount() {
    return ruleMetadata.values().stream()
        .filter(metadata -> metadata.getStatus() == RuleMetadata.RuleStatus.ACTIVE)
        .count();
  }
}
