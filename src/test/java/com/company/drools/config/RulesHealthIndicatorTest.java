package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleLoadState;
import com.company.drools.core.model.RuleMetadata;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

@DisplayName("RulesHealthIndicator")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RulesHealthIndicatorTest {

  @Mock private DroolsEngineService engine;
  @Mock private RuleLoadState loadState;

  private Health health(boolean allowEmpty) {
    return new RulesHealthIndicator(engine, loadState, allowEmpty).health();
  }

  private static RuleMetadata active() {
    return new RuleMetadata(
        "1", LocalDateTime.now(), LocalDateTime.now(), RuleMetadata.RuleStatus.ACTIVE, null, 0, 0);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = RuleLoadState.Phase.class,
      names = {"NOT_LOADED", "FAILED"})
  @DisplayName("DOWN while the startup load has not succeeded, even with rules compiled")
  void downUntilLoaded(RuleLoadState.Phase phase) {
    when(loadState.getPhase()).thenReturn(phase);
    when(engine.getLoadedRulesCount()).thenReturn(5);

    Health health = health(false);

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("phase", phase.name())
        .containsEntry("loaded_rules", 5);
    assertThat((String) health.getDetails().get("reason"))
        .contains("startup rule load has not succeeded")
        .contains(phase.name());
  }

  @Test
  @DisplayName("DOWN when loaded but 0 rules compiled")
  void downWithZeroRules() {
    when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.LOADED);
    when(engine.getLoadedRulesCount()).thenReturn(0);

    Health health = health(false);

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "no rules loaded");
  }

  @Test
  @DisplayName("allow-empty=true: UP with 0 rules once loaded")
  void allowEmptyUpWithZero() {
    when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.LOADED);
    when(engine.getLoadedRulesCount()).thenReturn(0);

    Health health = health(true);

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).doesNotContainKey("reason");
  }

  @Test
  @DisplayName("allow-empty=true does not override a failed startup load")
  void allowEmptyStillDownWhenNotLoaded() {
    when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.FAILED);
    when(engine.getLoadedRulesCount()).thenReturn(0);

    assertThat(health(true).getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("UP when loaded with rules; last-refresh details included")
  void upWhenLoaded() {
    Instant at = Instant.parse("2026-11-18T10:00:00Z");
    when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.LOADED);
    when(loadState.getLastRefreshAt()).thenReturn(at);
    when(loadState.getLastRefreshSource()).thenReturn("startup");
    when(loadState.getLastRefreshResult()).thenReturn("ok");
    when(engine.getLoadedRulesCount()).thenReturn(3);
    when(engine.getAllRuleMetadata())
        .thenReturn(Map.of("a", active(), "b", active(), "c", active()));

    Health health = health(false);

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("phase", "LOADED")
        .containsEntry("loaded_rules", 3)
        .containsEntry("rules_with_errors", 0)
        .containsEntry("last_refresh_at", at.toString())
        .containsEntry("last_refresh_source", "startup")
        .containsEntry("last_refresh_result", "ok")
        .doesNotContainKeys("error_rule_ids", "last_error", "reason");
  }

  @Test
  @DisplayName("per-ID errors are listed (sorted, max 20) but the instance stays UP")
  void errorIdsListedButUp() {
    when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.LOADED);
    when(loadState.getLastError()).thenReturn("compile error in r07");
    when(engine.getLoadedRulesCount()).thenReturn(1);
    Map<String, RuleMetadata> meta = new LinkedHashMap<>();
    meta.put("ok.rule", active());
    // 24 broken IDs that never compiled (ERROR) ...
    for (int i = 24; i >= 1; i--) {
      meta.put(String.format("r%02d", i), RuleMetadata.createNew().withError("bad"));
    }
    // ... and one whose update was rejected while the previous version keeps serving
    meta.put("a.rejected.update", active().withFailedUpdate("bad update"));
    when(engine.getAllRuleMetadata()).thenReturn(meta);

    Health health = health(false);

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("rules_with_errors", 25);
    @SuppressWarnings("unchecked")
    List<String> listed = (List<String>) health.getDetails().get("error_rule_ids");
    assertThat(listed).hasSize(20);
    assertThat(listed.get(0)).isEqualTo("a.rejected.update");
    assertThat(listed.subList(1, 4)).containsExactly("r01", "r02", "r03");
    assertThat(listed).doesNotContain("ok.rule", "r24");
    assertThat(health.getDetails()).containsEntry("last_error", "compile error in r07");
  }

  @Test
  @DisplayName("a few errors are all listed")
  void fewErrorsAllListed() {
    when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.LOADED);
    when(engine.getLoadedRulesCount()).thenReturn(1);
    when(engine.getAllRuleMetadata())
        .thenReturn(Map.of("ok", active(), "bad", RuleMetadata.createNew().withError("x")));

    @SuppressWarnings("unchecked")
    List<String> listed = (List<String>) health(false).getDetails().get("error_rule_ids");
    assertThat(listed).containsExactly("bad");
  }
}
