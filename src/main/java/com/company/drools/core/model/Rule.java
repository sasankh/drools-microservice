package com.company.drools.core.model;

import java.time.LocalDateTime;
import java.util.Objects;

public class Rule {

  private final String ruleId;
  private final String content;
  private final RuleMetadata metadata;

  public Rule(String ruleId, String content, RuleMetadata metadata) {
    this.ruleId = Objects.requireNonNull(ruleId, "Rule ID cannot be null");
    this.content = Objects.requireNonNull(content, "Rule content cannot be null");
    this.metadata = Objects.requireNonNull(metadata, "Rule metadata cannot be null");
  }

  public String getRuleId() {
    return ruleId;
  }

  public String getContent() {
    return content;
  }

  public RuleMetadata getMetadata() {
    return metadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Rule rule = (Rule) o;
    return Objects.equals(ruleId, rule.ruleId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ruleId);
  }

  @Override
  public String toString() {
    return "Rule{" +
        "ruleId='" + ruleId + '\'' +
        ", metadata=" + metadata +
        '}';
  }
}