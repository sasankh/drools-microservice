package com.company.drools.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Pub/sub event published when a task refreshes its local rule state. Other tasks subscribe to the
 * channel and use this event to keep their own {@code kieContainer} in sync.
 *
 * <p>Serialised as JSON via Jackson. Schema is part of the cross-service contract — see {@code
 * project-documentation/04-architecture.md}.
 *
 * @param event event type — see {@link EventType}
 * @param ruleId rule id for single-rule events; {@code null} for {@link
 *     EventType#RULE_REFRESHED_BULK}
 * @param sourceInstanceId UUID of the publishing instance; used by subscribers to skip self-emitted
 *     events
 * @param timestamp UTC instant the publisher emitted this event
 */
public record RefreshEvent(
    @JsonProperty("event") EventType event,
    @JsonProperty("rule_id") String ruleId,
    @JsonProperty("source_instance_id") String sourceInstanceId,
    @JsonProperty("timestamp") Instant timestamp) {

  @JsonCreator
  public RefreshEvent {
    // canonical record constructor for Jackson — no validation; subscribers tolerate nulls
  }

  /** Convenience factory for single-rule refresh. */
  public static RefreshEvent refreshed(String ruleId, String sourceInstanceId) {
    return new RefreshEvent(EventType.RULE_REFRESHED, ruleId, sourceInstanceId, Instant.now());
  }

  /** Convenience factory for bulk refresh. */
  public static RefreshEvent bulkRefreshed(String sourceInstanceId) {
    return new RefreshEvent(EventType.RULE_REFRESHED_BULK, null, sourceInstanceId, Instant.now());
  }

  /** Convenience factory for rule deletion. */
  public static RefreshEvent deleted(String ruleId, String sourceInstanceId) {
    return new RefreshEvent(EventType.RULE_DELETED, ruleId, sourceInstanceId, Instant.now());
  }

  /**
   * Event type. An unknown value (published by a newer version) fails Jackson deserialization; the
   * subscriber logs it (with the {@code reason=deserialize} metric) and skips it, staying alive —
   * so unknown events are tolerated by being ignored rather than by partial mapping.
   */
  public enum EventType {
    RULE_REFRESHED,
    RULE_REFRESHED_BULK,
    RULE_DELETED
  }
}
