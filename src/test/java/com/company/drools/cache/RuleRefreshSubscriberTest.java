package com.company.drools.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.core.model.RuleMetadata.RuleStatus;
import com.company.drools.storage.RuleStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

/** Unit tests for {@link RuleRefreshSubscriber}. */
@ExtendWith(MockitoExtension.class)
class RuleRefreshSubscriberTest {

  private static final String LOCAL_INSTANCE = "local-instance";
  private static final String REMOTE_INSTANCE = "remote-instance";

  @Mock private RuleStorage storage;
  @Mock private DroolsEngineService droolsEngineService;

  private MeterRegistry meterRegistry;
  private ObjectMapper objectMapper;
  private RuleRefreshSubscriber subscriber;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    subscriber =
        new RuleRefreshSubscriber(
            storage, droolsEngineService, LOCAL_INSTANCE, objectMapper, meterRegistry);
  }

  private Rule rule(String id) {
    RuleMetadata meta =
        new RuleMetadata(
            "1", LocalDateTime.now(), LocalDateTime.now(), RuleStatus.ACTIVE, null, 0L, 0.0);
    return new Rule(id, "rule \"" + id + "\" when then end", meta);
  }

  private Message msg(RefreshEvent event) throws Exception {
    byte[] body = objectMapper.writeValueAsBytes(event);
    Message m = org.mockito.Mockito.mock(Message.class);
    when(m.getBody()).thenReturn(body);
    return m;
  }

  // ─── Self-dedup ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Self-emitted event (same instance ID) is skipped, skipped_self metric incremented")
  void selfEventSkipped() throws Exception {
    RefreshEvent self = RefreshEvent.refreshed("any", LOCAL_INSTANCE);

    subscriber.onMessage(msg(self), null);

    verifyNoInteractions(storage);
    verifyNoInteractions(droolsEngineService);
    assertThat(meterRegistry.counter("drools.refresh.skipped_self").count()).isEqualTo(1.0);
  }

  // ─── RULE_REFRESHED ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("RULE_REFRESHED from remote fetches rule from storage and reloads engine")
  void singleRefreshReplacesRule() throws Exception {
    Rule r = rule("pricing.simple");
    when(storage.getRule("pricing.simple")).thenReturn(Optional.of(r));

    subscriber.onMessage(msg(RefreshEvent.refreshed("pricing.simple", REMOTE_INSTANCE)), null);

    verify(storage).getRule("pricing.simple");
    verify(droolsEngineService).loadOrReplaceRule(r);
    assertThat(meterRegistry.counter("drools.refresh.received", "event", "RULE_REFRESHED").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("RULE_REFRESHED with rule missing in storage: WARN logged, engine not called")
  void singleRefreshMissingRule() throws Exception {
    when(storage.getRule("ghost")).thenReturn(Optional.empty());

    subscriber.onMessage(msg(RefreshEvent.refreshed("ghost", REMOTE_INSTANCE)), null);

    verify(storage).getRule("ghost");
    verify(droolsEngineService, never()).loadOrReplaceRule(any());
  }

  // ─── RULE_REFRESHED_BULK ────────────────────────────────────────────────────

  @Test
  @DisplayName("RULE_REFRESHED_BULK fetches all rules and reloads engine")
  void bulkRefreshReloads() throws Exception {
    List<Rule> all = List.of(rule("a"), rule("b"));
    when(storage.getAllRules()).thenReturn(all);

    subscriber.onMessage(msg(RefreshEvent.bulkRefreshed(REMOTE_INSTANCE)), null);

    verify(storage).getAllRules();
    verify(droolsEngineService).loadRules(all);
    assertThat(
            meterRegistry
                .counter("drools.refresh.received", "event", "RULE_REFRESHED_BULK")
                .count())
        .isEqualTo(1.0);
  }

  // ─── RULE_DELETED ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("RULE_DELETED is logged but does NOT call engine (deferred to v2)")
  void deleteEventNotApplied() throws Exception {
    subscriber.onMessage(msg(RefreshEvent.deleted("old", REMOTE_INSTANCE)), null);

    verifyNoInteractions(droolsEngineService);
    assertThat(meterRegistry.counter("drools.refresh.received", "event", "RULE_DELETED").count())
        .isEqualTo(1.0);
  }

  // ─── Failure modes ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("Malformed JSON: ERROR logged, subscriber survives, deserialize-fail metric")
  void malformedJson() {
    Message bad = org.mockito.Mockito.mock(Message.class);
    when(bad.getBody()).thenReturn("not-json".getBytes());

    subscriber.onMessage(bad, null);

    verifyNoInteractions(storage);
    verifyNoInteractions(droolsEngineService);
    assertThat(
            meterRegistry
                .counter("drools.refresh.failed", "layer", "subscriber", "reason", "deserialize")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("Engine throws during refresh: ERROR logged, process_error metric, subscriber alive")
  void engineFailureSwallowed() throws Exception {
    Rule r = rule("pricing.simple");
    when(storage.getRule(anyString())).thenReturn(Optional.of(r));
    org.mockito.Mockito.doThrow(new RuntimeException("compile failed"))
        .when(droolsEngineService)
        .loadOrReplaceRule(r);

    // Should NOT throw — subscriber must survive
    subscriber.onMessage(msg(RefreshEvent.refreshed("pricing.simple", REMOTE_INSTANCE)), null);

    assertThat(
            meterRegistry
                .counter("drools.refresh.failed", "layer", "subscriber", "reason", "process_error")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("Event with null type: WARN logged, no engine call")
  void nullEventType() throws Exception {
    RefreshEvent withNullType =
        new RefreshEvent(null, "id", REMOTE_INSTANCE, java.time.Instant.now());

    subscriber.onMessage(msg(withNullType), null);

    verifyNoInteractions(droolsEngineService);
  }
}
