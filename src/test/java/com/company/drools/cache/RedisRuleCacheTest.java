package com.company.drools.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.company.drools.BaseUnitTest;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.testutil.RuleTestUtils;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@DisplayName("RedisRuleCache")
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRuleCacheTest extends BaseUnitTest {

  @Mock private RedisTemplate<String, Rule> redisTemplate;
  @Mock private ValueOperations<String, Rule> valueOperations;

  private CircuitBreaker redisCircuitBreaker;
  private Duration ttlDuration;
  private RedisRuleCache redisRuleCache;

  @BeforeEach
  void setUp() {
    ttlDuration = Duration.ofMinutes(30);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    // Use a real CircuitBreaker (avoids static method mocking issues)
    CircuitBreakerConfig cbConfig =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slidingWindowSize(5)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    redisCircuitBreaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("redis-test");

    // Reset CB to CLOSED state for each test
    redisCircuitBreaker.transitionToClosedState();

    redisRuleCache =
        new RedisRuleCache(redisTemplate, ttlDuration, meterRegistry, redisCircuitBreaker);
  }

  /** Force the circuit breaker into OPEN state by transitioning it directly. */
  private void forceCircuitBreakerOpen() {
    redisCircuitBreaker.transitionToOpenState();
  }

  /** Mocks the SCAN-based key lookup to return the given keys. */
  @SuppressWarnings("unchecked")
  private void mockScanKeys(Set<String> keys) {
    when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(keys);
  }

  // ========================================================================
  // Happy Path Tests
  // ========================================================================

  @Nested
  @DisplayName("Happy Path")
  class HappyPath {

    @Test
    @DisplayName("put and get succeeds with correct serialization")
    void testPutAndGet_Success_SerializesCorrectly() {
      // Given
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      String expectedKey = "drools:rule:pricing.discount.simple";

      // When - put the rule
      redisRuleCache.put(rule);

      // Then - verify set was called with correct key, rule, and TTL
      verify(valueOperations).set(expectedKey, rule, ttlDuration);

      // Given - setup get to return the same rule
      when(valueOperations.get(expectedKey)).thenReturn(rule);

      // When - get the rule
      Optional<Rule> result = redisRuleCache.get("pricing.discount.simple");

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getRuleId()).isEqualTo("pricing.discount.simple");
      assertThat(result.get().getContent()).isEqualTo(rule.getContent());
    }

    @Test
    @DisplayName("contains checks Redis for key existence")
    void testContains_ChecksRedisKey() {
      // Given
      String ruleId = "pricing.discount.simple";
      String expectedKey = "drools:rule:pricing.discount.simple";
      when(redisTemplate.hasKey(expectedKey)).thenReturn(true);

      // When
      boolean result = redisRuleCache.contains(ruleId);

      // Then
      assertThat(result).isTrue();
      verify(redisTemplate).hasKey(expectedKey);
    }

    @Test
    @DisplayName("remove deletes the key from Redis")
    void testRemove_DeletesFromRedis() {
      // Given
      String ruleId = "pricing.discount.simple";
      String expectedKey = "drools:rule:pricing.discount.simple";
      when(redisTemplate.delete(expectedKey)).thenReturn(true);

      // When
      redisRuleCache.remove(ruleId);

      // Then
      verify(redisTemplate).delete(expectedKey);
    }
  }

  // ========================================================================
  // Circuit Breaker Integration Tests
  // ========================================================================

  @Nested
  @DisplayName("Circuit Breaker Integration")
  class CircuitBreakerIntegration {

    @Test
    @DisplayName("put silently fails when circuit breaker is open")
    void testPut_CircuitBreakerOpen_ThrowsException() {
      // Given
      forceCircuitBreakerOpen();
      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");

      // When - put should not throw, it catches CallNotPermittedException internally
      assertThatCode(() -> redisRuleCache.put(rule)).doesNotThrowAnyException();

      // Then - verify Redis was never actually called since CB is open
      verify(valueOperations, never()).set(anyString(), any(Rule.class), any(Duration.class));
    }

    @Test
    @DisplayName("get returns empty when circuit breaker is open")
    void testGet_CircuitBreakerOpen_ReturnsEmpty() {
      // Given
      forceCircuitBreakerOpen();

      // When
      Optional<Rule> result = redisRuleCache.get("pricing.discount.simple");

      // Then
      assertThat(result).isEmpty();

      // Verify Redis was never called
      verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("circuit breaker transitions between states correctly")
    void testCircuitBreakerStates_TransitionsCorrectly() {
      // Test 1: CLOSED state - calls go through
      assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      String expectedKey = "drools:rule:pricing.discount.simple";

      redisRuleCache.put(rule);
      verify(valueOperations).set(expectedKey, rule, ttlDuration);

      // Test 2: Transition to OPEN - put should silently fail
      forceCircuitBreakerOpen();
      assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

      Rule rule2 = RuleTestUtils.createSimpleRule("pricing.discount.vip");
      redisRuleCache.put(rule2);

      // Verify the second rule was NOT stored (CB is open)
      verify(valueOperations, never())
          .set(eq("drools:rule:pricing.discount.vip"), any(Rule.class), any(Duration.class));

      // Test 3: Transition to HALF_OPEN - calls go through again
      redisCircuitBreaker.transitionToHalfOpenState();
      assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

      when(valueOperations.get(expectedKey)).thenReturn(rule);
      Optional<Rule> result = redisRuleCache.get("pricing.discount.simple");

      assertThat(result).isPresent();
      assertThat(result.get().getRuleId()).isEqualTo("pricing.discount.simple");
    }
  }

  // ========================================================================
  // Serialization Tests
  // ========================================================================

  @Nested
  @DisplayName("Serialization")
  class Serialization {

    @Test
    @DisplayName("handles complex rules with metadata correctly")
    void testSerialization_HandlesComplexRules() {
      // Given - a rule with rich metadata
      RuleMetadata metadata = RuleMetadata.createNew().withStatus(RuleMetadata.RuleStatus.ACTIVE);
      String complexContent =
          """
          package com.company.rules.pricing.discount

          import java.util.Map
          import java.math.BigDecimal

          rule "Complex VIP Discount"
              salience 10
              no-loop true
          when
              $data : Map(
                  this["customerType"] == "VIP",
                  this["amount"] != null
              )
          then
              BigDecimal amount = new BigDecimal($data.get("amount").toString());
              BigDecimal discount = amount.multiply(new BigDecimal("0.20"));
              $data.put("discount", discount);
              $data.put("applied", true);
          end
          """;

      Rule complexRule = new Rule("pricing.discount.vip-complex", complexContent, metadata);
      String expectedKey = "drools:rule:pricing.discount.vip-complex";

      // When
      redisRuleCache.put(complexRule);

      // Then - verify the exact rule object was passed to Redis
      ArgumentCaptor<Rule> ruleCaptor = ArgumentCaptor.forClass(Rule.class);
      verify(valueOperations).set(eq(expectedKey), ruleCaptor.capture(), eq(ttlDuration));

      Rule capturedRule = ruleCaptor.getValue();
      assertThat(capturedRule.getRuleId()).isEqualTo("pricing.discount.vip-complex");
      assertThat(capturedRule.getContent()).contains("Complex VIP Discount");
      assertThat(capturedRule.getContent()).contains("BigDecimal");
      assertThat(capturedRule.getMetadata().getStatus()).isEqualTo(RuleMetadata.RuleStatus.ACTIVE);
    }

    @Test
    @DisplayName("deserialization reconstructs rule from Redis")
    void testDeserialization_ReconstructsRule() {
      // Given - a rule stored in Redis
      Rule originalRule = RuleTestUtils.createSimpleRule("validation.input.basic");
      String expectedKey = "drools:rule:validation.input.basic";
      when(valueOperations.get(expectedKey)).thenReturn(originalRule);

      // When
      Optional<Rule> result = redisRuleCache.get("validation.input.basic");

      // Then
      assertThat(result).isPresent();
      Rule retrievedRule = result.get();
      assertThat(retrievedRule.getRuleId()).isEqualTo(originalRule.getRuleId());
      assertThat(retrievedRule.getContent()).isEqualTo(originalRule.getContent());
      assertThat(retrievedRule.getMetadata()).isNotNull();
      assertThat(retrievedRule.getMetadata().getVersion())
          .isEqualTo(originalRule.getMetadata().getVersion());
    }
  }

  // ========================================================================
  // Error Handling Tests
  // ========================================================================

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("Redis connection failure is handled gracefully")
    void testRedisConnectionFailure_HandledGracefully() {
      // Given - Redis connection fails on get
      RedisConnectionFailureException connectionError =
          new RedisConnectionFailureException("Unable to connect to Redis");
      when(valueOperations.get(anyString())).thenThrow(connectionError);

      // When - get should return empty, not throw
      Optional<Rule> result = redisRuleCache.get("pricing.discount.simple");

      // Then
      assertThat(result).isEmpty();

      // Also verify contains handles connection failure gracefully
      when(redisTemplate.hasKey(anyString())).thenThrow(connectionError);
      boolean containsResult = redisRuleCache.contains("pricing.discount.simple");
      assertThat(containsResult).isFalse();

      // And verify remove handles it gracefully too
      when(redisTemplate.delete(anyString())).thenThrow(connectionError);
      assertThatCode(() -> redisRuleCache.remove("pricing.discount.simple"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TTL is configured correctly on put operations")
    void testTTL_ExpiresCorrectly() {
      // Given - a specific TTL of 15 minutes
      Duration customTtl = Duration.ofMinutes(15);
      RedisRuleCache customTtlCache =
          new RedisRuleCache(redisTemplate, customTtl, meterRegistry, redisCircuitBreaker);

      Rule rule = RuleTestUtils.createSimpleRule("pricing.discount.simple");
      String expectedKey = "drools:rule:pricing.discount.simple";

      // When
      customTtlCache.put(rule);

      // Then - verify TTL duration is passed correctly
      verify(valueOperations).set(expectedKey, rule, customTtl);

      // Verify with a different TTL
      reset(valueOperations);
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);

      Duration longTtl = Duration.ofHours(2);
      RedisRuleCache longTtlCache =
          new RedisRuleCache(redisTemplate, longTtl, meterRegistry, redisCircuitBreaker);

      longTtlCache.put(rule);
      verify(valueOperations).set(expectedKey, rule, longTtl);
    }
  }

  // ========================================================================
  // Clear Tests
  // ========================================================================

  @Nested
  @DisplayName("Clear")
  class Clear {

    @Test
    @DisplayName("clear deletes all cache keys from Redis")
    void testClear_DeletesAllKeys() {
      Set<String> keys = new HashSet<>();
      keys.add("drools:rule:rule1");
      keys.add("drools:rule:rule2");
      keys.add("drools:rule:rule3");

      mockScanKeys(keys);
      when(redisTemplate.delete(keys)).thenReturn(3L);

      redisRuleCache.clear();

      verify(redisTemplate).delete(keys);
    }

    @Test
    @DisplayName("clear with no keys does not call delete")
    @SuppressWarnings("unchecked")
    void testClear_NoKeys_DoesNotCallDelete() {
      mockScanKeys(new HashSet<>());

      redisRuleCache.clear();

      verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    @DisplayName("clear with null keys (scan returns empty) does not call delete")
    @SuppressWarnings("unchecked")
    void testClear_NullKeys_DoesNotCallDelete() {
      mockScanKeys(null);

      redisRuleCache.clear();

      verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    @DisplayName("clear handles DataAccessException gracefully")
    @SuppressWarnings("unchecked")
    void testClear_DataAccessException_HandledGracefully() {
      when(redisTemplate.execute(any(RedisCallback.class)))
          .thenThrow(new QueryTimeoutException("Redis timeout"));

      assertThatCode(() -> redisRuleCache.clear()).doesNotThrowAnyException();
    }
  }

  // ========================================================================
  // Size Tests
  // ========================================================================

  @Nested
  @DisplayName("Size")
  class Size {

    @Test
    @DisplayName("size returns correct count of keys")
    void testSize_ReturnsCorrectCount() {
      Set<String> keys = new HashSet<>();
      keys.add("drools:rule:rule1");
      keys.add("drools:rule:rule2");

      mockScanKeys(keys);

      assertThat(redisRuleCache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("size returns 0 when no keys exist")
    void testSize_NoKeys_ReturnsZero() {
      mockScanKeys(null);

      assertThat(redisRuleCache.size()).isZero();
    }

    @Test
    @DisplayName("size returns 0 on DataAccessException")
    @SuppressWarnings("unchecked")
    void testSize_DataAccessException_ReturnsZero() {
      when(redisTemplate.execute(any(RedisCallback.class)))
          .thenThrow(new QueryTimeoutException("Redis timeout"));

      assertThat(redisRuleCache.size()).isZero();
    }
  }

  // ========================================================================
  // maxSize Tests
  // ========================================================================

  @Nested
  @DisplayName("MaxSize")
  class MaxSize {

    @Test
    @DisplayName("maxSize returns Long.MAX_VALUE for Redis cache")
    void testMaxSize_ReturnsLongMaxValue() {
      assertThat(redisRuleCache.maxSize()).isEqualTo(Long.MAX_VALUE);
    }
  }

  // ========================================================================
  // getCachedRuleIds Tests
  // ========================================================================

  @Nested
  @DisplayName("GetCachedRuleIds")
  class GetCachedRuleIds {

    @Test
    @DisplayName("getCachedRuleIds strips prefix and returns rule IDs")
    void testGetCachedRuleIds_StripsPrefix() {
      Set<String> keys = new HashSet<>();
      keys.add("drools:rule:pricing.discount.simple");
      keys.add("drools:rule:validation.input.basic");

      mockScanKeys(keys);

      List<String> ruleIds = redisRuleCache.getCachedRuleIds();

      assertThat(ruleIds)
          .hasSize(2)
          .containsExactlyInAnyOrder("pricing.discount.simple", "validation.input.basic");
    }

    @Test
    @DisplayName("getCachedRuleIds returns empty list when no keys exist")
    void testGetCachedRuleIds_NoKeys_ReturnsEmptyList() {
      mockScanKeys(new HashSet<>());

      List<String> ruleIds = redisRuleCache.getCachedRuleIds();

      assertThat(ruleIds).isEmpty();
    }

    @Test
    @DisplayName("getCachedRuleIds returns empty list when keys are null")
    void testGetCachedRuleIds_NullKeys_ReturnsEmptyList() {
      mockScanKeys(null);

      List<String> ruleIds = redisRuleCache.getCachedRuleIds();

      assertThat(ruleIds).isEmpty();
    }

    @Test
    @DisplayName("getCachedRuleIds returns empty list on DataAccessException")
    @SuppressWarnings("unchecked")
    void testGetCachedRuleIds_DataAccessException_ReturnsEmptyList() {
      when(redisTemplate.execute(any(RedisCallback.class)))
          .thenThrow(new QueryTimeoutException("Redis timeout"));

      List<String> ruleIds = redisRuleCache.getCachedRuleIds();

      assertThat(ruleIds).isEmpty();
    }
  }

  // ========================================================================
  // getStatistics Tests
  // ========================================================================

  @Nested
  @DisplayName("GetStatistics")
  class GetStatistics {

    @Test
    @DisplayName("getStatistics returns CacheStatistics with correct local stats")
    void testGetStatistics_ReturnsCorrectStats() {
      // Setup size mock (SCAN returns empty)
      mockScanKeys(new HashSet<>());

      // Generate some hits and misses
      Rule rule = RuleTestUtils.createSimpleRule("rule.one");
      String key = "drools:rule:rule.one";
      when(valueOperations.get(key)).thenReturn(rule);
      redisRuleCache.get("rule.one"); // hit

      when(valueOperations.get("drools:rule:rule.missing")).thenReturn(null);
      redisRuleCache.get("rule.missing"); // miss

      CacheStatistics stats = redisRuleCache.getStatistics();

      assertThat(stats.getHits()).isEqualTo(1);
      assertThat(stats.getMisses()).isEqualTo(1);
      assertThat(stats.getMaxSize()).isEqualTo(Long.MAX_VALUE);
      assertThat(stats.getLastAccess()).isNotNull();
    }
  }

  // ========================================================================
  // warmUp Tests
  // ========================================================================

  @Nested
  @DisplayName("WarmUp")
  class WarmUp {

    @Test
    @DisplayName("warmUp puts all provided rules into the cache")
    void testWarmUp_PutsAllRules() {
      List<Rule> rules = new ArrayList<>();
      rules.add(RuleTestUtils.createSimpleRule("rule.one"));
      rules.add(RuleTestUtils.createSimpleRule("rule.two"));
      rules.add(RuleTestUtils.createSimpleRule("rule.three"));

      redisRuleCache.warmUp(rules);

      verify(valueOperations, times(3)).set(anyString(), any(Rule.class), eq(ttlDuration));
      verify(valueOperations).set(eq("drools:rule:rule.one"), any(Rule.class), eq(ttlDuration));
      verify(valueOperations).set(eq("drools:rule:rule.two"), any(Rule.class), eq(ttlDuration));
      verify(valueOperations).set(eq("drools:rule:rule.three"), any(Rule.class), eq(ttlDuration));
    }

    @Test
    @DisplayName("warmUp with null list does nothing")
    void testWarmUp_NullList_DoesNothing() {
      redisRuleCache.warmUp(null);

      verify(valueOperations, never()).set(anyString(), any(Rule.class), any(Duration.class));
    }

    @Test
    @DisplayName("warmUp with empty list does nothing")
    void testWarmUp_EmptyList_DoesNothing() {
      redisRuleCache.warmUp(new ArrayList<>());

      verify(valueOperations, never()).set(anyString(), any(Rule.class), any(Duration.class));
    }

    @Test
    @DisplayName("warmUp handles DataAccessException gracefully")
    void testWarmUp_DataAccessException_HandledGracefully() {
      doThrow(new QueryTimeoutException("Redis timeout"))
          .when(valueOperations)
          .set(anyString(), any(Rule.class), any(Duration.class));

      List<Rule> rules = new ArrayList<>();
      rules.add(RuleTestUtils.createSimpleRule("rule.one"));

      // The DataAccessException is caught inside put() -> CB wrapper -> catch Exception
      assertThatCode(() -> redisRuleCache.warmUp(rules)).doesNotThrowAnyException();
    }
  }

  // ========================================================================
  // evictIfNeeded Tests
  // ========================================================================

  @Nested
  @DisplayName("EvictIfNeeded")
  class EvictIfNeeded {

    @Test
    @DisplayName("evictIfNeeded is a no-op for Redis cache")
    @SuppressWarnings("unchecked")
    void testEvictIfNeeded_IsNoOp() {
      // Should not throw and should not interact with Redis
      assertThatCode(() -> redisRuleCache.evictIfNeeded()).doesNotThrowAnyException();

      // No scan/delete calls should be made
      verify(redisTemplate, never()).execute(any(RedisCallback.class));
      verify(redisTemplate, never()).delete(anyString());
    }
  }

  // ========================================================================
  // isEnabled Tests
  // ========================================================================

  @Nested
  @DisplayName("IsEnabled")
  class IsEnabled {

    @Test
    @DisplayName("isEnabled returns true when Redis connectivity test succeeds")
    void testIsEnabled_ConnectivitySucceeds_ReturnsTrue() {
      when(valueOperations.get("connectivity-test")).thenReturn(null);

      assertThat(redisRuleCache.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("isEnabled returns false when Redis connectivity test fails")
    void testIsEnabled_ConnectivityFails_ReturnsFalse() {
      when(valueOperations.get("connectivity-test"))
          .thenThrow(new RedisConnectionFailureException("Cannot connect"));

      assertThat(redisRuleCache.isEnabled()).isFalse();
    }
  }

  // ========================================================================
  // getLocalCacheEfficiency Tests
  // ========================================================================

  @Nested
  @DisplayName("GetLocalCacheEfficiency")
  class GetLocalCacheEfficiency {

    @Test
    @DisplayName("getLocalCacheEfficiency returns hit rate from statistics")
    void testGetLocalCacheEfficiency_ReturnsHitRate() {
      // Setup: no keys for size()
      mockScanKeys(new HashSet<>());

      // Generate 3 hits
      Rule rule = RuleTestUtils.createSimpleRule("rule.one");
      when(valueOperations.get("drools:rule:rule.one")).thenReturn(rule);
      redisRuleCache.get("rule.one");
      redisRuleCache.get("rule.one");
      redisRuleCache.get("rule.one");

      // Generate 1 miss
      when(valueOperations.get("drools:rule:rule.missing")).thenReturn(null);
      redisRuleCache.get("rule.missing");

      // Efficiency = 3/4 = 0.75
      assertThat(redisRuleCache.getLocalCacheEfficiency())
          .isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("getLocalCacheEfficiency returns 0.0 when no requests have been made")
    void testGetLocalCacheEfficiency_NoRequests_ReturnsZero() {
      mockScanKeys(new HashSet<>());

      assertThat(redisRuleCache.getLocalCacheEfficiency()).isEqualTo(0.0);
    }
  }
}
