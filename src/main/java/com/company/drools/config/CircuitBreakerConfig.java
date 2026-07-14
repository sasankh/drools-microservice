package com.company.drools.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Circuit breaker configuration for external service calls */
@Configuration
public class CircuitBreakerConfig {

  private static final Logger log = LoggerFactory.getLogger(CircuitBreakerConfig.class);

  // S3 Circuit Breaker Configuration
  @Value("${drools.circuit-breaker.s3.failure-rate-threshold:50}")
  private int s3FailureRateThreshold;

  @Value("${drools.circuit-breaker.s3.wait-duration-in-open-state:60000}")
  private long s3WaitDurationInOpenState;

  @Value("${drools.circuit-breaker.s3.sliding-window-size:100}")
  private int s3SlidingWindowSize;

  @Value("${drools.circuit-breaker.s3.minimum-number-of-calls:10}")
  private int s3MinimumNumberOfCalls;

  // Redis Circuit Breaker Configuration
  @Value("${drools.circuit-breaker.redis.failure-rate-threshold:60}")
  private int redisFailureRateThreshold;

  @Value("${drools.circuit-breaker.redis.wait-duration-in-open-state:30000}")
  private long redisWaitDurationInOpenState;

  @Value("${drools.circuit-breaker.redis.sliding-window-size:50}")
  private int redisSlidingWindowSize;

  @Value("${drools.circuit-breaker.redis.minimum-number-of-calls:5}")
  private int redisMinimumNumberOfCalls;

  /** Circuit breaker registry with metrics integration */
  @Bean
  public CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

    // Register metrics with Micrometer
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);

    return registry;
  }

  /** Circuit breaker for S3 operations */
  @Bean
  public CircuitBreaker s3CircuitBreaker(CircuitBreakerRegistry registry) {
    io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
            .failureRateThreshold(s3FailureRateThreshold)
            .waitDurationInOpenState(Duration.ofMillis(s3WaitDurationInOpenState))
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(s3SlidingWindowSize)
            .minimumNumberOfCalls(s3MinimumNumberOfCalls)
            .slowCallRateThreshold(70) // 70% slow calls threshold
            .slowCallDurationThreshold(
                Duration.ofSeconds(5)) // Calls slower than 5s are considered slow
            .permittedNumberOfCallsInHalfOpenState(5)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordExceptions(
                software.amazon.awssdk.core.exception.SdkException.class,
                java.util.concurrent.TimeoutException.class,
                java.io.IOException.class)
            .ignoreExceptions(
                software.amazon.awssdk.services.s3.model.NoSuchKeyException
                    .class // Don't break on missing rules
                )
            .build();

    CircuitBreaker circuitBreaker = registry.circuitBreaker("s3", config);

    // Add event listeners
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                log.info(
                    "S3 Circuit breaker state transition: {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));

    circuitBreaker
        .getEventPublisher()
        .onCallNotPermitted(event -> log.warn("S3 Circuit breaker call not permitted"));

    log.info(
        "S3 Circuit Breaker configured: failure-rate={}%, wait-duration={}ms, window-size={}",
        s3FailureRateThreshold, s3WaitDurationInOpenState, s3SlidingWindowSize);

    return circuitBreaker;
  }

  /** Circuit breaker for Redis operations */
  @Bean
  public CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
    io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
            .failureRateThreshold(redisFailureRateThreshold)
            .waitDurationInOpenState(Duration.ofMillis(redisWaitDurationInOpenState))
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(redisSlidingWindowSize)
            .minimumNumberOfCalls(redisMinimumNumberOfCalls)
            .slowCallRateThreshold(80) // 80% slow calls threshold
            .slowCallDurationThreshold(
                Duration.ofSeconds(2)) // Calls slower than 2s are considered slow
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            // Lettuce throws io.lettuce.core.RedisCommandTimeoutException on its
            // commandTimeout; Spring's LettuceExceptionConverter translates that to
            // org.springframework.dao.QueryTimeoutException, NOT to RedisSystemException
            // (they're siblings under DataAccessException, not parent-child). Without
            // QueryTimeoutException in this allow-list, Resilience4j classifies the
            // throw as kind=successful and the CB never trips during a Redis outage
            // — see Phase 9.4 follow-up plan in .ai-workspace/project-plans/.
            .recordExceptions(
                org.springframework.data.redis.RedisConnectionFailureException.class,
                org.springframework.data.redis.RedisSystemException.class,
                org.springframework.dao.QueryTimeoutException.class,
                java.util.concurrent.TimeoutException.class,
                java.net.ConnectException.class)
            .build();

    CircuitBreaker circuitBreaker = registry.circuitBreaker("redis", config);

    // Add event listeners
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                log.info(
                    "Redis Circuit breaker state transition: {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));

    circuitBreaker
        .getEventPublisher()
        .onCallNotPermitted(event -> log.warn("Redis Circuit breaker call not permitted"));

    log.info(
        "Redis Circuit Breaker configured: failure-rate={}%, wait-duration={}ms, window-size={}",
        redisFailureRateThreshold, redisWaitDurationInOpenState, redisSlidingWindowSize);

    return circuitBreaker;
  }
}
