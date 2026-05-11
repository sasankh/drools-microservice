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

  public StorageFactory(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Returns the active {@link RuleStorage}. When {@code redis.enabled=true}, the base storage
   * (S3/local/memory) is transparently wrapped with {@link RedisCachedRuleStorage}, giving
   * read-through caching and write-through invalidation. Otherwise the base storage is returned
   * directly.
   *
   * <p>This method is called from multiple sites ({@code RuleLoadingConfig}, {@code
   * AdminController}) — the underlying beans are singletons so repeated calls are safe and
   * idempotent.
   */
  public RuleStorage createRuleStorage() {
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
      default -> {
        log.warn("Unknown rule source: {}, defaulting to in-memory storage", ruleSource);
        yield applicationContext.getBean(InMemoryRuleStorageAdapter.class);
      }
    };
  }
}
