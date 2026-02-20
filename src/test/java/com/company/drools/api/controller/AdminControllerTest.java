package com.company.drools.api.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.drools.cache.CacheStatistics;
import com.company.drools.cache.RuleCache;
import com.company.drools.config.ThreadPoolConfig;
import com.company.drools.config.ValidationConfig;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.StorageFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

  private MockMvc mockMvc;

  @Mock private DroolsEngineService droolsEngineService;
  @Mock private StorageFactory storageFactory;
  @Mock private RuleCache ruleCache;
  @Mock private ThreadPoolConfig threadPoolConfig;
  @Mock private RuleStorage mockStorage;

  private AdminController adminController;

  @BeforeEach
  void setUp() {
    // Use SimpleMeterRegistry to avoid complex Timer/Counter mocking
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    adminController =
        new AdminController(
            droolsEngineService, storageFactory, ruleCache, meterRegistry, threadPoolConfig);

    // Create a Spring context with ValidationConfig so @ValidRuleId validator works.
    // The SpringConstraintValidatorFactory allows Hibernate Validator to inject Spring beans
    // into custom ConstraintValidators like RuleIdValidator.
    StaticApplicationContext appContext = new StaticApplicationContext();
    appContext.getBeanFactory().registerSingleton("validationConfig", new ValidationConfig());
    appContext.refresh();

    LocalValidatorFactoryBean validatorFactory = new LocalValidatorFactoryBean();
    validatorFactory.setApplicationContext(appContext);
    validatorFactory.afterPropertiesSet();

    // Build MockMvc with validator
    mockMvc =
        MockMvcBuilders.standaloneSetup(adminController).setValidator(validatorFactory).build();

    // Default: storageFactory returns our mock storage
    lenient().when(storageFactory.createRuleStorage()).thenReturn(mockStorage);

    // Default cache behavior
    lenient().when(ruleCache.isEnabled()).thenReturn(true);
    lenient().when(ruleCache.size()).thenReturn(5L);
    lenient().when(ruleCache.maxSize()).thenReturn(100L);
    lenient()
        .when(ruleCache.getStatistics())
        .thenReturn(new CacheStatistics(80, 20, 2, 5, 100, Instant.now()));
  }

  // ===== Health Check Tests (6) =====

  @Test
  void testHealth_AllComponentsUp_ReturnsUp() throws Exception {
    // Given - drools has rules loaded
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    // Storage is accessible
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // When/Then
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.drools.status").value("UP"))
        .andExpect(jsonPath("$.components.storage.status").value("UP"))
        .andExpect(jsonPath("$.components.cache.status").value("UP"))
        .andExpect(jsonPath("$.components.circuit-breakers.status").value("UP"));
  }

  @Test
  void testHealth_DroolsDown_ReturnsDown() throws Exception {
    // Given - no rules loaded means drools is DOWN
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(0);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(0L);
    when(mockStorage.getTotalRuleCount()).thenReturn(0L);

    // When/Then
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.components.drools.status").value("DOWN"))
        .andExpect(jsonPath("$.components.drools.details.error").value("No rules loaded"));
  }

  @Test
  void testHealth_StorageDown_ReturnsDown() throws Exception {
    // Given - drools is healthy
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(3);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(3L);
    // Storage throws exception (S3 inaccessible)
    when(storageFactory.createRuleStorage())
        .thenThrow(new RuntimeException("S3 connection refused"));

    // When/Then
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.components.storage.status").value("DOWN"))
        .andExpect(jsonPath("$.components.storage.details.error").value("S3 connection refused"));
  }

  @Test
  void testHealth_CacheDown_DoesNotAffectOverallStatus() throws Exception {
    // Given - drools and storage are healthy
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Cache: isEnabled() returns true for checkDroolsHealth -> calculateCacheHitRate,
    // but then checkCacheHealth sees size() throw
    when(ruleCache.isEnabled()).thenReturn(true);
    when(ruleCache.getStatistics())
        .thenReturn(new CacheStatistics(80, 20, 2, 5, 100, Instant.now()));
    when(ruleCache.size()).thenThrow(new RuntimeException("Cache connection lost"));

    // When/Then - overall should still be UP because cache is non-critical
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.cache.status").value("DOWN"))
        .andExpect(jsonPath("$.components.drools.status").value("UP"));
  }

  @Test
  void testHealth_CircuitBreakerOpen_DoesNotAffectStatus() throws Exception {
    // Given - drools and storage are healthy
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // When/Then - circuit breakers don't affect overall status
    // No s3CircuitBreaker or redisCircuitBreaker are injected (null by default)
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.circuit-breakers.status").value("UP"))
        .andExpect(
            jsonPath("$.components.circuit-breakers.details.circuit_breakers_enabled")
                .value(false));
  }

  @Test
  void testHealth_IncludesComponentDetails() throws Exception {
    // Given
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(10);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(8L);
    when(mockStorage.getTotalRuleCount()).thenReturn(10L);

    // When/Then - verify component details are present in response
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.components.drools.details.loaded_rules").value(10))
        .andExpect(jsonPath("$.components.drools.details.active_rules").value(8))
        .andExpect(jsonPath("$.components.drools.details.cache_hit_rate").value(0.8))
        .andExpect(jsonPath("$.components.storage.details.total_rules").value(10))
        .andExpect(jsonPath("$.components.cache.details.enabled").value(true))
        .andExpect(jsonPath("$.components.cache.details.size").value(5))
        .andExpect(jsonPath("$.components.cache.details.max_size").value(100))
        .andExpect(jsonPath("$.components.cache.details.statistics").exists())
        .andExpect(jsonPath("$.components.cache.details.statistics.hits").value(80))
        .andExpect(jsonPath("$.components.cache.details.statistics.misses").value(20))
        .andExpect(jsonPath("$.components.cache.details.statistics.evictions").value(2));
  }

  // ===== Rule Management Tests (5) =====

  @Test
  void testRefreshAllRules_Success_ReloadsAllRules() throws Exception {
    // Given
    Rule rule1 = createTestRule("pricing.discount.simple");
    Rule rule2 = createTestRule("pricing.discount.vip");
    when(mockStorage.getAllRules()).thenReturn(List.of(rule1, rule2));
    when(droolsEngineService.loadRules(anyList())).thenReturn(true);

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.rules_loaded").value(2))
        .andExpect(jsonPath("$.rules_failed").value(0))
        .andExpect(jsonPath("$.errors", hasSize(0)));

    // Verify cache was cleared before reload
    verify(ruleCache).clear();
    // Verify cache was warmed up with new rules
    verify(ruleCache).warmUp(List.of(rule1, rule2));
  }

  @Test
  void testRefreshAllRules_CompilationFailure_ReturnsErrors() throws Exception {
    // Given
    Rule rule1 = createTestRule("pricing.discount.broken");
    when(mockStorage.getAllRules()).thenReturn(List.of(rule1));
    when(droolsEngineService.loadRules(anyList())).thenReturn(false);

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed_with_errors"))
        .andExpect(jsonPath("$.rules_loaded").value(0))
        .andExpect(jsonPath("$.rules_failed").value(1))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].rule_id").value("ALL"))
        .andExpect(jsonPath("$.errors[0].error").value("Failed to load rules into engine"));
  }

  @Test
  void testRefreshRule_Success_UpdatesRule() throws Exception {
    // Given
    String ruleId = "pricing.discount.simple";
    Rule rule = createTestRule(ruleId);
    RuleMetadata metadata =
        new RuleMetadata(
            "1.0",
            LocalDateTime.now().minusHours(1),
            LocalDateTime.now().minusMinutes(30),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            50,
            12.5);
    when(droolsEngineService.getRuleMetadata(ruleId)).thenReturn(metadata);
    when(mockStorage.getRule(ruleId)).thenReturn(Optional.of(rule));
    when(droolsEngineService.loadRules(anyList())).thenReturn(true);

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("success"));

    // Verify cache was invalidated and re-populated
    verify(ruleCache).remove(ruleId);
    verify(ruleCache).put(rule);
  }

  @Test
  void testRefreshRule_NotFound_Returns404() throws Exception {
    // Given
    String ruleId = "nonexistent.rule";
    when(droolsEngineService.getRuleMetadata(ruleId)).thenReturn(null);
    when(mockStorage.getRule(ruleId)).thenReturn(Optional.empty());

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isNotFound());
  }

  @Test
  void testRefreshRule_InvalidatesCache() throws Exception {
    // Given
    String ruleId = "pricing.discount.vip";
    Rule rule = createTestRule(ruleId);
    RuleMetadata metadata =
        new RuleMetadata(
            "1.0",
            LocalDateTime.now(),
            LocalDateTime.now(),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            0,
            0.0);
    when(droolsEngineService.getRuleMetadata(ruleId)).thenReturn(metadata);
    when(mockStorage.getRule(ruleId)).thenReturn(Optional.of(rule));
    when(droolsEngineService.loadRules(anyList())).thenReturn(true);

    // When
    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk());

    // Then - verify cache.remove() was called
    InOrder inOrder = inOrder(ruleCache, droolsEngineService);
    inOrder.verify(ruleCache).remove(ruleId);
    inOrder.verify(droolsEngineService).loadRules(anyList());
    inOrder.verify(ruleCache).put(rule);
  }

  // ===== Listing & Info Tests (4) =====

  @Test
  void testListRules_ReturnsAllLoadedRules() throws Exception {
    // Given
    Map<String, RuleMetadata> allMetadata = new LinkedHashMap<>();
    allMetadata.put(
        "pricing.discount.simple",
        new RuleMetadata(
            "1.0",
            LocalDateTime.now(),
            LocalDateTime.now(),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            100,
            15.2));
    allMetadata.put(
        "pricing.discount.vip",
        new RuleMetadata(
            "2.0",
            LocalDateTime.now(),
            LocalDateTime.now(),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            50,
            8.3));
    when(droolsEngineService.getAllRuleMetadata()).thenReturn(allMetadata);
    when(ruleCache.contains("pricing.discount.simple")).thenReturn(true);
    when(ruleCache.contains("pricing.discount.vip")).thenReturn(false);

    // When/Then
    mockMvc
        .perform(get("/admin/rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_rules").value(2))
        .andExpect(jsonPath("$.rules", hasSize(2)))
        .andExpect(jsonPath("$.rules[?(@.rule_id=='pricing.discount.simple')]").exists())
        .andExpect(jsonPath("$.rules[?(@.rule_id=='pricing.discount.vip')]").exists());
  }

  @Test
  void testListRules_IncludesCacheStatus() throws Exception {
    // Given
    Map<String, RuleMetadata> allMetadata = new LinkedHashMap<>();
    allMetadata.put(
        "pricing.discount.simple",
        new RuleMetadata(
            "1.0",
            LocalDateTime.now(),
            LocalDateTime.now(),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            100,
            15.2));
    allMetadata.put(
        "pricing.discount.vip",
        new RuleMetadata(
            "2.0",
            LocalDateTime.now(),
            LocalDateTime.now(),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            50,
            8.3));
    when(droolsEngineService.getAllRuleMetadata()).thenReturn(allMetadata);
    when(ruleCache.contains("pricing.discount.simple")).thenReturn(true);
    when(ruleCache.contains("pricing.discount.vip")).thenReturn(false);

    // When/Then - verify cached flag is present per rule
    mockMvc
        .perform(get("/admin/rules"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.rules[?(@.rule_id=='pricing.discount.simple')].cached").value(true))
        .andExpect(
            jsonPath("$.rules[?(@.rule_id=='pricing.discount.vip')].cached").value(false));
  }

  @Test
  void testThreadPoolStats_ReturnsStatistics() throws Exception {
    // Given
    when(threadPoolConfig.getRuleExecutionPoolStats())
        .thenReturn("RuleExecutionPool[active=2, pool=10/50, queue=0/100, completed=150]");
    when(threadPoolConfig.getStoragePoolStats())
        .thenReturn("StoragePool[active=0, pool=5/20, queue=0/50, completed=30]");

    // When/Then
    mockMvc
        .perform(get("/admin/thread-pools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_execution_pool").exists())
        .andExpect(jsonPath("$.storage_pool").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void testInfo_ReturnsSystemInfo() throws Exception {
    // When/Then
    mockMvc
        .perform(get("/admin/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("Drools Rule Engine Microservice"))
        .andExpect(jsonPath("$.version").value("1.0.0"))
        .andExpect(jsonPath("$.java_version").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  // ===== Helper Methods =====

  private Rule createTestRule(String ruleId) {
    RuleMetadata metadata =
        new RuleMetadata(
            "1.0",
            LocalDateTime.now(),
            LocalDateTime.now(),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            0,
            0.0);
    String content =
        "package com.company.rules\n"
            + "rule \""
            + ruleId
            + "\"\n"
            + "when\n"
            + "    $data : java.util.Map()\n"
            + "then\n"
            + "    // test rule\n"
            + "end";
    return new Rule(ruleId, content, metadata);
  }
}
