package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetricsConfig")
class MetricsConfigTest {

  private MetricsConfig metricsConfig;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Exception {
    metricsConfig = new MetricsConfig();
    meterRegistry = new SimpleMeterRegistry();

    // Set @Value fields via reflection
    setField(metricsConfig, "metricsNamespace", "TestNamespace");
    setField(metricsConfig, "cloudWatchEnabled", false);
    setField(metricsConfig, "applicationName", "test-app");
    setField(metricsConfig, "environment", "test");
  }

  @Test
  @DisplayName("ruleExecutionTimer creates timer with correct tags")
  void testRuleExecutionTimer() {
    Timer timer = metricsConfig.ruleExecutionTimer(meterRegistry);

    assertThat(timer).isNotNull();
    assertThat(timer.getId().getName()).isEqualTo("drools.rule.execution.time");
    assertThat(timer.getId().getTag("application")).isEqualTo("test-app");
    assertThat(timer.getId().getTag("environment")).isEqualTo("test");
  }

  @Test
  @DisplayName("ruleExecutionSuccessCounter creates counter with correct tags")
  void testRuleExecutionSuccessCounter() {
    Counter counter = metricsConfig.ruleExecutionSuccessCounter(meterRegistry);

    assertThat(counter).isNotNull();
    assertThat(counter.getId().getName()).isEqualTo("drools.rule.execution.success");
    assertThat(counter.getId().getTag("application")).isEqualTo("test-app");
  }

  @Test
  @DisplayName("ruleExecutionErrorCounter creates counter with correct tags")
  void testRuleExecutionErrorCounter() {
    Counter counter = metricsConfig.ruleExecutionErrorCounter(meterRegistry);

    assertThat(counter).isNotNull();
    assertThat(counter.getId().getName()).isEqualTo("drools.rule.execution.error");
  }

  @Test
  @DisplayName("cacheHitCounter creates counter")
  void testCacheHitCounter() {
    Counter counter = metricsConfig.cacheHitCounter(meterRegistry);

    assertThat(counter).isNotNull();
    assertThat(counter.getId().getName()).isEqualTo("drools.cache.hits");
  }

  @Test
  @DisplayName("cacheMissCounter creates counter")
  void testCacheMissCounter() {
    Counter counter = metricsConfig.cacheMissCounter(meterRegistry);

    assertThat(counter).isNotNull();
    assertThat(counter.getId().getName()).isEqualTo("drools.cache.misses");
  }

  @Test
  @DisplayName("cacheEvictionCounter creates counter")
  void testCacheEvictionCounter() {
    Counter counter = metricsConfig.cacheEvictionCounter(meterRegistry);

    assertThat(counter).isNotNull();
    assertThat(counter.getId().getName()).isEqualTo("drools.cache.evictions");
  }

  @Test
  @DisplayName("cacheSizeGauge creates gauge")
  void testCacheSizeGauge() {
    Gauge gauge = metricsConfig.cacheSizeGauge(meterRegistry);

    assertThat(gauge).isNotNull();
    assertThat(gauge.getId().getName()).isEqualTo("drools.cache.size");
    assertThat(gauge.value()).isEqualTo(0.0);

    // Update the atomic integer and verify gauge reflects it
    metricsConfig.getCacheSize().set(42);
    assertThat(gauge.value()).isEqualTo(42.0);
  }

  @Test
  @DisplayName("loadedRulesGauge creates gauge")
  void testLoadedRulesGauge() {
    Gauge gauge = metricsConfig.loadedRulesGauge(meterRegistry);

    assertThat(gauge).isNotNull();
    assertThat(gauge.getId().getName()).isEqualTo("drools.rules.loaded");

    metricsConfig.getLoadedRulesCount().set(10);
    assertThat(gauge.value()).isEqualTo(10.0);
  }

  @Test
  @DisplayName("apiRequestCounter creates counter")
  void testApiRequestCounter() {
    Counter counter = metricsConfig.apiRequestCounter(meterRegistry);

    assertThat(counter).isNotNull();
    assertThat(counter.getId().getName()).isEqualTo("drools.api.requests");
  }

  @Test
  @DisplayName("apiErrorCounter creates counter")
  void testApiErrorCounter() {
    Counter counter = metricsConfig.apiErrorCounter(meterRegistry);

    assertThat(counter).isNotNull();
    assertThat(counter.getId().getName()).isEqualTo("drools.api.errors");
  }

  @Test
  @DisplayName("storageOperationTimer creates timer")
  void testStorageOperationTimer() {
    Timer timer = metricsConfig.storageOperationTimer(meterRegistry);

    assertThat(timer).isNotNull();
    assertThat(timer.getId().getName()).isEqualTo("drools.storage.operation.time");
  }

  @Test
  @DisplayName("getCacheSize returns atomic integer")
  void testGetCacheSize() {
    assertThat(metricsConfig.getCacheSize()).isNotNull();
    assertThat(metricsConfig.getCacheSize().get()).isZero();
  }

  @Test
  @DisplayName("getLoadedRulesCount returns atomic integer")
  void testGetLoadedRulesCount() {
    assertThat(metricsConfig.getLoadedRulesCount()).isNotNull();
    assertThat(metricsConfig.getLoadedRulesCount().get()).isZero();
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
