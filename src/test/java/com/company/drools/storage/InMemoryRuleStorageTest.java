package com.company.drools.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryRuleStorage & Adapter")
class InMemoryRuleStorageTest {

  private InMemoryRuleStorage storage;
  private InMemoryRuleStorageAdapter adapter;

  @BeforeEach
  void setUp() {
    storage = new InMemoryRuleStorage();
    adapter = new InMemoryRuleStorageAdapter(storage);
  }

  @Nested
  @DisplayName("InMemoryRuleStorage")
  class DirectStorageTests {

    @Test
    @DisplayName("initializes with sample rules")
    void testInitializesWithSampleRules() {
      assertThat(storage.getRuleCount()).isEqualTo(2);
      assertThat(storage.hasRule("pricing.discount.simple")).isTrue();
      assertThat(storage.hasRule("pricing.discount.vip")).isTrue();
    }

    @Test
    @DisplayName("loadAllRules returns all rules")
    void testLoadAllRules() {
      List<Rule> rules = storage.loadAllRules();
      assertThat(rules).hasSize(2);
      assertThat(rules).extracting(Rule::getRuleId)
          .containsExactlyInAnyOrder("pricing.discount.simple", "pricing.discount.vip");
    }

    @Test
    @DisplayName("loadRule returns existing rule")
    void testLoadRule() {
      Rule rule = storage.loadRule("pricing.discount.simple");
      assertThat(rule).isNotNull();
      assertThat(rule.getRuleId()).isEqualTo("pricing.discount.simple");
      assertThat(rule.getContent()).contains("Simple Discount Rule");
    }

    @Test
    @DisplayName("loadRule returns null for non-existent rule")
    void testLoadRuleNotFound() {
      Rule rule = storage.loadRule("nonexistent.rule");
      assertThat(rule).isNull();
    }

    @Test
    @DisplayName("addRule adds new rule")
    void testAddRule() {
      storage.addRule("new.rule", "rule content");
      assertThat(storage.hasRule("new.rule")).isTrue();
      assertThat(storage.getRuleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("removeRule removes existing rule")
    void testRemoveRule() {
      storage.removeRule("pricing.discount.simple");
      assertThat(storage.hasRule("pricing.discount.simple")).isFalse();
      assertThat(storage.getRuleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("hasRule returns false for non-existent rule")
    void testHasRuleFalse() {
      assertThat(storage.hasRule("nonexistent")).isFalse();
    }
  }

  @Nested
  @DisplayName("InMemoryRuleStorageAdapter")
  class AdapterTests {

    @Test
    @DisplayName("getRule returns existing rule")
    void testGetRule() {
      Optional<Rule> rule = adapter.getRule("pricing.discount.simple");
      assertThat(rule).isPresent();
      assertThat(rule.get().getRuleId()).isEqualTo("pricing.discount.simple");
    }

    @Test
    @DisplayName("getRule returns empty for non-existent rule")
    void testGetRuleNotFound() {
      Optional<Rule> rule = adapter.getRule("nonexistent");
      assertThat(rule).isEmpty();
    }

    @Test
    @DisplayName("getAllRules returns all rules")
    void testGetAllRules() {
      List<Rule> rules = adapter.getAllRules();
      assertThat(rules).hasSize(2);
    }

    @Test
    @DisplayName("saveRule persists rule")
    void testSaveRule() {
      Rule rule = new Rule("new.rule", "content", RuleMetadata.createNew());
      adapter.saveRule(rule);
      assertThat(adapter.ruleExists("new.rule")).isTrue();
    }

    @Test
    @DisplayName("deleteRule removes rule")
    void testDeleteRule() {
      adapter.deleteRule("pricing.discount.simple");
      assertThat(adapter.ruleExists("pricing.discount.simple")).isFalse();
    }

    @Test
    @DisplayName("ruleExists checks existence")
    void testRuleExists() {
      assertThat(adapter.ruleExists("pricing.discount.simple")).isTrue();
      assertThat(adapter.ruleExists("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("refreshCache is no-op")
    void testRefreshCache() {
      adapter.refreshCache(); // should not throw
    }

    @Test
    @DisplayName("refreshRule is no-op")
    void testRefreshRule() {
      adapter.refreshRule("pricing.discount.simple"); // should not throw
    }

    @Test
    @DisplayName("getTotalRuleCount returns count")
    void testGetTotalRuleCount() {
      assertThat(adapter.getTotalRuleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("getRuleIds returns all IDs")
    void testGetRuleIds() {
      List<String> ids = adapter.getRuleIds();
      assertThat(ids).containsExactlyInAnyOrder(
          "pricing.discount.simple", "pricing.discount.vip");
    }
  }
}
