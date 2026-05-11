package com.company.drools.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Response DTO for listing loaded rules. */
public class RuleListResponse {

  @JsonProperty("total_rules")
  private int totalRules;

  @JsonProperty("rules")
  private List<RuleInfo> rules;

  @JsonProperty("timestamp")
  private Instant timestamp;

  public RuleListResponse() {
    this.timestamp = Instant.now();
  }

  public RuleListResponse(List<RuleInfo> rules) {
    this.rules = rules;
    this.totalRules = rules != null ? rules.size() : 0;
    this.timestamp = Instant.now();
  }

  public int getTotalRules() {
    return totalRules;
  }

  public void setTotalRules(int totalRules) {
    this.totalRules = totalRules;
  }

  public List<RuleInfo> getRules() {
    return rules;
  }

  public void setRules(List<RuleInfo> rules) {
    this.rules = rules;
    this.totalRules = rules != null ? rules.size() : 0;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  /** Information about a loaded rule. */
  public static class RuleInfo {

    @JsonProperty("rule_id")
    private String ruleId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("loaded_at")
    private Instant loadedAt;

    @JsonProperty("last_modified")
    private Instant lastModified;

    @JsonProperty("execution_count")
    private long executionCount;

    @JsonProperty("avg_execution_time_ms")
    private double avgExecutionTimeMs;

    @JsonProperty("cached")
    private boolean cached;

    @JsonProperty("version")
    private String version;

    public RuleInfo() {}

    @SuppressWarnings("java:S107") // DTO constructor — 8 fields represent distinct rule state
    public RuleInfo(
        String ruleId,
        String status,
        Instant loadedAt,
        Instant lastModified,
        long executionCount,
        double avgExecutionTimeMs,
        boolean cached,
        String version) {
      this.ruleId = ruleId;
      this.status = status;
      this.loadedAt = loadedAt;
      this.lastModified = lastModified;
      this.executionCount = executionCount;
      this.avgExecutionTimeMs = avgExecutionTimeMs;
      this.cached = cached;
      this.version = version;
    }

    // Getters and setters
    public String getRuleId() {
      return ruleId;
    }

    public void setRuleId(String ruleId) {
      this.ruleId = ruleId;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public Instant getLoadedAt() {
      return loadedAt;
    }

    public void setLoadedAt(Instant loadedAt) {
      this.loadedAt = loadedAt;
    }

    public Instant getLastModified() {
      return lastModified;
    }

    public void setLastModified(Instant lastModified) {
      this.lastModified = lastModified;
    }

    public long getExecutionCount() {
      return executionCount;
    }

    public void setExecutionCount(long executionCount) {
      this.executionCount = executionCount;
    }

    public double getAvgExecutionTimeMs() {
      return avgExecutionTimeMs;
    }

    public void setAvgExecutionTimeMs(double avgExecutionTimeMs) {
      this.avgExecutionTimeMs = avgExecutionTimeMs;
    }

    public boolean isCached() {
      return cached;
    }

    public void setCached(boolean cached) {
      this.cached = cached;
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = version;
    }
  }
}
