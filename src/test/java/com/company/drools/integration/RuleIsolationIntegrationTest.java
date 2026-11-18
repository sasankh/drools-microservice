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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Rule-ID isolation acceptance tests (engine-isolation plan §A4.6, ADR-025): a request for {@code
 * rule_id = X} runs only the rules in X's DRL file, with real compilation of the sample rules.
 */
@DisplayName("Rule isolation per rule_id")
class RuleIsolationIntegrationTest {

  private ExecutorService pool;
  private CompiledRulesetRegistry registry;
  private DroolsEngineService engine;

  @BeforeEach
  void setUp() {
    pool = Executors.newFixedThreadPool(8);
    registry = new CompiledRulesetRegistry();
    TimeoutConfig timeout = Mockito.mock(TimeoutConfig.class);
    Mockito.when(timeout.getRuleExecutionTimeoutSeconds()).thenReturn(30);
    engine =
        new DroolsEngineService(
            new RuleCompiler(new DrlSanitizer()),
            new RuleExecutor(pool),
            registry,
            Mockito.mock(RuleStorage.class),
            timeout,
            new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  @DisplayName("simple with VIP fields applies only simple's 10% (90), not VIP stacked (was 72)")
  void simpleDoesNotRunVip() throws IOException {
    assertThat(
            engine.loadRules(
                List.of(sample("pricing.discount.simple"), sample("pricing.discount.vip"))))
        .isTrue();

    Map<String, Object> out =
        run("pricing.discount.simple", Map.of("customerType", "VIP", "amount", 100));

    assertThat(amount(out)).isEqualTo(90.0);
    assertThat(out).doesNotContainKey("customerTier");
  }

  @Test
  @DisplayName("VIP alone gives 80 with all 17 sample rules loaded")
  void vipGivesEighty() throws IOException {
    assertThat(engine.loadRules(allSamples())).isTrue();

    Map<String, Object> out =
        run("pricing.discount.vip", Map.of("customerType", "VIP", "amount", 100));

    assertThat(amount(out)).isEqualTo(80.0);
    assertThat(out)
        .containsEntry("customerTier", "VIP")
        .containsEntry("discountReason", "VIP customer exclusive discount");
  }

  @Test
  @DisplayName("an unrelated rule whose LHS matches does not change the output")
  void unrelatedMatchingRuleDoesNotMutate() throws IOException {
    engine.loadRules(allSamples());

    // simple's LHS (amount != null) matches this payload, but we ask for the age rule.
    Map<String, Object> out =
        run("validation.customer.age", Map.of("customerAge", 15, "amount", 100));

    assertThat(out).containsEntry("validationResult", "REJECTED");
    assertThat(amount(out)).isEqualTo(100.0);
    assertThat(out).doesNotContainKeys("discount", "discountReason", "discountPercent");
  }

  @Test
  @DisplayName("two files in the same DRL package isolate (bulk vs simple)")
  void sameDrlPackageIsolates() throws IOException {
    engine.loadRules(allSamples());

    Map<String, Object> out = run("pricing.discount.bulk", Map.of("quantity", 15, "amount", 200));

    assertThat(amount(out)).isEqualTo(170.0);
    assertThat(out).containsEntry("discountPercent", 15).containsEntry("qualifyingQuantity", 15);
  }

  @Test
  @DisplayName("a file with two rules fires both, in salience order")
  void twoRulesInOneFileFireInSalienceOrder() {
    String drl =
        """
        package com.company.rules.test.multi
        import java.util.Map
        import java.util.List
        rule "second" salience 10
        when $d : Map(this["trace"] != null)
        then ((List) $d.get("trace")).add("second");
        end
        rule "first" salience 100
        when $d : Map(this["trace"] != null)
        then ((List) $d.get("trace")).add("first");
        end
        """;
    assertThat(engine.loadOrReplaceRule(rule("test.multi", drl))).isTrue();
    Map<String, Object> data = new HashMap<>();
    data.put("trace", new ArrayList<String>());

    RuleExecutor.ExecutionResult result = engine.executeRule("test.multi", data);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getResult().get("trace")).isEqualTo(List.of("first", "second"));
    assertThat(registry.get("test.multi").orElseThrow().ruleNames())
        .containsExactly("first", "second");
  }

  @Test
  @DisplayName("forall without price succeeds (the bundle rule's NPE can no longer interfere)")
  void forallWithoutPriceNoLongerCrashes() throws IOException {
    engine.loadRules(allSamples());
    Map<String, Object> data = new HashMap<>();
    data.put("items", new ArrayList<>(List.of(new HashMap<>(Map.of("stockLevel", 10)))));

    RuleExecutor.ExecutionResult result = engine.executeRule("validation.cart.forall", data);

    assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
    assertThat(result.getResult()).containsEntry("allItemsInStock", true);
  }

  @Test
  @DisplayName("an unknown rule ID never falls back to other rules")
  void unknownIdNeverFallsBack() throws IOException {
    engine.loadRules(allSamples());
    Map<String, Object> data = new HashMap<>(Map.of("amount", 100));

    RuleExecutor.ExecutionResult result = engine.executeRule("pricing.discount.nope", data);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrorMessage()).contains("Rule not found");
    assertThat(amount(data)).isEqualTo(100.0);
  }

  @Test
  @DisplayName("a removed rule ID is no longer executable; others are untouched")
  void removedRuleIsGone() throws IOException {
    engine.loadRules(List.of(sample("pricing.discount.simple"), sample("pricing.discount.vip")));
    CompiledRuleset simpleBefore = registry.get("pricing.discount.simple").orElseThrow();

    engine.removeRule("pricing.discount.vip");

    RuleExecutor.ExecutionResult vip =
        engine.executeRule("pricing.discount.vip", new HashMap<>(Map.of("amount", 100)));
    assertThat(vip.getErrorMessage()).contains("Rule not found");
    assertThat(registry.get("pricing.discount.simple")).containsSame(simpleBefore);
    assertThat(amount(run("pricing.discount.simple", Map.of("amount", 100)))).isEqualTo(90.0);
  }

  @Test
  @DisplayName("a new rule that fails to compile is ERROR and not executable; others load")
  void failedNewRuleIsErrorNotExecutable() throws IOException {
    Rule bad = rule("test.bad", "package x\nrule \"bad\" when then this is not drl end");

    DroolsEngineService.LoadReport report =
        engine.loadRulesReport(List.of(bad, sample("pricing.discount.simple")));

    assertThat(report.loaded()).containsExactly("pricing.discount.simple");
    assertThat(report.failed()).containsOnlyKeys("test.bad");
    assertThat(engine.getRuleMetadata("test.bad").getStatus())
        .isEqualTo(RuleMetadata.RuleStatus.ERROR);
    assertThat(engine.executeRule("test.bad", new HashMap<>()).getErrorMessage())
        .contains("not active");
    assertThat(amount(run("pricing.discount.simple", Map.of("amount", 100)))).isEqualTo(90.0);
  }

  @Test
  @DisplayName("a broken refresh of X keeps X's last good version; Y is untouched")
  void brokenRefreshKeepsLastGood() throws IOException {
    engine.loadRules(List.of(sample("pricing.discount.simple"), sample("pricing.discount.vip")));
    CompiledRuleset simpleBefore = registry.get("pricing.discount.simple").orElseThrow();
    CompiledRuleset vipBefore = registry.get("pricing.discount.vip").orElseThrow();

    boolean ok = engine.loadOrReplaceRule(rule("pricing.discount.vip", "package x\nrule broken"));

    assertThat(ok).isFalse();
    assertThat(registry.get("pricing.discount.vip")).containsSame(vipBefore);
    assertThat(registry.get("pricing.discount.simple")).containsSame(simpleBefore);
    RuleMetadata vipMeta = engine.getRuleMetadata("pricing.discount.vip");
    assertThat(vipMeta.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
    assertThat(vipMeta.getErrorMessage()).isNotBlank();
    assertThat(amount(run("pricing.discount.vip", Map.of("customerType", "VIP", "amount", 100))))
        .isEqualTo(80.0);
  }

  @Test
  @DisplayName("replacing X recompiles only X (Y keeps the same compiled instance)")
  void replaceOnlyRecompilesThatId() throws IOException {
    engine.loadRules(allSamples());
    Map<String, CompiledRuleset> before = registry.snapshot();
    String newVip =
        sample("pricing.discount.vip").getContent().replace("amount * 0.80", "amount * 0.70");

    assertThat(engine.loadOrReplaceRule(rule("pricing.discount.vip", newVip))).isTrue();

    Map<String, CompiledRuleset> after = registry.snapshot();
    assertThat(after).hasSameSizeAs(before);
    before.forEach(
        (id, handle) -> {
          if (id.equals("pricing.discount.vip")) {
            assertThat(after.get(id)).isNotSameAs(handle);
            assertThat(after.get(id).contentDigest()).isNotEqualTo(handle.contentDigest());
          } else {
            assertThat(after.get(id)).as(id).isSameAs(handle);
          }
        });
    assertThat(amount(run("pricing.discount.vip", Map.of("customerType", "VIP", "amount", 100))))
        .isEqualTo(70.0);
  }

  @Test
  @DisplayName("concurrent requests for different rule IDs never cross-contaminate")
  void concurrentDifferentIdsDoNotCrossContaminate() throws Exception {
    engine.loadRules(List.of(sample("pricing.discount.simple"), sample("pricing.discount.vip")));
    ExecutorService callers = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 400; i++) {
        boolean vip = i % 2 == 0;
        futures.add(
            callers.submit(
                () -> {
                  String id = vip ? "pricing.discount.vip" : "pricing.discount.simple";
                  double got = amount(run(id, Map.of("customerType", "VIP", "amount", 100)));
                  return got == (vip ? 80.0 : 90.0);
                }));
      }
      for (Future<Boolean> f : futures) {
        assertThat(f.get(30, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      callers.shutdownNow();
    }
  }

  @Test
  @DisplayName("a refresh during an in-flight request: it finishes on the old version")
  void inFlightRequestFinishesOnOldVersion() throws Exception {
    engine.loadOrReplaceRule(rule("test.versioned", versioned("v1")));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Map<String, Object> slow = new HashMap<>();
    slow.put(
        "hook",
        (Runnable)
            () -> {
              entered.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      Future<RuleExecutor.ExecutionResult> inFlight =
          caller.submit(() -> engine.executeRule("test.versioned", slow));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

      // Refresh while the first request is still inside its rule; no lock blocks this.
      assertThat(engine.loadOrReplaceRule(rule("test.versioned", versioned("v2")))).isTrue();
      Map<String, Object> fresh = new HashMap<>();
      fresh.put("hook", (Runnable) () -> {});
      assertThat(engine.executeRule("test.versioned", fresh).getResult())
          .containsEntry("version", "v2");

      release.countDown();
      assertThat(inFlight.get(10, TimeUnit.SECONDS).getResult()).containsEntry("version", "v1");
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  @DisplayName("100 refreshes of one rule ID keep exactly one handle per ID")
  void repeatedRefreshesStayBounded() throws IOException {
    engine.loadRules(allSamples());
    Rule simple = sample("pricing.discount.simple");
    for (int i = 0; i < 100; i++) {
      assertThat(engine.loadOrReplaceRule(simple)).isTrue();
    }
    assertThat(registry.size()).isEqualTo(17);
    assertThat(engine.getLoadedRulesCount()).isEqualTo(17);
    assertThat(amount(run("pricing.discount.simple", Map.of("amount", 100)))).isEqualTo(90.0);
  }

  // ---------------------------------------------------------------- helpers

  private Map<String, Object> run(String ruleId, Map<String, Object> input) {
    RuleExecutor.ExecutionResult result = engine.executeRule(ruleId, new HashMap<>(input));
    assertThat(result.isSuccess()).as("%s: %s", ruleId, result.getErrorMessage()).isTrue();
    return result.getResult();
  }

  private static double amount(Map<String, Object> out) {
    return ((Number) out.get("amount")).doubleValue();
  }

  private static String versioned(String version) {
    return """
        package com.company.rules.test
        import java.util.Map
        rule "versioned"
        when $d : Map(this["hook"] != null)
        then
            ((Runnable) $d.get("hook")).run();
            $d.put("version", "%s");
        end
        """
        .formatted(version);
  }

  private static Rule rule(String ruleId, String drl) {
    return new Rule(ruleId, drl, RuleMetadata.createNew());
  }

  private static Rule sample(String ruleId) throws IOException {
    String content =
        Files.readString(Paths.get("sample-rules/" + ruleId.replace('.', '/') + ".drl"));
    return rule(ruleId, content);
  }

  private static List<Rule> allSamples() throws IOException {
    Path root = Paths.get("sample-rules");
    List<Rule> rules = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path p : paths.filter(x -> x.toString().endsWith(".drl")).sorted().toList()) {
        String rel = root.relativize(p).toString();
        rules.add(rule(rel.substring(0, rel.length() - 4).replace('/', '.'), Files.readString(p)));
      }
    }
    assertThat(rules).hasSize(17);
    return rules;
  }
}
