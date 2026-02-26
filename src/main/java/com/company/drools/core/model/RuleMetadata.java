package com.company.drools.core.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

public class RuleMetadata {

  private String version;
  private LocalDateTime loadedAt;
  private LocalDateTime lastModified;
  private RuleStatus status;
  private String errorMessage;
  private long executionCount;
  private double averageExecutionTimeMs;

  /** No-arg constructor required for Redis/Jackson deserialization. */
  protected RuleMetadata() {}

  public RuleMetadata(
      String version,
      LocalDateTime loadedAt,
      LocalDateTime lastModified,
      RuleStatus status,
      String errorMessage,
      long executionCount,
      double averageExecutionTimeMs) {
    this.version = version;
    this.loadedAt = Objects.requireNonNull(loadedAt, "Loaded at cannot be null");
    this.lastModified = lastModified;
    this.status = Objects.requireNonNull(status, "Rule status cannot be null");
    this.errorMessage = errorMessage;
    this.executionCount = executionCount;
    this.averageExecutionTimeMs = averageExecutionTimeMs;
  }

  public static RuleMetadata createNew() {
    return new RuleMetadata("1.0", LocalDateTime.now(), null, RuleStatus.LOADING, null, 0, 0.0);
  }

  public RuleMetadata withStatus(RuleStatus status) {
    return new RuleMetadata(
        version,
        loadedAt,
        lastModified,
        status,
        errorMessage,
        executionCount,
        averageExecutionTimeMs);
  }

  public RuleMetadata withError(String errorMessage) {
    return new RuleMetadata(
        version,
        loadedAt,
        lastModified,
        RuleStatus.ERROR,
        errorMessage,
        executionCount,
        averageExecutionTimeMs);
  }

  public RuleMetadata withExecution(double executionTimeMs) {
    long newCount = executionCount + 1;
    // Incremental average avoids overflow for large execution counts
    double newAverage =
        averageExecutionTimeMs + (executionTimeMs - averageExecutionTimeMs) / newCount;
    return new RuleMetadata(
        version, loadedAt, lastModified, status, errorMessage, newCount, newAverage);
  }

  public RuleMetadata withLastModified(Instant lastModified) {
    LocalDateTime lastModifiedLdt =
        lastModified != null
            ? LocalDateTime.ofInstant(lastModified, java.time.ZoneOffset.UTC)
            : null;
    return new RuleMetadata(
        version,
        loadedAt,
        lastModifiedLdt,
        status,
        errorMessage,
        executionCount,
        averageExecutionTimeMs);
  }

  public String getVersion() {
    return version;
  }

  public LocalDateTime getLoadedAt() {
    return loadedAt;
  }

  public LocalDateTime getLastModified() {
    return lastModified;
  }

  public RuleStatus getStatus() {
    return status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public long getExecutionCount() {
    return executionCount;
  }

  public double getAverageExecutionTimeMs() {
    return averageExecutionTimeMs;
  }

  @Override
  public String toString() {
    return "RuleMetadata{"
        + "version='"
        + version
        + '\''
        + ", loadedAt="
        + loadedAt
        + ", status="
        + status
        + ", executionCount="
        + executionCount
        + ", averageExecutionTimeMs="
        + averageExecutionTimeMs
        + '}';
  }

  public enum RuleStatus {
    LOADING,
    ACTIVE,
    ERROR,
    DISABLED
  }
}
