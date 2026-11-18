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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.mockito.Mockito;

/**
 * Rule-ID isolation (engine-isolation plan, ADR-025): a request for {@code rule_id = X} must run
 * only the rules in X's DRL file.
 */
@DisplayName("Rule isolation per rule_id")
class RuleIsolationIntegrationTest {

  private ExecutorService pool;
  private DroolsEngineService engine;

  @BeforeEach
  void setUp() {
    KieServices ks = KieServices.Factory.get();
    pool = Executors.newFixedThreadPool(4);
    TimeoutConfig timeout = Mockito.mock(TimeoutConfig.class);
    Mockito.when(timeout.getRuleExecutionTimeoutSeconds()).thenReturn(30);
    var releaseId =
        ks.newReleaseId(
            RuleCompiler.GROUP_ID, RuleCompiler.ARTIFACT_ID, RuleCompiler.INITIAL_VERSION);
    var kfs = ks.newKieFileSystem();
    kfs.generateAndWritePomXML(releaseId);
    ks.newKieBuilder(kfs).buildAll();
    KieContainer empty = ks.newKieContainer(releaseId);
    engine =
        new DroolsEngineService(
            new RuleCompiler(ks, new DrlSanitizer()),
            new RuleExecutor(pool),
            empty,
            ks.getRepository(),
            Mockito.mock(RuleStorage.class),
            timeout,
            new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  @Disabled(
      "RED until engine-isolation Phase 2: observed 72.0 on 2026-11-18 (all loaded rules fire;"
          + " simple 10% stacks on VIP 20%)")
  @DisplayName("simple with VIP fields applies only the simple 10% (90), not VIP stacked (72)")
  void simpleDoesNotRunVip() throws IOException {
    assertThat(
            engine.loadRules(
                List.of(sample("pricing.discount.simple"), sample("pricing.discount.vip"))))
        .isTrue();

    Map<String, Object> data = new HashMap<>(Map.of("customerType", "VIP", "amount", 100));
    RuleExecutor.ExecutionResult result = engine.executeRule("pricing.discount.simple", data);

    assertThat(result.isSuccess()).isTrue();
    assertThat(((Number) result.getResult().get("amount")).doubleValue()).isEqualTo(90.0);
    assertThat(result.getResult()).doesNotContainKey("customerTier");
  }

  private static Rule sample(String ruleId) throws IOException {
    String content =
        Files.readString(Paths.get("sample-rules/" + ruleId.replace('.', '/') + ".drl"));
    return new Rule(ruleId, content, RuleMetadata.createNew());
  }
}
