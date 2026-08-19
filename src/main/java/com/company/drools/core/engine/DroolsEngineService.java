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
import java.util.concurrent.locks.ReentrantLock;
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

  // Guards the compiled KieBase + loadedRules/ruleMetadata maps. Reads (executeRule) take the read
  // lock; only the brief updateToVersion + map swap takes the write lock.
  private final ReentrantReadWriteLock rulesLock = new ReentrantReadWriteLock();

  // Serializes refreshes so concurrent snapshot+compile sequences can't lose each other's updates.
  // Separate from rulesLock so the (slow) rule compile happens WITHOUT holding the write lock —
  // rule-execution reads are not blocked during compilation (finding P7).
  private final ReentrantLock refreshLock = new ReentrantLock();

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
        // Atomic read-derive-write so concurrent executions of the same rule don't lose stat
        // updates (previous get()+put() could interleave and drop counts under load).
        ruleMetadata.compute(
            ruleId,
            (id, current) ->
                (current != null ? current : metadata).withExecution(result.getExecutionTimeMs()));

        // NOTE: rule_id is a metric tag here — cardinality grows with the rule count. Fine at the
        // 100s-of-rules scale; at 1000s+ consider dropping the per-rule tag on the high-volume
        // timers/counters to bound registry/scrape size.
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
    // Serialize all refreshes. Compile still runs WITHOUT the write lock (see doLoadRules), so
    // rule-execution reads are not blocked during the (possibly long) compile. (P7)
    refreshLock.lock();
    try {
      return doLoadRules(rules);
    } finally {
      refreshLock.unlock();
    }
  }

  private boolean doLoadRules(List<Rule> rules) {
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
   * <p>Serializes with other refreshes via {@code refreshLock}, but does NOT hold the write lock
   * across the compile. The current rule set is snapshotted under a short read lock, then the
   * compile + KieBase swap happens via {@link #doLoadRules} (which takes the write lock only
   * briefly for {@code updateToVersion}). Rule-execution reads are therefore not blocked during
   * compilation (finding P7).
   */
  public boolean loadOrReplaceRule(Rule rule) {
    refreshLock.lock();
    try {
      List<Rule> combined;
      rulesLock.readLock().lock();
      try {
        combined = new ArrayList<>(loadedRules.values());
      } finally {
        rulesLock.readLock().unlock();
      }
      combined.removeIf(r -> r.getRuleId().equals(rule.getRuleId()));
      combined.add(rule);
      return doLoadRules(combined);
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * Remove a single rule from the loaded corpus and recompile the remainder, preserving all other
   * rules. Used by the pub/sub {@code RULE_DELETED} handler so a delete on one instance propagates
   * to siblings' compiled state (finding S5). Serialized via {@code refreshLock}; the compile
   * happens off the write lock, same as {@link #loadOrReplaceRule}.
   *
   * @return {@code true} if the rule was absent (no-op) or successfully removed+recompiled; {@code
   *     false} if the recompile failed (the previous KieBase remains in effect).
   */
  public boolean removeRule(String ruleId) {
    refreshLock.lock();
    try {
      List<Rule> combined;
      rulesLock.readLock().lock();
      try {
        combined = new ArrayList<>(loadedRules.values());
      } finally {
        rulesLock.readLock().unlock();
      }
      boolean removed = combined.removeIf(r -> r.getRuleId().equals(ruleId));
      if (!removed) {
        log.info("removeRule: {} is not loaded; nothing to remove", ruleId);
        return true;
      }
      log.info("Removing rule {} and recompiling remaining {} rules", ruleId, combined.size());
      return doLoadRules(combined);
    } finally {
      refreshLock.unlock();
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
