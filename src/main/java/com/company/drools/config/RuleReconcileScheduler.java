package com.company.drools.config;

import com.company.drools.core.engine.RuleRefreshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic reconcile ({@code drools.refresh.auto-enabled=true}, every {@code
 * drools.refresh.interval-minutes}): re-reads storage directly and applies only what changed.
 *
 * <p>Pub/sub refresh events are fire-and-forget; an instance that misses one (Redis blip, reconnect
 * window, restart) would otherwise stay stale until the next manual refresh. Reconcile bounds that
 * staleness to one interval, and also picks up changes made directly in the bucket. Reconcile never
 * publishes events, so instances can't trigger each other in a loop.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "drools.refresh.auto-enabled", havingValue = "true")
public class RuleReconcileScheduler {

  private static final Logger log = LoggerFactory.getLogger(RuleReconcileScheduler.class);

  private final RuleRefreshService refreshService;

  public RuleReconcileScheduler(RuleRefreshService refreshService) {
    this.refreshService = refreshService;
    log.info("Scheduled rule reconcile enabled");
  }

  @Scheduled(
      initialDelayString = "#{${drools.refresh.interval-minutes:5} * 60000}",
      fixedDelayString = "#{${drools.refresh.interval-minutes:5} * 60000}")
  public void reconcile() {
    RuleRefreshService.BulkResult result = refreshService.reconcile();
    switch (result.status()) {
      case LOADED -> {
        var report = result.report();
        if (report.changedAnything() || !report.allSucceeded()) {
          log.info(
              "Reconcile: {} updated, {} unchanged, {} removed, {} failed",
              report.loaded().size(),
              report.unchanged().size(),
              report.removed().size(),
              report.failed().size());
        } else {
          log.debug("Reconcile: no changes ({} rules)", report.unchanged().size());
        }
      }
      case STORAGE_FAILED, REJECTED_EMPTY -> log.warn("Reconcile skipped: {}", result.error());
    }
  }
}
