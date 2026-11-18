package com.company.drools.api.exception;

/**
 * The rules for one request would fire more than {@code drools.execution.max-rule-firings} times —
 * almost always a rule loop (a consequence that re-triggers itself). Maps to HTTP 422 {@code
 * RULE_LIMIT_EXCEEDED}; no partial result is returned.
 */
public class RuleLimitExceededException extends RuntimeException {

  private final String ruleId;
  private final int limit;

  public RuleLimitExceededException(String ruleId, int limit) {
    super("Rule " + ruleId + " exceeded the maximum of " + limit + " rule firings per request");
    this.ruleId = ruleId;
    this.limit = limit;
  }

  public String getRuleId() {
    return ruleId;
  }

  public int getLimit() {
    return limit;
  }
}
