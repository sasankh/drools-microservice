package com.company.drools.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.engine.RuleRefreshService;
import com.company.drools.core.engine.RuleRefreshService.BulkResult;
import com.company.drools.core.engine.RuleRefreshService.BulkStatus;
import com.company.drools.core.engine.RuleRefreshService.ReadMode;
import com.company.drools.core.engine.RuleRefreshService.SingleResult;
import com.company.drools.core.engine.RuleRefreshService.SingleStatus;
import com.company.drools.core.engine.RuleRefreshService.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

/** Unit tests for {@link RuleRefreshSubscriber}. */
@ExtendWith(MockitoExtension.class)
class RuleRefreshSubscriberTest {

  private static final String LOCAL_INSTANCE = "local-instance";
  private static final String REMOTE_INSTANCE = "remote-instance";

  @Mock private RuleRefreshService refreshService;

  private MeterRegistry meterRegistry;
  private ObjectMapper objectMapper;
  private RuleRefreshSubscriber subscriber;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    subscriber =
        new RuleRefreshSubscriber(refreshService, LOCAL_INSTANCE, objectMapper, meterRegistry);
  }

  private Message msg(RefreshEvent event) throws Exception {
    byte[] body = objectMapper.writeValueAsBytes(event);
    Message m = org.mockito.Mockito.mock(Message.class);
    when(m.getBody()).thenReturn(body);
    return m;
  }

  private double failed(String reason) {
    return meterRegistry
        .counter("drools.refresh.failed", "layer", "subscriber", "reason", reason)
        .count();
  }

  private static BulkResult bulkLoaded(Map<String, String> failures) {
    return new BulkResult(
        BulkStatus.LOADED,
        new DroolsEngineService.LoadReport(List.of("a"), List.of("b"), failures, List.of()),
        null);
  }

  // ─── Self-dedup ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Self-emitted event (same instance ID) is skipped, skipped_self metric incremented")
  void selfEventSkipped() throws Exception {
    RefreshEvent self = RefreshEvent.refreshed("any", LOCAL_INSTANCE);

    subscriber.onMessage(msg(self), null);

    verifyNoInteractions(refreshService);
    assertThat(meterRegistry.counter("drools.refresh.skipped_self").count()).isEqualTo(1.0);
  }

  // ─── RULE_REFRESHED ─────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SingleStatus.class,
      names = {"INSTALLED", "UNCHANGED", "REMOVED", "NOT_FOUND"})
  @DisplayName("RULE_REFRESHED from remote refreshes that one rule ID (no cache invalidation)")
  void singleRefreshAppliedOutcomes(SingleStatus status) throws Exception {
    when(refreshService.refreshOne("pricing.simple", Source.PUBSUB, false))
        .thenReturn(new SingleResult(status, null, null));

    subscriber.onMessage(msg(RefreshEvent.refreshed("pricing.simple", REMOTE_INSTANCE)), null);

    verify(refreshService).refreshOne("pricing.simple", Source.PUBSUB, false);
    assertThat(meterRegistry.counter("drools.refresh.received", "event", "RULE_REFRESHED").count())
        .isEqualTo(1.0);
    assertThat(failed("process_error")).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SingleStatus.class,
      names = {"FAILED", "STORAGE_FAILED"})
  @DisplayName("RULE_REFRESHED that fails: counted as process_error, subscriber survives")
  void singleRefreshFailureCounted(SingleStatus status) throws Exception {
    when(refreshService.refreshOne("pricing.simple", Source.PUBSUB, false))
        .thenReturn(new SingleResult(status, "abc", "boom"));

    // Should NOT throw — subscriber must survive
    subscriber.onMessage(msg(RefreshEvent.refreshed("pricing.simple", REMOTE_INSTANCE)), null);

    assertThat(failed("process_error")).isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "RULE_REFRESHED with a path-traversal ruleId is rejected before touching storage (S11)")
  void singleRefreshRejectsUnsafeRuleId() throws Exception {
    subscriber.onMessage(msg(RefreshEvent.refreshed("../../etc/passwd", REMOTE_INSTANCE)), null);

    verifyNoInteractions(refreshService);
  }

  // ─── RULE_REFRESHED_BULK ────────────────────────────────────────────────────

  @Test
  @DisplayName("RULE_REFRESHED_BULK runs a full PUBSUB refresh with a cached read")
  void bulkRefreshReloads() throws Exception {
    when(refreshService.refreshAll(Source.PUBSUB, ReadMode.CACHED))
        .thenReturn(bulkLoaded(Map.of()));

    subscriber.onMessage(msg(RefreshEvent.bulkRefreshed(REMOTE_INSTANCE)), null);

    verify(refreshService).refreshAll(Source.PUBSUB, ReadMode.CACHED);
    assertThat(
            meterRegistry
                .counter("drools.refresh.received", "event", "RULE_REFRESHED_BULK")
                .count())
        .isEqualTo(1.0);
    assertThat(failed("compile")).isZero();
    assertThat(failed("process_error")).isZero();
  }

  @Test
  @DisplayName("Two bulk events back-to-back both refresh (no coalescing window drops one)")
  void twoBulkEventsBothRefresh() throws Exception {
    when(refreshService.refreshAll(Source.PUBSUB, ReadMode.CACHED))
        .thenReturn(bulkLoaded(Map.of()));

    subscriber.onMessage(msg(RefreshEvent.bulkRefreshed(REMOTE_INSTANCE)), null);
    subscriber.onMessage(msg(RefreshEvent.bulkRefreshed(REMOTE_INSTANCE)), null);

    verify(refreshService, times(2)).refreshAll(Source.PUBSUB, ReadMode.CACHED);
  }

  @Test
  @DisplayName("Bulk refresh with per-ID compile failures: compile counter += failure count")
  void bulkRefreshCompileFailuresCounted() throws Exception {
    when(refreshService.refreshAll(Source.PUBSUB, ReadMode.CACHED))
        .thenReturn(bulkLoaded(Map.of("x", "err1", "y", "err2")));

    subscriber.onMessage(msg(RefreshEvent.bulkRefreshed(REMOTE_INSTANCE)), null);

    assertThat(failed("compile")).isEqualTo(2.0);
    assertThat(failed("process_error")).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = BulkStatus.class,
      names = {"STORAGE_FAILED", "REJECTED_EMPTY"})
  @DisplayName("Bulk refresh that changes nothing (storage failed / rejected empty): process_error")
  void bulkRefreshNotLoadedCounted(BulkStatus status) throws Exception {
    when(refreshService.refreshAll(Source.PUBSUB, ReadMode.CACHED))
        .thenReturn(new BulkResult(status, null, "nope"));

    subscriber.onMessage(msg(RefreshEvent.bulkRefreshed(REMOTE_INSTANCE)), null);

    assertThat(failed("process_error")).isEqualTo(1.0);
  }

  // ─── RULE_DELETED ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("RULE_DELETED removes the rule ID via the refresh service (S5)")
  void deleteEventRemovesRule() throws Exception {
    subscriber.onMessage(msg(RefreshEvent.deleted("old", REMOTE_INSTANCE)), null);

    verify(refreshService).removeOne("old", Source.PUBSUB);
    assertThat(meterRegistry.counter("drools.refresh.received", "event", "RULE_DELETED").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("RULE_DELETED with an unsafe ruleId is rejected (S11)")
  void deleteEventRejectsUnsafeRuleId() throws Exception {
    subscriber.onMessage(msg(RefreshEvent.deleted("../../etc/passwd", REMOTE_INSTANCE)), null);

    verify(refreshService, never()).removeOne(anyString(), any());
  }

  // ─── Failure modes ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("Malformed JSON: ERROR logged, subscriber survives, deserialize-fail metric")
  void malformedJson() {
    Message bad = org.mockito.Mockito.mock(Message.class);
    when(bad.getBody()).thenReturn("not-json".getBytes());

    subscriber.onMessage(bad, null);

    verifyNoInteractions(refreshService);
    assertThat(failed("deserialize")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Refresh service throws: ERROR logged, process_error metric, subscriber alive")
  void serviceExceptionSwallowed() throws Exception {
    when(refreshService.refreshOne(anyString(), any(), anyBoolean()))
        .thenThrow(new RuntimeException("unexpected"));

    // Should NOT throw — subscriber must survive
    subscriber.onMessage(msg(RefreshEvent.refreshed("pricing.simple", REMOTE_INSTANCE)), null);

    assertThat(failed("process_error")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Event with null type: WARN logged, no refresh")
  void nullEventType() throws Exception {
    RefreshEvent withNullType =
        new RefreshEvent(null, "id", REMOTE_INSTANCE, java.time.Instant.now());

    subscriber.onMessage(msg(withNullType), null);

    verifyNoInteractions(refreshService);
  }
}
