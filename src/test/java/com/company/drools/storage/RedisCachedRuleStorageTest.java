package com.company.drools.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.core.model.RuleMetadata.RuleStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Unit tests for {@link RedisCachedRuleStorage}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisCachedRuleStorageTest {

  private static final String KEY_PREFIX = "drools:rule:";
  private static final long TTL_MINUTES = 15;

  @Mock private RedisTemplate<String, Rule> redisTemplate;
  @Mock private ValueOperations<String, Rule> valueOps;
  @Mock private RuleStorage delegate;

  private CircuitBreaker circuitBreaker;
  private MeterRegistry meterRegistry;
  private RedisCachedRuleStorage storage;

  @BeforeEach
  void setUp() {
    circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
    meterRegistry = new SimpleMeterRegistry();
    when(redisTemplate.opsForValue()).thenReturn(valueOps);

    storage =
        new RedisCachedRuleStorage(
            redisTemplate, circuitBreaker, TTL_MINUTES, KEY_PREFIX, meterRegistry);
    storage.setDelegate(delegate);
  }

  private Rule rule(String id) {
    RuleMetadata meta =
        new RuleMetadata(
            "1", LocalDateTime.now(), LocalDateTime.now(), RuleStatus.ACTIVE, null, 0L, 0.0);
    return new Rule(id, "rule \"" + id + "\" when then end", meta);
  }

  // ─── getRule ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("getRule cache hit returns Redis value without calling delegate")
  void getRuleCacheHit() {
    Rule cached = rule("pricing.simple");
    when(valueOps.get(KEY_PREFIX + "pricing.simple")).thenReturn(cached);

    Optional<Rule> result = storage.getRule("pricing.simple");

    assertThat(result).contains(cached);
    verifyNoInteractions(delegate);
    assertThat(meterRegistry.counter("drools.cache.hit", "layer", "redis").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("getRule cache miss falls through to delegate, populates Redis with TTL")
  void getRuleCacheMissPopulatesRedis() {
    Rule fresh = rule("pricing.simple");
    when(valueOps.get(KEY_PREFIX + "pricing.simple")).thenReturn(null);
    when(delegate.getRule("pricing.simple")).thenReturn(Optional.of(fresh));

    Optional<Rule> result = storage.getRule("pricing.simple");

    assertThat(result).contains(fresh);
    verify(delegate).getRule("pricing.simple");
    verify(valueOps).set(KEY_PREFIX + "pricing.simple", fresh, Duration.ofMinutes(TTL_MINUTES));
    assertThat(meterRegistry.counter("drools.cache.miss", "layer", "redis").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("getRule with circuit breaker open falls through to delegate, no cache write")
  void getRuleCircuitOpen() {
    CircuitBreaker openCb =
        CircuitBreaker.of(
            "open",
            CircuitBreakerConfig.custom().slidingWindowSize(1).failureRateThreshold(1.0f).build());
    openCb.transitionToOpenState();
    storage =
        new RedisCachedRuleStorage(redisTemplate, openCb, TTL_MINUTES, KEY_PREFIX, meterRegistry);
    storage.setDelegate(delegate);

    Rule fresh = rule("pricing.simple");
    when(delegate.getRule("pricing.simple")).thenReturn(Optional.of(fresh));

    Optional<Rule> result = storage.getRule("pricing.simple");

    assertThat(result).contains(fresh);
    verify(delegate).getRule("pricing.simple");
    verify(valueOps, never()).get(anyString());
  }

  @Test
  @DisplayName("getRule delegate returns empty → no cache write")
  void getRuleDelegateEmpty() {
    when(valueOps.get(KEY_PREFIX + "pricing.simple")).thenReturn(null);
    when(delegate.getRule("pricing.simple")).thenReturn(Optional.empty());

    Optional<Rule> result = storage.getRule("pricing.simple");

    assertThat(result).isEmpty();
    verify(valueOps, never()).set(anyString(), any(Rule.class), any(Duration.class));
  }

  // ─── getAllRules (bulk) ─────────────────────────────────────────────────────

  @Test
  @DisplayName("getAllRules with empty expected ids returns delegate's empty list")
  void getAllRulesEmptyExpected() {
    when(delegate.getRuleIds()).thenReturn(List.of());
    when(delegate.getAllRules()).thenReturn(List.of());

    List<Rule> result = storage.getAllRules();

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("getAllRules with cache miss falls through to delegate for missing rules")
  void getAllRulesPartialCacheMiss() {
    when(delegate.getRuleIds()).thenReturn(List.of("a", "b"));
    when(delegate.getAllRules()).thenReturn(List.of(rule("a"), rule("b")));

    List<Rule> result = storage.getAllRules();

    assertThat(result).hasSize(2);
    verify(delegate).getAllRules();
    // Bulk-miss metric incremented (with missing_count=2 tag)
    assertThat(
            meterRegistry
                .find("drools.cache.bulk.miss")
                .tag("missing_count", "2")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  // ─── saveRule (write-through) ───────────────────────────────────────────────

  @Test
  @DisplayName("saveRule writes delegate first, then populates Redis with TTL")
  void saveRuleWriteThrough() {
    Rule r = rule("new.rule");

    storage.saveRule(r);

    verify(delegate).saveRule(r);
    verify(valueOps).set(KEY_PREFIX + "new.rule", r, Duration.ofMinutes(TTL_MINUTES));
  }

  @Test
  @DisplayName("saveRule with delegate failure propagates and does NOT touch Redis")
  void saveRuleDelegateFails() {
    Rule r = rule("bad.rule");
    org.mockito.Mockito.doThrow(new RuntimeException("S3 down")).when(delegate).saveRule(r);

    assertThatThrownBy(() -> storage.saveRule(r))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("S3 down");
    verify(valueOps, never()).set(anyString(), any(Rule.class), any(Duration.class));
  }

  // ─── deleteRule (write-through delete) ──────────────────────────────────────

  @Test
  @DisplayName("deleteRule deletes via delegate first, then DEL Redis key")
  void deleteRuleWriteThrough() {
    storage.deleteRule("old.rule");

    verify(delegate).deleteRule("old.rule");
    verify(redisTemplate).delete(KEY_PREFIX + "old.rule");
  }

  // ─── refreshRule (single invalidation) ──────────────────────────────────────

  @Test
  @DisplayName("refreshRule DELs Redis key then delegates")
  void refreshRuleSingleInvalidation() {
    storage.refreshRule("pricing.simple");

    verify(redisTemplate).delete(KEY_PREFIX + "pricing.simple");
    verify(delegate).refreshRule("pricing.simple");
    assertThat(meterRegistry.counter("drools.cache.invalidation", "scope", "single").count())
        .isEqualTo(1.0);
  }

  // ─── ruleExists ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("ruleExists returns true when Redis hasKey returns true (no delegate call)")
  void ruleExistsCacheHit() {
    when(redisTemplate.hasKey(KEY_PREFIX + "pricing.simple")).thenReturn(true);

    assertThat(storage.ruleExists("pricing.simple")).isTrue();
    verifyNoInteractions(delegate);
  }

  @Test
  @DisplayName("ruleExists falls through to delegate when Redis hasKey returns false")
  void ruleExistsFallsThrough() {
    when(redisTemplate.hasKey(KEY_PREFIX + "pricing.simple")).thenReturn(false);
    when(delegate.ruleExists("pricing.simple")).thenReturn(true);

    assertThat(storage.ruleExists("pricing.simple")).isTrue();
    verify(delegate).ruleExists("pricing.simple");
  }

  // ─── Authoritative pass-through ─────────────────────────────────────────────

  @Test
  @DisplayName("getTotalRuleCount and getRuleIds always delegate (S3 truth)")
  void authoritativePassThrough() {
    when(delegate.getTotalRuleCount()).thenReturn(42L);
    when(delegate.getRuleIds()).thenReturn(List.of("a", "b", "c"));

    assertThat(storage.getTotalRuleCount()).isEqualTo(42L);
    assertThat(storage.getRuleIds()).containsExactly("a", "b", "c");
    verify(delegate, times(1)).getTotalRuleCount();
    verify(delegate, times(1)).getRuleIds();
  }

  // ─── Delegate not set ───────────────────────────────────────────────────────

  @Test
  @DisplayName("Methods throw IllegalStateException if setDelegate() was never called")
  void delegateNotSet() {
    RedisCachedRuleStorage orphan =
        new RedisCachedRuleStorage(
            redisTemplate, circuitBreaker, TTL_MINUTES, KEY_PREFIX, meterRegistry);

    assertThatThrownBy(() -> orphan.getRule("x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("delegate not set");
  }

  // ─── Constructor sanity ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Constructor logs init and configures TTL from minutes")
  void constructorInit() {
    RedisCachedRuleStorage fresh =
        new RedisCachedRuleStorage(redisTemplate, circuitBreaker, 30, KEY_PREFIX, meterRegistry);

    Rule r = rule("ttl.check");
    fresh.setDelegate(delegate);
    fresh.saveRule(r);

    verify(valueOps).set(KEY_PREFIX + "ttl.check", r, Duration.ofMinutes(30));
  }
}
