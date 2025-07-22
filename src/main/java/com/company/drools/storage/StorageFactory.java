package com.company.drools.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class StorageFactory {

  private static final Logger log = LoggerFactory.getLogger(StorageFactory.class);

  private final ApplicationContext applicationContext;

  @Value("${drools.rule-source:local}")
  private String ruleSource;

  public StorageFactory(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  public RuleStorage createRuleStorage() {
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