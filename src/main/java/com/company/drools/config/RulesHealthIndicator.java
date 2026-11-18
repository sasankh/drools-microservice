package com.company.drools.config;

import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleLoadState;
import com.company.drools.core.model.RuleMetadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator {@code rules}: can this instance serve rules? Part of the readiness group.
 *
 * <p>DOWN when the startup load has not succeeded yet (still loading, or storage unreadable), or
 * when no rule is compiled (unless {@code drools.refresh.allow-empty=true}). Individual rule IDs
 * that failed to compile are reported in the details but do not make the instance DOWN: every other
 * rule ID still serves, and taking every replica out of rotation for one bad file would turn a
 * single-rule problem into an outage.
 */
@Component("rules")
public class RulesHealthIndicator implements HealthIndicator {

  private static final int MAX_LISTED_ERRORS = 20;

  private final DroolsEngineService engine;
  private final RuleLoadState loadState;
  private final boolean allowEmpty;

  public RulesHealthIndicator(
      DroolsEngineService engine,
      RuleLoadState loadState,
      @Value("${drools.refresh.allow-empty:false}") boolean allowEmpty) {
    this.engine = engine;
    this.loadState = loadState;
    this.allowEmpty = allowEmpty;
  }

  @Override
  public Health health() {
    int loaded = engine.getLoadedRulesCount();
    List<String> errorIds =
        engine.getAllRuleMetadata().entrySet().stream()
            .filter(
                e ->
                    e.getValue().getStatus() == RuleMetadata.RuleStatus.ERROR
                        || e.getValue().getErrorMessage() != null)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("phase", loadState.getPhase().name());
    details.put("loaded_rules", loaded);
    details.put("rules_with_errors", errorIds.size());
    if (!errorIds.isEmpty()) {
      details.put(
          "error_rule_ids",
          errorIds.size() > MAX_LISTED_ERRORS ? errorIds.subList(0, MAX_LISTED_ERRORS) : errorIds);
    }
    if (loadState.getLastRefreshAt() != null) {
      details.put("last_refresh_at", loadState.getLastRefreshAt().toString());
      details.put("last_refresh_source", loadState.getLastRefreshSource());
      details.put("last_refresh_result", loadState.getLastRefreshResult());
    }
    if (loadState.getLastError() != null) {
      details.put("last_error", loadState.getLastError());
    }

    boolean up = loadState.getPhase() == RuleLoadState.Phase.LOADED && (loaded > 0 || allowEmpty);
    if (!up) {
      details.put(
          "reason",
          loadState.getPhase() != RuleLoadState.Phase.LOADED
              ? "startup rule load has not succeeded (" + loadState.getPhase() + ")"
              : "no rules loaded");
    }
    return (up ? Health.up() : Health.down()).withDetails(details).build();
  }
}
