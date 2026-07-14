package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CircuitBreakerConfig")
class CircuitBreakerConfigTest {

  private CircuitBreakerConfig config;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Exception {
    config = new CircuitBreakerConfig();
    meterRegistry = new SimpleMeterRegistry();

    // Set S3 circuit breaker values
    setField(config, "s3FailureRateThreshold", 50);
    setField(config, "s3WaitDurationInOpenState", 60000L);
    setField(config, "s3SlidingWindowSize", 100);
    setField(config, "s3MinimumNumberOfCalls", 10);

    // Set Redis circuit breaker values
    setField(config, "redisFailureRateThreshold", 60);
    setField(config, "redisWaitDurationInOpenState", 30000L);
    setField(config, "redisSlidingWindowSize", 50);
    setField(config, "redisMinimumNumberOfCalls", 5);
  }

  @Test
  @DisplayName("circuitBreakerRegistry creates registry with metrics")
  void testCircuitBreakerRegistry() {
    CircuitBreakerRegistry registry = config.circuitBreakerRegistry(meterRegistry);

    assertThat(registry).isNotNull();
  }

  @Test
  @DisplayName("s3CircuitBreaker creates circuit breaker with correct config")
  void testS3CircuitBreaker() {
    CircuitBreakerRegistry registry = config.circuitBreakerRegistry(meterRegistry);
    CircuitBreaker cb = config.s3CircuitBreaker(registry);

    assertThat(cb).isNotNull();
    assertThat(cb.getName()).isEqualTo("s3");
    assertThat(cb.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
    assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(100);
    assertThat(cb.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(10);
  }

  @Test
  @DisplayName("redisCircuitBreaker creates circuit breaker with correct config")
  void testRedisCircuitBreaker() {
    CircuitBreakerRegistry registry = config.circuitBreakerRegistry(meterRegistry);
    CircuitBreaker cb = config.redisCircuitBreaker(registry);

    assertThat(cb).isNotNull();
    assertThat(cb.getName()).isEqualTo("redis");
    assertThat(cb.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(60f);
    assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(50);
    assertThat(cb.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
  }

  @Test
  @DisplayName("s3CircuitBreaker starts in CLOSED state")
  void testS3CircuitBreakerInitialState() {
    CircuitBreakerRegistry registry = config.circuitBreakerRegistry(meterRegistry);
    CircuitBreaker cb = config.s3CircuitBreaker(registry);

    assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("redisCircuitBreaker starts in CLOSED state")
  void testRedisCircuitBreakerInitialState() {
    CircuitBreakerRegistry registry = config.circuitBreakerRegistry(meterRegistry);
    CircuitBreaker cb = config.redisCircuitBreaker(registry);

    assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("redisCircuitBreaker records the 5 expected exception classes as failures")
  void testRedisCircuitBreakerRecordsExpectedExceptions() {
    // Guards against the Phase 9.4 bug where Lettuce's RedisCommandTimeoutException
    // — translated to org.springframework.dao.QueryTimeoutException by Spring's
    // LettuceExceptionConverter — was not in recordExceptions, so the CB classified
    // timeouts as kind=successful and never tripped during a Redis outage.
    // QueryTimeoutException is a SIBLING of RedisSystemException under DataAccessException,
    // not a subtype, so it must be listed explicitly.
    CircuitBreakerRegistry registry = config.circuitBreakerRegistry(meterRegistry);
    CircuitBreaker cb = config.redisCircuitBreaker(registry);

    java.util.function.Predicate<Throwable> recordPredicate =
        cb.getCircuitBreakerConfig().getRecordExceptionPredicate();

    assertThat(
            recordPredicate.test(
                new org.springframework.data.redis.RedisConnectionFailureException("test")))
        .as("RedisConnectionFailureException should count as CB failure")
        .isTrue();
    assertThat(
            recordPredicate.test(
                new org.springframework.data.redis.RedisSystemException(
                    "test", new RuntimeException())))
        .as("RedisSystemException should count as CB failure")
        .isTrue();
    assertThat(
            recordPredicate.test(
                new org.springframework.dao.QueryTimeoutException("Redis command timed out")))
        .as(
            "QueryTimeoutException (Lettuce timeout via LettuceExceptionConverter) should count as CB failure")
        .isTrue();
    assertThat(recordPredicate.test(new java.util.concurrent.TimeoutException("test")))
        .as("java.util.concurrent.TimeoutException should count as CB failure")
        .isTrue();
    assertThat(recordPredicate.test(new java.net.ConnectException("test")))
        .as("java.net.ConnectException should count as CB failure")
        .isTrue();
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
