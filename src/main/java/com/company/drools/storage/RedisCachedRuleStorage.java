package com.company.drools.storage;

import com.company.drools.core.model.Rule;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

/**
 * Read-through Redis cache decorator over an underlying {@link RuleStorage}.
 *
 * <p>When {@code redis.enabled=true}, {@link StorageFactory#createRuleStorage()} wraps the
 * configured base storage (S3/local/memory) with this decorator. Cache layer for DRL source text
 * only — compiled KieBases live in {@code DroolsEngineService.kieContainer}, not here.
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li><b>Reads</b> ({@link #getRule}, {@link #getAllRules}, {@link #ruleExists}): check Redis
 *       first; on miss or circuit-open, delegate to underlying storage and populate Redis on the
 *       way back.
 *   <li><b>Writes</b> ({@link #saveRule}, {@link #deleteRule}): write-through — delegate first,
 *       then update Redis (best-effort).
 *   <li><b>Invalidation</b> ({@link #refreshCache}, {@link #refreshRule}): clear Redis entries
 *       BEFORE delegating, so subsequent reads repopulate fresh data.
 *   <li><b>Authoritative</b> ({@link #getTotalRuleCount}, {@link #getRuleIds}): always delegate (S3
 *       is source of truth for "what rules exist").
 * </ul>
 *
 * <p>Failure modes:
 *
 * <ul>
 *   <li>Redis down → circuit breaker opens → reads fall through to delegate, writes silently
 *       dropped. Service continues to serve.
 *   <li>Best-effort writes: a failed cache populate is logged but not propagated.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")
public class RedisCachedRuleStorage implements RuleStorage {

  private static final Logger log = LoggerFactory.getLogger(RedisCachedRuleStorage.class);

  private static final String METRIC_HIT = "drools.cache.hit";
  private static final String METRIC_MISS = "drools.cache.miss";
  private static final String METRIC_BULK_HIT = "drools.cache.bulk.hit";
  private static final String METRIC_BULK_MISS = "drools.cache.bulk.miss";
  private static final String METRIC_READ_DURATION = "drools.cache.read.duration";
  private static final String METRIC_WRITE_DURATION = "drools.cache.write.duration";
  private static final String METRIC_INVALIDATION = "drools.cache.invalidation";
  private static final String TAG_LAYER = "layer";
  private static final String LAYER_REDIS = "redis";
  private static final String TAG_RESULT = "result";
  private static final String TAG_SCOPE = "scope";
  private static final int SCAN_BATCH = 1000;

  private final RedisTemplate<String, Rule> redisTemplate;
  private final CircuitBreaker redisCircuitBreaker;
  private final Duration ttl;
  private final String keyPrefix;
  private final MeterRegistry meterRegistry;

  /**
   * Underlying storage; set by {@link StorageFactory} immediately after construction. Not final
   * because the bean is constructed before the factory knows which base storage to wrap. {@code
   * volatile} so the {@link #setDelegate} write is visible to threads that obtained the decorator
   * via a different path (finding S10).
   */
  private volatile RuleStorage delegate;

  public RedisCachedRuleStorage(
      RedisTemplate<String, Rule> redisTemplate,
      @Qualifier("redisCircuitBreaker") CircuitBreaker redisCircuitBreaker,
      @Value("${redis.drl-rules.ttl-minutes:15}") long ttlMinutes,
      @Value("${redis.drl-rules.key-prefix:drools:rule:}") String keyPrefix,
      MeterRegistry meterRegistry) {
    this.redisTemplate = redisTemplate;
    this.redisCircuitBreaker = redisCircuitBreaker;
    this.ttl = Duration.ofMinutes(ttlMinutes);
    this.keyPrefix = keyPrefix;
    this.meterRegistry = meterRegistry;
    log.info("RedisCachedRuleStorage initialized: ttl={}, keyPrefix={}", ttl, keyPrefix);
  }

  /** Wire the underlying storage. Called by {@link StorageFactory} before first use. */
  public void setDelegate(RuleStorage delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    log.info("RedisCachedRuleStorage delegate set: {}", delegate.getClass().getSimpleName());
  }

  // ─── RuleStorage: reads ──────────────────────────────────────────────────────

  @Override
  public Optional<Rule> getRule(String ruleId) {
    requireDelegate();
    String key = redisKey(ruleId);
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      Supplier<Rule> op =
          CircuitBreaker.decorateSupplier(
              redisCircuitBreaker, () -> redisTemplate.opsForValue().get(key));
      Rule cached = op.get();
      if (cached != null) {
        meterRegistry.counter(METRIC_HIT, TAG_LAYER, LAYER_REDIS).increment();
        sample.stop(
            meterRegistry.timer(METRIC_READ_DURATION, TAG_LAYER, LAYER_REDIS, TAG_RESULT, "hit"));
        return Optional.of(cached);
      }
    } catch (CallNotPermittedException _) {
      log.debug("Redis circuit breaker open, falling through for ruleId={}", ruleId);
    } catch (Exception e) {
      log.warn("Redis GET failed for ruleId={}, falling through", ruleId, e);
    }

    meterRegistry.counter(METRIC_MISS, TAG_LAYER, LAYER_REDIS).increment();
    sample.stop(
        meterRegistry.timer(METRIC_READ_DURATION, TAG_LAYER, LAYER_REDIS, TAG_RESULT, "miss"));

    Optional<Rule> result = delegate.getRule(ruleId);
    result.ifPresent(this::cachePut);
    return result;
  }

  @Override
  public List<Rule> getAllRules() {
    requireDelegate();
    Timer.Sample sample = Timer.start(meterRegistry);

    List<String> expectedIds = delegate.getRuleIds();
    Map<String, Rule> existing = collectFromRedis();

    // Drop cached entries no longer authoritative (deleted at the delegate but still within their
    // Redis TTL) so a deleted rule is never resurrected into the compiled corpus. (S1)
    existing.keySet().retainAll(new HashSet<>(expectedIds));

    Set<String> missing = new HashSet<>(expectedIds);
    missing.removeAll(existing.keySet());

    if (missing.isEmpty() && !expectedIds.isEmpty()) {
      meterRegistry.counter(METRIC_BULK_HIT).increment();
      sample.stop(
          meterRegistry.timer(
              METRIC_READ_DURATION, TAG_LAYER, LAYER_REDIS, TAG_RESULT, "bulk_hit"));
      return new ArrayList<>(existing.values());
    }

    meterRegistry
        .counter(METRIC_BULK_MISS, "missing_count", String.valueOf(missing.size()))
        .increment();

    List<Rule> all = delegate.getAllRules();
    for (Rule r : all) {
      if (!existing.containsKey(r.getRuleId())) {
        cachePut(r);
        existing.put(r.getRuleId(), r);
      }
    }
    sample.stop(
        meterRegistry.timer(METRIC_READ_DURATION, TAG_LAYER, LAYER_REDIS, TAG_RESULT, "bulk_miss"));
    return new ArrayList<>(existing.values());
  }

  @Override
  public boolean ruleExists(String ruleId) {
    requireDelegate();
    try {
      Supplier<Boolean> op =
          CircuitBreaker.decorateSupplier(
              redisCircuitBreaker, () -> redisTemplate.hasKey(redisKey(ruleId)));
      Boolean has = op.get();
      if (Boolean.TRUE.equals(has)) {
        return true;
      }
    } catch (CallNotPermittedException _) {
      // fall through
    } catch (Exception e) {
      log.warn("Redis EXISTS failed for ruleId={}, falling through", ruleId, e);
    }
    return delegate.ruleExists(ruleId);
  }

  // ─── RuleStorage: writes ─────────────────────────────────────────────────────

  @Override
  public void saveRule(Rule rule) {
    requireDelegate();
    delegate.saveRule(rule);
    cachePut(rule);
  }

  @Override
  public void deleteRule(String ruleId) {
    requireDelegate();
    delegate.deleteRule(ruleId);
    safeDelete(ruleId);
  }

  // ─── RuleStorage: invalidation ───────────────────────────────────────────────

  @Override
  public void refreshCache() {
    requireDelegate();
    invalidateAll();
    delegate.refreshCache();
  }

  @Override
  public void refreshRule(String ruleId) {
    requireDelegate();
    meterRegistry.counter(METRIC_INVALIDATION, TAG_SCOPE, "single").increment();
    safeDelete(ruleId);
    delegate.refreshRule(ruleId);
    // Re-populate authoritatively from the base storage (write-through). If the best-effort DEL
    // above was dropped (Redis blip / circuit open), this SET overwrites any stale entry so
    // siblings
    // don't keep serving old DRL text after a "successful" refresh. (S2)
    delegate.getRule(ruleId).ifPresent(this::cachePut);
  }

  // ─── RuleStorage: authoritative pass-through ─────────────────────────────────

  @Override
  public long getTotalRuleCount() {
    requireDelegate();
    return delegate.getTotalRuleCount();
  }

  @Override
  public List<String> getRuleIds() {
    requireDelegate();
    return delegate.getRuleIds();
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private void cachePut(Rule rule) {
    if (rule == null) {
      return;
    }
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      Runnable op =
          CircuitBreaker.decorateRunnable(
              redisCircuitBreaker,
              () -> redisTemplate.opsForValue().set(redisKey(rule.getRuleId()), rule, ttl));
      op.run();
      sample.stop(
          meterRegistry.timer(METRIC_WRITE_DURATION, TAG_LAYER, LAYER_REDIS, TAG_RESULT, "ok"));
    } catch (CallNotPermittedException _) {
      log.debug("Redis circuit breaker open, skipping put for ruleId={}", rule.getRuleId());
      sample.stop(
          meterRegistry.timer(
              METRIC_WRITE_DURATION, TAG_LAYER, LAYER_REDIS, TAG_RESULT, "cb_open"));
    } catch (Exception e) {
      log.warn("Redis SET failed for ruleId={}, best-effort skip", rule.getRuleId(), e);
      sample.stop(
          meterRegistry.timer(METRIC_WRITE_DURATION, TAG_LAYER, LAYER_REDIS, TAG_RESULT, "error"));
    }
  }

  private void safeDelete(String ruleId) {
    try {
      Runnable op =
          CircuitBreaker.decorateRunnable(
              redisCircuitBreaker, () -> redisTemplate.delete(redisKey(ruleId)));
      op.run();
    } catch (CallNotPermittedException _) {
      log.debug("Redis circuit breaker open, skipping delete for ruleId={}", ruleId);
    } catch (Exception e) {
      log.warn("Redis DEL failed for ruleId={}", ruleId, e);
    }
  }

  private void invalidateAll() {
    meterRegistry.counter(METRIC_INVALIDATION, TAG_SCOPE, "bulk").increment();
    try {
      Set<String> keys = scanKeys();
      if (keys.isEmpty()) {
        return;
      }
      Runnable op =
          CircuitBreaker.decorateRunnable(redisCircuitBreaker, () -> redisTemplate.delete(keys));
      op.run();
      log.info("Invalidated {} keys from Redis cache", keys.size());
    } catch (CallNotPermittedException _) {
      log.warn("Redis circuit breaker open during bulk invalidation");
    } catch (Exception e) {
      log.warn("Redis bulk invalidation failed", e);
    }
  }

  /** Collect everything currently cached under {@code keyPrefix*} via SCAN + MGET. */
  private Map<String, Rule> collectFromRedis() {
    Map<String, Rule> existing = new HashMap<>();
    try {
      Set<String> keys = scanKeys();
      if (keys.isEmpty()) {
        return existing;
      }
      List<String> keyList = new ArrayList<>(keys);
      Supplier<List<Rule>> op =
          CircuitBreaker.decorateSupplier(
              redisCircuitBreaker, () -> redisTemplate.opsForValue().multiGet(keyList));
      List<Rule> values = op.get();
      if (values != null) {
        for (int i = 0; i < keyList.size() && i < values.size(); i++) {
          Rule v = values.get(i);
          if (v != null) {
            existing.put(ruleIdFromKey(keyList.get(i)), v);
          }
        }
      }
    } catch (CallNotPermittedException _) {
      log.debug("Redis circuit breaker open during bulk SCAN");
    } catch (Exception e) {
      log.warn("Redis bulk SCAN+MGET failed, falling through", e);
    }
    return existing;
  }

  /**
   * SCAN all keys under {@code keyPrefix*}. CB-wrapped so SCAN failures contribute to the Redis
   * circuit-breaker's sliding window (previously SCAN bypassed the CB entirely).
   *
   * <p>Returns an empty {@link Set} on circuit-open, connection failure, or any other Redis error.
   * Callers must treat an empty result as a cache miss — both call sites ({@link #invalidateAll},
   * {@link #collectFromRedis}) already short-circuit on empty key sets and degrade gracefully to
   * the delegate.
   */
  private Set<String> scanKeys() {
    try {
      Supplier<Set<String>> op =
          CircuitBreaker.decorateSupplier(
              redisCircuitBreaker,
              () -> {
                Set<String> result =
                    redisTemplate.execute(
                        (RedisCallback<Set<String>>)
                            connection -> {
                              Set<String> keys = new HashSet<>();
                              ScanOptions options =
                                  ScanOptions.scanOptions()
                                      .match(keyPrefix + "*")
                                      .count(SCAN_BATCH)
                                      .build();
                              try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                                while (cursor.hasNext()) {
                                  keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                                }
                              }
                              return keys;
                            });
                return result != null ? result : Set.of();
              });
      return op.get();
    } catch (CallNotPermittedException _) {
      log.debug("Redis circuit breaker open, SCAN skipped → empty result");
      return Set.of();
    } catch (Exception e) {
      log.warn("Redis SCAN failed, returning empty result", e);
      return Set.of();
    }
  }

  private String redisKey(String ruleId) {
    return keyPrefix + ruleId;
  }

  private String ruleIdFromKey(String key) {
    return key.startsWith(keyPrefix) ? key.substring(keyPrefix.length()) : key;
  }

  private void requireDelegate() {
    if (delegate == null) {
      throw new IllegalStateException(
          "RedisCachedRuleStorage delegate not set; StorageFactory must call setDelegate()");
    }
  }
}
