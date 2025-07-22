package com.company.drools.cache;

import java.time.Instant;

/**
 * Immutable cache statistics data.
 */
public class CacheStatistics {

  private final long hits;
  private final long misses;
  private final long evictions;
  private final long size;
  private final long maxSize;
  private final double hitRate;
  private final Instant lastAccess;
  private final Instant createdAt;

  public CacheStatistics(long hits, long misses, long evictions, long size, long maxSize, 
                        Instant lastAccess) {
    this.hits = hits;
    this.misses = misses;
    this.evictions = evictions;
    this.size = size;
    this.maxSize = maxSize;
    this.hitRate = calculateHitRate(hits, misses);
    this.lastAccess = lastAccess;
    this.createdAt = Instant.now();
  }

  private double calculateHitRate(long hits, long misses) {
    long total = hits + misses;
    return total == 0 ? 0.0 : (double) hits / total;
  }

  public long getHits() {
    return hits;
  }

  public long getMisses() {
    return misses;
  }

  public long getEvictions() {
    return evictions;
  }

  public long getSize() {
    return size;
  }

  public long getMaxSize() {
    return maxSize;
  }

  public double getHitRate() {
    return hitRate;
  }

  public double getMissRate() {
    return 1.0 - hitRate;
  }

  public Instant getLastAccess() {
    return lastAccess;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public long getTotalRequests() {
    return hits + misses;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public boolean isFull() {
    return size >= maxSize;
  }

  @Override
  public String toString() {
    return "CacheStatistics{" +
        "hits=" + hits +
        ", misses=" + misses +
        ", evictions=" + evictions +
        ", size=" + size +
        ", maxSize=" + maxSize +
        ", hitRate=" + String.format("%.2f%%", hitRate * 100) +
        ", lastAccess=" + lastAccess +
        '}';
  }
}