package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.company.drools.cache.RuleCache;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.StorageFactory;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

@DisplayName("RuleLoadingConfig")
@ExtendWith(MockitoExtension.class)
class RuleLoadingConfigTest {

  private RuleLoadingConfig ruleLoadingConfig;

  @Mock private DroolsEngineService droolsEngineService;
  @Mock private StorageFactory storageFactory;
  @Mock private RuleCache ruleCache;
  @Mock private RuleStorage ruleStorage;
  @Mock private ApplicationArguments applicationArguments;

  @BeforeEach
  void setUp() {
    ruleLoadingConfig = new RuleLoadingConfig();
  }

  @Test
  @DisplayName("loadRulesOnStartup returns a non-null ApplicationRunner bean")
  void testLoadRulesOnStartupReturnsApplicationRunner() {
    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory, ruleCache);

    assertThat(runner).isNotNull();
  }

  @Test
  @DisplayName("runner loads rules successfully and warms up cache when cache is enabled")
  void testRunnerSuccessWithCacheEnabled() throws Exception {
    List<Rule> rules =
        List.of(
            new Rule("rule-1", "content-1", RuleMetadata.createNew()),
            new Rule("rule-2", "content-2", RuleMetadata.createNew()));

    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenReturn(rules);
    when(droolsEngineService.loadRules(rules)).thenReturn(true);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(2L);
    when(ruleCache.isEnabled()).thenReturn(true);
    when(ruleCache.size()).thenReturn(2L);

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory, ruleCache);
    runner.run(applicationArguments);

    verify(storageFactory).createRuleStorage();
    verify(ruleStorage).getAllRules();
    verify(droolsEngineService).loadRules(rules);
    verify(ruleCache).isEnabled();
    verify(ruleCache).warmUp(rules);
    verify(ruleCache).size();
  }

  @Test
  @DisplayName("runner loads rules successfully and skips cache warm-up when cache is disabled")
  void testRunnerSuccessWithCacheDisabled() throws Exception {
    List<Rule> rules = List.of(new Rule("rule-1", "content-1", RuleMetadata.createNew()));

    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenReturn(rules);
    when(droolsEngineService.loadRules(rules)).thenReturn(true);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(1L);
    when(ruleCache.isEnabled()).thenReturn(false);

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory, ruleCache);
    runner.run(applicationArguments);

    verify(storageFactory).createRuleStorage();
    verify(ruleStorage).getAllRules();
    verify(droolsEngineService).loadRules(rules);
    verify(ruleCache, atLeastOnce()).isEnabled();
    verify(ruleCache, never()).warmUp(anyList());
  }

  @Test
  @DisplayName("runner skips cache warm-up when rules list is empty even if cache is enabled")
  void testRunnerSuccessWithEmptyRulesAndCacheEnabled() throws Exception {
    List<Rule> rules = Collections.emptyList();

    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenReturn(rules);
    when(droolsEngineService.loadRules(rules)).thenReturn(true);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(0L);
    when(ruleCache.isEnabled()).thenReturn(true);

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory, ruleCache);
    runner.run(applicationArguments);

    verify(ruleCache, never()).warmUp(anyList());
  }

  @Test
  @DisplayName("runner logs error when loadRules returns false and does not warm up cache")
  void testRunnerLoadRulesFails() throws Exception {
    List<Rule> rules = List.of(new Rule("rule-1", "content-1", RuleMetadata.createNew()));

    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenReturn(rules);
    when(droolsEngineService.loadRules(rules)).thenReturn(false);

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory, ruleCache);
    runner.run(applicationArguments);

    verify(droolsEngineService).loadRules(rules);
    verify(ruleCache, never()).warmUp(anyList());
    verify(ruleCache, never()).isEnabled();
  }

  @Test
  @DisplayName("runner catches exception from storageFactory and does not crash")
  void testRunnerExceptionFromStorageFactory() throws Exception {
    when(storageFactory.createRuleStorage())
        .thenThrow(new RuntimeException("S3 connection failed"));

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory, ruleCache);

    // Should not throw - the runner catches exceptions to avoid blocking startup
    runner.run(applicationArguments);

    verify(storageFactory).createRuleStorage();
    verify(droolsEngineService, never()).loadRules(anyList());
    verify(ruleCache, never()).warmUp(anyList());
  }

  @Test
  @DisplayName("runner catches exception from getAllRules and does not crash")
  void testRunnerExceptionFromGetAllRules() throws Exception {
    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenThrow(new RuntimeException("Storage read failure"));

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory, ruleCache);

    // Should not throw
    runner.run(applicationArguments);

    verify(droolsEngineService, never()).loadRules(anyList());
    verify(ruleCache, never()).warmUp(anyList());
  }
}
