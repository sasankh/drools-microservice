package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.StorageFactory;
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
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory);
    assertThat(runner).isNotNull();
  }

  @Test
  @DisplayName("runner loads rules successfully from storage")
  void testRunnerSuccess() throws Exception {
    List<Rule> rules =
        List.of(
            new Rule("rule-1", "content-1", RuleMetadata.createNew()),
            new Rule("rule-2", "content-2", RuleMetadata.createNew()));

    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenReturn(rules);
    when(droolsEngineService.loadRules(rules)).thenReturn(true);
    when(droolsEngineService.getActiveRulesCount()).thenReturn(2L);

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory);
    runner.run(applicationArguments);

    verify(storageFactory).createRuleStorage();
    verify(ruleStorage).getAllRules();
    verify(droolsEngineService).loadRules(rules);
  }

  @Test
  @DisplayName("runner logs error when loadRules returns false (does not crash)")
  void testRunnerLoadRulesFails() throws Exception {
    List<Rule> rules = List.of(new Rule("rule-1", "content-1", RuleMetadata.createNew()));

    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenReturn(rules);
    when(droolsEngineService.loadRules(rules)).thenReturn(false);

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory);
    runner.run(applicationArguments);

    verify(droolsEngineService).loadRules(rules);
  }

  @Test
  @DisplayName("runner catches exception from storageFactory and does not crash")
  void testRunnerExceptionFromStorageFactory() throws Exception {
    when(storageFactory.createRuleStorage())
        .thenThrow(new RuntimeException("S3 connection failed"));

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory);

    runner.run(applicationArguments);

    verify(storageFactory).createRuleStorage();
    verify(droolsEngineService, never()).loadRules(anyList());
  }

  @Test
  @DisplayName("runner catches exception from getAllRules and does not crash")
  void testRunnerExceptionFromGetAllRules() throws Exception {
    when(storageFactory.createRuleStorage()).thenReturn(ruleStorage);
    when(ruleStorage.getAllRules()).thenThrow(new RuntimeException("Storage read failure"));

    ApplicationRunner runner =
        ruleLoadingConfig.loadRulesOnStartup(droolsEngineService, storageFactory);

    runner.run(applicationArguments);

    verify(droolsEngineService, never()).loadRules(anyList());
  }
}
