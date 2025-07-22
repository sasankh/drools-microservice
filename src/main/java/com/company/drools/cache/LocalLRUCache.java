package com.company.drools.cache;

import com.company.drools.core.model.Rule;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thread-safe LRU (Least Recently Used) cache implementation using LinkedHashMap. Automatically
 * evicts oldest entries when maximum capacity is reached.
 */
@Component
public class LocalLRUCache implements RuleCache {

  private static final Logger log = LoggerFactory.getLogger(LocalLRUCache.class);

  private final int maxSize;
  private final boolean enabled;
  private final Map<String, Rule> cache;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final MeterRegistry meterRegistry;

  // Statistics tracking
  private final AtomicLong hits = new AtomicLong(0);
  private final AtomicLong misses = new AtomicLong(0);
  private final AtomicLong evictions = new AtomicLong(0);
  private volatile Instant lastAccess = Instant.now();

  public LocalLRUCache(
      @Value("${drools.cache.lru-max-size:100}") int maxSize,
      @Value("${drools.cache.enabled:true}") boolean enabled,
      MeterRegistry meterRegistry) {
    this.maxSize = maxSize;
    this.enabled = enabled;
    this.meterRegistry = meterRegistry;
    this.cache =
        new LinkedHashMap<String, Rule>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Rule> eldest) {
            boolean shouldRemove = size() > LocalLRUCache.this.maxSize;
            if (shouldRemove) {
              log.debug("Evicting rule from cache: {}", eldest.getKey());
              evictions.incrementAndGet();
              // Record cache eviction metric
              meterRegistry.counter("drools.cache.evictions", "cache_type", "local").increment();
            }
            return shouldRemove;
          }
        };

    log.info("LocalLRUCache initialized: maxSize={}, enabled={}", maxSize, enabled);
  }

  @Override
  public Optional<Rule> get(String ruleId) {
    if (!enabled) {
      return Optional.empty();
    }

    lock.readLock().lock();
    try {
      Rule rule = cache.get(ruleId);
      lastAccess = Instant.now();

      if (rule != null) {
        hits.incrementAndGet();
        meterRegistry.counter("drools.cache.hits", "cache_type", "local").increment();
        log.debug("Cache hit for rule: {}", ruleId);
        return Optional.of(rule);
      } else {
        misses.incrementAndGet();
        meterRegistry.counter("drools.cache.misses", "cache_type", "local").increment();
        log.debug("Cache miss for rule: {}", ruleId);
        return Optional.empty();
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void put(Rule rule) {
    if (!enabled || rule == null) {
      return;
    }

    lock.writeLock().lock();
    try {
      cache.put(rule.getRuleId(), rule);
      lastAccess = Instant.now();
      log.debug("Cached rule: {} (cache size: {})", rule.getRuleId(), cache.size());
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void remove(String ruleId) {
    if (!enabled) {
      return;
    }

    lock.writeLock().lock();
    try {
      Rule removed = cache.remove(ruleId);
      if (removed != null) {
        log.debug("Removed rule from cache: {}", ruleId);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public boolean contains(String ruleId) {
    if (!enabled) {
      return false;
    }

    lock.readLock().lock();
    try {
      return cache.containsKey(ruleId);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void clear() {
    if (!enabled) {
      return;
    }

    lock.writeLock().lock();
    try {
      int size = cache.size();
      cache.clear();
      log.info("Cleared cache: {} rules removed", size);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public long size() {
    if (!enabled) {
      return 0;
    }

    lock.readLock().lock();
    try {
      return cache.size();
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public long maxSize() {
    return maxSize;
  }

  @Override
  public List<String> getCachedRuleIds() {
    if (!enabled) {
      return new ArrayList<>();
    }

    lock.readLock().lock();
    try {
      return new ArrayList<>(cache.keySet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public CacheStatistics getStatistics() {
    lock.readLock().lock();
    try {
      return new CacheStatistics(
          hits.get(), misses.get(), evictions.get(), cache.size(), maxSize, lastAccess);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void warmUp(List<Rule> rules) {
    if (!enabled || rules == null || rules.isEmpty()) {
      return;
    }

    lock.writeLock().lock();
    try {
      log.info("Warming up cache with {} rules", rules.size());
      int warmedUp = 0;

      for (Rule rule : rules) {
        if (cache.size() >= maxSize) {
          log.debug("Cache full during warm-up, stopping at {} rules", warmedUp);
          break;
        }
        cache.put(rule.getRuleId(), rule);
        warmedUp++;
      }

      log.info("Cache warm-up complete: {} rules cached", warmedUp);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void evictIfNeeded() {
    // LinkedHashMap handles eviction automatically via removeEldestEntry
    // This method is provided for interface compliance
    if (!enabled) {
      return;
    }

    lock.readLock().lock();
    try {
      if (cache.size() > maxSize) {
        log.debug(
            "Cache size ({}) exceeds max size ({}), eviction will occur on next put",
            cache.size(),
            maxSize);
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  /** Returns cache efficiency metrics. */
  public double getCacheEfficiency() {
    CacheStatistics stats = getStatistics();
    return stats.getHitRate();
  }

  /** Returns cache utilization percentage. */
  public double getCacheUtilization() {
    return (double) size() / maxSize * 100;
  }
}
