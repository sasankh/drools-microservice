package com.company.drools.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CacheStatistics")
class CacheStatisticsTest {

  @Nested
  @DisplayName("Hit Rate and Miss Rate")
  class HitRateAndMissRate {

    @Test
    @DisplayName("getMissRate returns 1 minus hit rate")
    void testGetMissRate_ReturnsOneMinusHitRate() {
      // 3 hits, 1 miss -> hitRate = 0.75, missRate = 0.25
      CacheStatistics stats = new CacheStatistics(3, 1, 0, 4, 100, Instant.now());

      assertThat(stats.getHitRate()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.001));
      assertThat(stats.getMissRate()).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("getMissRate returns 1.0 when all requests are misses")
    void testGetMissRate_AllMisses_ReturnsOne() {
      CacheStatistics stats = new CacheStatistics(0, 5, 0, 0, 100, Instant.now());

      assertThat(stats.getHitRate()).isEqualTo(0.0);
      assertThat(stats.getMissRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("getMissRate returns 0.0 when all requests are hits")
    void testGetMissRate_AllHits_ReturnsZero() {
      CacheStatistics stats = new CacheStatistics(10, 0, 0, 5, 100, Instant.now());

      assertThat(stats.getHitRate()).isEqualTo(1.0);
      assertThat(stats.getMissRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("hit rate and miss rate are both 0.0/1.0 when no requests made")
    void testHitRateAndMissRate_NoRequests() {
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 0, 100, Instant.now());

      assertThat(stats.getHitRate()).isEqualTo(0.0);
      assertThat(stats.getMissRate()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Created At")
  class CreatedAt {

    @Test
    @DisplayName("getCreatedAt returns non-null Instant")
    void testGetCreatedAt_ReturnsNonNull() {
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 0, 100, Instant.now());

      assertThat(stats.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("getCreatedAt returns an Instant close to now")
    void testGetCreatedAt_IsCloseToNow() {
      Instant before = Instant.now();
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 0, 100, Instant.now());
      Instant after = Instant.now();

      assertThat(stats.getCreatedAt()).isAfterOrEqualTo(before);
      assertThat(stats.getCreatedAt()).isBeforeOrEqualTo(after);
    }
  }

  @Nested
  @DisplayName("isEmpty")
  class IsEmpty {

    @Test
    @DisplayName("isEmpty returns true when size is 0")
    void testIsEmpty_SizeZero_ReturnsTrue() {
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 0, 100, Instant.now());

      assertThat(stats.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("isEmpty returns false when size is greater than 0")
    void testIsEmpty_SizeGreaterThanZero_ReturnsFalse() {
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 5, 100, Instant.now());

      assertThat(stats.isEmpty()).isFalse();
    }
  }

  @Nested
  @DisplayName("isFull")
  class IsFull {

    @Test
    @DisplayName("isFull returns false when size is less than maxSize")
    void testIsFull_SizeLessThanMaxSize_ReturnsFalse() {
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 3, 10, Instant.now());

      assertThat(stats.isFull()).isFalse();
    }

    @Test
    @DisplayName("isFull returns true when size equals maxSize")
    void testIsFull_SizeEqualsMaxSize_ReturnsTrue() {
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 10, 10, Instant.now());

      assertThat(stats.isFull()).isTrue();
    }

    @Test
    @DisplayName("isFull returns true when size exceeds maxSize")
    void testIsFull_SizeExceedsMaxSize_ReturnsTrue() {
      CacheStatistics stats = new CacheStatistics(0, 0, 0, 15, 10, Instant.now());

      assertThat(stats.isFull()).isTrue();
    }
  }

  @Nested
  @DisplayName("toString")
  class ToString {

    @Test
    @DisplayName("toString contains expected fields")
    void testToString_ContainsExpectedFields() {
      CacheStatistics stats = new CacheStatistics(10, 5, 2, 8, 100, Instant.now());

      String result = stats.toString();

      assertThat(result).contains("CacheStatistics{");
      assertThat(result).contains("hits=10");
      assertThat(result).contains("misses=5");
      assertThat(result).contains("evictions=2");
      assertThat(result).contains("size=8");
      assertThat(result).contains("maxSize=100");
      assertThat(result).contains("hitRate=");
      assertThat(result).contains("lastAccess=");
    }

    @Test
    @DisplayName("toString formats hit rate as percentage")
    void testToString_FormatsHitRateAsPercentage() {
      // 3 hits, 1 miss = 75% hit rate
      CacheStatistics stats = new CacheStatistics(3, 1, 0, 4, 100, Instant.now());

      String result = stats.toString();

      assertThat(result).contains("hitRate=75.00%");
    }
  }

  @Nested
  @DisplayName("Basic Getters")
  class BasicGetters {

    @Test
    @DisplayName("all getters return correct values")
    void testGetters_ReturnCorrectValues() {
      Instant lastAccess = Instant.now();
      CacheStatistics stats = new CacheStatistics(10, 5, 3, 8, 100, lastAccess);

      assertThat(stats.getHits()).isEqualTo(10);
      assertThat(stats.getMisses()).isEqualTo(5);
      assertThat(stats.getEvictions()).isEqualTo(3);
      assertThat(stats.getSize()).isEqualTo(8);
      assertThat(stats.getMaxSize()).isEqualTo(100);
      assertThat(stats.getTotalRequests()).isEqualTo(15);
      assertThat(stats.getLastAccess()).isEqualTo(lastAccess);
    }
  }
}
