package com.company.drools.storage;

import com.company.drools.core.model.Rule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class InMemoryRuleStorageAdapter implements RuleStorage {

  private final InMemoryRuleStorage inMemoryStorage;

  public InMemoryRuleStorageAdapter(InMemoryRuleStorage inMemoryStorage) {
    this.inMemoryStorage = inMemoryStorage;
  }

  @Override
  public Optional<Rule> getRule(String ruleId) {
    Rule rule = inMemoryStorage.loadRule(ruleId);
    return Optional.ofNullable(rule);
  }

  @Override
  public List<Rule> getAllRules() {
    return inMemoryStorage.loadAllRules();
  }

  @Override
  public void saveRule(Rule rule) {
    inMemoryStorage.addRule(rule.getRuleId(), rule.getContent());
  }

  @Override
  public void deleteRule(String ruleId) {
    inMemoryStorage.removeRule(ruleId);
  }

  @Override
  public boolean ruleExists(String ruleId) {
    return inMemoryStorage.hasRule(ruleId);
  }

  @Override
  public void refreshCache() {
    // No-op for in-memory storage
  }

  @Override
  public void refreshRule(String ruleId) {
    // No-op for in-memory storage
  }

  @Override
  public long getTotalRuleCount() {
    return inMemoryStorage.getRuleCount();
  }

  @Override
  public List<String> getRuleIds() {
    return getAllRules().stream().map(Rule::getRuleId).collect(Collectors.toList());
  }
}