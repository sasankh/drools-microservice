package com.company.drools.config;

import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.StorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class RuleLoadingConfig {

  private static final Logger log = LoggerFactory.getLogger(RuleLoadingConfig.class);

  @Bean
  public ApplicationRunner loadRulesOnStartup(DroolsEngineService droolsEngineService, 
                                             StorageFactory storageFactory) {
    return args -> {
      log.info("Loading rules on application startup...");
      
      try {
        RuleStorage ruleStorage = storageFactory.createRuleStorage();
        List<Rule> rules = ruleStorage.getAllRules();
        log.info("Found {} rules to load", rules.size());
        
        boolean success = droolsEngineService.loadRules(rules);
        
        if (success) {
          log.info("Successfully loaded {} rules. Active rules: {}", 
              rules.size(), droolsEngineService.getActiveRulesCount());
        } else {
          log.error("Failed to load some or all rules");
        }
        
      } catch (Exception e) {
        log.error("Failed to load rules on startup", e);
        // Don't fail the startup - continue with empty rule set
      }
    };
  }
}