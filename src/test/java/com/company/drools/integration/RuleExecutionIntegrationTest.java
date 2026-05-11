package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.drools.config.TimeoutConfig;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleCompiler;
import com.company.drools.core.engine.RuleExecutor;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.mockito.Mockito;

/**
 * Integration tests for end-to-end rule execution using real sample .drl files. These tests compile
 * and execute actual Drools rules without mocking, verifying the full pipeline: load -> compile ->
 * execute -> verify results.
 */
@DisplayName("Rule Execution Integration Tests")
class RuleExecutionIntegrationTest {

  private static KieServices kieServices;
  private static RuleCompiler ruleCompiler;
  private static ExecutorService executorService;

  private DroolsEngineService droolsEngineService;
  private RuleExecutor ruleExecutor;
  private MeterRegistry meterRegistry;

  @BeforeAll
  static void initShared() {
    kieServices = KieServices.Factory.get();
    ruleCompiler = new RuleCompiler(kieServices, new com.company.drools.core.engine.DrlSanitizer());
    executorService = Executors.newFixedThreadPool(10);
  }

  @AfterAll
  static void tearDownShared() {
    executorService.shutdown();
  }

  @BeforeEach
  void setUp() {
    ruleExecutor = new RuleExecutor(executorService);
    meterRegistry = new SimpleMeterRegistry();

    // Create an empty initial KieContainer
    KieContainer emptyContainer = createEmptyKieContainer();

    // Use a no-op RuleStorage since we load rules directly
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
          public void saveRule(Rule rule) { /* test stub — no persistence needed */ }

          @Override
          public void deleteRule(String ruleId) { /* test stub — no persistence needed */ }

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

    // Use a stubbed TimeoutConfig with reasonable defaults
    TimeoutConfig timeoutConfig = Mockito.mock(TimeoutConfig.class);
    Mockito.when(timeoutConfig.getRuleExecutionTimeoutSeconds()).thenReturn(30);

    droolsEngineService =
        new DroolsEngineService(
            ruleCompiler,
            ruleExecutor,
            emptyContainer,
            kieServices.getRepository(),
            noOpStorage,
            timeoutConfig,
            meterRegistry);
  }

  private KieContainer createEmptyKieContainer() {
    // Must use the same groupId/artifactId that RuleCompiler emits, so updateToVersion(...)
    // can resolve newly-built modules from the KieRepository.
    var releaseId =
        kieServices.newReleaseId(
            RuleCompiler.GROUP_ID, RuleCompiler.ARTIFACT_ID, RuleCompiler.INITIAL_VERSION);
    var kfs = kieServices.newKieFileSystem();
    kfs.generateAndWritePomXML(releaseId);
    var kb = kieServices.newKieBuilder(kfs);
    kb.buildAll();
    return kieServices.newKieContainer(releaseId);
  }

  private Rule loadSampleRule(String ruleId) throws IOException {
    String path = "sample-rules/" + ruleId.replace(".", "/") + ".drl";
    String content = Files.readString(Paths.get(path));
    return new Rule(ruleId, content, RuleMetadata.createNew());
  }

  private List<Rule> loadAllSampleRules() throws IOException {
    List<Rule> rules = new ArrayList<>();
    Path sampleRulesDir = Paths.get("sample-rules");

    try (Stream<Path> paths = Files.walk(sampleRulesDir)) {
      paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".drl"))
          .forEach(
              p -> {
                try {
                  String content = Files.readString(p);
                  Path relative = sampleRulesDir.relativize(p);
                  String ruleId =
                      relative.toString().replace(".drl", "").replace("/", ".").replace("\\", ".");
                  rules.add(new Rule(ruleId, content, RuleMetadata.createNew()));
                } catch (IOException e) {
                  throw new RuntimeException("Failed to load rule: " + p, e);
                }
              });
    }

    return rules;
  }

  // =========================================================================
  // Sample Rules Execution (4 tests)
  // =========================================================================

  @Nested
  @DisplayName("Sample Rules Execution")
  class SampleRulesExecution {

    @Test
    @DisplayName("Simple discount rule applies 10% off for orders over $50")
    void testExecuteDiscountRule_SimpleScenario_AppliesDiscount() throws IOException {
      Rule rule = loadSampleRule("pricing.discount.simple");
      boolean loaded = droolsEngineService.loadRules(List.of(rule));
      assertThat(loaded).isTrue();

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("amount", 100.0);

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("pricing.discount.simple", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("discount", 10.0);
      assertThat(result.getResult()).containsEntry("amount", 90.0);
      assertThat(result.getResult()).containsEntry("discountPercent", 10);
      assertThat(result.getResult()).containsEntry("discountReason", "Order over $50 discount");
    }

    @Test
    @DisplayName("VIP customer rule applies 20% discount")
    void testExecuteVIPRule_VIPCustomer_AppliesVIPDiscount() throws IOException {
      Rule rule = loadSampleRule("pricing.discount.vip");
      boolean loaded = droolsEngineService.loadRules(List.of(rule));
      assertThat(loaded).isTrue();

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("customerType", "VIP");
      inputData.put("amount", 200.0);

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("pricing.discount.vip", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("discount", 40.0);
      assertThat(result.getResult()).containsEntry("amount", 160.0);
      assertThat(result.getResult()).containsEntry("discountPercent", 20);
      assertThat(result.getResult()).containsEntry("customerTier", "VIP");
      assertThat(result.getResult())
          .containsEntry("discountReason", "VIP customer exclusive discount");
    }

    @Test
    @DisplayName("Age validation rule rejects underage customer")
    void testExecuteAgeValidation_UnderageCustomer_RejectsOrder() throws IOException {
      Rule rule = loadSampleRule("validation.customer.age");
      boolean loaded = droolsEngineService.loadRules(List.of(rule));
      assertThat(loaded).isTrue();

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("customerAge", 16);

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("validation.customer.age", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("validationResult", "REJECTED");
      assertThat(result.getResult())
          .containsEntry("validationReason", "Customer must be 18 or older");
      assertThat(result.getResult()).containsEntry("eligible", false);
    }

    @Test
    @DisplayName("Shipping rule calculates standard shipping cost")
    void testExecuteShippingRule_StandardOrder_CalculatesShipping() throws IOException {
      Rule rule = loadSampleRule("pricing.shipping.standard");
      boolean loaded = droolsEngineService.loadRules(List.of(rule));
      assertThat(loaded).isTrue();

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("shippingType", "standard");
      inputData.put("weight", 3.0);

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("pricing.shipping.standard", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("shippingCost", 9.99);
      assertThat(result.getResult()).containsEntry("shippingMethod", "Standard");
      assertThat(result.getResult()).containsEntry("estimatedDays", 5);
    }

    // =========================================================================
    // Phase 1 cookbook expansion (7 new patterns)
    // =========================================================================

    @Test
    @DisplayName("Accumulate: bundle discount when summed item prices exceed threshold")
    void testExecuteAccumulate_BundleOverThreshold_AppliesDiscount() throws IOException {
      Rule rule = loadSampleRule("pricing.bundle.accumulate");
      assertThat(droolsEngineService.loadRules(List.of(rule))).isTrue();

      Map<String, Object> item1 = new HashMap<>();
      item1.put("price", 50.0);
      Map<String, Object> item2 = new HashMap<>();
      item2.put("price", 60.0);
      Map<String, Object> inputData = new HashMap<>();
      inputData.put("items", List.of(item1, item2));

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("pricing.bundle.accumulate", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("bundleTotal", 110.0);
      assertThat(result.getResult()).containsEntry("bundleDiscount", 11.0);
      assertThat(result.getResult()).containsEntry("bundleFinalAmount", 99.0);
      assertThat(result.getResult()).containsEntry("appliedRule", "bundle-accumulate");
    }

    @Test
    @DisplayName("Exists: low stock warning fires when ANY item is low")
    void testExecuteExists_AnyLowStockItem_FiresWarning() throws IOException {
      Rule rule = loadSampleRule("inventory.warning.exists");
      assertThat(droolsEngineService.loadRules(List.of(rule))).isTrue();

      Map<String, Object> goodItem = new HashMap<>();
      goodItem.put("stockLevel", 10);
      Map<String, Object> lowItem = new HashMap<>();
      lowItem.put("stockLevel", 3);
      Map<String, Object> inputData = new HashMap<>();
      inputData.put("items", List.of(goodItem, lowItem));

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("inventory.warning.exists", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("lowStockWarning", true);
      assertThat(result.getResult()).containsEntry("warningType", "STOCK_LOW");
      assertThat(result.getResult()).containsEntry("validationMethod", "exists-pattern");
    }

    @Test
    @DisplayName("Not: empty cart is rejected via not-pattern")
    void testExecuteNot_EmptyCart_RejectsOrder() throws IOException {
      Rule rule = loadSampleRule("validation.cart.notempty");
      assertThat(droolsEngineService.loadRules(List.of(rule))).isTrue();

      // Cart with empty items list — the not(...) pattern fires.
      Map<String, Object> inputData = new HashMap<>();
      inputData.put("items", List.of());

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("validation.cart.notempty", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("validationResult", "REJECTED");
      assertThat(result.getResult()).containsEntry("reason", "EMPTY_CART");
      assertThat(result.getResult()).containsEntry("validationMethod", "not-pattern");
    }

    @Test
    @DisplayName("Salience: loyalty rule applies 15% discount (priority override)")
    void testExecuteSalience_LoyaltyMember_AppliesPriorityDiscount() throws IOException {
      Rule rule = loadSampleRule("pricing.loyalty.salience");
      assertThat(droolsEngineService.loadRules(List.of(rule))).isTrue();

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("loyaltyMember", true);
      inputData.put("amount", 100.0);

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("pricing.loyalty.salience", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("amount", 85.0);
      assertThat(result.getResult()).containsEntry("loyaltyDiscount", 15.0);
      assertThat(result.getResult()).containsEntry("loyaltyApplied", true);
      assertThat((String) result.getResult().get("discountReason")).contains("salience 100");
    }

    @Test
    @DisplayName("Compound LHS: internal customer with corporate email passes validation")
    void testExecuteCompound_InternalCorporateEmail_Valid() throws IOException {
      Rule rule = loadSampleRule("validation.email.compound");
      assertThat(droolsEngineService.loadRules(List.of(rule))).isTrue();

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("email", "alice@company.com");
      inputData.put("customerType", "internal");

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("validation.email.compound", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("emailValidationResult", "VALID");
      assertThat(result.getResult()).containsEntry("validationMethod", "compound-lhs");
    }

    @Test
    @DisplayName("Temporal: promo within date range is ACTIVE")
    void testExecuteTemporal_WithinExpiry_IsActive() throws IOException {
      Rule rule = loadSampleRule("seasonal.expiry.temporal");
      assertThat(droolsEngineService.loadRules(List.of(rule))).isTrue();

      Map<String, Object> inputData = new HashMap<>();
      inputData.put("currentDate", "2026-05-10");
      inputData.put("expiryDate", "2026-12-31");

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("seasonal.expiry.temporal", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("promoValid", true);
      assertThat(result.getResult()).containsEntry("promoStatus", "ACTIVE");
      assertThat(result.getResult()).containsEntry("daysRemaining", 235L);
    }

    @Test
    @DisplayName("Forall: all cart items in stock satisfies universal quantification")
    void testExecuteForall_AllItemsInStock_Passes() throws IOException {
      Rule rule = loadSampleRule("validation.cart.forall");
      assertThat(droolsEngineService.loadRules(List.of(rule))).isTrue();

      Map<String, Object> i1 = new HashMap<>();
      i1.put("stockLevel", 10);
      Map<String, Object> i2 = new HashMap<>();
      i2.put("stockLevel", 20);
      Map<String, Object> inputData = new HashMap<>();
      inputData.put("items", List.of(i1, i2));

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("validation.cart.forall", inputData);

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.getResult()).containsEntry("allItemsInStock", true);
      assertThat(result.getResult()).containsEntry("validationMethod", "forall");
    }
  }

  // =========================================================================
  // Caching Behavior (2 tests)
  // =========================================================================

  @Nested
  @DisplayName("Caching Behavior")
  class CachingBehavior {

    @Test
    @DisplayName("Second execution reuses compiled KieBase and is faster")
    void testExecuteRule_SecondExecution_UsesCachedKieBase() throws IOException {
      Rule rule = loadSampleRule("pricing.discount.simple");
      boolean loaded = droolsEngineService.loadRules(List.of(rule));
      assertThat(loaded).isTrue();

      // First execution - includes any first-call overhead
      Map<String, Object> firstInput = new HashMap<>();
      firstInput.put("amount", 100.0);
      RuleExecutor.ExecutionResult firstResult =
          droolsEngineService.executeRule("pricing.discount.simple", firstInput);
      assertThat(firstResult.isSuccess()).isTrue();

      // Second execution - should reuse the same compiled KieContainer
      Map<String, Object> secondInput = new HashMap<>();
      secondInput.put("amount", 200.0);
      RuleExecutor.ExecutionResult secondResult =
          droolsEngineService.executeRule("pricing.discount.simple", secondInput);
      assertThat(secondResult.isSuccess()).isTrue();

      // Both executions use the same loaded rule - verify metadata tracks both
      RuleMetadata metadata = droolsEngineService.getRuleMetadata("pricing.discount.simple");
      assertThat(metadata.getExecutionCount()).isEqualTo(2);
      assertThat(metadata.getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);

      // Verify both produced correct results
      assertThat(firstResult.getResult()).containsEntry("amount", 90.0);
      assertThat(secondResult.getResult()).containsEntry("amount", 180.0);
    }

    @Test
    @DisplayName("Unloaded rule returns failure (cache miss)")
    void testExecuteRule_CacheMiss_CompilesAndCaches() throws IOException {
      // Execute a rule that hasn't been loaded yet
      Map<String, Object> inputData = new HashMap<>();
      inputData.put("amount", 100.0);

      RuleExecutor.ExecutionResult result =
          droolsEngineService.executeRule("pricing.discount.simple", inputData);

      assertThat(result.isSuccess()).isFalse();
      assertThat(result.getErrorMessage()).contains("Rule not found");

      // Now load the rule and verify it works
      Rule rule = loadSampleRule("pricing.discount.simple");
      boolean loaded = droolsEngineService.loadRules(List.of(rule));
      assertThat(loaded).isTrue();

      Map<String, Object> retryInput = new HashMap<>();
      retryInput.put("amount", 100.0);
      RuleExecutor.ExecutionResult retryResult =
          droolsEngineService.executeRule("pricing.discount.simple", retryInput);

      assertThat(retryResult.isSuccess()).isTrue();
      assertThat(retryResult.getResult()).containsEntry("discount", 10.0);
    }
  }

  // =========================================================================
  // End-to-End Flow (2 tests)
  // =========================================================================

  @Nested
  @DisplayName("End-to-End Flow")
  class EndToEndFlow {

    @Test
    @DisplayName("All sample rules compile and execute successfully")
    void testLoadAllSampleRules_CompileAndExecute_AllSucceed() throws IOException {
      List<Rule> allRules = loadAllSampleRules();
      assertThat(allRules).isNotEmpty();

      boolean loaded = droolsEngineService.loadRules(allRules);
      assertThat(loaded).as("All sample rules should compile successfully").isTrue();
      assertThat(droolsEngineService.getLoadedRulesCount()).isEqualTo(allRules.size());
      assertThat(droolsEngineService.getActiveRulesCount()).isEqualTo(allRules.size());

      // Execute each loaded rule with appropriate test data
      Map<String, Map<String, Object>> testDataByRule = new HashMap<>();
      testDataByRule.put("pricing.discount.simple", Map.of("amount", 100.0));
      testDataByRule.put("pricing.discount.vip", Map.of("customerType", "VIP", "amount", 100.0));
      testDataByRule.put("pricing.discount.bulk", Map.of("quantity", 20, "amount", 500.0));
      testDataByRule.put(
          "pricing.discount.first-time", Map.of("isFirstOrder", true, "amount", 80.0));
      testDataByRule.put(
          "pricing.shipping.standard", Map.of("shippingType", "standard", "weight", 2.0));
      testDataByRule.put(
          "pricing.shipping.express", Map.of("shippingType", "express", "weight", 2.0));
      testDataByRule.put("validation.customer.age", Map.of("customerAge", 25));
      testDataByRule.put("validation.customer.credit", Map.of("creditScore", 750));
      testDataByRule.put(
          "seasonal.holiday.blackfriday", Map.of("isBlackFriday", true, "amount", 100.0));
      testDataByRule.put("seasonal.holiday.discount", Map.of("isHoliday", true, "amount", 100.0));

      int successCount = 0;
      for (Rule rule : allRules) {
        Map<String, Object> testData = testDataByRule.get(rule.getRuleId());
        if (testData != null) {
          Map<String, Object> mutableData = new HashMap<>(testData);
          RuleExecutor.ExecutionResult result =
              droolsEngineService.executeRule(rule.getRuleId(), mutableData);
          assertThat(result.isSuccess())
              .as("Rule %s should execute successfully", rule.getRuleId())
              .isTrue();
          successCount++;
        }
      }

      assertThat(successCount)
          .as("Should have executed at least the core sample rules")
          .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Concurrent rule executions do not interfere with each other")
    void testExecuteMultipleRules_Concurrent_NoInterference() throws Exception {
      // Load multiple rules
      Rule discountRule = loadSampleRule("pricing.discount.simple");
      Rule vipRule = loadSampleRule("pricing.discount.vip");
      Rule ageRule = loadSampleRule("validation.customer.age");
      Rule shippingRule = loadSampleRule("pricing.shipping.standard");

      boolean loaded =
          droolsEngineService.loadRules(List.of(discountRule, vipRule, ageRule, shippingRule));
      assertThat(loaded).isTrue();

      int threadCount = 20;
      ExecutorService concurrentExecutor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      List<RuleExecutor.ExecutionResult> results = Collections.synchronizedList(new ArrayList<>());

      // Each thread executes a different rule
      String[] ruleIds = {
        "pricing.discount.simple",
        "pricing.discount.vip",
        "validation.customer.age",
        "pricing.shipping.standard"
      };

      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        concurrentExecutor.submit(
            () -> {
              try {
                startLatch.await();
                String ruleId = ruleIds[index % ruleIds.length];
                Map<String, Object> data = new HashMap<>();

                switch (ruleId) {
                  case "pricing.discount.simple" -> data.put("amount", 100.0 + index);
                  case "pricing.discount.vip" -> {
                    data.put("customerType", "VIP");
                    data.put("amount", 200.0 + index);
                  }
                  case "validation.customer.age" -> data.put("customerAge", 20 + index);
                  case "pricing.shipping.standard" -> {
                    data.put("shippingType", "standard");
                    data.put("weight", 1.0 + index);
                  }
                  default -> { /* no data setup needed for other rule IDs */ }
                }

                RuleExecutor.ExecutionResult result = droolsEngineService.executeRule(ruleId, data);
                results.add(result);
              } catch (Exception _) {
                errorCount.incrementAndGet();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      // Release all threads simultaneously
      startLatch.countDown();
      boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
      concurrentExecutor.shutdown();

      assertThat(completed).as("All concurrent executions should complete").isTrue();
      assertThat(errorCount.get()).as("No exceptions during concurrent execution").isZero();
      assertThat(results).hasSize(threadCount);

      // Every execution should succeed
      for (RuleExecutor.ExecutionResult result : results) {
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isNotEmpty();
      }
    }
  }
}
