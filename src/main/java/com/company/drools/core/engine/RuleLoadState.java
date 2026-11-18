package com.company.drools.core.engine;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * What the last rule load did, for health/readiness: whether the startup load has succeeded yet,
 * and the outcome of the most recent refresh of any kind. Written by {@link RuleRefreshService}.
 */
@Component
public class RuleLoadState {

  /** Whether the service has completed a successful full load since start. */
  public enum Phase {
    /** Startup load not finished yet. */
    NOT_LOADED,
    /** At least one full load read storage completely. */
    LOADED,
    /** The startup load failed and no full load has succeeded since. */
    FAILED
  }

  private volatile Phase phase = Phase.NOT_LOADED;
  private volatile Instant lastRefreshAt;
  private volatile String lastRefreshSource;
  private volatile String lastRefreshResult;
  private volatile String lastError;

  /** A full read of storage succeeded and was applied. */
  void recordFullLoad(String source, String result) {
    phase = Phase.LOADED;
    record(source, result, null);
  }

  /** A full read failed. Only the startup load moves the phase to FAILED. */
  void recordFullLoadFailure(String source, String error, boolean startup) {
    if (startup || phase == Phase.NOT_LOADED) {
      phase = Phase.FAILED;
    }
    record(source, "failed", error);
  }

  /** Any other refresh (single rule, refused bulk load). */
  void recordRefresh(String source, String result, String error) {
    record(source, result, error);
  }

  private void record(String source, String result, String error) {
    lastRefreshAt = Instant.now();
    lastRefreshSource = source;
    lastRefreshResult = result;
    lastError = error;
  }

  public Phase getPhase() {
    return phase;
  }

  public Instant getLastRefreshAt() {
    return lastRefreshAt;
  }

  public String getLastRefreshSource() {
    return lastRefreshSource;
  }

  public String getLastRefreshResult() {
    return lastRefreshResult;
  }

  public String getLastError() {
    return lastError;
  }
}
