package com.company.drools.cache;

import com.company.drools.common.RuleIds;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.storage.RuleStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the Redis pub/sub channel published by {@link RuleRefreshPublisher} and refreshes
 * this task's local {@code kieContainer} when another task notifies it of a rule change.
 *
 * <p>Active only when {@code redis.enabled=true && redis.pubsub.enabled=true}. The {@code
 * RedisMessageListenerContainer} bean wires this listener to the channel (see {@code RedisConfig}).
 *
 * <p>Self-message dedup: events emitted by THIS instance are skipped (the publisher already
 * refreshed locally before publishing). Detection uses the {@code sourceInstanceId} field on the
 * event compared against the local {@code droolsInstanceId} bean.
 *
 * <p>Error handling: any exception during event processing is logged at ERROR and the metric {@code
 * drools.refresh.failed{layer=subscriber}} is incremented. The subscriber stays alive — a single
 * bad event does not poison the listener.
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

  // Coalesce a burst of bulk-refresh events: skip a bulk recompile if one completed within this
  // window. Each bulk refresh reloads the full corpus from storage, so back-to-back ones are
  // redundant; anything missed inside the window is reconciled by the next event or auto-refresh.
  private static final long BULK_COALESCE_WINDOW_MS = 500L;

  private final RuleStorage storage;
  private final DroolsEngineService droolsEngineService;
  private final String instanceId;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  // Handling runs on the single-thread rule-refresh executor (see RedisConfig), so this is only
  // ever
  // read/written by one thread; volatile guards visibility if that ever changes.
  private volatile long lastBulkRefreshAtMs = 0L;

  public RuleRefreshSubscriber(
      RuleStorage storage,
      DroolsEngineService droolsEngineService,
      @Qualifier("droolsInstanceId") String instanceId,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.storage = storage;
    this.droolsEngineService = droolsEngineService;
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
    Optional<Rule> rule = storage.getRule(ruleId);
    if (rule.isEmpty()) {
      log.warn("RULE_REFRESHED for {} but rule not found in storage, skipping", ruleId);
      return;
    }
    droolsEngineService.loadOrReplaceRule(rule.get());
  }

  private void handleBulkRefresh() {
    long now = System.currentTimeMillis();
    long since = now - lastBulkRefreshAtMs;
    if (since < BULK_COALESCE_WINDOW_MS) {
      log.debug(
          "Bulk refresh {}ms ago (< {}ms) — coalescing event", since, BULK_COALESCE_WINDOW_MS);
      return;
    }
    lastBulkRefreshAtMs = now;
    List<Rule> rules = storage.getAllRules();
    droolsEngineService.loadRules(rules);
  }

  private void handleDelete(String ruleId) {
    // Validate the raw ruleId (bypasses HTTP validation — see handleSingleRefresh). (S11)
    if (!RuleIds.isPathSafe(ruleId)) {
      log.warn("RULE_DELETED event with invalid/unsafe ruleId, skipping: {}", ruleId);
      return;
    }
    // Propagate the delete to this instance's compiled corpus so a rule deleted on one task stops
    // firing on siblings. (S5)
    droolsEngineService.removeRule(ruleId);
  }
}
