package com.company.drools.config;

import com.company.drools.core.engine.RuleRefreshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuleLoadingConfig {

  private static final Logger log = LoggerFactory.getLogger(RuleLoadingConfig.class);

  /**
   * Loads every rule from storage once the application has started. Spring publishes readiness
   * ACCEPTING_TRAFFIC only after runners complete, and the {@code rules} health indicator stays
   * DOWN until a full load succeeds, so traffic is not routed to an instance with no rules.
   *
   * <p>A failed storage read no longer pretends to be a healthy empty start: the instance stays up
   * but NOT ready (and keeps retrying via reconcile / admin refresh), or, with {@code
   * drools.startup.fail-on-load-error=true}, exits.
   */
  @Bean
  public ApplicationRunner loadRulesOnStartup(
      RuleRefreshService refreshService,
      @Value("${drools.startup.fail-on-load-error:false}") boolean failOnLoadError) {
    return args -> {
      log.info("Loading rules on application startup...");
      RuleRefreshService.BulkResult result =
          refreshService.refreshAll(
              RuleRefreshService.Source.STARTUP, RuleRefreshService.ReadMode.CACHED);

      switch (result.status()) {
        case LOADED -> {
          var report = result.report();
          if (report.allSucceeded()) {
            log.info("Startup load complete: {} rules loaded", report.loaded().size());
          } else {
            log.error(
                "Startup load: {} rules loaded, {} failed to compile: {}",
                report.loaded().size(),
                report.failed().size(),
                report.failed().keySet());
          }
        }
        case STORAGE_FAILED, REJECTED_EMPTY -> {
          log.error("Startup load failed; instance is NOT ready: {}", result.error());
          if (failOnLoadError) {
            throw new IllegalStateException(
                "Startup rule load failed (drools.startup.fail-on-load-error=true): "
                    + result.error());
          }
        }
      }
    };
  }
}
