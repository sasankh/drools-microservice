package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleRefreshService;
import com.company.drools.core.engine.RuleRefreshService.BulkResult;
import com.company.drools.core.engine.RuleRefreshService.BulkStatus;
import com.company.drools.core.engine.RuleRefreshService.ReadMode;
import com.company.drools.core.engine.RuleRefreshService.Source;
import java.util.List;
import java.util.Map;
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

  @Mock private RuleRefreshService refreshService;
  @Mock private ApplicationArguments applicationArguments;

  @BeforeEach
  void setUp() {
    ruleLoadingConfig = new RuleLoadingConfig();
  }

  private static BulkResult loaded(List<String> ok, Map<String, String> failed) {
    return new BulkResult(
        BulkStatus.LOADED,
        new DroolsEngineService.LoadReport(ok, List.of(), failed, List.of()),
        null);
  }

  @Test
  @DisplayName("loadRulesOnStartup returns a non-null ApplicationRunner bean")
  void testLoadRulesOnStartupReturnsApplicationRunner() {
    ApplicationRunner runner = ruleLoadingConfig.loadRulesOnStartup(refreshService, false);
    assertThat(runner).isNotNull();
  }

  @Test
  @DisplayName("runner performs a STARTUP refresh with a normal (cached) read")
  void testRunnerSuccess() throws Exception {
    when(refreshService.refreshAll(Source.STARTUP, ReadMode.CACHED))
        .thenReturn(loaded(List.of("rule-1", "rule-2"), Map.of()));

    ruleLoadingConfig.loadRulesOnStartup(refreshService, true).run(applicationArguments);

    verify(refreshService).refreshAll(Source.STARTUP, ReadMode.CACHED);
  }

  @Test
  @DisplayName("per-ID compile failures never fail startup, even with fail-on-load-error")
  void testRunnerCompileFailuresDoNotThrow() {
    when(refreshService.refreshAll(Source.STARTUP, ReadMode.CACHED))
        .thenReturn(loaded(List.of("rule-1"), Map.of("rule-2", "syntax error")));

    ApplicationRunner runner = ruleLoadingConfig.loadRulesOnStartup(refreshService, true);

    assertThatCode(() -> runner.run(applicationArguments)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("storage failure without fail-on-load-error: logs, does not crash")
  void testRunnerStorageFailedDoesNotThrowByDefault() {
    when(refreshService.refreshAll(Source.STARTUP, ReadMode.CACHED))
        .thenReturn(new BulkResult(BulkStatus.STORAGE_FAILED, null, "S3 connection failed"));

    ApplicationRunner runner = ruleLoadingConfig.loadRulesOnStartup(refreshService, false);

    assertThatCode(() -> runner.run(applicationArguments)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("storage failure with fail-on-load-error=true: startup fails")
  void testRunnerStorageFailedThrowsWhenConfigured() {
    when(refreshService.refreshAll(Source.STARTUP, ReadMode.CACHED))
        .thenReturn(new BulkResult(BulkStatus.STORAGE_FAILED, null, "S3 connection failed"));

    ApplicationRunner runner = ruleLoadingConfig.loadRulesOnStartup(refreshService, true);

    assertThatThrownBy(() -> runner.run(applicationArguments))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("S3 connection failed");
  }

  @Test
  @DisplayName("rejected-empty with fail-on-load-error=true: startup fails")
  void testRunnerRejectedEmptyThrowsWhenConfigured() {
    when(refreshService.refreshAll(Source.STARTUP, ReadMode.CACHED))
        .thenReturn(new BulkResult(BulkStatus.REJECTED_EMPTY, null, "Storage returned 0 rules"));

    ApplicationRunner runner = ruleLoadingConfig.loadRulesOnStartup(refreshService, true);

    assertThatThrownBy(() -> runner.run(applicationArguments))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0 rules");
  }

  @Test
  @DisplayName("rejected-empty without fail-on-load-error: logs, does not crash")
  void testRunnerRejectedEmptyDoesNotThrowByDefault() {
    when(refreshService.refreshAll(Source.STARTUP, ReadMode.CACHED))
        .thenReturn(new BulkResult(BulkStatus.REJECTED_EMPTY, null, "Storage returned 0 rules"));

    ApplicationRunner runner = ruleLoadingConfig.loadRulesOnStartup(refreshService, false);

    assertThatCode(() -> runner.run(applicationArguments)).doesNotThrowAnyException();
  }
}
