package com.company.drools.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fails startup in the {@code prod} profile if Redis is enabled without TLS + authentication.
 * Cached {@code Rule} data must not traverse an unauthenticated/plaintext Redis in production —
 * this is the project's own still-open finding #28, enforced here for the prod profile only.
 * Dev/docker/local keep loopback-bound plaintext Redis for convenience. (P6)
 */
@Configuration
@Profile("prod")
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")
public class RedisSecurityValidator {

  private static final Logger log = LoggerFactory.getLogger(RedisSecurityValidator.class);

  private final String redisUrl;

  public RedisSecurityValidator(@Value("${spring.data.redis.url:}") String redisUrl) {
    this.redisUrl = redisUrl;
  }

  @PostConstruct
  void validate() {
    if (redisUrl == null || redisUrl.isBlank()) {
      throw new IllegalStateException(
          "prod profile with redis.enabled=true requires REDIS_URL (spring.data.redis.url) to be set");
    }
    if (!redisUrl.startsWith("rediss://")) {
      throw new IllegalStateException(
          "prod requires a TLS Redis connection: REDIS_URL must use the rediss:// scheme. "
              + "Refusing to start.");
    }
    if (!hasCredentials(redisUrl)) {
      throw new IllegalStateException(
          "prod requires an authenticated Redis connection: REDIS_URL must include credentials "
              + "(rediss://user:password@host:port). Refusing to start.");
    }
    log.info("Redis security validated for prod profile: TLS (rediss://) + authentication present");
  }

  private static boolean hasCredentials(String url) {
    // rediss://[user]:password@host:port — require a non-empty user-info segment before '@'.
    int schemeEnd = url.indexOf("://");
    if (schemeEnd < 0) {
      return false;
    }
    int at = url.indexOf('@', schemeEnd + 3);
    if (at < 0) {
      return false;
    }
    return !url.substring(schemeEnd + 3, at).isBlank();
  }
}
