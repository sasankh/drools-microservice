package com.company.drools.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

public class RuleExecutionResponse {

  @JsonProperty("rule_id")
  private String ruleId;

  @JsonProperty("result")
  private Map<String, Object> result;

  @JsonProperty("error")
  private ErrorResponse error;

  @JsonProperty("execution_time_ms")
  private Long executionTimeMs;

  public RuleExecutionResponse() {}

  public RuleExecutionResponse(
      String ruleId, Map<String, Object> result, ErrorResponse error, Long executionTimeMs) {
    this.ruleId = ruleId;
    this.result = result;
    this.error = error;
    this.executionTimeMs = executionTimeMs;
  }

  public static RuleExecutionResponse success(
      String ruleId, Map<String, Object> result, long executionTimeMs) {
    return new RuleExecutionResponse(ruleId, result, null, executionTimeMs);
  }

  public static RuleExecutionResponse failure(
      String ruleId, String errorCode, String errorMessage) {
    ErrorResponse error = new ErrorResponse(errorCode, errorMessage, null);
    return new RuleExecutionResponse(ruleId, null, error, null);
  }

  public static RuleExecutionResponse failure(
      String ruleId, String errorCode, String errorMessage, String details) {
    ErrorResponse error = new ErrorResponse(errorCode, errorMessage, details);
    return new RuleExecutionResponse(ruleId, null, error, null);
  }

  public String getRuleId() {
    return ruleId;
  }

  public void setRuleId(String ruleId) {
    this.ruleId = ruleId;
  }

  public Map<String, Object> getResult() {
    return result;
  }

  public void setResult(Map<String, Object> result) {
    this.result = result;
  }

  public ErrorResponse getError() {
    return error;
  }

  public void setError(ErrorResponse error) {
    this.error = error;
  }

  public Long getExecutionTimeMs() {
    return executionTimeMs;
  }

  public void setExecutionTimeMs(Long executionTimeMs) {
    this.executionTimeMs = executionTimeMs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RuleExecutionResponse that = (RuleExecutionResponse) o;
    return Objects.equals(ruleId, that.ruleId)
        && Objects.equals(result, that.result)
        && Objects.equals(error, that.error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ruleId, result, error);
  }

  @Override
  public String toString() {
    return "RuleExecutionResponse{"
        + "ruleId='"
        + ruleId
        + '\''
        + ", result="
        + result
        + ", error="
        + error
        + ", executionTimeMs="
        + executionTimeMs
        + '}';
  }
}
