package com.company.drools.api.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.drools.cache.RuleRefreshPublisher;
import com.company.drools.config.ThreadPoolConfig;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.S3RuleStorage;
import com.company.drools.storage.StorageFactory;
import com.company.drools.testutil.ValidationConfigTestHelper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import software.amazon.awssdk.services.s3.S3Client;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

  private MockMvc mockMvc;

  @Mock private DroolsEngineService droolsEngineService;
  @Mock private StorageFactory storageFactory;
  @Mock private RuleRefreshPublisher refreshPublisher;
  @Mock private ThreadPoolConfig threadPoolConfig;
  @Mock private RuleStorage mockStorage;

  private AdminController adminController;

  @BeforeEach
  void setUp() {
    // Use SimpleMeterRegistry to avoid complex Timer/Counter mocking
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    adminController =
        new AdminController(
            droolsEngineService,
            storageFactory,
            meterRegistry,
            threadPoolConfig,
            null,
            null,
            null,
            null,
            refreshPublisher);
    // Default: redis disabled — cache section in /admin/health reports "off"
    ReflectionTestUtils.setField(adminController, "redisEnabled", false);

    // Create a Spring context with ValidationConfig so @ValidRuleId validator works.
    // The SpringConstraintValidatorFactory allows Hibernate Validator to inject Spring beans
    // into custom ConstraintValidators like RuleIdValidator.
    AnnotationConfigApplicationContext appContext = new AnnotationConfigApplicationContext();
    appContext.registerBean(
        "validationConfig",
        com.company.drools.config.ValidationConfig.class,
        ValidationConfigTestHelper::createTestValidationConfig);
    appContext.refresh();

    LocalValidatorFactoryBean validatorFactory = new LocalValidatorFactoryBean();
    validatorFactory.setApplicationContext(appContext);
    // afterPropertiesSet() automatically configures SpringConstraintValidatorFactory when app
    // context is set
    validatorFactory.afterPropertiesSet();

    // Build MockMvc with validator
    mockMvc =
        MockMvcBuilders.standaloneSetup(adminController).setValidator(validatorFactory).build();

    // Default: storageFactory returns our mock storage
    lenient().when(storageFactory.createRuleStorage()).thenReturn(mockStorage);
  }

  // ===== Health Check Tests (6) =====

  @Test
  @DisplayName("Health: all components UP returns overall UP")
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
  @DisplayName("Health: drools DOWN returns overall DOWN")
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
  @DisplayName("Health: storage DOWN returns overall DOWN")
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
  @DisplayName("Health: cache DOWN does not affect overall status")
  void testHealth_CacheDisabled_ReportsOff() throws Exception {
    // Given - drools and storage are healthy, Redis disabled (default in setUp)
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // When/Then - cache section reports off when Redis is disabled, overall stays UP
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.cache.details.enabled").value(false))
        .andExpect(jsonPath("$.components.cache.details.mode").value("off"))
        .andExpect(jsonPath("$.components.drools.status").value("UP"));
  }

  @Test
  @DisplayName("Health: circuit breakers null shows circuit_breakers_enabled=false")
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
  @DisplayName("Health: includes detailed component information")
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
        .andExpect(jsonPath("$.components.drools.details.cache_hit_rate").value(0.0))
        .andExpect(jsonPath("$.components.storage.details.total_rules").value(10))
        .andExpect(jsonPath("$.components.cache.details.enabled").value(false))
        .andExpect(jsonPath("$.components.cache.details.mode").value("off"));
  }

  // ===== Rule Management Tests (5) =====

  @Test
  @DisplayName("Refresh all rules: success reloads all rules and warms cache")
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

    // Verify storage cache was invalidated before reload (decorator no-op when Redis off)
    verify(mockStorage).refreshCache();
    // Verify pub/sub fan-out (no-op when publisher null, but here it's mocked)
    verify(refreshPublisher).publishBulkRefresh();
  }

  @Test
  @DisplayName("Refresh all rules: compilation failure returns errors")
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
  @DisplayName("Refresh rule: success updates rule in engine and cache")
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
    when(droolsEngineService.loadOrReplaceRule(org.mockito.ArgumentMatchers.any(Rule.class)))
        .thenReturn(true);

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("success"));

    // Verify the merge primitive was used, not the full-replace loadRules.
    verify(droolsEngineService).loadOrReplaceRule(rule);
    verify(droolsEngineService, never()).loadRules(anyList());
    // Verify storage cache was invalidated (decorator no-op when Redis off)
    verify(mockStorage).refreshRule(ruleId);
    // Verify pub/sub fan-out
    verify(refreshPublisher).publishRefresh(ruleId);
  }

  @Test
  @DisplayName("Refresh rule: not found returns 404")
  void testRefreshRule_NotFound_Returns404() throws Exception {
    // Given
    String ruleId = "nonexistent.rule";
    when(droolsEngineService.getRuleMetadata(ruleId)).thenReturn(null);
    when(mockStorage.getRule(ruleId)).thenReturn(Optional.empty());

    // When/Then
    mockMvc.perform(post("/admin/refresh-rules/{ruleId}", ruleId)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Refresh rule: invalidates cache in correct order")
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
    when(droolsEngineService.loadOrReplaceRule(org.mockito.ArgumentMatchers.any(Rule.class)))
        .thenReturn(true);

    // When
    mockMvc.perform(post("/admin/refresh-rules/{ruleId}", ruleId)).andExpect(status().isOk());

    // Then - storage invalidate → engine merge → publisher fan-out, in that order
    InOrder inOrder = inOrder(mockStorage, droolsEngineService, refreshPublisher);
    inOrder.verify(mockStorage).refreshRule(ruleId);
    inOrder.verify(droolsEngineService).loadOrReplaceRule(rule);
    inOrder.verify(refreshPublisher).publishRefresh(ruleId);
  }

  // ===== Listing & Info Tests (4) =====

  @Test
  @DisplayName("List rules: returns all loaded rules with metadata")
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
  @DisplayName("Thread pool stats: returns pool statistics with timestamp")
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
  @DisplayName("Info: returns application metadata")
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

  // ===== Circuit Breaker Health Tests =====

  @Test
  @DisplayName("Health: S3 circuit breaker non-null returns s3_state and s3_metrics")
  void testHealth_S3CircuitBreakerPresent_IncludesS3Details() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Inject a real CircuitBreaker via reflection
    CircuitBreaker s3CB = CircuitBreaker.ofDefaults("s3");
    ReflectionTestUtils.setField(adminController, "s3CircuitBreaker", s3CB);

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.circuit-breakers.status").value("UP"))
          .andExpect(jsonPath("$.components.circuit-breakers.details.s3_state").value("CLOSED"))
          .andExpect(jsonPath("$.components.circuit-breakers.details.s3_metrics").exists())
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.s3_metrics.failure_rate").exists())
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.s3_metrics.successful_calls")
                  .exists())
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.s3_metrics.failed_calls").exists())
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.s3_metrics.not_permitted_calls")
                  .exists())
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.circuit_breakers_enabled")
                  .value(true));
    } finally {
      ReflectionTestUtils.setField(adminController, "s3CircuitBreaker", null);
    }
  }

  @Test
  @DisplayName("Health: Redis circuit breaker non-null returns redis_state and redis_metrics")
  void testHealth_RedisCircuitBreakerPresent_IncludesRedisDetails() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Inject a real CircuitBreaker for redis via reflection
    CircuitBreaker redisCB = CircuitBreaker.ofDefaults("redis");
    ReflectionTestUtils.setField(adminController, "redisCircuitBreaker", redisCB);

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.circuit-breakers.status").value("UP"))
          .andExpect(jsonPath("$.components.circuit-breakers.details.redis_state").value("CLOSED"))
          .andExpect(jsonPath("$.components.circuit-breakers.details.redis_metrics").exists())
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.circuit_breakers_enabled")
                  .value(true));
    } finally {
      ReflectionTestUtils.setField(adminController, "redisCircuitBreaker", null);
    }
  }

  @Test
  @DisplayName("Health: both S3 and Redis circuit breakers present shows both details")
  void testHealth_BothCircuitBreakersPresent_IncludesBothDetails() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Inject both circuit breakers
    CircuitBreaker s3CB = CircuitBreaker.ofDefaults("s3");
    CircuitBreaker redisCB = CircuitBreaker.ofDefaults("redis");
    ReflectionTestUtils.setField(adminController, "s3CircuitBreaker", s3CB);
    ReflectionTestUtils.setField(adminController, "redisCircuitBreaker", redisCB);

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.circuit-breakers.details.s3_state").value("CLOSED"))
          .andExpect(jsonPath("$.components.circuit-breakers.details.s3_metrics").exists())
          .andExpect(jsonPath("$.components.circuit-breakers.details.redis_state").value("CLOSED"))
          .andExpect(jsonPath("$.components.circuit-breakers.details.redis_metrics").exists())
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.circuit_breakers_enabled")
                  .value(true));
    } finally {
      ReflectionTestUtils.setField(adminController, "s3CircuitBreaker", null);
      ReflectionTestUtils.setField(adminController, "redisCircuitBreaker", null);
    }
  }

  @Test
  @DisplayName("Health: circuit breaker metrics exception returns UP with error detail")
  void testHealth_CircuitBreakerMetricsException_ReturnsUpWithError() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Inject a mock circuit breaker that throws on getMetrics()
    CircuitBreaker mockCB = mock(CircuitBreaker.class);
    when(mockCB.getState()).thenReturn(CircuitBreaker.State.CLOSED);
    when(mockCB.getMetrics()).thenThrow(new RuntimeException("Metrics unavailable"));
    ReflectionTestUtils.setField(adminController, "s3CircuitBreaker", mockCB);

    try {
      // When/Then - circuit breaker errors don't affect health; returns UP with error detail
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.circuit-breakers.status").value("UP"))
          .andExpect(
              jsonPath("$.components.circuit-breakers.details.error").value("Metrics unavailable"));
    } finally {
      ReflectionTestUtils.setField(adminController, "s3CircuitBreaker", null);
    }
  }

  // ===== Redis Health Tests =====

  @Test
  @DisplayName("Health: Redis enabled with successful ping shows connected=true")
  void testHealth_RedisEnabledAndConnected_ShowsConnectedTrue() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Enable Redis and inject mock connection factory
    ReflectionTestUtils.setField(adminController, "redisEnabled", true);
    RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
    RedisConnection mockConnection = mock(RedisConnection.class);
    when(mockFactory.getConnection()).thenReturn(mockConnection);
    when(mockConnection.ping()).thenReturn("PONG");
    ReflectionTestUtils.setField(adminController, "redisConnectionFactory", mockFactory);

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.redis.status").value("UP"))
          .andExpect(jsonPath("$.components.redis.details.connected").value(true));
    } finally {
      ReflectionTestUtils.setField(adminController, "redisEnabled", false);
      ReflectionTestUtils.setField(adminController, "redisConnectionFactory", null);
    }
  }

  @Test
  @DisplayName("Health: Redis enabled but connectionFactory null shows enabled=false")
  void testHealth_RedisEnabledButFactoryNull_ShowsEnabledFalse() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Enable Redis but leave connectionFactory null
    ReflectionTestUtils.setField(adminController, "redisEnabled", true);
    ReflectionTestUtils.setField(adminController, "redisConnectionFactory", null);

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.redis.status").value("UP"))
          .andExpect(jsonPath("$.components.redis.details.enabled").value(false));
    } finally {
      ReflectionTestUtils.setField(adminController, "redisEnabled", false);
    }
  }

  @Test
  @DisplayName("Health: Redis enabled but ping throws exception shows connected=false")
  void testHealth_RedisEnabledPingFails_ShowsConnectedFalse() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Enable Redis with a factory that throws on getConnection
    ReflectionTestUtils.setField(adminController, "redisEnabled", true);
    RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
    when(mockFactory.getConnection()).thenThrow(new RuntimeException("Connection refused"));
    ReflectionTestUtils.setField(adminController, "redisConnectionFactory", mockFactory);

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.redis.status").value("DOWN"))
          .andExpect(jsonPath("$.components.redis.details.connected").value(false))
          .andExpect(jsonPath("$.components.redis.details.error").value("Connection refused"));
    } finally {
      ReflectionTestUtils.setField(adminController, "redisEnabled", false);
      ReflectionTestUtils.setField(adminController, "redisConnectionFactory", null);
    }
  }

  @Test
  @DisplayName("Health: Redis enabled shows cache_decorator label in redis details")
  void testHealth_RedisEnabled_ShowsCacheDecorator() throws Exception {
    // Given - healthy drools and storage
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // Enable Redis and inject mock connection factory
    ReflectionTestUtils.setField(adminController, "redisEnabled", true);
    RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
    RedisConnection mockConnection = mock(RedisConnection.class);
    when(mockFactory.getConnection()).thenReturn(mockConnection);
    when(mockConnection.ping()).thenReturn("PONG");
    ReflectionTestUtils.setField(adminController, "redisConnectionFactory", mockFactory);

    try {
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.redis.status").value("UP"))
          .andExpect(jsonPath("$.components.redis.details.connected").value(true))
          .andExpect(
              jsonPath("$.components.redis.details.cache_decorator")
                  .value("RedisCachedRuleStorage"))
          .andExpect(jsonPath("$.components.cache.details.enabled").value(true))
          .andExpect(jsonPath("$.components.cache.details.mode").value("redis"));
    } finally {
      ReflectionTestUtils.setField(adminController, "redisEnabled", false);
      ReflectionTestUtils.setField(adminController, "redisConnectionFactory", null);
    }
  }

  // ===== Storage Health Tests =====

  @Test
  @DisplayName("Health: S3RuleStorage with accessible bucket shows s3_accessible=true")
  void testHealth_S3StorageAccessible_ShowsS3AccessibleTrue() throws Exception {
    // Given - healthy drools
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);

    // Create a mock S3RuleStorage and configure storageFactory to return it
    S3RuleStorage mockS3Storage = mock(S3RuleStorage.class);
    when(mockS3Storage.getTotalRuleCount()).thenReturn(5L);
    when(storageFactory.createRuleStorage()).thenReturn(mockS3Storage);

    // Inject S3 client and bucket name
    S3Client mockS3Client = mock(S3Client.class);
    ReflectionTestUtils.setField(adminController, "s3Client", mockS3Client);
    ReflectionTestUtils.setField(adminController, "s3BucketName", "test-bucket");
    ReflectionTestUtils.setField(adminController, "ruleSource", "s3");

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.storage.status").value("UP"))
          .andExpect(jsonPath("$.components.storage.details.s3_bucket").value("test-bucket"))
          .andExpect(jsonPath("$.components.storage.details.s3_accessible").value(true))
          .andExpect(jsonPath("$.components.storage.details.total_rules").value(5));
    } finally {
      ReflectionTestUtils.setField(adminController, "s3Client", null);
      ReflectionTestUtils.setField(adminController, "s3BucketName", "");
      ReflectionTestUtils.setField(adminController, "ruleSource", "memory");
    }
  }

  @Test
  @DisplayName("Health: S3 headBucket throws exception shows DOWN with s3_accessible=false")
  void testHealth_S3HeadBucketFails_ShowsDownWithS3Error() throws Exception {
    // Given - healthy drools
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);

    // Create a mock S3RuleStorage
    S3RuleStorage mockS3Storage = mock(S3RuleStorage.class);
    when(storageFactory.createRuleStorage()).thenReturn(mockS3Storage);

    // Inject S3 client that throws on headBucket
    S3Client mockS3Client = mock(S3Client.class);
    when(mockS3Client.headBucket(org.mockito.ArgumentMatchers.<java.util.function.Consumer>any()))
        .thenThrow(new RuntimeException("Bucket not found"));
    ReflectionTestUtils.setField(adminController, "s3Client", mockS3Client);
    ReflectionTestUtils.setField(adminController, "s3BucketName", "nonexistent-bucket");

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("DOWN"))
          .andExpect(jsonPath("$.components.storage.status").value("DOWN"))
          .andExpect(jsonPath("$.components.storage.details.s3_bucket").value("nonexistent-bucket"))
          .andExpect(jsonPath("$.components.storage.details.s3_accessible").value(false))
          .andExpect(jsonPath("$.components.storage.details.s3_error").value("Bucket not found"));
    } finally {
      ReflectionTestUtils.setField(adminController, "s3Client", null);
      ReflectionTestUtils.setField(adminController, "s3BucketName", "");
    }
  }

  @Test
  @DisplayName("Health: non-S3 storage does not perform S3 checks")
  void testHealth_NonS3Storage_NoS3Checks() throws Exception {
    // Given - healthy drools and a non-S3 storage (mockStorage is just RuleStorage)
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(3L);

    // When/Then - no s3_bucket or s3_accessible fields should be present
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.storage.status").value("UP"))
        .andExpect(jsonPath("$.components.storage.details.total_rules").value(3))
        .andExpect(jsonPath("$.components.storage.details.s3_bucket").doesNotExist())
        .andExpect(jsonPath("$.components.storage.details.s3_accessible").doesNotExist());
  }

  // ===== Refresh Rule Error Path Tests =====

  @Test
  @DisplayName("Refresh rule: loadRules returns false returns 500 with error status")
  void testRefreshRule_LoadRulesFails_Returns500() throws Exception {
    // Given
    String ruleId = "pricing.discount.simple";
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
    when(droolsEngineService.loadOrReplaceRule(org.mockito.ArgumentMatchers.any(Rule.class)))
        .thenReturn(false);

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.error").value("Failed to compile rule"));

    // Verify storage cache was invalidated but pub/sub NOT fired (refresh failed)
    verify(mockStorage).refreshRule(ruleId);
    verify(refreshPublisher, never()).publishRefresh(anyString());
  }

  @Test
  @DisplayName("Refresh rule: exception thrown returns 500 with error message")
  void testRefreshRule_ExceptionThrown_Returns500() throws Exception {
    // Given
    String ruleId = "pricing.discount.simple";
    when(droolsEngineService.getRuleMetadata(ruleId))
        .thenThrow(new RuntimeException("Engine unavailable"));

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.error").value("Engine unavailable"));
  }

  @Test
  @DisplayName("Refresh rule: null metadata but rule found in storage succeeds")
  void testRefreshRule_NullMetadata_RuleFoundInStorage_Succeeds() throws Exception {
    // Given
    String ruleId = "pricing.discount.simple";
    Rule rule = createTestRule(ruleId);
    when(droolsEngineService.getRuleMetadata(ruleId)).thenReturn(null);
    when(mockStorage.getRule(ruleId)).thenReturn(Optional.of(rule));
    when(droolsEngineService.loadOrReplaceRule(org.mockito.ArgumentMatchers.any(Rule.class)))
        .thenReturn(true);

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("success"))
        .andExpect(jsonPath("$.previous_version").isEmpty());
  }

  // ===== Refresh All Rules Error Path Tests =====

  @Test
  @DisplayName("Refresh all rules: storage exception returns error with SYSTEM rule_id")
  void testRefreshAllRules_StorageException_ReturnsSystemError() throws Exception {
    // Given - storage throws an exception
    when(storageFactory.createRuleStorage())
        .thenThrow(new RuntimeException("S3 connection timeout"));

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed_with_errors"))
        .andExpect(jsonPath("$.rules_loaded").value(0))
        .andExpect(jsonPath("$.rules_failed").value(1))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].rule_id").value("SYSTEM"))
        .andExpect(jsonPath("$.errors[0].error").value("S3 connection timeout"));
  }

  @Test
  @DisplayName("Refresh all rules: storage.refreshCache called once before reload")
  void testRefreshAllRules_InvalidatesCacheOnce() throws Exception {
    // Given
    Rule rule1 = createTestRule("pricing.discount.simple");
    when(mockStorage.getAllRules()).thenReturn(List.of(rule1));
    when(droolsEngineService.loadRules(anyList())).thenReturn(true);

    // When/Then
    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.rules_loaded").value(1));

    // Verify storage.refreshCache() called once and refreshPublisher fired
    verify(mockStorage, times(1)).refreshCache();
    verify(refreshPublisher, times(1)).publishBulkRefresh();
  }

  // ===== List Rules Error Tests =====

  @Test
  @DisplayName("List rules: exception returns 500")
  void testListRules_Exception_Returns500() throws Exception {
    // Given
    when(droolsEngineService.getAllRuleMetadata()).thenThrow(new RuntimeException("Engine error"));

    // When/Then
    mockMvc.perform(get("/admin/rules")).andExpect(status().isInternalServerError());
  }

  @Test
  @DisplayName("List rules: empty rules returns total_rules=0")
  void testListRules_EmptyRules_ReturnsTotalZero() throws Exception {
    // Given
    when(droolsEngineService.getAllRuleMetadata()).thenReturn(Collections.emptyMap());

    // When/Then
    mockMvc
        .perform(get("/admin/rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_rules").value(0))
        .andExpect(jsonPath("$.rules", hasSize(0)));
  }

  // ===== Thread Pool Stats Error Test =====

  @Test
  @DisplayName("Thread pool stats: exception propagates as ServletException")
  void testThreadPoolStats_Exception_PropagatesException() {
    // Given
    when(threadPoolConfig.getRuleExecutionPoolStats())
        .thenThrow(new RuntimeException("Pool not initialized"));

    // When/Then - the controller re-throws the exception, which MockMvc wraps in ServletException
    Exception thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            jakarta.servlet.ServletException.class,
            () -> mockMvc.perform(get("/admin/thread-pools")));

    // Verify the root cause message
    org.junit.jupiter.api.Assertions.assertTrue(
        thrown.getCause().getMessage().contains("Pool not initialized"));
  }

  // ===== Drools Health Exception Test =====

  @Test
  @DisplayName("Health: Drools engine exception returns DOWN with error details")
  void testHealth_DroolsEngineThrowsException_ReturnsDown() throws Exception {
    // Given - drools engine throws
    when(droolsEngineService.getLoadedRulesCount())
        .thenThrow(new RuntimeException("Engine crashed"));
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // When/Then
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.components.drools.status").value("DOWN"))
        .andExpect(jsonPath("$.components.drools.details.error").value("Engine crashed"));
  }

  // ===== Cache Disabled Tests =====

  @Test
  @DisplayName("Health: cache disabled (Redis off) shows hit_rate 0.0 in drools details")
  void testHealth_CacheDisabled_ShowsZeroHitRate() throws Exception {
    // Given - default setUp already has redisEnabled=false
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(5);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(5L);
    when(mockStorage.getTotalRuleCount()).thenReturn(5L);

    // When/Then
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.drools.details.cache_hit_rate").value(0.0))
        .andExpect(jsonPath("$.components.cache.details.enabled").value(false))
        .andExpect(jsonPath("$.components.cache.details.mode").value("off"))
        .andExpect(jsonPath("$.components.cache.details.statistics").doesNotExist());
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
