package com.company.drools.storage;

import com.company.drools.core.model.Rule;
import java.util.List;
import java.util.Optional;

public interface RuleStorage {

  Optional<Rule> getRule(String ruleId);

  /**
   * The COMPLETE set of rules in storage, or an exception. Implementations must never return a
   * partial list: a refresh treats the result as authoritative and removes every rule ID that is
   * absent from it, so a swallowed per-object read error would silently delete a healthy rule.
   *
   * @throws com.company.drools.api.exception.RuleStorageException if the set can't be read fully
   */
  List<Rule> getAllRules();

  /**
   * Like {@link #getAllRules()} but always reads the source of truth, bypassing any cache layer.
   * Used by the scheduled reconcile so cached DRL can't hide a change made directly in storage.
   */
  default List<Rule> getAllRulesFromSource() {
    return getAllRules();
  }

  void saveRule(Rule rule);

  void deleteRule(String ruleId);

  boolean ruleExists(String ruleId);

  void refreshCache();

  void refreshRule(String ruleId);

  long getTotalRuleCount();

  List<String> getRuleIds();
}
