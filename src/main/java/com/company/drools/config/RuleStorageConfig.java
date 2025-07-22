package com.company.drools.config;

import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.StorageFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuleStorageConfig {

  @Bean
  @org.springframework.context.annotation.Primary
  public RuleStorage ruleStorage(StorageFactory storageFactory) {
    return storageFactory.createRuleStorage();
  }
}
