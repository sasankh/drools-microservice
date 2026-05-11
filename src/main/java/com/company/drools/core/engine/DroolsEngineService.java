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
import org.kie.api.builder.KieRepository;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DroolsEngineService {

  private static final Logger log = LoggerFactory.getLogger(DroolsEngineService.class);

  private static final String METRIC_RULE_EXECUTION_TIME = "drools.rule.execution.time";
  private static final String METRIC_RULE_EXECUTION_ERROR = "drools.rule.execution.error";
  private static final String TAG_RULE_ID = "rule_id";
  private static final String TAG_STATUS = "status";
  private static final String STATUS_ERROR = "error";

  private final RuleCompiler ruleCompiler;
  private final RuleExecutor ruleExecutor;
  private final TimeoutConfig timeoutConfig;
  private final KieRepository kieRepository;

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
      KieRepository kieRepository,
      RuleStorage ruleStorage,
      TimeoutConfig timeoutConfig,
      MeterRegistry meterRegistry) {
    this.ruleCompiler = ruleCompiler;
    this.ruleExecutor = ruleExecutor;
    this.kieContainer = kieContainer;
    this.kieRepository = kieRepository;
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
            .counter(
                METRIC_RULE_EXECUTION_ERROR, TAG_RULE_ID, "unknown", STATUS_ERROR, "rule_not_found")
            .increment();
        sample.stop(
            Timer.builder(METRIC_RULE_EXECUTION_TIME)
                .tag(TAG_RULE_ID, "unknown")
                .tag(TAG_STATUS, STATUS_ERROR)
                .register(meterRegistry));
        return RuleExecutor.ExecutionResult.failure("Rule not found: " + ruleId);
      }

      // Check if rule is active
      if (metadata.getStatus() != RuleMetadata.RuleStatus.ACTIVE) {
        log.warn("Rule is not active: {} (status: {})", ruleId, metadata.getStatus());
        meterRegistry
            .counter(
                METRIC_RULE_EXECUTION_ERROR, TAG_RULE_ID, ruleId, STATUS_ERROR, "rule_not_active")
            .increment();
        sample.stop(
            Timer.builder(METRIC_RULE_EXECUTION_TIME)
                .tag(TAG_RULE_ID, ruleId)
                .tag(TAG_STATUS, STATUS_ERROR)
                .register(meterRegistry));
        return RuleExecutor.ExecutionResult.failure("Rule is not active: " + ruleId);
      }

      // Execute the rule with configured timeout
      RuleExecutor.ExecutionResult result =
          ruleExecutor.executeRule(
              kieContainer, ruleId, inputData, timeoutConfig.getRuleExecutionTimeoutSeconds());

      // Update execution statistics and metrics
      if (result.isSuccess()) {
        RuleMetadata updatedMetadata = metadata.withExecution(result.getExecutionTimeMs());
        ruleMetadata.put(ruleId, updatedMetadata);

        // Record successful execution metrics
        meterRegistry.counter("drools.rule.execution.success", TAG_RULE_ID, ruleId).increment();
        sample.stop(
            Timer.builder(METRIC_RULE_EXECUTION_TIME)
                .tag(TAG_RULE_ID, ruleId)
                .tag(TAG_STATUS, "success")
                .register(meterRegistry));
      } else {
        // Record failed execution metrics
        meterRegistry
            .counter(
                METRIC_RULE_EXECUTION_ERROR, TAG_RULE_ID, ruleId, STATUS_ERROR, "execution_failed")
            .increment();
        sample.stop(
            Timer.builder(METRIC_RULE_EXECUTION_TIME)
                .tag(TAG_RULE_ID, ruleId)
                .tag(TAG_STATUS, STATUS_ERROR)
                .register(meterRegistry));
      }

      return result;

    } finally {
      rulesLock.readLock().unlock();
    }
  }

  public boolean loadRules(List<Rule> rules) {
    log.info("Loading {} rules", rules.size());

    // Compile rules OUTSIDE the write lock so reads are not blocked. The compiler registers a
    // new versioned KieModule in the KieRepository; we then call kieContainer.updateToVersion(...)
    // to swap the running KieBase to that module.
    //
    // Important: we do NOT pre-mark rules as LOADING. An earlier version reset every rule's
    // metadata to LOADING upfront, which caused executeRule to return 400 "Rule is not active"
    // for the entire compile window — defeating the goal of non-blocking reads of the OLD
    // KieBase while a new one is being built. With 10-rule corpora the LOADING window was
    // sub-millisecond and never observable; at 1k+ rules it's tens of seconds and surfaces a
    // >1% error rate during refresh-under-load. Surfaced by the 2026-05-10 load test, Phase 5.
    // The existing metadata stays ACTIVE for previously-loaded rules during compile, and is
    // only updated under the write lock after updateToVersion succeeds, so the metadata-vs-
    // KieBase swap is observable atomically by readers.
    RuleCompiler.CompilationResult compilationResult = ruleCompiler.compileRules(rules);

    if (!compilationResult.isSuccess()) {
      log.error("Failed to compile rules: {}", compilationResult.getErrorMessage());
      // Compile failure: the previous KieBase + previous metadata remain in effect. Don't
      // mutate metadata (we'd flip ACTIVE rules to ERROR even though they're still serviceable
      // from the old KieBase). Caller observes the failure via the return value.
      return false;
    }

    ReleaseId newReleaseId = compilationResult.getReleaseId();
    ReleaseId oldReleaseId;
    rulesLock.writeLock().lock();
    try {
      oldReleaseId = kieContainer.getReleaseId();
      Results updateResults = kieContainer.updateToVersion(newReleaseId);
      if (updateResults.hasMessages(Message.Level.ERROR)) {
        log.error(
            "Failed to apply rule update: {}", updateResults.getMessages(Message.Level.ERROR));
        // KieBase remains at the previous version — Drools guarantees no partial swap on error.
        // Same reasoning as compile-failure path above: don't mutate ACTIVE metadata.
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
          "Successfully loaded {} rules at release {}", rules.size(), newReleaseId.getVersion());
    } finally {
      rulesLock.writeLock().unlock();
    }

    // Outside the write lock: evict the previous KieModule from the singleton KieRepository.
    // KieContainer.updateToVersion() does NOT auto-clean prior modules in Drools 10.2.0; without
    // explicit removal each refresh accumulates a KieModule + ProjectClassLoader + the compiled
    // rule bytecode in the repo (verified against the 10.2.0 source). The repository's internal
    // lock is independent of rulesLock — keep this outside the write lock to avoid lock inversion.
    if (oldReleaseId != null && !oldReleaseId.equals(newReleaseId)) {
      try {
        kieRepository.removeKieModule(oldReleaseId);
        log.debug("Evicted prior KieModule {} from KieRepository", oldReleaseId.getVersion());
      } catch (Exception e) {
        // Eviction failure is not fatal — the swap already succeeded. Log and continue.
        log.warn(
            "Failed to evict prior KieModule {} from KieRepository: {}",
            oldReleaseId.getVersion(),
            e.getMessage());
      }
    }

    return true;
  }

  /**
   * Replace (or add) a single rule, preserving all other currently-loaded rules. Reads the current
   * loaded rule set, swaps in the new rule, and re-runs the standard {@link #loadRules} path so the
   * resulting KieBase contains both the new rule and all the unchanged ones. Fixes the bug where
   * calling loadRules with a single-rule list discarded all other rules.
   *
   * <p>Holds the write lock for the full snapshot+compile+update sequence so concurrent merges
   * cannot lose each other's updates. The lock is reentrant, so the inner {@link #loadRules} call
   * re-acquires it without deadlock. Rule-execution reads block until the merge completes.
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
