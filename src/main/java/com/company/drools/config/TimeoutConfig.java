package com.company.drools.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** Configuration for various timeout settings across the application */
@Configuration
public class TimeoutConfig {

  @Value("${drools.timeout.http.connection:10}")
  private int httpConnectionTimeoutSeconds;

  @Value("${drools.timeout.http.read:30}")
  private int httpReadTimeoutSeconds;

  @Value("${drools.timeout.rule-execution:30}")
  private int ruleExecutionTimeoutSeconds;

  @Value("${drools.timeout.storage.operation:60}")
  private int storageOperationTimeoutSeconds;

  @Value("${drools.timeout.cache.operation:5}")
  private int cacheOperationTimeoutSeconds;

  /** Configure RestTemplate with timeouts for external HTTP calls */
  @Bean("timeoutRestTemplate")
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
        .connectTimeout(Duration.ofSeconds(httpConnectionTimeoutSeconds))
        .readTimeout(Duration.ofSeconds(httpReadTimeoutSeconds))
        .build();
  }

  /** Get rule execution timeout in seconds */
  public int getRuleExecutionTimeoutSeconds() {
    return ruleExecutionTimeoutSeconds;
  }

  /** Get storage operation timeout in seconds */
  public int getStorageOperationTimeoutSeconds() {
    return storageOperationTimeoutSeconds;
  }

  /** Get cache operation timeout in seconds */
  public int getCacheOperationTimeoutSeconds() {
    return cacheOperationTimeoutSeconds;
  }

  /** Get HTTP connection timeout in seconds */
  public int getHttpConnectionTimeoutSeconds() {
    return httpConnectionTimeoutSeconds;
  }

  /** Get HTTP read timeout in seconds */
  public int getHttpReadTimeoutSeconds() {
    return httpReadTimeoutSeconds;
  }
}
