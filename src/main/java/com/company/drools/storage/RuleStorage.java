package com.company.drools.storage;

import com.company.drools.core.model.Rule;
import java.util.List;
import java.util.Optional;

public interface RuleStorage {

  Optional<Rule> getRule(String ruleId);

  List<Rule> getAllRules();

  void saveRule(Rule rule);

  void deleteRule(String ruleId);

  boolean ruleExists(String ruleId);

  void refreshCache();

  void refreshRule(String ruleId);

  long getTotalRuleCount();

  List<String> getRuleIds();
}