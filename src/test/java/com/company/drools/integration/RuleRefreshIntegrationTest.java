package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.config.TimeoutConfig;
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
import org.kie.api.KieServices;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.mockito.Mockito;

/**
 * End-to-end integration tests for rule-refresh semantics, focused on the loadOrReplaceRule path
 * (which the AdminController single-rule-refresh endpoint now calls). Compiles real DRL through the
 * real {@link RuleCompiler} and executes through {@link RuleExecutor} — no mocks for the engine,
 * compiler, or executor.
 */
@DisplayName("Rule Refresh Integration Tests")
class RuleRefreshIntegrationTest {

  private static KieServices kieServices;
  private static RuleCompiler ruleCompiler;
  private static ExecutorService executorService;

  private DroolsEngineService droolsEngineService;
  private RuleExecutor ruleExecutor;

  @BeforeAll
  static void initShared() {
    kieServices = KieServices.Factory.get();
    ruleCompiler = new RuleCompiler(kieServices, new DrlSanitizer());
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

    KieContainer initialContainer = createEmptyKieContainer();

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
            ruleCompiler,
            ruleExecutor,
            initialContainer,
            kieServices.getRepository(),
            noOpStorage,
            timeoutConfig,
            meterRegistry);
  }

  private KieContainer createEmptyKieContainer() {
    ReleaseId releaseId =
        kieServices.newReleaseId(
            RuleCompiler.GROUP_ID, RuleCompiler.ARTIFACT_ID, RuleCompiler.INITIAL_VERSION);
    var kfs = kieServices.newKieFileSystem();
    kfs.generateAndWritePomXML(releaseId);
    var kb = kieServices.newKieBuilder(kfs);
    kb.buildAll();
    return kieServices.newKieContainer(releaseId);
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

    // Single-rule refresh of ruleB. Bug behaviour was: ruleA and ruleC stop firing.
    Rule ruleBv2 = markerRule("test.refresh.ruleB", "com.company.rules.refresh.b", "bravo");
    boolean refresh = droolsEngineService.loadOrReplaceRule(ruleBv2);
    assertThat(refresh).isTrue();

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
