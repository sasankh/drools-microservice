package com.company.drools.core.engine;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The set of compiled rule IDs currently able to serve requests (ADR-025). One immutable {@link
 * CompiledRuleset} per rule ID; every operation is atomic per ID, and readers never block.
 *
 * <p>Mutations are expected to be serialized by the caller ({@code DroolsEngineService}'s refresh
 * lock); the map itself is safe for concurrent readers during a mutation.
 */
@Component
public class CompiledRulesetRegistry {

  private final Map<String, CompiledRuleset> rulesets = new ConcurrentHashMap<>();

  /** The handle serving {@code ruleId}, if any. */
  public Optional<CompiledRuleset> get(String ruleId) {
    return Optional.ofNullable(rulesets.get(ruleId));
  }

  /**
   * Atomically install (or replace) the handle for its rule ID.
   *
   * @return the handle it replaced, if any
   */
  public Optional<CompiledRuleset> install(CompiledRuleset ruleset) {
    return Optional.ofNullable(rulesets.put(ruleset.ruleId(), ruleset));
  }

  /**
   * Atomically remove the handle for {@code ruleId}. In-flight requests already holding it finish
   * normally.
   *
   * @return the removed handle, if any
   */
  public Optional<CompiledRuleset> remove(String ruleId) {
    return Optional.ofNullable(rulesets.remove(ruleId));
  }

  public boolean contains(String ruleId) {
    return rulesets.containsKey(ruleId);
  }

  public int size() {
    return rulesets.size();
  }

  /** Snapshot of the rule IDs currently installed. */
  public Set<String> ruleIds() {
    return Set.copyOf(rulesets.keySet());
  }

  /** Immutable snapshot of all installed handles. */
  public Map<String, CompiledRuleset> snapshot() {
    return Map.copyOf(rulesets);
  }
}
