package com.company.drools.api.exception;

public class RuleNotFoundException extends RuntimeException {

  private final String ruleId;

  public RuleNotFoundException(String ruleId) {
    super("Rule not found: " + ruleId);
    this.ruleId = ruleId;
  }

  public RuleNotFoundException(String ruleId, String message) {
    super(message);
    this.ruleId = ruleId;
  }

  public RuleNotFoundException(String ruleId, String message, Throwable cause) {
    super(message, cause);
    this.ruleId = ruleId;
  }

  public String getRuleId() {
    return ruleId;
  }
}
