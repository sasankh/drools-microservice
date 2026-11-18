package com.company.drools.core.engine;

import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rule engine facade: executes a request against ITS OWN rule ID's compiled base, and loads /
 * replaces / removes rule IDs independently (ADR-025).
 *
 * <p><b>Concurrency.</b> Execution takes the immutable {@link CompiledRuleset} for its rule ID from
 * the {@link CompiledRulesetRegistry} and holds no lock; a concurrent refresh swaps the handle
 * atomically and the in-flight request finishes on the version it took. Mutations (load, replace,
 * remove) are serialized by {@code refreshLock}.
 *
 * <p><b>Failure isolation.</b> Each rule ID compiles on its own. A failed compile affects only that
 * ID: if a previous version exists it keeps serving (its metadata records the rejected update);
 * otherwise the ID is recorded as {@code ERROR} and never falls back to other rules.
 */
@Service
public class DroolsEngineService {

  private static final Logger log = LoggerFactory.getLogger(DroolsEngineService.class);

  private static final String METRIC_RULE_EXECUTION_TIME = "drools.rule.execution.time";
  private static final String METRIC_RULE_EXECUTION_ERROR = "drools.rule.execution.error";
  private static final String METRIC_RULE_COMPILE_TIME = "drools.rule.compile.time";
  private static final String METRIC_RULE_COMPILE_ERROR = "drools.rule.compile.error";
  private static final String TAG_RULE_ID = "rule_id";
  private static final String TAG_STATUS = "status";
  private static final String TAG_RESULT = "result";
  private static final String STATUS_ERROR = "error";

  private final RuleCompiler ruleCompiler;
  private final RuleExecutor ruleExecutor;
  private final CompiledRulesetRegistry registry;
  private final TimeoutConfig timeoutConfig;
  private final MeterRegistry meterRegistry;

  /** Metadata for every known rule ID, including IDs that failed to compile (status ERROR). */
  private final Map<String, RuleMetadata> ruleMetadata = new ConcurrentHashMap<>();

  /** Serializes load / replace / remove so concurrent refreshes can't interleave. */
  private final ReentrantLock refreshLock = new ReentrantLock();

  public DroolsEngineService(
      RuleCompiler ruleCompiler,
      RuleExecutor ruleExecutor,
      CompiledRulesetRegistry registry,
      RuleStorage ruleStorage,
      TimeoutConfig timeoutConfig,
      MeterRegistry meterRegistry) {
    this.ruleCompiler = ruleCompiler;
    this.ruleExecutor = ruleExecutor;
    this.registry = registry;
    this.timeoutConfig = timeoutConfig;
    this.meterRegistry = meterRegistry;
    log.info(
        "DroolsEngineService initialized with rule storage: {}",
        ruleStorage.getClass().getSimpleName());
  }

  public RuleExecutor.ExecutionResult executeRule(String ruleId, Map<String, Object> inputData) {
    log.debug("Executing rule: {}", ruleId);
    Timer.Sample sample = Timer.start();

    RuleMetadata metadata = ruleMetadata.get(ruleId);
    if (metadata == null) {
      log.warn("Rule not found: {}", ruleId);
      meterRegistry
          .counter(
              METRIC_RULE_EXECUTION_ERROR, TAG_RULE_ID, "unknown", STATUS_ERROR, "rule_not_found")
          .increment();
      stopTimer(sample, "unknown", STATUS_ERROR);
      return RuleExecutor.ExecutionResult.failure("Rule not found: " + ruleId);
    }

    // Take this rule ID's immutable handle once; a concurrent refresh can't change it under us.
    Optional<CompiledRuleset> ruleset = registry.get(ruleId);
    if (ruleset.isEmpty() || metadata.getStatus() != RuleMetadata.RuleStatus.ACTIVE) {
      log.warn("Rule is not active: {} (status: {})", ruleId, metadata.getStatus());
      meterRegistry
          .counter(
              METRIC_RULE_EXECUTION_ERROR, TAG_RULE_ID, ruleId, STATUS_ERROR, "rule_not_active")
          .increment();
      stopTimer(sample, ruleId, STATUS_ERROR);
      return RuleExecutor.ExecutionResult.failure("Rule is not active: " + ruleId);
    }

    RuleExecutor.ExecutionResult result =
        ruleExecutor.executeRule(
            ruleset.get().kieBase(),
            ruleId,
            inputData,
            timeoutConfig.getRuleExecutionTimeoutSeconds());

    if (result.isSuccess()) {
      // Atomic read-derive-write so concurrent executions of the same rule don't lose stats.
      ruleMetadata.computeIfPresent(
          ruleId, (id, current) -> current.withExecution(result.getExecutionTimeMs()));
      // NOTE: rule_id is a metric tag — cardinality grows with the rule count. Fine at the
      // 100s-of-rules scale; at 1000s+ consider dropping it on the high-volume timers.
      meterRegistry.counter("drools.rule.execution.success", TAG_RULE_ID, ruleId).increment();
      stopTimer(sample, ruleId, "success");
    } else {
      meterRegistry
          .counter(
              METRIC_RULE_EXECUTION_ERROR, TAG_RULE_ID, ruleId, STATUS_ERROR, "execution_failed")
          .increment();
      stopTimer(sample, ruleId, STATUS_ERROR);
    }
    return result;
  }

  /**
   * Load the authoritative full rule set: compile every rule ID independently, install the ones
   * that compile, remove IDs that are no longer present.
   *
   * @return {@code true} if every rule ID compiled
   */
  public boolean loadRules(List<Rule> rules) {
    return loadRulesReport(rules).allSucceeded();
  }

  /** Same as {@link #loadRules} but returns the per-ID outcome. */
  public LoadReport loadRulesReport(List<Rule> rules) {
    refreshLock.lock();
    try {
      log.info("Loading {} rules", rules.size());
      Map<String, Rule> byId = new LinkedHashMap<>();
      for (Rule rule : rules) {
        if (byId.put(rule.getRuleId(), rule) != null) {
          log.warn(
              "Duplicate rule ID '{}' in this load — the later definition wins", rule.getRuleId());
        }
      }

      // IDs that are no longer part of the set are removed (compiled + metadata-only entries).
      List<String> removed = new ArrayList<>();
      Set<String> known = new HashSet<>(ruleMetadata.keySet());
      known.addAll(registry.ruleIds());
      for (String id : known) {
        if (!byId.containsKey(id)) {
          registry.remove(id);
          ruleMetadata.remove(id);
          removed.add(id);
        }
      }

      List<String> loaded = new ArrayList<>();
      List<String> unchanged = new ArrayList<>();
      Map<String, String> failed = new LinkedHashMap<>();
      for (Rule rule : byId.values()) {
        InstallOutcome outcome = compileAndInstall(rule);
        switch (outcome.status()) {
          case INSTALLED -> loaded.add(rule.getRuleId());
          case UNCHANGED -> unchanged.add(rule.getRuleId());
          case FAILED -> failed.put(rule.getRuleId(), outcome.error());
        }
      }

      LoadReport report = new LoadReport(loaded, unchanged, failed, removed);
      if (failed.isEmpty()) {
        log.info(
            "Loaded {} rules ({} unchanged, {} removed)",
            loaded.size(),
            unchanged.size(),
            removed.size());
      } else {
        log.error(
            "Loaded {} rules, {} unchanged, {} failed to compile ({} removed): {}",
            loaded.size(),
            unchanged.size(),
            failed.size(),
            removed.size(),
            failed.keySet());
      }
      return report;
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * Replace (or add) one rule ID. Only that ID is recompiled; every other rule ID is untouched. If
   * the new version fails to compile, the previous version (if any) keeps serving. If the DRL is
   * byte-identical to the version already serving (same SHA-256), nothing is recompiled.
   *
   * @return {@code true} if the given version is now serving (newly compiled or unchanged)
   */
  public boolean loadOrReplaceRule(Rule rule) {
    return replaceRule(rule).status() != InstallStatus.FAILED;
  }

  /** Same as {@link #loadOrReplaceRule} but returns the detailed outcome. */
  public InstallOutcome replaceRule(Rule rule) {
    refreshLock.lock();
    try {
      return compileAndInstall(rule);
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * Remove one rule ID. Only that ID is affected; requests that already hold its handle finish.
   *
   * @return always {@code true} (an absent rule is a no-op)
   */
  public boolean removeRule(String ruleId) {
    refreshLock.lock();
    try {
      boolean wasCompiled = registry.remove(ruleId).isPresent();
      boolean hadMetadata = ruleMetadata.remove(ruleId) != null;
      if (wasCompiled || hadMetadata) {
        log.info("Removed rule {}", ruleId);
      } else {
        log.info("removeRule: {} is not loaded; nothing to remove", ruleId);
      }
      return true;
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * Compile one rule and, on success, install it. Skips the compile when the same DRL (same digest)
   * is already serving cleanly. Caller holds {@code refreshLock}.
   */
  private InstallOutcome compileAndInstall(Rule rule) {
    String ruleId = rule.getRuleId();
    String digest = rule.contentDigest();
    Optional<CompiledRuleset> current = registry.get(ruleId);
    RuleMetadata currentMeta = ruleMetadata.get(ruleId);
    if (current.isPresent()
        && current.get().contentDigest().equals(digest)
        && currentMeta != null
        && currentMeta.getStatus() == RuleMetadata.RuleStatus.ACTIVE
        && currentMeta.getErrorMessage() == null) {
      log.debug(
          "Rule {} unchanged (digest {}), not recompiled", ruleId, current.get().shortDigest());
      return InstallOutcome.unchanged();
    }

    Timer.Sample sample = Timer.start();
    RuleCompiler.RuleCompilation compilation = ruleCompiler.compileRule(rule);
    sample.stop(
        Timer.builder(METRIC_RULE_COMPILE_TIME)
            .tag(TAG_RESULT, compilation.isSuccess() ? "success" : STATUS_ERROR)
            .register(meterRegistry));

    if (compilation.isSuccess()) {
      CompiledRuleset ruleset = compilation.ruleset();
      registry.install(ruleset);
      java.time.LocalDateTime sourceModified =
          rule.getMetadata() != null ? rule.getMetadata().getLastModified() : null;
      ruleMetadata.compute(
          ruleId,
          (id, existing) ->
              (existing != null ? existing : RuleMetadata.createNew())
                  .withCompiled(sourceModified));
      log.debug("Installed rule {} (digest {})", ruleId, ruleset.shortDigest());
      return InstallOutcome.installed();
    }

    meterRegistry.counter(METRIC_RULE_COMPILE_ERROR, TAG_RULE_ID, ruleId).increment();
    String error = compilation.errorMessage();
    if (registry.contains(ruleId)) {
      // Previous version keeps serving; record the rejected update.
      ruleMetadata.computeIfPresent(ruleId, (id, existing) -> existing.withFailedUpdate(error));
      log.error("Rule {} update rejected; previous version keeps serving: {}", ruleId, error);
    } else {
      ruleMetadata.compute(
          ruleId,
          (id, existing) ->
              (existing != null ? existing : RuleMetadata.createNew()).withError(error));
      log.error("Rule {} failed to compile and has no previous version: {}", ruleId, error);
    }
    return InstallOutcome.failed(error);
  }

  private void stopTimer(Timer.Sample sample, String ruleId, String status) {
    sample.stop(
        Timer.builder(METRIC_RULE_EXECUTION_TIME)
            .tag(TAG_RULE_ID, ruleId)
            .tag(TAG_STATUS, status)
            .register(meterRegistry));
  }

  public Map<String, RuleMetadata> getAllRuleMetadata() {
    return Map.copyOf(ruleMetadata);
  }

  public RuleMetadata getRuleMetadata(String ruleId) {
    return ruleMetadata.get(ruleId);
  }

  /** {@code true} if the rule ID is known (serving, or recorded as failed to compile). */
  public boolean hasRule(String ruleId) {
    return ruleMetadata.containsKey(ruleId);
  }

  /** SHA-256 of the DRL currently serving for {@code ruleId}, if any. */
  public Optional<String> getServingDigest(String ruleId) {
    return registry.get(ruleId).map(CompiledRuleset::contentDigest);
  }

  /** Rule ID → SHA-256 of the DRL currently serving, for every compiled rule ID. */
  public Map<String, String> getServingDigests() {
    Map<String, String> digests = new LinkedHashMap<>();
    registry.snapshot().forEach((id, handle) -> digests.put(id, handle.contentDigest()));
    return digests;
  }

  /** Number of rule IDs with a compiled version able to serve requests. */
  public int getLoadedRulesCount() {
    return registry.size();
  }

  public long getActiveRulesCount() {
    return ruleMetadata.entrySet().stream()
        .filter(e -> e.getValue().getStatus() == RuleMetadata.RuleStatus.ACTIVE)
        .filter(e -> registry.contains(e.getKey()))
        .count();
  }

  /**
   * Per-ID outcome of a bulk load.
   *
   * @param loaded newly compiled and now serving
   * @param unchanged same DRL as the version already serving (not recompiled)
   * @param failed rule ID → compile error (a previous version, if any, keeps serving)
   * @param removed no longer present in the set, removed
   */
  public record LoadReport(
      List<String> loaded,
      List<String> unchanged,
      Map<String, String> failed,
      List<String> removed) {

    public LoadReport {
      loaded = List.copyOf(loaded);
      unchanged = List.copyOf(unchanged);
      failed = Map.copyOf(failed);
      removed = List.copyOf(removed);
    }

    public boolean allSucceeded() {
      return failed.isEmpty();
    }

    /** {@code true} if anything changed (installed or removed). */
    public boolean changedAnything() {
      return !loaded.isEmpty() || !removed.isEmpty();
    }
  }

  /** Outcome of installing one rule ID. */
  public enum InstallStatus {
    INSTALLED,
    UNCHANGED,
    FAILED
  }

  /** Install outcome plus the compile error when {@link InstallStatus#FAILED}. */
  public record InstallOutcome(InstallStatus status, String error) {

    static InstallOutcome installed() {
      return new InstallOutcome(InstallStatus.INSTALLED, null);
    }

    static InstallOutcome unchanged() {
      return new InstallOutcome(InstallStatus.UNCHANGED, null);
    }

    static InstallOutcome failed(String error) {
      return new InstallOutcome(InstallStatus.FAILED, error);
    }
  }
}
