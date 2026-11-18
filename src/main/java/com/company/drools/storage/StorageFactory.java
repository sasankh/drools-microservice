package com.company.drools.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class StorageFactory {

  private static final Logger log = LoggerFactory.getLogger(StorageFactory.class);

  private final ApplicationContext applicationContext;

  @Value("${drools.rule-source:local}")
  private String ruleSource;

  @Value("${redis.enabled:false}")
  private boolean redisEnabled;

  /** The storage built on first use; reused afterwards (it only depends on fixed config). */
  private volatile RuleStorage resolved;

  public StorageFactory(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Returns the active {@link RuleStorage}. When {@code redis.enabled=true}, the base storage
   * (S3/local/memory) is transparently wrapped with {@link RedisCachedRuleStorage}, giving
   * read-through caching and write-through invalidation. Otherwise the base storage is returned
   * directly.
   *
   * <p>Built once on first call and reused, so frequent callers (health checks, every refresh)
   * don't re-wire the decorator and log on each call.
   *
   * @throws IllegalStateException if {@code drools.rule-source} is not one of local, file, s3
   */
  public RuleStorage createRuleStorage() {
    RuleStorage current = resolved;
    if (current == null) {
      synchronized (this) {
        current = resolved;
        if (current == null) {
          current = build();
          resolved = current;
        }
      }
    }
    return current;
  }

  private RuleStorage build() {
    RuleStorage base = createBaseStorage();
    if (!redisEnabled) {
      return base;
    }
    try {
      RedisCachedRuleStorage decorator = applicationContext.getBean(RedisCachedRuleStorage.class);
      decorator.setDelegate(base);
      log.info(
          "Redis caching enabled: wrapping {} with RedisCachedRuleStorage",
          base.getClass().getSimpleName());
      return decorator;
    } catch (NoSuchBeanDefinitionException _) {
      log.warn(
          "redis.enabled=true but RedisCachedRuleStorage bean is missing — falling back to {}",
          base.getClass().getSimpleName());
      return base;
    }
  }

  private RuleStorage createBaseStorage() {
    log.info("Creating rule storage for source: {}", ruleSource);

    return switch (ruleSource.toLowerCase()) {
      case "local" -> {
        log.info("Using in-memory rule storage");
        yield applicationContext.getBean(InMemoryRuleStorageAdapter.class);
      }
      case "file" -> {
        log.info("Using local file storage");
        yield applicationContext.getBean("localFileStorage", RuleStorage.class);
      }
      case "s3" -> {
        log.info("Using S3 rule storage");
        yield applicationContext.getBean("s3RuleStorage", RuleStorage.class);
      }
      default ->
          // Fail fast: silently falling back to the in-memory demo rules would serve the wrong
          // rules while health reports UP.
          throw new IllegalStateException(
              "Unknown drools.rule-source '"
                  + ruleSource
                  + "' (RULE_SOURCE). Expected one of: local, file, s3");
    };
  }
}
