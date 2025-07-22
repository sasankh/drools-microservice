package com.company.drools.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for rule refresh operations.
 */
public class RefreshRulesResponse {

  @JsonProperty("status")
  private String status;

  @JsonProperty("rules_loaded")
  private int rulesLoaded;

  @JsonProperty("rules_failed")
  private int rulesFailed;

  @JsonProperty("duration_ms")
  private long durationMs;

  @JsonProperty("errors")
  private List<RuleError> errors;

  @JsonProperty("cache_updated_at")
  private Instant cacheUpdatedAt;

  @JsonProperty("timestamp")
  private Instant timestamp;

  public RefreshRulesResponse() {
    this.timestamp = Instant.now();
  }

  public RefreshRulesResponse(String status, int rulesLoaded, int rulesFailed, 
                             long durationMs, List<RuleError> errors) {
    this.status = status;
    this.rulesLoaded = rulesLoaded;
    this.rulesFailed = rulesFailed;
    this.durationMs = durationMs;
    this.errors = errors;
    this.cacheUpdatedAt = Instant.now();
    this.timestamp = Instant.now();
  }

  // Getters and setters
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public int getRulesLoaded() { return rulesLoaded; }
  public void setRulesLoaded(int rulesLoaded) { this.rulesLoaded = rulesLoaded; }

  public int getRulesFailed() { return rulesFailed; }
  public void setRulesFailed(int rulesFailed) { this.rulesFailed = rulesFailed; }

  public long getDurationMs() { return durationMs; }
  public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

  public List<RuleError> getErrors() { return errors; }
  public void setErrors(List<RuleError> errors) { this.errors = errors; }

  public Instant getCacheUpdatedAt() { return cacheUpdatedAt; }
  public void setCacheUpdatedAt(Instant cacheUpdatedAt) { this.cacheUpdatedAt = cacheUpdatedAt; }

  public Instant getTimestamp() { return timestamp; }
  public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

  /**
   * Represents an error that occurred during rule refresh.
   */
  public static class RuleError {

    @JsonProperty("rule_id")
    private String ruleId;

    @JsonProperty("error")
    private String error;

    public RuleError() {}

    public RuleError(String ruleId, String error) {
      this.ruleId = ruleId;
      this.error = error;
    }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
  }
}