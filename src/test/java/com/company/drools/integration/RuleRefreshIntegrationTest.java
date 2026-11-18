package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.engine.CompiledRuleset;
import com.company.drools.core.engine.CompiledRulesetRegistry;
import com.company.drools.core.engine.DrlSanitizer;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleCompiler;
import com.company.drools.core.engine.RuleExecutor;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * End-to-end integration tests for rule-refresh semantics, focused on the loadOrReplaceRule path
 * (which the AdminController single-rule-refresh endpoint now calls). Compiles real DRL through the
 * real {@link RuleCompiler} and executes through {@link RuleExecutor} — no mocks for the engine,
 * compiler, or executor.
 */
@DisplayName("Rule Refresh Integration Tests")
class RuleRefreshIntegrationTest {

  private static RuleCompiler ruleCompiler;
  private static ExecutorService executorService;

  private DroolsEngineService droolsEngineService;
  private RuleExecutor ruleExecutor;
  private CompiledRulesetRegistry registry;

  @BeforeAll
  static void initShared() {
    ruleCompiler = new RuleCompiler(new DrlSanitizer());
    executorService = Executors.newFixedThreadPool(4);
  }

  @AfterAll
  static void tearDownShared() {
    executorService.shutdown();
  }

  @BeforeEach
  void setUp() {
    ruleExecutor = new RuleExecutor(executorService);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    registry = new CompiledRulesetRegistry();

    RuleStorage noOpStorage =
        new RuleStorage() {
          @Override
          public Optional<Rule> getRule(String ruleId) {
            return Optional.empty();
          }

          @Override
          public List<Rule> getAllRules() {
            return Collections.emptyList();
          }

          @Override
          public void saveRule(Rule rule) {
            // intentionally empty — test-only RuleStorage stub
          }

          @Override
          public void deleteRule(String ruleId) {
            // intentionally empty — test-only RuleStorage stub
          }

          @Override
          public boolean ruleExists(String ruleId) {
            return false;
          }

          @Override
          public void refreshCache() {
            // intentionally empty — test-only RuleStorage stub
          }

          @Override
          public void refreshRule(String ruleId) {
            // intentionally empty — test-only RuleStorage stub
          }

          @Override
          public long getTotalRuleCount() {
            return 0;
          }

          @Override
          public List<String> getRuleIds() {
            return Collections.emptyList();
          }
        };

    TimeoutConfig timeoutConfig = Mockito.mock(TimeoutConfig.class);
    Mockito.when(timeoutConfig.getRuleExecutionTimeoutSeconds()).thenReturn(30);

    droolsEngineService =
        new DroolsEngineService(
            ruleCompiler, ruleExecutor, registry, noOpStorage, timeoutConfig, meterRegistry);
  }

  /** Builds a tiny DRL whose LHS matches a Map containing a specific marker key. */
  private Rule markerRule(String ruleId, String packageName, String markerKey) {
    String content =
        String.format(
            """
            package %s

            import java.util.Map

            rule "%s"
            when
                $data : Map(this["%s"] != null)
            then
                $data.put("fired_%s", true);
            end
            """,
            packageName, ruleId, markerKey, markerKey);
    return new Rule(ruleId, content, RuleMetadata.createNew());
  }

  @Test
  @DisplayName(
      "Finding #1: single-rule refresh via loadOrReplaceRule preserves all other loaded rules")
  void singleRuleRefresh_doesNotDegradeOtherRules() {
    // Each rule fires only when its own marker key is present in the input map.
    Rule ruleA = markerRule("test.refresh.ruleA", "com.company.rules.refresh.a", "alpha");
    Rule ruleB = markerRule("test.refresh.ruleB", "com.company.rules.refresh.b", "bravo");
    Rule ruleC = markerRule("test.refresh.ruleC", "com.company.rules.refresh.c", "charlie");

    // Initial load: 3 rules. All three should fire on their respective inputs.
    boolean initialLoad = droolsEngineService.loadRules(List.of(ruleA, ruleB, ruleC));
    assertThat(initialLoad).isTrue();

    assertThat(executeAndGetMarker("test.refresh.ruleA", "alpha")).isTrue();
    assertThat(executeAndGetMarker("test.refresh.ruleB", "bravo")).isTrue();
    assertThat(executeAndGetMarker("test.refresh.ruleC", "charlie")).isTrue();

    CompiledRuleset handleA = registry.get("test.refresh.ruleA").orElseThrow();
    CompiledRuleset handleB = registry.get("test.refresh.ruleB").orElseThrow();
    CompiledRuleset handleC = registry.get("test.refresh.ruleC").orElseThrow();

    // Refresh of ruleB with byte-identical DRL: skipped by digest, nothing recompiled.
    Rule ruleBSame = markerRule("test.refresh.ruleB", "com.company.rules.refresh.b", "bravo");
    assertThat(droolsEngineService.replaceRule(ruleBSame).status())
        .isEqualTo(DroolsEngineService.InstallStatus.UNCHANGED);
    assertThat(registry.get("test.refresh.ruleB")).containsSame(handleB);

    // Single-rule refresh of ruleB with changed DRL (same behaviour, extra comment).
    // Bug behaviour was: ruleA and ruleC stop firing.
    Rule ruleBv2 =
        new Rule(
            "test.refresh.ruleB", ruleBSame.getContent() + "// v2\n", RuleMetadata.createNew());
    boolean refresh = droolsEngineService.loadOrReplaceRule(ruleBv2);
    assertThat(refresh).isTrue();

    // Per-ID isolation (ADR-025): only ruleB was recompiled; A and C keep the very same handle.
    assertThat(registry.get("test.refresh.ruleA")).containsSame(handleA);
    assertThat(registry.get("test.refresh.ruleC")).containsSame(handleC);
    CompiledRuleset newHandleB = registry.get("test.refresh.ruleB").orElseThrow();
    assertThat(newHandleB).isNotSameAs(handleB);
    assertThat(newHandleB.contentDigest())
        .isNotEqualTo(handleB.contentDigest())
        .isEqualTo(RuleCompiler.sha256(ruleBv2.getContent()));
    assertThat(registry.size()).isEqualTo(3);

    // Fix: all three rules MUST still fire after the single-rule refresh.
    assertThat(executeAndGetMarker("test.refresh.ruleA", "alpha"))
        .as("ruleA should still fire after single-rule refresh of ruleB")
        .isTrue();
    assertThat(executeAndGetMarker("test.refresh.ruleB", "bravo"))
        .as("ruleB (the refreshed one) should still fire")
        .isTrue();
    assertThat(executeAndGetMarker("test.refresh.ruleC", "charlie"))
        .as("ruleC should still fire after single-rule refresh of ruleB")
        .isTrue();
  }

  @Test
  @DisplayName("content change produces a new handle with a new digest and new behaviour")
  void contentChange_swapsHandleAndDigest() {
    Rule ruleA = markerRule("test.refresh.ruleA", "com.company.rules.refresh.a", "alpha");
    Rule ruleB = markerRule("test.refresh.ruleB", "com.company.rules.refresh.b", "bravo");
    assertThat(droolsEngineService.loadRules(List.of(ruleA, ruleB))).isTrue();

    CompiledRuleset handleA = registry.get("test.refresh.ruleA").orElseThrow();
    CompiledRuleset handleB = registry.get("test.refresh.ruleB").orElseThrow();
    assertThat(handleB.contentDigest()).isEqualTo(RuleCompiler.sha256(ruleB.getContent()));

    // ruleB v2 matches a different marker key.
    Rule ruleBv2 = markerRule("test.refresh.ruleB", "com.company.rules.refresh.b", "bravo2");
    assertThat(droolsEngineService.loadOrReplaceRule(ruleBv2)).isTrue();

    CompiledRuleset newHandleB = registry.get("test.refresh.ruleB").orElseThrow();
    assertThat(newHandleB.contentDigest())
        .isNotEqualTo(handleB.contentDigest())
        .isEqualTo(RuleCompiler.sha256(ruleBv2.getContent()));
    assertThat(registry.get("test.refresh.ruleA")).containsSame(handleA);

    assertThat(executeAndGetMarker("test.refresh.ruleB", "bravo2")).isTrue();
    assertThat(executeAndGetMarker("test.refresh.ruleB", "bravo")).isFalse();
    assertThat(executeAndGetMarker("test.refresh.ruleA", "alpha")).isTrue();
  }

  @Test
  @DisplayName("a broken update keeps the previous version serving and records the error")
  void brokenUpdate_keepsPreviousVersionServing() {
    Rule ruleA = markerRule("test.refresh.ruleA", "com.company.rules.refresh.a", "alpha");
    Rule ruleB = markerRule("test.refresh.ruleB", "com.company.rules.refresh.b", "bravo");
    assertThat(droolsEngineService.loadRules(List.of(ruleA, ruleB))).isTrue();
    CompiledRuleset handleB = registry.get("test.refresh.ruleB").orElseThrow();

    Rule brokenB =
        new Rule(
            "test.refresh.ruleB",
            "package com.company.rules.refresh.b\nrule \"broken\" when then $x.oops( end",
            RuleMetadata.createNew());
    assertThat(droolsEngineService.loadOrReplaceRule(brokenB)).isFalse();

    assertThat(registry.get("test.refresh.ruleB")).containsSame(handleB);
    RuleMetadata metadata = droolsEngineService.getRuleMetadata("test.refresh.ruleB");
    assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
    assertThat(metadata.getErrorMessage()).isNotBlank();
    assertThat(executeAndGetMarker("test.refresh.ruleB", "bravo")).isTrue();
    assertThat(executeAndGetMarker("test.refresh.ruleA", "alpha")).isTrue();

    // Re-applying the (identical) good DRL is NOT skipped while an error is recorded: it
    // recompiles and clears the error.
    assertThat(droolsEngineService.replaceRule(ruleB).status())
        .isEqualTo(DroolsEngineService.InstallStatus.INSTALLED);
    assertThat(droolsEngineService.getRuleMetadata("test.refresh.ruleB").getErrorMessage())
        .isNull();
    assertThat(droolsEngineService.replaceRule(ruleB).status())
        .isEqualTo(DroolsEngineService.InstallStatus.UNCHANGED);
  }

  @Test
  @DisplayName("removeRule and a smaller full reload remove only the absent IDs")
  void removal_affectsOnlyThatId() {
    Rule ruleA = markerRule("test.refresh.ruleA", "com.company.rules.refresh.a", "alpha");
    Rule ruleB = markerRule("test.refresh.ruleB", "com.company.rules.refresh.b", "bravo");
    Rule ruleC = markerRule("test.refresh.ruleC", "com.company.rules.refresh.c", "charlie");
    assertThat(droolsEngineService.loadRules(List.of(ruleA, ruleB, ruleC))).isTrue();
    CompiledRuleset handleA = registry.get("test.refresh.ruleA").orElseThrow();

    assertThat(droolsEngineService.removeRule("test.refresh.ruleC")).isTrue();
    assertThat(registry.ruleIds())
        .containsExactlyInAnyOrder("test.refresh.ruleA", "test.refresh.ruleB");
    assertThat(
            droolsEngineService
                .executeRule("test.refresh.ruleC", new HashMap<>())
                .getErrorMessage())
        .contains("Rule not found");

    DroolsEngineService.LoadReport report = droolsEngineService.loadRulesReport(List.of(ruleA));
    assertThat(report.removed()).containsExactly("test.refresh.ruleB");
    assertThat(registry.ruleIds()).containsExactly("test.refresh.ruleA");
    assertThat(executeAndGetMarker("test.refresh.ruleA", "alpha")).isTrue();
    // A full reload skips IDs whose DRL is unchanged (same digest): ruleA keeps its handle.
    assertThat(report.unchanged()).containsExactly("test.refresh.ruleA");
    assertThat(report.loaded()).isEmpty();
    assertThat(registry.get("test.refresh.ruleA")).containsSame(handleA);
  }

  /** Executes the named rule with a single-key map and returns whether the rule's marker fired. */
  private boolean executeAndGetMarker(String ruleId, String markerKey) {
    Map<String, Object> input = new HashMap<>();
    input.put(markerKey, "present");
    RuleExecutor.ExecutionResult result = droolsEngineService.executeRule(ruleId, input);
    if (!result.isSuccess()) {
      return false;
    }
    Object fired = result.getResult().get("fired_" + markerKey);
    return Boolean.TRUE.equals(fired);
  }
}
