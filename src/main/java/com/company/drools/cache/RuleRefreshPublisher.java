package com.company.drools.cache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link RefreshEvent}s to the Redis pub/sub channel so other ECS tasks can keep their
 * {@code kieContainer} in sync after a refresh. Fire-and-forget — publish failures log WARN but do
 * not propagate (the local refresh has already succeeded).
 *
 * <p>Active only when {@code redis.enabled=true && redis.pubsub.enabled=true}.
 */
@Component
@ConditionalOnExpression("${redis.enabled:false} and ${redis.pubsub.enabled:true}")
public class RuleRefreshPublisher {

  private static final Logger log = LoggerFactory.getLogger(RuleRefreshPublisher.class);

  private static final String METRIC_PUBLISHED = "drools.refresh.published";
  private static final String METRIC_FAILED = "drools.refresh.failed";
  private static final String TAG_EVENT = "event";
  private static final String TAG_LAYER = "layer";
  private static final String LAYER_PUBLISHER = "publisher";

  private final StringRedisTemplate stringRedisTemplate;
  private final CircuitBreaker redisCircuitBreaker;
  private final String instanceId;
  private final String channel;
  private final MeterRegistry meterRegistry;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  public RuleRefreshPublisher(
      StringRedisTemplate stringRedisTemplate,
      @Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker,
      @Qualifier("droolsInstanceId") String instanceId,
      @Value("${redis.pubsub.channel:drools:rule:events}") String channel,
      MeterRegistry meterRegistry,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.redisCircuitBreaker = redisCircuitBreaker;
    this.instanceId = instanceId;
    this.channel = channel;
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
    log.info("RuleRefreshPublisher initialized: channel={}, instanceId={}", channel, instanceId);
  }

  /** Publish a single-rule refresh event. */
  public void publishRefresh(String ruleId) {
    publish(RefreshEvent.refreshed(ruleId, instanceId));
  }

  /** Publish a bulk refresh event. */
  public void publishBulkRefresh() {
    publish(RefreshEvent.bulkRefreshed(instanceId));
  }

  /** Publish a rule-deleted event. */
  public void publishDelete(String ruleId) {
    publish(RefreshEvent.deleted(ruleId, instanceId));
  }

  private void publish(RefreshEvent event) {
    try {
      String payload = objectMapper.writeValueAsString(event);
      Runnable op =
          CircuitBreaker.decorateRunnable(
              redisCircuitBreaker, () -> stringRedisTemplate.convertAndSend(channel, payload));
      op.run();
      meterRegistry.counter(METRIC_PUBLISHED, TAG_EVENT, event.event().name()).increment();
      log.info(
          "Published refresh event: type={} ruleId={} channel={}",
          event.event(),
          event.ruleId(),
          channel);
    } catch (CallNotPermittedException _) {
      log.warn(
          "Redis circuit breaker open — failed to publish event type={} ruleId={}",
          event.event(),
          event.ruleId());
      meterRegistry
          .counter(METRIC_FAILED, TAG_LAYER, LAYER_PUBLISHER, "reason", "circuit_breaker_open")
          .increment();
    } catch (Exception e) {
      log.warn(
          "Failed to publish refresh event type={} ruleId={}", event.event(), event.ruleId(), e);
      meterRegistry
          .counter(METRIC_FAILED, TAG_LAYER, LAYER_PUBLISHER, "reason", "exception")
          .increment();
    }
  }
}
