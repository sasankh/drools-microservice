package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleRefreshService;
import com.company.drools.core.engine.RuleRefreshService.BulkResult;
import com.company.drools.core.engine.RuleRefreshService.BulkStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@DisplayName("RuleReconcileScheduler")
@ExtendWith(MockitoExtension.class)
class RuleReconcileSchedulerTest {

  @Mock private RuleRefreshService refreshService;

  private final Logger logger = (Logger) LoggerFactory.getLogger(RuleReconcileScheduler.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Level previousLevel;
  private RuleReconcileScheduler scheduler;

  @BeforeEach
  void setUp() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    appender.start();
    logger.addAppender(appender);
    scheduler = new RuleReconcileScheduler(refreshService);
    appender.list.clear(); // drop the constructor's "enabled" line
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
  }

  private static BulkResult loaded(
      List<String> loaded,
      List<String> unchanged,
      Map<String, String> failed,
      List<String> removed) {
    return new BulkResult(
        BulkStatus.LOADED,
        new DroolsEngineService.LoadReport(loaded, unchanged, failed, removed),
        null);
  }

  private ILoggingEvent onlyEvent() {
    assertThat(appender.list).hasSize(1);
    return appender.list.get(0);
  }

  @Test
  @DisplayName("no drift → DEBUG 'no changes'")
  void noChanges() {
    when(refreshService.reconcile())
        .thenReturn(loaded(List.of(), List.of("a", "b"), Map.of(), List.of()));

    scheduler.reconcile();

    verify(refreshService).reconcile();
    ILoggingEvent e = onlyEvent();
    assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
    assertThat(e.getFormattedMessage()).contains("no changes").contains("2 rules");
  }

  @Test
  @DisplayName("drift → INFO with the per-category counts")
  void drift() {
    when(refreshService.reconcile())
        .thenReturn(loaded(List.of("a"), List.of("b"), Map.of(), List.of("c", "d")));

    scheduler.reconcile();

    ILoggingEvent e = onlyEvent();
    assertThat(e.getLevel()).isEqualTo(Level.INFO);
    assertThat(e.getFormattedMessage())
        .isEqualTo("Reconcile: 1 updated, 1 unchanged, 2 removed, 0 failed");
  }

  @Test
  @DisplayName("compile failures without other changes are still reported at INFO")
  void failuresOnly() {
    when(refreshService.reconcile())
        .thenReturn(loaded(List.of(), List.of("a"), Map.of("b", "bad"), List.of()));

    scheduler.reconcile();

    ILoggingEvent e = onlyEvent();
    assertThat(e.getLevel()).isEqualTo(Level.INFO);
    assertThat(e.getFormattedMessage()).contains("1 failed");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = BulkStatus.class,
      names = {"STORAGE_FAILED", "REJECTED_EMPTY"})
  @DisplayName("storage failed / rejected empty → WARN 'skipped' with the reason")
  void skipped(BulkStatus status) {
    when(refreshService.reconcile()).thenReturn(new BulkResult(status, null, "why: " + status));

    scheduler.reconcile();

    ILoggingEvent e = onlyEvent();
    assertThat(e.getLevel()).isEqualTo(Level.WARN);
    assertThat(e.getFormattedMessage()).isEqualTo("Reconcile skipped: why: " + status);
  }
}
