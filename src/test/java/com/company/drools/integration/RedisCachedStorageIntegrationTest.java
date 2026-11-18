package com.company.drools.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.core.model.RuleMetadata.RuleStatus;
import com.company.drools.storage.RedisCachedRuleStorage;
import com.company.drools.storage.RuleStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration tests for {@link RedisCachedRuleStorage} against a real Redis container.
 * Verifies read-through behaviour, write-through invalidation, bulk SCAN+MGET, circuit breaker
 * fallback when Redis is killed, and connection recovery after restart.
 *
 * <p>The {@code delegate} is mocked — we're exercising the decorator's Redis integration, not the
 * underlying S3/file storage.
 */
@DisplayName("RedisCachedRuleStorage Integration Tests")
@Testcontainers
class RedisCachedStorageIntegrationTest {

  private static final String KEY_PREFIX = "drools:rule:";
  private static final long TTL_MINUTES = 15;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static RedisTemplate<String, Rule> redisTemplate;

  private RuleStorage delegate;
  private RedisCachedRuleStorage cache;
  private SimpleMeterRegistry meterRegistry;

  @BeforeAll
  static void setupRedis() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = buildRuleTemplate(connectionFactory);
  }

  @AfterAll
  static void tearDownRedis() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @BeforeEach
  void setUp() {
    // Flush Redis between tests
    redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();

    delegate = Mockito.mock(RuleStorage.class);
    CircuitBreaker cb = CircuitBreaker.of("redis-test", CircuitBreakerConfig.ofDefaults());
    meterRegistry = new SimpleMeterRegistry();
    cache = new RedisCachedRuleStorage(redisTemplate, cb, TTL_MINUTES, KEY_PREFIX, meterRegistry);
    cache.setDelegate(delegate);
  }

  private Rule rule(String id) {
    return new Rule(
        id,
        "rule \"" + id + "\" when then end",
        new RuleMetadata(
            "1", LocalDateTime.now(), LocalDateTime.now(), RuleStatus.ACTIVE, null, 0L, 0.0));
  }

  // ─── Read-through ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("Cold cache: getRule fetches from delegate, populates Redis, next call hits Redis")
  void coldCacheThenWarm() {
    Rule expected = rule("pricing.simple");
    when(delegate.getRule("pricing.simple")).thenReturn(Optional.of(expected));

    // First call — cache miss, delegate hit, Redis populated
    Optional<Rule> first = cache.getRule("pricing.simple");
    assertThat(first).contains(expected);
    verify(delegate, times(1)).getRule("pricing.simple");
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "pricing.simple")).isNotNull();

    // Second call — cache hit, delegate NOT called again
    Optional<Rule> second = cache.getRule("pricing.simple");
    assertThat(second).contains(expected);
    verify(delegate, times(1)).getRule("pricing.simple"); // still just 1
    assertThat(meterRegistry.counter("drools.cache.hit", "layer", "redis").count()).isEqualTo(1.0);
    assertThat(meterRegistry.counter("drools.cache.miss", "layer", "redis").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("TTL applied: cache entry has expiry set per redis.drl-rules.ttl-minutes")
  void ttlApplied() {
    Rule expected = rule("pricing.simple");
    when(delegate.getRule("pricing.simple")).thenReturn(Optional.of(expected));

    cache.getRule("pricing.simple");

    Long ttlSeconds =
        redisTemplate
            .getRequiredConnectionFactory()
            .getConnection()
            .keyCommands()
            .ttl((KEY_PREFIX + "pricing.simple").getBytes());
    assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(TTL_MINUTES * 60);
  }

  // ─── Bulk SCAN + MGET ───────────────────────────────────────────────────────

  @Test
  @DisplayName("getAllRules with N rules: bulk-miss → delegate fetched → all populated in Redis")
  void getAllRulesPopulatesAll() {
    List<Rule> all = List.of(rule("a"), rule("b"), rule("c"));
    when(delegate.getRuleIds()).thenReturn(List.of("a", "b", "c"));
    when(delegate.getAllRules()).thenReturn(all);

    List<Rule> result = cache.getAllRules();

    assertThat(result).hasSize(3).containsExactlyInAnyOrderElementsOf(all);
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "a")).isNotNull();
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "b")).isNotNull();
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "c")).isNotNull();
  }

  @Test
  @DisplayName("getAllRules warm cache: SCAN finds all keys → no delegate call")
  void getAllRulesWarmCacheNoDelegate() {
    // Pre-populate Redis
    redisTemplate.opsForValue().set(KEY_PREFIX + "a", rule("a"), Duration.ofMinutes(TTL_MINUTES));
    redisTemplate.opsForValue().set(KEY_PREFIX + "b", rule("b"), Duration.ofMinutes(TTL_MINUTES));
    when(delegate.getRuleIds()).thenReturn(List.of("a", "b"));

    List<Rule> result = cache.getAllRules();

    assertThat(result).hasSize(2);
    verify(delegate, never()).getAllRules();
    assertThat(meterRegistry.counter("drools.cache.bulk.hit").count()).isEqualTo(1.0);
  }

  // ─── Invalidation ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("refreshCache: SCAN+DEL clears all prefix-matching keys then delegates")
  void refreshCacheClearsAll() {
    redisTemplate.opsForValue().set(KEY_PREFIX + "a", rule("a"), Duration.ofMinutes(TTL_MINUTES));
    redisTemplate.opsForValue().set(KEY_PREFIX + "b", rule("b"), Duration.ofMinutes(TTL_MINUTES));
    redisTemplate.opsForValue().set("other:key", rule("c"), Duration.ofMinutes(TTL_MINUTES));

    cache.refreshCache();

    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "a")).isNull();
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "b")).isNull();
    // Foreign prefix untouched
    assertThat(redisTemplate.opsForValue().get("other:key")).isNotNull();
    verify(delegate, times(1)).refreshCache();
  }

  @Test
  @DisplayName("refreshRule: DEL only the targeted key")
  void refreshRuleSingleKey() {
    redisTemplate.opsForValue().set(KEY_PREFIX + "a", rule("a"), Duration.ofMinutes(TTL_MINUTES));
    redisTemplate.opsForValue().set(KEY_PREFIX + "b", rule("b"), Duration.ofMinutes(TTL_MINUTES));

    cache.refreshRule("a");

    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "a")).isNull();
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "b")).isNotNull();
    verify(delegate, times(1)).refreshRule("a");
  }

  // ─── Write-through ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("saveRule: delegate writes first, then Redis populated")
  void saveRuleWriteThrough() {
    Rule r = rule("new.rule");
    cache.saveRule(r);
    verify(delegate).saveRule(r);
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "new.rule")).isNotNull();
  }

  @Test
  @DisplayName("deleteRule: delegate deletes first, then Redis key removed")
  void deleteRuleWriteThrough() {
    Rule r = rule("old.rule");
    redisTemplate.opsForValue().set(KEY_PREFIX + r.getRuleId(), r, Duration.ofMinutes(TTL_MINUTES));

    cache.deleteRule(r.getRuleId());

    verify(delegate).deleteRule(r.getRuleId());
    assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + r.getRuleId())).isNull();
  }

  // ─── Circuit breaker fallback ───────────────────────────────────────────────

  @Test
  @DisplayName("Redis killed mid-test: reads fall through to delegate, service stays up")
  void redisKillCircuitBreakerFallback() {
    Rule expected = rule("pricing.simple");
    when(delegate.getRule(any())).thenReturn(Optional.of(expected));

    // Verify normal hit works
    cache.getRule("pricing.simple");

    // Kill Redis
    redis.stop();
    try {
      // Reads must still succeed via delegate fallback (just slower; CB may trip)
      Optional<Rule> result = cache.getRule("pricing.simple");
      assertThat(result).contains(expected);
      verify(delegate, atLeastOnce()).getRule("pricing.simple");
    } finally {
      // Restart Redis so other @Container tests can use it; recreate the connection
      redis.start();
      // Rebuild the connection factory pointing at the new ephemeral port
      LettuceConnectionFactory newFactory =
          new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
      newFactory.afterPropertiesSet();
      // Swap in the new factory + template on the static field so subsequent tests work
      connectionFactory.destroy();
      connectionFactory = newFactory;
      redisTemplate = buildRuleTemplate(connectionFactory);
    }
  }

  // ─── scanKeys CB coverage (Phase 9.4 follow-up) ─────────────────────────────

  @Test
  @DisplayName(
      "scanKeys with CB open: refreshCache completes <3s and delegates without hitting Redis")
  void scanKeysCircuitBreakerFallback() {
    // Pre-trip a fresh CB so scanKeys must short-circuit. Long waitDurationInOpenState
    // keeps the CB locked-open for the duration of the test.
    CircuitBreaker openCb =
        CircuitBreaker.of(
            "scan-cb-test",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1.0f)
                .waitDurationInOpenState(Duration.ofMinutes(10))
                .build());
    openCb.transitionToOpenState();
    assertThat(openCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    RedisCachedRuleStorage testCache =
        new RedisCachedRuleStorage(redisTemplate, openCb, TTL_MINUTES, KEY_PREFIX, meterRegistry);
    testCache.setDelegate(delegate);

    // Pre-populate one key directly via the live Redis template; with CB open, scanKeys
    // must NOT find or delete it (proves the supplier never runs against the open CB).
    redisTemplate.opsForValue().set(KEY_PREFIX + "leftover", rule("leftover"));
    assertThat(redisTemplate.hasKey(KEY_PREFIX + "leftover")).isTrue();

    // The actual assertion — refreshCache returns fast and delegates correctly.
    Instant start = Instant.now();
    testCache.refreshCache();
    Duration elapsed = Duration.between(start, Instant.now());

    assertThat(elapsed)
        .as("refreshCache must short-circuit on open CB; pre-fix this could hang on SCAN")
        .isLessThan(Duration.ofSeconds(3));
    verify(delegate).refreshCache();
    // CB stayed open; the pre-existing key remains because SCAN+DEL was skipped.
    assertThat(redisTemplate.hasKey(KEY_PREFIX + "leftover")).isTrue();
    assertThat(openCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private static RedisTemplate<String, Rule> buildRuleTemplate(LettuceConnectionFactory factory) {
    RedisTemplate<String, Rule> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    Jackson2JsonRedisSerializer<Rule> jsonSerializer = buildJsonSerializer();
    template.setValueSerializer(jsonSerializer);
    template.setHashValueSerializer(jsonSerializer);
    template.afterPropertiesSet();
    return template;
  }

  private static Jackson2JsonRedisSerializer<Rule> buildJsonSerializer() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    PolymorphicTypeValidator ptv =
        BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType("com.company.drools.core.model")
            .allowIfBaseType("java.util")
            .allowIfBaseType("java.time")
            .build();
    objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);
    return new Jackson2JsonRedisSerializer<>(objectMapper, Rule.class);
  }

  // Silence Awaitility unused-import warning if we add a polling test later
  @SuppressWarnings("unused")
  private void awaitNoop() {
    await().atMost(Duration.ofMillis(1));
  }
}
