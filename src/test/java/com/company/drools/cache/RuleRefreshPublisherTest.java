package com.company.drools.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Unit tests for {@link RuleRefreshPublisher}. */
@ExtendWith(MockitoExtension.class)
class RuleRefreshPublisherTest {

  private static final String INSTANCE_ID = "test-instance-uuid";
  private static final String CHANNEL = "drools:rule:events";

  @Mock private StringRedisTemplate redisTemplate;

  private CircuitBreaker circuitBreaker;
  private MeterRegistry meterRegistry;
  private ObjectMapper objectMapper;
  private RuleRefreshPublisher publisher;

  @BeforeEach
  void setUp() {
    circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
    meterRegistry = new SimpleMeterRegistry();
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    publisher =
        new RuleRefreshPublisher(
            redisTemplate, circuitBreaker, INSTANCE_ID, CHANNEL, meterRegistry, objectMapper);
  }

  @Test
  @DisplayName("publishRefresh sends JSON event with RULE_REFRESHED type and instance ID")
  void publishRefreshSendsEvent() throws Exception {
    publisher.publishRefresh("pricing.simple");

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(redisTemplate).convertAndSend(payloadCaptor.capture(), payloadCaptor.capture());

    String channelArg = payloadCaptor.getAllValues().get(0);
    String payload = payloadCaptor.getAllValues().get(1);
    assertThat(channelArg).isEqualTo(CHANNEL);

    RefreshEvent decoded = objectMapper.readValue(payload, RefreshEvent.class);
    assertThat(decoded.event()).isEqualTo(RefreshEvent.EventType.RULE_REFRESHED);
    assertThat(decoded.ruleId()).isEqualTo("pricing.simple");
    assertThat(decoded.sourceInstanceId()).isEqualTo(INSTANCE_ID);
    assertThat(decoded.timestamp()).isNotNull();
    assertThat(meterRegistry.counter("drools.refresh.published", "event", "RULE_REFRESHED").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("publishBulkRefresh sends event with null ruleId and BULK type")
  void publishBulkSendsEvent() throws Exception {
    publisher.publishBulkRefresh();

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(redisTemplate).convertAndSend(anyString(), payloadCaptor.capture());

    RefreshEvent decoded = objectMapper.readValue(payloadCaptor.getValue(), RefreshEvent.class);
    assertThat(decoded.event()).isEqualTo(RefreshEvent.EventType.RULE_REFRESHED_BULK);
    assertThat(decoded.ruleId()).isNull();
    assertThat(
            meterRegistry
                .counter("drools.refresh.published", "event", "RULE_REFRESHED_BULK")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("publishDelete sends DELETED event with ruleId")
  void publishDeleteSendsEvent() throws Exception {
    publisher.publishDelete("pricing.old");

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(redisTemplate).convertAndSend(anyString(), payloadCaptor.capture());

    RefreshEvent decoded = objectMapper.readValue(payloadCaptor.getValue(), RefreshEvent.class);
    assertThat(decoded.event()).isEqualTo(RefreshEvent.EventType.RULE_DELETED);
    assertThat(decoded.ruleId()).isEqualTo("pricing.old");
  }

  @Test
  @DisplayName("Circuit breaker open: no publish attempted; failure metric incremented")
  void circuitBreakerOpen() {
    CircuitBreaker openCb =
        CircuitBreaker.of(
            "open",
            CircuitBreakerConfig.custom().slidingWindowSize(1).failureRateThreshold(1.0f).build());
    openCb.transitionToOpenState();
    publisher =
        new RuleRefreshPublisher(
            redisTemplate, openCb, INSTANCE_ID, CHANNEL, meterRegistry, objectMapper);

    publisher.publishRefresh("any.rule");

    verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    assertThat(
            meterRegistry
                .counter(
                    "drools.refresh.failed", "layer", "publisher", "reason", "circuit_breaker_open")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("RedisTemplate throws: caught, logged, doesn't propagate; failure metric")
  void redisFailureSwallowed() {
    when(redisTemplate.convertAndSend(anyString(), anyString()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("conn refused"));

    // No exception expected
    publisher.publishRefresh("pricing.simple");

    assertThat(
            meterRegistry
                .counter("drools.refresh.failed", "layer", "publisher", "reason", "exception")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("publishRefresh increments published metric only on success")
  void publishedMetricOnlyOnSuccess() {
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("x"))
        .when(redisTemplate)
        .convertAndSend(anyString(), anyString());

    publisher.publishRefresh("any.rule");

    assertThat(meterRegistry.counter("drools.refresh.published", "event", "RULE_REFRESHED").count())
        .isZero();
  }
}
