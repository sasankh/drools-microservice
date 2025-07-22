package com.company.drools.config;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * Configuration for Micrometer metrics collection and CloudWatch integration. Provides custom
 * metrics beans for rule execution, cache performance, and error tracking.
 */
@Configuration
public class MetricsConfig {

  @Value("${management.metrics.export.cloudwatch.namespace:DroolsEngine}")
  private String metricsNamespace;

  @Value("${management.metrics.export.cloudwatch.enabled:false}")
  private boolean cloudWatchEnabled;

  @Value("${spring.application.name:drools-rule-engine}")
  private String applicationName;

  @Value("${SPRING_PROFILES_ACTIVE:local}")
  private String environment;

  /** CloudWatch metrics registry for AWS integration. Only enabled when cloudwatch.enabled=true. */
  @Bean
  @ConditionalOnProperty(
      value = "management.metrics.export.cloudwatch.enabled",
      havingValue = "true")
  public CloudWatchMeterRegistry cloudWatchMeterRegistry() {
    CloudWatchConfig cloudWatchConfig =
        new CloudWatchConfig() {
          @Override
          public String get(String key) {
            return null; // Accept defaults
          }

          @Override
          public String namespace() {
            return metricsNamespace;
          }

          @Override
          public Duration step() {
            return Duration.ofMinutes(1); // Publish metrics every minute
          }
        };

    return new CloudWatchMeterRegistry(
        cloudWatchConfig, Clock.SYSTEM, CloudWatchAsyncClient.create());
  }

  /** Timer for measuring rule execution duration. Tagged by rule ID for granular monitoring. */
  @Bean
  public Timer ruleExecutionTimer(MeterRegistry meterRegistry) {
    return Timer.builder("drools.rule.execution.time")
        .description("Time taken to execute a rule")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /**
   * Counter for successful rule executions. Tagged by rule ID for tracking individual rule
   * performance.
   */
  @Bean
  public Counter ruleExecutionSuccessCounter(MeterRegistry meterRegistry) {
    return Counter.builder("drools.rule.execution.success")
        .description("Number of successful rule executions")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Counter for failed rule executions. Tagged by rule ID and error type for troubleshooting. */
  @Bean
  public Counter ruleExecutionErrorCounter(MeterRegistry meterRegistry) {
    return Counter.builder("drools.rule.execution.error")
        .description("Number of failed rule executions")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Counter for cache hits. Measures cache effectiveness. */
  @Bean
  public Counter cacheHitCounter(MeterRegistry meterRegistry) {
    return Counter.builder("drools.cache.hits")
        .description("Number of cache hits")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Counter for cache misses. Measures cache effectiveness. */
  @Bean
  public Counter cacheMissCounter(MeterRegistry meterRegistry) {
    return Counter.builder("drools.cache.misses")
        .description("Number of cache misses")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Counter for cache evictions. Monitors cache pressure. */
  @Bean
  public Counter cacheEvictionCounter(MeterRegistry meterRegistry) {
    return Counter.builder("drools.cache.evictions")
        .description("Number of cache evictions")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Atomic integer to track cache size for gauge metrics. */
  private final AtomicInteger cacheSize = new AtomicInteger(0);

  /** Atomic integer to track loaded rules for gauge metrics. */
  private final AtomicInteger loadedRulesCount = new AtomicInteger(0);

  /** Gauge for current cache size. Monitors cache utilization. */
  @Bean
  public Gauge cacheSizeGauge(MeterRegistry meterRegistry) {
    return Gauge.builder("drools.cache.size", cacheSize, AtomicInteger::doubleValue)
        .description("Current number of items in cache")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Gauge for number of loaded rules. Monitors rule engine state. */
  @Bean
  public Gauge loadedRulesGauge(MeterRegistry meterRegistry) {
    return Gauge.builder("drools.rules.loaded", loadedRulesCount, AtomicInteger::doubleValue)
        .description("Number of rules currently loaded")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Get cache size atomic integer for updating metrics. */
  public AtomicInteger getCacheSize() {
    return cacheSize;
  }

  /** Get loaded rules count atomic integer for updating metrics. */
  public AtomicInteger getLoadedRulesCount() {
    return loadedRulesCount;
  }

  /** Counter for API requests by endpoint. Measures API usage patterns. */
  @Bean
  public Counter apiRequestCounter(MeterRegistry meterRegistry) {
    return Counter.builder("drools.api.requests")
        .description("Number of API requests")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Counter for API errors by endpoint and status code. Measures API error rates. */
  @Bean
  public Counter apiErrorCounter(MeterRegistry meterRegistry) {
    return Counter.builder("drools.api.errors")
        .description("Number of API errors")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }

  /** Timer for storage operations (S3 fetch time). Measures storage backend performance. */
  @Bean
  public Timer storageOperationTimer(MeterRegistry meterRegistry) {
    return Timer.builder("drools.storage.operation.time")
        .description("Time taken for storage operations")
        .tag("application", applicationName)
        .tag("environment", environment)
        .register(meterRegistry);
  }
}
