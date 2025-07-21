package com.company.drools.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.Objects;

public class RuleExecutionRequest {

  @JsonProperty("rule_id")
  @NotBlank(message = "Rule ID cannot be blank")
  private String ruleId;

  @JsonProperty("data")
  @NotNull(message = "Data cannot be null")
  private Map<String, Object> data;

  public RuleExecutionRequest() {
  }

  public RuleExecutionRequest(String ruleId, Map<String, Object> data) {
    this.ruleId = ruleId;
    this.data = data;
  }

  public String getRuleId() {
    return ruleId;
  }

  public void setRuleId(String ruleId) {
    this.ruleId = ruleId;
  }

  public Map<String, Object> getData() {
    return data;
  }

  public void setData(Map<String, Object> data) {
    this.data = data;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RuleExecutionRequest that = (RuleExecutionRequest) o;
    return Objects.equals(ruleId, that.ruleId) && Objects.equals(data, that.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ruleId, data);
  }

  @Override
  public String toString() {
    return "RuleExecutionRequest{" +
        "ruleId='" + ruleId + '\'' +
        ", data=" + data +
        '}';
  }
}