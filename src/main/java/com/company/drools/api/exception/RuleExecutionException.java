package com.company.drools.api.exception;

public class RuleExecutionException extends RuntimeException {

  private final String ruleId;

  public RuleExecutionException(String ruleId, String message) {
    super(message);
    this.ruleId = ruleId;
  }

  public RuleExecutionException(String ruleId, String message, Throwable cause) {
    super(message, cause);
    this.ruleId = ruleId;
  }

  public String getRuleId() {
    return ruleId;
  }
}