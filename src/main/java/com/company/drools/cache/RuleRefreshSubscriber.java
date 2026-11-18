package com.company.drools.cache;

import com.company.drools.common.RuleIds;
import com.company.drools.core.engine.RuleRefreshService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the Redis pub/sub channel published by {@link RuleRefreshPublisher} and refreshes
 * this instance's compiled rules (via {@link RuleRefreshService}) when another instance notifies it
 * of a rule change. Pub/sub is fire-and-forget; a missed event is repaired by the scheduled
 * reconcile ({@code drools.refresh.auto-enabled}).
 *
 * <p>Active only when {@code redis.enabled=true && redis.pubsub.enabled=true}. The {@code
 * RedisMessageListenerContainer} bean wires this listener to the channel (see {@code RedisConfig}).
 *
 * <p>Self-message dedup: events emitted by THIS instance are skipped (the publisher already
 * refreshed locally before publishing). Detection uses the {@code sourceInstanceId} field on the
 * event compared against the local {@code droolsInstanceId} bean.
 *
 * <p>Error handling: a failed refresh (storage unreadable, compile error, refused empty set) and
 * any exception are logged at ERROR and counted in {@code drools.refresh.failed{layer=subscriber}}.
 * The subscriber stays alive — a single bad event does not poison the listener.
 */
@Component
@ConditionalOnExpression("${redis.enabled:false} and ${redis.pubsub.enabled:true}")
public class RuleRefreshSubscriber implements MessageListener {

  private static final Logger log = LoggerFactory.getLogger(RuleRefreshSubscriber.class);

  private static final String METRIC_RECEIVED = "drools.refresh.received";
  private static final String METRIC_SKIPPED_SELF = "drools.refresh.skipped_self";
  private static final String METRIC_FAILED = "drools.refresh.failed";
  private static final String METRIC_PROCESSING_DURATION = "drools.refresh.processing.duration";
  private static final String TAG_EVENT = "event";
  private static final String TAG_LAYER = "layer";
  private static final String LAYER_SUBSCRIBER = "subscriber";

  // No bulk-event coalescing: the old 500 ms window could drop an event whose change landed after
  // the previous read started. Unchanged rules are skipped by digest, so a repeated bulk refresh
  // only costs a storage read.

  private final RuleRefreshService refreshService;
  private final String instanceId;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public RuleRefreshSubscriber(
      RuleRefreshService refreshService,
      @Qualifier("droolsInstanceId") String instanceId,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.refreshService = refreshService;
    this.instanceId = instanceId;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    log.info("RuleRefreshSubscriber initialized: instanceId={}", instanceId);
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    RefreshEvent event;
    try {
      event = objectMapper.readValue(message.getBody(), RefreshEvent.class);
    } catch (Exception e) {
      log.error(
          "Failed to deserialize refresh event payload (length={})", message.getBody().length, e);
      meterRegistry
          .counter(METRIC_FAILED, TAG_LAYER, LAYER_SUBSCRIBER, "reason", "deserialize")
          .increment();
      return;
    }

    if (event.event() == null) {
      log.warn("Received refresh event with null type, skipping");
      return;
    }

    if (instanceId.equals(event.sourceInstanceId())) {
      meterRegistry.counter(METRIC_SKIPPED_SELF).increment();
      log.debug(
          "Skipping self-emitted refresh event: type={} ruleId={}", event.event(), event.ruleId());
      return;
    }

    meterRegistry.counter(METRIC_RECEIVED, TAG_EVENT, event.event().name()).increment();
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      handle(event);
      sample.stop(
          meterRegistry.timer(
              METRIC_PROCESSING_DURATION, TAG_EVENT, event.event().name(), "result", "ok"));
      log.info(
          "Processed refresh event: type={} ruleId={} sourceInstanceId={}",
          event.event(),
          event.ruleId(),
          event.sourceInstanceId());
    } catch (Exception e) {
      sample.stop(
          meterRegistry.timer(
              METRIC_PROCESSING_DURATION, TAG_EVENT, event.event().name(), "result", "error"));
      meterRegistry
          .counter(METRIC_FAILED, TAG_LAYER, LAYER_SUBSCRIBER, "reason", "process_error")
          .increment();
      log.error(
          "Failed to process refresh event: type={} ruleId={} sourceInstanceId={}",
          event.event(),
          event.ruleId(),
          event.sourceInstanceId(),
          e);
    }
  }

  private void handle(RefreshEvent event) {
    switch (event.event()) {
      case RULE_REFRESHED -> handleSingleRefresh(event.ruleId());
      case RULE_REFRESHED_BULK -> handleBulkRefresh();
      case RULE_DELETED -> handleDelete(event.ruleId());
    }
  }

  private void handleSingleRefresh(String ruleId) {
    // Validate the raw ruleId before it reaches storage — this path bypasses the HTTP-layer
    // @ValidRuleId check, so an attacker with Redis publish access could otherwise inject a
    // traversal id. (S11)
    if (!RuleIds.isPathSafe(ruleId)) {
      log.warn("RULE_REFRESHED event with invalid/unsafe ruleId, skipping: {}", ruleId);
      return;
    }
    // The publisher already invalidated the shared Redis entry before publishing.
    RuleRefreshService.SingleResult result =
        refreshService.refreshOne(ruleId, RuleRefreshService.Source.PUBSUB, false);
    switch (result.status()) {
      case INSTALLED, UNCHANGED, REMOVED -> {
        // applied
      }
      case NOT_FOUND -> log.warn("RULE_REFRESHED for {} but rule not found in storage", ruleId);
      case FAILED, STORAGE_FAILED ->
          throw new IllegalStateException(
              "Refresh of " + ruleId + " failed (" + result.status() + "): " + result.error());
    }
  }

  private void handleBulkRefresh() {
    RuleRefreshService.BulkResult result =
        refreshService.refreshAll(
            RuleRefreshService.Source.PUBSUB, RuleRefreshService.ReadMode.CACHED);
    if (result.status() != RuleRefreshService.BulkStatus.LOADED) {
      throw new IllegalStateException(
          "Bulk refresh failed (" + result.status() + "): " + result.error());
    }
    if (!result.report().allSucceeded()) {
      meterRegistry
          .counter(METRIC_FAILED, TAG_LAYER, LAYER_SUBSCRIBER, "reason", "compile")
          .increment(result.report().failed().size());
      log.error(
          "Bulk refresh: {} rule(s) failed to compile (previous versions keep serving): {}",
          result.report().failed().size(),
          result.report().failed().keySet());
    }
  }

  private void handleDelete(String ruleId) {
    // Validate the raw ruleId (bypasses HTTP validation — see handleSingleRefresh). (S11)
    if (!RuleIds.isPathSafe(ruleId)) {
      log.warn("RULE_DELETED event with invalid/unsafe ruleId, skipping: {}", ruleId);
      return;
    }
    // Propagate the delete so a rule deleted on one instance stops serving on siblings. (S5)
    refreshService.removeOne(ruleId, RuleRefreshService.Source.PUBSUB);
  }
}
