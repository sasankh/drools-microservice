package com.company.drools.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Rule")
class RuleTest {

  @Nested
  @DisplayName("Constructor")
  class Constructor {

    @Test
    @DisplayName("creates rule with valid parameters")
    void testValidConstruction() {
      RuleMetadata metadata = RuleMetadata.createNew();
      Rule rule = new Rule("test.rule", "rule content", metadata);

      assertThat(rule.getRuleId()).isEqualTo("test.rule");
      assertThat(rule.getContent()).isEqualTo("rule content");
      assertThat(rule.getMetadata()).isSameAs(metadata);
    }

    @Test
    @DisplayName("throws NullPointerException when ruleId is null")
    void testNullRuleId() {
      RuleMetadata metadata = RuleMetadata.createNew();

      assertThatThrownBy(() -> new Rule(null, "content", metadata))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("Rule ID cannot be null");
    }

    @Test
    @DisplayName("throws NullPointerException when content is null")
    void testNullContent() {
      RuleMetadata metadata = RuleMetadata.createNew();

      assertThatThrownBy(() -> new Rule("test.rule", null, metadata))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("Rule content cannot be null");
    }

    @Test
    @DisplayName("throws NullPointerException when metadata is null")
    void testNullMetadata() {
      assertThatThrownBy(() -> new Rule("test.rule", "content", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("Rule metadata cannot be null");
    }
  }

  @Nested
  @DisplayName("equals and hashCode")
  class EqualsAndHashCode {

    @Test
    @DisplayName("equals returns true for same instance")
    void testEqualsIdentity() {
      Rule rule = new Rule("test.rule", "content", RuleMetadata.createNew());

      assertThat(rule.equals(rule)).isTrue();
    }

    @Test
    @DisplayName("equals returns false for null")
    void testEqualsNull() {
      Rule rule = new Rule("test.rule", "content", RuleMetadata.createNew());

      assertThat(rule.equals(null)).isFalse();
    }

    @Test
    @DisplayName("equals returns false for different class")
    void testEqualsDifferentClass() {
      Rule rule = new Rule("test.rule", "content", RuleMetadata.createNew());

      assertThat(rule.equals("not a rule")).isFalse();
    }

    @Test
    @DisplayName("equals returns true for rules with same ruleId")
    void testEqualsSameRuleId() {
      Rule rule1 = new Rule("test.rule", "content1", RuleMetadata.createNew());
      Rule rule2 = new Rule("test.rule", "different-content", RuleMetadata.createNew());

      assertThat(rule1).isEqualTo(rule2);
    }

    @Test
    @DisplayName("equals returns false for rules with different ruleId")
    void testEqualsDifferentRuleId() {
      Rule rule1 = new Rule("rule.a", "content", RuleMetadata.createNew());
      Rule rule2 = new Rule("rule.b", "content", RuleMetadata.createNew());

      assertThat(rule1).isNotEqualTo(rule2);
    }

    @Test
    @DisplayName("hashCode is consistent for equal rules")
    void testHashCodeConsistency() {
      Rule rule1 = new Rule("test.rule", "content1", RuleMetadata.createNew());
      Rule rule2 = new Rule("test.rule", "content2", RuleMetadata.createNew());

      assertThat(rule1).hasSameHashCodeAs(rule2);
    }

    @Test
    @DisplayName("hashCode differs for rules with different ruleIds")
    void testHashCodeDiffers() {
      Rule rule1 = new Rule("rule.a", "content", RuleMetadata.createNew());
      Rule rule2 = new Rule("rule.b", "content", RuleMetadata.createNew());

      assertThat(rule1.hashCode()).isNotEqualTo(rule2.hashCode());
    }
  }

  @Nested
  @DisplayName("toString")
  class ToString {

    @Test
    @DisplayName("contains ruleId in string representation")
    void testToStringContainsRuleId() {
      Rule rule = new Rule("pricing.discount", "content", RuleMetadata.createNew());

      String result = rule.toString();

      assertThat(result).contains("pricing.discount").startsWith("Rule{");
    }
  }
}
