package com.company.drools.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Response DTO for single rule refresh operations.
 */
public class RefreshRuleResponse {

  @JsonProperty("rule_id")
  private String ruleId;

  @JsonProperty("status")
  private String status;

  @JsonProperty("previous_version")
  private Instant previousVersion;

  @JsonProperty("current_version")
  private Instant currentVersion;

  @JsonProperty("compilation_time_ms")
  private long compilationTimeMs;

  @JsonProperty("error")
  private String error;

  @JsonProperty("timestamp")
  private Instant timestamp;

  public RefreshRuleResponse() {
    this.timestamp = Instant.now();
  }

  public RefreshRuleResponse(String ruleId, String status) {
    this.ruleId = ruleId;
    this.status = status;
    this.timestamp = Instant.now();
  }

  public RefreshRuleResponse(String ruleId, String status, Instant previousVersion, 
                            Instant currentVersion, long compilationTimeMs) {
    this.ruleId = ruleId;
    this.status = status;
    this.previousVersion = previousVersion;
    this.currentVersion = currentVersion;
    this.compilationTimeMs = compilationTimeMs;
    this.timestamp = Instant.now();
  }

  // Getters and setters
  public String getRuleId() { return ruleId; }
  public void setRuleId(String ruleId) { this.ruleId = ruleId; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public Instant getPreviousVersion() { return previousVersion; }
  public void setPreviousVersion(Instant previousVersion) { this.previousVersion = previousVersion; }

  public Instant getCurrentVersion() { return currentVersion; }
  public void setCurrentVersion(Instant currentVersion) { this.currentVersion = currentVersion; }

  public long getCompilationTimeMs() { return compilationTimeMs; }
  public void setCompilationTimeMs(long compilationTimeMs) { this.compilationTimeMs = compilationTimeMs; }

  public String getError() { return error; }
  public void setError(String error) { this.error = error; }

  public Instant getTimestamp() { return timestamp; }
  public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}