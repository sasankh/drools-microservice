package com.company.drools.api.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.drools.cache.RuleRefreshPublisher;
import com.company.drools.config.RulesHealthIndicator;
import com.company.drools.config.ThreadPoolConfig;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleLoadState;
import com.company.drools.core.engine.RuleRefreshService;
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
  @Mock private RuleRefreshService refreshService;
  @Mock private RuleLoadState loadState;

  private SimpleMeterRegistry meterRegistry;
  private LocalValidatorFactoryBean validatorFactory;
  private AdminController adminController;

  @BeforeEach
  void setUp() {
    // Use SimpleMeterRegistry to avoid complex Timer/Counter mocking
    meterRegistry = new SimpleMeterRegistry();

    // Default: the startup load has succeeded. The real RulesHealthIndicator then decides UP/DOWN
    // from droolsEngineService.getLoadedRulesCount() (and error metadata).
    lenient().when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.LOADED);

    // Create a Spring context with ValidationConfig so @ValidRuleId validator works.
    // The SpringConstraintValidatorFactory allows Hibernate Validator to inject Spring beans
    // into custom ConstraintValidators like RuleIdValidator.
    AnnotationConfigApplicationContext appContext = new AnnotationConfigApplicationContext();
    appContext.registerBean(
        "validationConfig",
        com.company.drools.config.ValidationConfig.class,
        ValidationConfigTestHelper::createTestValidationConfig);
    appContext.refresh();

    validatorFactory = new LocalValidatorFactoryBean();
    validatorFactory.setApplicationContext(appContext);
    // afterPropertiesSet() automatically configures SpringConstraintValidatorFactory when app
    // context is set
    validatorFactory.afterPropertiesSet();

    adminController = newController(refreshPublisher);
    mockMvc = buildMockMvc(adminController);

    // Default: storageFactory returns our mock storage
    lenient().when(storageFactory.createRuleStorage()).thenReturn(mockStorage);
  }

  private AdminController newController(RuleRefreshPublisher publisher) {
    AdminController controller =
        new AdminController(
            droolsEngineService,
            storageFactory,
            meterRegistry,
            threadPoolConfig,
            null,
            null,
            null,
            null,
            publisher,
            refreshService,
            new RulesHealthIndicator(droolsEngineService, loadState, false));
    // Default: redis disabled — cache section in /admin/health reports "off"
    ReflectionTestUtils.setField(controller, "redisEnabled", false);
    return controller;
  }

  private MockMvc buildMockMvc(AdminController controller) {
    return MockMvcBuilders.standaloneSetup(controller).setValidator(validatorFactory).build();
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
  @DisplayName("Health: no rules loaded → drools DOWN, overall DOWN, HTTP 503")
  void testHealth_DroolsDown_ReturnsDown() throws Exception {
    // Given - startup load succeeded but no rule ID compiled
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(0);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(0L);
    when(mockStorage.getTotalRuleCount()).thenReturn(0L);

    // When/Then
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.components.drools.status").value("DOWN"))
        .andExpect(jsonPath("$.components.drools.details.error").value("no rules loaded"));
  }

  @Test
  @DisplayName("Health: startup load not succeeded (NOT_LOADED / FAILED) → DOWN, HTTP 503")
  void testHealth_StartupLoadNotSucceeded_ReturnsDown() throws Exception {
    for (RuleLoadState.Phase phase :
        List.of(RuleLoadState.Phase.NOT_LOADED, RuleLoadState.Phase.FAILED)) {
      when(loadState.getPhase()).thenReturn(phase);
      // Even with rules compiled, a not-yet-successful startup load is not ready
      when(droolsEngineService.getLoadedRulesCount()).thenReturn(3);
      when(mockStorage.getTotalRuleCount()).thenReturn(3L);

      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.status").value("DOWN"))
          .andExpect(jsonPath("$.components.drools.status").value("DOWN"))
          .andExpect(jsonPath("$.components.drools.details.phase").value(phase.name()))
          .andExpect(
              jsonPath("$.components.drools.details.error")
                  .value(containsString("startup rule load has not succeeded")));
    }
  }

  @Test
  @DisplayName("Health: storage DOWN while rules serve → DEGRADED, HTTP 200")
  void testHealth_StorageDown_ReturnsDegraded() throws Exception {
    // Given - drools is healthy
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(3);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(3L);
    // Storage throws exception (S3 inaccessible)
    when(storageFactory.createRuleStorage())
        .thenThrow(new RuntimeException("S3 connection refused"));

    // When/Then - compiled rules keep serving, so the instance stays in rotation
    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEGRADED"))
        .andExpect(jsonPath("$.components.drools.status").value("UP"))
        .andExpect(jsonPath("$.components.storage.status").value("DOWN"))
        .andExpect(jsonPath("$.components.storage.details.error").value("S3 connection refused"));
  }

  @Test
  @DisplayName("Health: storage DOWN and startup load failed → DOWN (not DEGRADED), HTTP 503")
  void testHealth_StorageDownAndLoadFailed_ReturnsDown() throws Exception {
    when(loadState.getPhase()).thenReturn(RuleLoadState.Phase.FAILED);
    when(storageFactory.createRuleStorage()).thenThrow(new RuntimeException("S3 down"));

    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.components.storage.status").value("DOWN"));
  }

  @Test
  @DisplayName("Health: per-ID compile errors are listed but the instance stays UP")
  void testHealth_RuleErrors_ListedButUp() throws Exception {
    when(droolsEngineService.getLoadedRulesCount()).thenReturn(1);
    when(mockStorage.getTotalRuleCount()).thenReturn(2L);
    Map<String, RuleMetadata> meta = new LinkedHashMap<>();
    meta.put(
        "ok.rule",
        new RuleMetadata(
            "1",
            LocalDateTime.now(),
            LocalDateTime.now(),
            RuleMetadata.RuleStatus.ACTIVE,
            null,
            0,
            0.0));
    meta.put("bad.rule", RuleMetadata.createNew().withError("compile error"));
    when(droolsEngineService.getAllRuleMetadata()).thenReturn(meta);

    mockMvc
        .perform(get("/admin/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.drools.status").value("UP"))
        .andExpect(jsonPath("$.components.drools.details.rules_with_errors").value(1))
        .andExpect(jsonPath("$.components.drools.details.error_rule_ids[0]").value("bad.rule"));
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

  // ===== Refresh-all Tests =====

  private static RuleRefreshService.BulkResult bulkLoaded(
      List<String> loaded,
      List<String> unchanged,
      Map<String, String> failed,
      List<String> removed) {
    return new RuleRefreshService.BulkResult(
        RuleRefreshService.BulkStatus.LOADED,
        new DroolsEngineService.LoadReport(loaded, unchanged, failed, removed),
        null);
  }

  private void givenBulk(RuleRefreshService.BulkResult result) {
    when(refreshService.refreshAll(
            eq(RuleRefreshService.Source.ADMIN),
            eq(RuleRefreshService.ReadMode.INVALIDATE_THEN_READ),
            any()))
        .thenReturn(result);
  }

  @Test
  @DisplayName("Refresh all: invalidate-then-read ADMIN refresh; counts reported; bulk event sent")
  void testRefreshAllRules_Success_ReportsCountsAndPublishes() throws Exception {
    givenBulk(bulkLoaded(List.of("a", "b"), List.of("c"), Map.of(), List.of("d")));

    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        // rules_loaded = every rule ID serving from this snapshot (recompiled or unchanged)
        .andExpect(jsonPath("$.rules_loaded").value(3))
        .andExpect(jsonPath("$.rules_unchanged").value(1))
        .andExpect(jsonPath("$.rules_removed").value(1))
        .andExpect(jsonPath("$.rules_failed").value(0))
        .andExpect(jsonPath("$.errors", hasSize(0)));

    verify(refreshService)
        .refreshAll(
            eq(RuleRefreshService.Source.ADMIN),
            eq(RuleRefreshService.ReadMode.INVALIDATE_THEN_READ),
            any());
    verify(refreshPublisher, times(1)).publishBulkRefresh();
  }

  @Test
  @DisplayName(
      "Refresh all: nothing changed here → 200, bulk event still sent (siblings may be stale)")
  void testRefreshAllRules_AllUnchanged_StillPublishes() throws Exception {
    givenBulk(bulkLoaded(List.of(), List.of("a", "b"), Map.of(), List.of()));

    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.rules_loaded").value(2))
        .andExpect(jsonPath("$.rules_unchanged").value(2))
        .andExpect(jsonPath("$.rules_removed").value(0));

    verify(refreshPublisher).publishBulkRefresh();
  }

  @Test
  @DisplayName("Refresh all: per-ID compile failure → completed_with_errors listing that ID")
  void testRefreshAllRules_CompilationFailure_ListsPerIdErrors() throws Exception {
    givenBulk(
        bulkLoaded(
            List.of("pricing.discount.ok"),
            List.of(),
            Map.of("pricing.discount.broken", "Rule Compilation error"),
            List.of()));

    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed_with_errors"))
        .andExpect(jsonPath("$.rules_loaded").value(1))
        .andExpect(jsonPath("$.rules_failed").value(1))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].rule_id").value("pricing.discount.broken"))
        .andExpect(jsonPath("$.errors[0].error").value(containsString("Compilation error")));

    // The rule IDs that compiled are serving here, so siblings are told
    verify(refreshPublisher).publishBulkRefresh();
  }

  @Test
  @DisplayName("Refresh all: only failures here → bulk event still sent")
  void testRefreshAllRules_OnlyFailures_StillPublishes() throws Exception {
    givenBulk(bulkLoaded(List.of(), List.of("a"), Map.of("b", "bad"), List.of()));

    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed_with_errors"))
        .andExpect(jsonPath("$.rules_loaded").value(1))
        .andExpect(jsonPath("$.rules_failed").value(1));

    verify(refreshPublisher).publishBulkRefresh();
  }

  @Test
  @DisplayName("Refresh all: storage read failed → 500 failed, SYSTEM error, nothing published")
  void testRefreshAllRules_StorageFailed_Returns500() throws Exception {
    givenBulk(
        new RuleRefreshService.BulkResult(
            RuleRefreshService.BulkStatus.STORAGE_FAILED,
            null,
            "Storage read failed; nothing changed: S3 connection timeout"));

    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value("failed"))
        .andExpect(jsonPath("$.rules_loaded").value(0))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].rule_id").value("SYSTEM"))
        .andExpect(jsonPath("$.errors[0].error").value(containsString("S3 connection timeout")));

    verify(refreshPublisher, never()).publishBulkRefresh();
  }

  @Test
  @DisplayName("Refresh all: empty storage while rules loaded → 409 rejected_empty")
  void testRefreshAllRules_RejectedEmpty_Returns409() throws Exception {
    givenBulk(
        new RuleRefreshService.BulkResult(
            RuleRefreshService.BulkStatus.REJECTED_EMPTY,
            null,
            "Storage returned 0 rules while 5 are loaded"));

    mockMvc
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("rejected_empty"))
        .andExpect(jsonPath("$.errors[0].rule_id").value("SYSTEM"))
        .andExpect(jsonPath("$.errors[0].error").value(containsString("0 rules")));

    verify(refreshPublisher, never()).publishBulkRefresh();
  }

  @Test
  @DisplayName("Refresh all: no publisher (pub/sub off) still succeeds")
  void testRefreshAllRules_NoPublisher_Succeeds() throws Exception {
    MockMvc noPubSub = buildMockMvc(newController(null));
    givenBulk(bulkLoaded(List.of("a"), List.of(), Map.of(), List.of()));

    noPubSub
        .perform(post("/admin/refresh-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"));
    verifyNoInteractions(refreshPublisher);
  }

  // ===== Refresh-one Tests =====

  private void givenSingle(String ruleId, RuleRefreshService.SingleStatus status, String error) {
    when(refreshService.refreshOne(
            eq(ruleId), eq(RuleRefreshService.Source.ADMIN), eq(true), any()))
        .thenReturn(new RuleRefreshService.SingleResult(status, "olddigest", error));
  }

  @Test
  @DisplayName("Refresh: the caller's remote address is passed to the refresh service as actor")
  void testRefresh_PassesCallerAddressAsActor() throws Exception {
    String ruleId = "pricing.discount.simple";
    when(refreshService.refreshOne(
            eq(ruleId), eq(RuleRefreshService.Source.ADMIN), eq(true), eq("10.1.2.3")))
        .thenReturn(
            new RuleRefreshService.SingleResult(
                RuleRefreshService.SingleStatus.UNCHANGED, "olddigest", null));
    when(refreshService.refreshAll(
            eq(RuleRefreshService.Source.ADMIN),
            eq(RuleRefreshService.ReadMode.INVALIDATE_THEN_READ),
            eq("10.1.2.3")))
        .thenReturn(bulkLoaded(List.of(), List.of("a"), Map.of(), List.of()));

    mockMvc
        .perform(
            post("/admin/refresh-rules/{ruleId}", ruleId)
                .with(
                    req -> {
                      req.setRemoteAddr("10.1.2.3");
                      return req;
                    }))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/admin/refresh-rules")
                .with(
                    req -> {
                      req.setRemoteAddr("10.1.2.3");
                      return req;
                    }))
        .andExpect(status().isOk());

    verify(refreshService).refreshOne(ruleId, RuleRefreshService.Source.ADMIN, true, "10.1.2.3");
    verify(refreshService)
        .refreshAll(
            RuleRefreshService.Source.ADMIN,
            RuleRefreshService.ReadMode.INVALIDATE_THEN_READ,
            "10.1.2.3");
  }

  private static RuleMetadata metadataModifiedAt(LocalDateTime modified) {
    return new RuleMetadata(
        "1.0", LocalDateTime.now(), modified, RuleMetadata.RuleStatus.ACTIVE, null, 0, 0.0);
  }

  @Test
  @DisplayName("Refresh rule: INSTALLED → 200 success with versions; single-rule event sent")
  void testRefreshRule_Installed_PublishesRefresh() throws Exception {
    String ruleId = "pricing.discount.simple";
    when(droolsEngineService.getRuleMetadata(ruleId))
        .thenReturn(metadataModifiedAt(LocalDateTime.of(2026, 1, 1, 0, 0)))
        .thenReturn(metadataModifiedAt(LocalDateTime.of(2026, 2, 1, 0, 0)));
    givenSingle(ruleId, RuleRefreshService.SingleStatus.INSTALLED, null);

    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("success"))
        // Instants serialize as epoch seconds in this standalone MockMvc: 2026-01-01Z / 2026-02-01Z
        .andExpect(jsonPath("$.previous_version", closeTo(1767225600.0, 0.001), Double.class))
        .andExpect(jsonPath("$.current_version", closeTo(1769904000.0, 0.001), Double.class));

    // invalidateCache=true: an operator refresh must bypass the shared Redis entry
    verify(refreshService)
        .refreshOne(eq(ruleId), eq(RuleRefreshService.Source.ADMIN), eq(true), any());
    verify(refreshPublisher).publishRefresh(ruleId);
  }

  @Test
  @DisplayName("Refresh rule: UNCHANGED → 200 unchanged; event still sent (siblings may be stale)")
  void testRefreshRule_Unchanged_StillPublishes() throws Exception {
    String ruleId = "pricing.discount.simple";
    when(droolsEngineService.getRuleMetadata(ruleId))
        .thenReturn(metadataModifiedAt(LocalDateTime.now()));
    givenSingle(ruleId, RuleRefreshService.SingleStatus.UNCHANGED, null);

    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("unchanged"));

    verify(refreshPublisher).publishRefresh(ruleId);
  }

  @Test
  @DisplayName("Refresh rule: null metadata (new rule) → success, previous_version empty")
  void testRefreshRule_NullMetadata_RuleFoundInStorage_Succeeds() throws Exception {
    String ruleId = "pricing.discount.simple";
    when(droolsEngineService.getRuleMetadata(ruleId)).thenReturn(null);
    givenSingle(ruleId, RuleRefreshService.SingleStatus.INSTALLED, null);

    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("success"))
        .andExpect(jsonPath("$.previous_version").isEmpty())
        .andExpect(jsonPath("$.current_version").isEmpty());
  }

  @Test
  @DisplayName("Refresh rule: REMOVED (gone from storage) → 200 removed; delete event sent")
  void testRefreshRule_Removed_PublishesDelete() throws Exception {
    String ruleId = "pricing.discount.gone";
    givenSingle(ruleId, RuleRefreshService.SingleStatus.REMOVED, null);

    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("removed"))
        .andExpect(jsonPath("$.error").value(containsString("no longer exists")));

    verify(refreshPublisher).publishDelete(ruleId);
    verify(refreshPublisher, never()).publishRefresh(anyString());
  }

  @Test
  @DisplayName("Refresh rule: NOT_FOUND → 404 with a body")
  void testRefreshRule_NotFound_Returns404() throws Exception {
    String ruleId = "nonexistent.rule";
    givenSingle(ruleId, RuleRefreshService.SingleStatus.NOT_FOUND, null);

    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("not_found"))
        .andExpect(jsonPath("$.error").value("Rule not found in storage"));

    verifyNoInteractions(refreshPublisher);
  }

  @Test
  @DisplayName("Refresh rule: compile FAILED → 500 error; previous keeps serving; no event")
  void testRefreshRule_CompileFailed_Returns500() throws Exception {
    String ruleId = "pricing.discount.simple";
    givenSingle(ruleId, RuleRefreshService.SingleStatus.FAILED, "Unable to resolve ObjectType");

    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.rule_id").value(ruleId))
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Failed to compile rule")))
        .andExpect(jsonPath("$.error").value(containsString("Unable to resolve ObjectType")));

    verify(refreshPublisher, never()).publishRefresh(anyString());
  }

  @Test
  @DisplayName("Refresh rule: STORAGE_FAILED → 500 error with the storage message; no event")
  void testRefreshRule_StorageFailed_Returns500() throws Exception {
    String ruleId = "pricing.discount.simple";
    givenSingle(
        ruleId,
        RuleRefreshService.SingleStatus.STORAGE_FAILED,
        "Storage read failed; nothing changed: S3 down");

    mockMvc
        .perform(post("/admin/refresh-rules/{ruleId}", ruleId))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.error").value(containsString("S3 down")))
        .andExpect(jsonPath("$.error").value(not(containsString("compile"))));

    verifyNoInteractions(refreshPublisher);
  }

  @Test
  @DisplayName("Refresh rule: no publisher (pub/sub off) — INSTALLED and REMOVED still 200")
  void testRefreshRule_NoPublisher_Succeeds() throws Exception {
    MockMvc noPubSub = buildMockMvc(newController(null));
    givenSingle("a.b", RuleRefreshService.SingleStatus.INSTALLED, null);
    givenSingle("c.d", RuleRefreshService.SingleStatus.REMOVED, null);

    noPubSub
        .perform(post("/admin/refresh-rules/{ruleId}", "a.b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("success"));
    noPubSub
        .perform(post("/admin/refresh-rules/{ruleId}", "c.d"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("removed"));
    verifyNoInteractions(refreshPublisher);
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
      ReflectionTestUtils.setField(adminController, "ruleSource", "local");
    }
  }

  @Test
  @DisplayName("Health: S3 headBucket fails → storage DOWN, overall DEGRADED")
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
    ReflectionTestUtils.setField(adminController, "ruleSource", "s3");

    try {
      // When/Then
      mockMvc
          .perform(get("/admin/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("DEGRADED"))
          .andExpect(jsonPath("$.components.storage.status").value("DOWN"))
          .andExpect(jsonPath("$.components.storage.details.s3_bucket").value("nonexistent-bucket"))
          .andExpect(jsonPath("$.components.storage.details.s3_accessible").value(false))
          .andExpect(jsonPath("$.components.storage.details.s3_error").value("Bucket not found"));
    } finally {
      ReflectionTestUtils.setField(adminController, "s3Client", null);
      ReflectionTestUtils.setField(adminController, "s3BucketName", "");
      ReflectionTestUtils.setField(adminController, "ruleSource", "local");
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
        .andExpect(status().isServiceUnavailable())
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
}
