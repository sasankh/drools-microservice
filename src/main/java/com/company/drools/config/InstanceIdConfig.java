package com.company.drools.config;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Generates a per-JVM unique instance ID at startup. Used by the Redis pub/sub layer to deduplicate
 * self-emitted refresh events (a subscriber that receives an event it itself published should skip
 * processing).
 */
@Configuration
public class InstanceIdConfig {

  private static final Logger log = LoggerFactory.getLogger(InstanceIdConfig.class);

  @Bean
  public String droolsInstanceId() {
    String id = UUID.randomUUID().toString();
    log.info("Drools instance ID: {}", id);
    return id;
  }
}
