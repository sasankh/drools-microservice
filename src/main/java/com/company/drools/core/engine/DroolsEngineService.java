package com.company.drools.core.engine;

import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
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

  // Long-lived KieContainer. Updated in place via KieContainer.updateToVersion(ReleaseId) — no
  // explicit dispose() / two-container swap. See ADR-003 (2026-05-10 supersession note).
  private final KieContainer kieContainer;

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
    this.kieContainer = kieContainer;
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
      // Atomic lookup (avoids TOCTOU race between containsKey and get)
      Rule rule = loadedRules.get(ruleId);
      RuleMetadata metadata = ruleMetadata.get(ruleId);

      if (rule == null || metadata == null) {
        log.warn("Rule not found: {}", ruleId);
        meterRegistry
            .counter("drools.rule.execution.error", "rule_id", "unknown", "error", "rule_not_found")
            .increment();
        sample.stop(
            Timer.builder("drools.rule.execution.time")
                .tag("rule_id", "unknown")
                .tag("status", "error")
                .register(meterRegistry));
        return RuleExecutor.ExecutionResult.failure("Rule not found: " + ruleId);
      }

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
              kieContainer,
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

    // Mark all rules as loading (ConcurrentHashMap — no lock needed)
    for (Rule rule : rules) {
      ruleMetadata.put(rule.getRuleId(), RuleMetadata.createNew());
    }

    // Compile rules OUTSIDE the write lock so reads are not blocked. The compiler registers a
    // new versioned KieModule in the KieRepository; we then call kieContainer.updateToVersion(...)
    // to swap the running KieBase to that module.
    RuleCompiler.CompilationResult compilationResult = ruleCompiler.compileRules(rules);

    if (!compilationResult.isSuccess()) {
      log.error("Failed to compile rules: {}", compilationResult.getErrorMessage());
      for (Rule rule : rules) {
        RuleMetadata current = ruleMetadata.get(rule.getRuleId());
        if (current != null) {
          ruleMetadata.put(
              rule.getRuleId(), current.withError(compilationResult.getErrorMessage()));
        }
      }
      return false;
    }

    rulesLock.writeLock().lock();
    try {
      Results updateResults = kieContainer.updateToVersion(compilationResult.getReleaseId());
      if (updateResults.hasMessages(Message.Level.ERROR)) {
        log.error(
            "Failed to apply rule update: {}",
            updateResults.getMessages(Message.Level.ERROR));
        // KieBase remains at the previous version — Drools guarantees no partial swap on error
        for (Rule rule : rules) {
          RuleMetadata current = ruleMetadata.get(rule.getRuleId());
          if (current != null) {
            ruleMetadata.put(
                rule.getRuleId(),
                current.withError(updateResults.getMessages(Message.Level.ERROR).toString()));
          }
        }
        return false;
      }

      // The new release is authoritative — drop rules from the previous release that are not
      // in this set, then repopulate. (The previous code accumulated rule IDs across loads,
      // which could leave stale loadedRules / ruleMetadata entries for removed rules.)
      Set<String> newRuleIds = new HashSet<>();
      for (Rule rule : rules) {
        newRuleIds.add(rule.getRuleId());
      }
      loadedRules.keySet().retainAll(newRuleIds);
      ruleMetadata.keySet().retainAll(newRuleIds);

      for (Rule rule : rules) {
        loadedRules.put(rule.getRuleId(), rule);
        RuleMetadata current = ruleMetadata.get(rule.getRuleId());
        if (current == null) {
          current = RuleMetadata.createNew();
        }
        ruleMetadata.put(rule.getRuleId(), current.withStatus(RuleMetadata.RuleStatus.ACTIVE));
      }

      log.info(
          "Successfully loaded {} rules at release {}",
          rules.size(),
          compilationResult.getReleaseId().getVersion());
      return true;

    } finally {
      rulesLock.writeLock().unlock();
    }
  }

  /**
   * Replace (or add) a single rule, preserving all other currently-loaded rules. Reads the current
   * loaded rule set, swaps in the new rule, and re-runs the standard {@link #loadRules} path so
   * the resulting KieBase contains both the new rule and all the unchanged ones. Fixes the bug
   * where calling loadRules with a single-rule list discarded all other rules.
   *
   * <p>Holds the write lock for the full snapshot+compile+update sequence so concurrent merges
   * cannot lose each other's updates. The lock is reentrant, so the inner {@link #loadRules}
   * call re-acquires it without deadlock. Rule-execution reads block until the merge completes.
   */
  public boolean loadOrReplaceRule(Rule rule) {
    rulesLock.writeLock().lock();
    try {
      List<Rule> combined = new ArrayList<>(loadedRules.values());
      combined.removeIf(r -> r.getRuleId().equals(rule.getRuleId()));
      combined.add(rule);
      return loadRules(combined);
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
