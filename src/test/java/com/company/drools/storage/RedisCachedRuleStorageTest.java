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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
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

  @Test
  @DisplayName("getAllRules partial miss: fresh delegate values win over stale cached ones")
  @SuppressWarnings("unchecked") // any(RedisCallback.class) — generic erasure in Mockito
  void getAllRulesPartialMissFreshWinsOverStale() {
    Rule staleA = new Rule("a", "rule \"a\" when then end // OLD", rule("a").getMetadata());
    Rule freshA = new Rule("a", "rule \"a\" when then end // NEW", rule("a").getMetadata());
    Rule freshB = rule("b");
    // Redis has a stale "a" and no "b" → partial miss
    when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(Set.of(KEY_PREFIX + "a"));
    when(valueOps.multiGet(List.of(KEY_PREFIX + "a"))).thenReturn(List.of(staleA));
    when(delegate.getRuleIds()).thenReturn(List.of("a", "b"));
    when(delegate.getAllRules()).thenReturn(List.of(freshA, freshB));

    List<Rule> result = storage.getAllRules();

    assertThat(result).containsExactlyInAnyOrder(freshA, freshB);
    assertThat(result).extracting(Rule::getContent).doesNotContain(staleA.getContent());
    // Every fresh value is re-cached, including the one that was stale
    verify(valueOps).set(KEY_PREFIX + "a", freshA, Duration.ofMinutes(TTL_MINUTES));
    verify(valueOps).set(KEY_PREFIX + "b", freshB, Duration.ofMinutes(TTL_MINUTES));
  }

  @Test
  @DisplayName(
      "getAllRules warm cache (no miss): served from Redis, delegate.getAllRules not called")
  @SuppressWarnings("unchecked")
  void getAllRulesFullHitServedFromRedis() {
    Rule a = rule("a");
    when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(Set.of(KEY_PREFIX + "a"));
    when(valueOps.multiGet(List.of(KEY_PREFIX + "a"))).thenReturn(List.of(a));
    when(delegate.getRuleIds()).thenReturn(List.of("a"));

    assertThat(storage.getAllRules()).containsExactly(a);
    verify(delegate, never()).getAllRules();
  }

  @Test
  @DisplayName("getAllRules: delegate failure on the miss path propagates (never a partial set)")
  void getAllRulesDelegateFailurePropagates() {
    when(delegate.getRuleIds()).thenReturn(List.of("a", "b"));
    when(delegate.getAllRules())
        .thenThrow(
            new com.company.drools.api.exception.RuleStorageException("S3 read failed", null));

    assertThatThrownBy(() -> storage.getAllRules())
        .isInstanceOf(com.company.drools.api.exception.RuleStorageException.class)
        .hasMessageContaining("S3 read failed");
    verify(valueOps, never()).set(anyString(), any(Rule.class), any(Duration.class));
  }

  @Test
  @DisplayName("getAllRulesFromSource bypasses the Redis read and re-caches what the source says")
  @SuppressWarnings("unchecked")
  void getAllRulesFromSourceBypassesRedisRead() {
    Rule a = rule("a");
    when(delegate.getAllRulesFromSource()).thenReturn(List.of(a));

    List<Rule> result = storage.getAllRulesFromSource();

    assertThat(result).containsExactly(a);
    verify(delegate).getAllRulesFromSource();
    verify(delegate, never()).getAllRules();
    verify(delegate, never()).getRuleIds();
    verify(valueOps, never()).multiGet(any());
    verify(redisTemplate, never()).execute(any(RedisCallback.class));
    verify(valueOps).set(KEY_PREFIX + "a", a, Duration.ofMinutes(TTL_MINUTES));
  }

  @Test
  @DisplayName("getAllRulesFromSource: delegate failure propagates, nothing cached")
  void getAllRulesFromSourceFailurePropagates() {
    when(delegate.getAllRulesFromSource()).thenThrow(new IllegalStateException("source down"));

    assertThatThrownBy(() -> storage.getAllRulesFromSource())
        .isInstanceOf(IllegalStateException.class);
    verify(valueOps, never()).set(anyString(), any(Rule.class), any(Duration.class));
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
  @DisplayName("refreshRule DELs key, delegates, then write-through re-populates from base (S2)")
  void refreshRuleSingleInvalidation() {
    Rule fresh = rule("pricing.simple");
    when(delegate.getRule("pricing.simple")).thenReturn(Optional.of(fresh));

    storage.refreshRule("pricing.simple");

    verify(redisTemplate).delete(KEY_PREFIX + "pricing.simple");
    verify(delegate).refreshRule("pricing.simple");
    // Write-through: cache is re-populated with the fresh value so stale DRL isn't served even if
    // the best-effort DEL was dropped. (S2)
    verify(valueOps).set(KEY_PREFIX + "pricing.simple", fresh, Duration.ofMinutes(TTL_MINUTES));
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

  // ─── scanKeys CB coverage (Phase 9.4 follow-up) ─────────────────────────────

  @Test
  @DisplayName(
      "scanKeys with circuit breaker open returns empty; never calls redisTemplate.execute")
  @SuppressWarnings("unchecked") // any(RedisCallback.class) — generic erasure in Mockito
  void scanKeysCircuitBreakerOpenReturnsEmpty() {
    CircuitBreaker openCb =
        CircuitBreaker.of(
            "scan-open",
            CircuitBreakerConfig.custom().slidingWindowSize(1).failureRateThreshold(1.0f).build());
    openCb.transitionToOpenState();
    storage =
        new RedisCachedRuleStorage(redisTemplate, openCb, TTL_MINUTES, KEY_PREFIX, meterRegistry);
    storage.setDelegate(delegate);

    when(delegate.getRuleIds()).thenReturn(List.of("a", "b"));
    when(delegate.getAllRules()).thenReturn(List.of(rule("a"), rule("b")));

    List<Rule> result = storage.getAllRules();

    // SCAN is rejected by the open CB → empty key set → fall through to delegate
    assertThat(result).hasSize(2);
    verify(delegate).getAllRules();
    verify(redisTemplate, never()).execute(any(RedisCallback.class));
  }

  @Test
  @DisplayName("refreshCache when scan CB is open still calls delegate.refreshCache()")
  @SuppressWarnings("unchecked") // any(RedisCallback.class) — generic erasure in Mockito
  void refreshCacheWhenScanCircuitBreakerOpenStillDelegates() {
    CircuitBreaker openCb =
        CircuitBreaker.of(
            "scan-refresh-open",
            CircuitBreakerConfig.custom().slidingWindowSize(1).failureRateThreshold(1.0f).build());
    openCb.transitionToOpenState();
    storage =
        new RedisCachedRuleStorage(redisTemplate, openCb, TTL_MINUTES, KEY_PREFIX, meterRegistry);
    storage.setDelegate(delegate);

    storage.refreshCache();

    verify(delegate).refreshCache();
    verify(redisTemplate, never()).execute(any(RedisCallback.class));
  }

  @Test
  @DisplayName(
      "scanKeys when redisTemplate.execute throws RedisConnectionFailureException returns empty,"
          + " getAllRules falls through to delegate")
  @SuppressWarnings("unchecked") // any(RedisCallback.class) — generic erasure in Mockito
  void scanKeysRedisExceptionReturnsEmpty() {
    when(redisTemplate.execute(any(RedisCallback.class)))
        .thenThrow(new RedisConnectionFailureException("redis down"));
    when(delegate.getRuleIds()).thenReturn(List.of("a", "b"));
    when(delegate.getAllRules()).thenReturn(List.of(rule("a"), rule("b")));

    List<Rule> result = storage.getAllRules();

    assertThat(result).hasSize(2);
    verify(delegate).getAllRules();
    verify(redisTemplate, times(1)).execute(any(RedisCallback.class));
  }
}
