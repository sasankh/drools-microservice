package com.company.drools.cache;

import com.company.drools.core.model.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-based distributed cache implementation for rules.
 * Provides distributed caching across multiple application instances.
 */
@Component
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true")
public class RedisRuleCache implements RuleCache {

  private static final Logger log = LoggerFactory.getLogger(RedisRuleCache.class);
  private static final String CACHE_KEY_PREFIX = "drools:rule:";
  private static final String CACHE_STATS_KEY = "drools:stats:";

  private final RedisTemplate<String, Rule> redisTemplate;
  private final Duration ttlDuration;

  // Local statistics (per instance)
  private final AtomicLong localHits = new AtomicLong(0);
  private final AtomicLong localMisses = new AtomicLong(0);
  private final AtomicLong localEvictions = new AtomicLong(0);
  private volatile Instant lastAccess = Instant.now();

  @Autowired
  public RedisRuleCache(RedisTemplate<String, Rule> redisTemplate, Duration redisTtlDuration) {
    this.redisTemplate = redisTemplate;
    this.ttlDuration = redisTtlDuration;
    log.info("RedisRuleCache initialized with TTL: {}", ttlDuration);
  }

  @Override
  public Optional<Rule> get(String ruleId) {
    String key = buildCacheKey(ruleId);
    
    try {
      Rule rule = redisTemplate.opsForValue().get(key);
      lastAccess = Instant.now();
      
      if (rule != null) {
        localHits.incrementAndGet();
        log.debug("Redis cache hit for rule: {}", ruleId);
        return Optional.of(rule);
      } else {
        localMisses.incrementAndGet();
        log.debug("Redis cache miss for rule: {}", ruleId);
        return Optional.empty();
      }
      
    } catch (DataAccessException e) {
      log.warn("Redis error during get operation for rule: {}", ruleId, e);
      localMisses.incrementAndGet();
      return Optional.empty();
    }
  }

  @Override
  public void put(Rule rule) {
    if (rule == null) {
      return;
    }

    String key = buildCacheKey(rule.getRuleId());
    
    try {
      redisTemplate.opsForValue().set(key, rule, ttlDuration);
      lastAccess = Instant.now();
      log.debug("Cached rule in Redis: {} (TTL: {})", rule.getRuleId(), ttlDuration);
      
    } catch (DataAccessException e) {
      log.warn("Redis error during put operation for rule: {}", rule.getRuleId(), e);
    }
  }

  @Override
  public void remove(String ruleId) {
    String key = buildCacheKey(ruleId);
    
    try {
      Boolean deleted = redisTemplate.delete(key);
      if (Boolean.TRUE.equals(deleted)) {
        log.debug("Removed rule from Redis cache: {}", ruleId);
      }
      
    } catch (DataAccessException e) {
      log.warn("Redis error during remove operation for rule: {}", ruleId, e);
    }
  }

  @Override
  public boolean contains(String ruleId) {
    String key = buildCacheKey(ruleId);
    
    try {
      return Boolean.TRUE.equals(redisTemplate.hasKey(key));
      
    } catch (DataAccessException e) {
      log.warn("Redis error during contains operation for rule: {}", ruleId, e);
      return false;
    }
  }

  @Override
  public void clear() {
    try {
      Set<String> keys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
      if (keys != null && !keys.isEmpty()) {
        Long deletedCount = redisTemplate.delete(keys);
        log.info("Cleared Redis cache: {} rules removed", deletedCount);
      }
      
    } catch (DataAccessException e) {
      log.error("Redis error during clear operation", e);
    }
  }

  @Override
  public long size() {
    try {
      Set<String> keys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
      return keys != null ? keys.size() : 0;
      
    } catch (DataAccessException e) {
      log.warn("Redis error during size operation", e);
      return 0;
    }
  }

  @Override
  public long maxSize() {
    // Redis doesn't have a built-in max size concept for our use case
    // Return a large number to indicate "unlimited" from application perspective
    return Long.MAX_VALUE;
  }

  @Override
  public List<String> getCachedRuleIds() {
    try {
      Set<String> keys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
      if (keys == null || keys.isEmpty()) {
        return new ArrayList<>();
      }
      
      // Extract rule IDs from cache keys
      return keys.stream()
          .map(this::extractRuleIdFromKey)
          .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
          
    } catch (DataAccessException e) {
      log.warn("Redis error during getCachedRuleIds operation", e);
      return new ArrayList<>();
    }
  }

  @Override
  public CacheStatistics getStatistics() {
    // Note: For Redis cache, size might be expensive to compute frequently
    // In production, consider caching this value
    long currentSize = size();
    
    return new CacheStatistics(
        localHits.get(),
        localMisses.get(),
        localEvictions.get(), // Redis handles eviction internally
        currentSize,
        maxSize(),
        lastAccess
    );
  }

  @Override
  public void warmUp(List<Rule> rules) {
    if (rules == null || rules.isEmpty()) {
      return;
    }

    log.info("Warming up Redis cache with {} rules", rules.size());
    int warmedUp = 0;

    try {
      // Use simple batch operations for warming up multiple rules
      for (Rule rule : rules) {
        put(rule);
        warmedUp++;
      }

      warmedUp = rules.size();
      log.info("Redis cache warm-up complete: {} rules cached", warmedUp);
      
    } catch (DataAccessException e) {
      log.error("Redis error during cache warm-up", e);
    }
  }

  @Override
  public void evictIfNeeded() {
    // Redis handles eviction automatically based on its eviction policies
    // This is a no-op for Redis cache
    log.debug("Redis handles eviction automatically, no manual eviction needed");
  }

  @Override
  public boolean isEnabled() {
    try {
      // Test Redis connectivity
      redisTemplate.opsForValue().get("connectivity-test");
      return true;
      
    } catch (Exception e) {
      log.warn("Redis connectivity test failed", e);
      return false;
    }
  }

  /**
   * Builds the Redis cache key for a rule ID.
   */
  private String buildCacheKey(String ruleId) {
    return CACHE_KEY_PREFIX + ruleId;
  }

  /**
   * Extracts rule ID from Redis cache key.
   */
  private String extractRuleIdFromKey(String key) {
    return key.startsWith(CACHE_KEY_PREFIX) 
        ? key.substring(CACHE_KEY_PREFIX.length()) 
        : key;
  }

  /**
   * Returns cache efficiency for this instance.
   */
  public double getLocalCacheEfficiency() {
    CacheStatistics stats = getStatistics();
    return stats.getHitRate();
  }
}