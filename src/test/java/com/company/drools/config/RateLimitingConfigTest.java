package com.company.drools.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("RateLimitingConfig")
class RateLimitingConfigTest {

  private RateLimitingConfig config;

  @BeforeEach
  void setUp() {
    config = new RateLimitingConfig();
    ReflectionTestUtils.setField(config, "rateLimitingEnabled", true);
    ReflectionTestUtils.setField(config, "requestsPerMinute", 10);
    ReflectionTestUtils.setField(config, "requestsPerHour", 500);
    ReflectionTestUtils.setField(config, "burstSize", 20);
    ReflectionTestUtils.setField(config, "cleanupIntervalMinutes", 3);
    ReflectionTestUtils.setField(config, "maxClients", 10000);
  }

  @Nested
  @DisplayName("Config Getters")
  class ConfigGetters {

    @Test
    @DisplayName("isRateLimitingEnabled returns configured value")
    void testIsRateLimitingEnabled() {
      assertThat(config.isRateLimitingEnabled()).isTrue();

      ReflectionTestUtils.setField(config, "rateLimitingEnabled", false);
      assertThat(config.isRateLimitingEnabled()).isFalse();
    }

    @Test
    @DisplayName("getRequestsPerMinute returns configured value")
    void testGetRequestsPerMinute() {
      assertThat(config.getRequestsPerMinute()).isEqualTo(10);
    }

    @Test
    @DisplayName("getRequestsPerHour returns configured value")
    void testGetRequestsPerHour() {
      assertThat(config.getRequestsPerHour()).isEqualTo(500);
    }

    @Test
    @DisplayName("getBurstSize returns configured value")
    void testGetBurstSize() {
      assertThat(config.getBurstSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("getCleanupIntervalMinutes returns configured value")
    void testGetCleanupIntervalMinutes() {
      assertThat(config.getCleanupIntervalMinutes()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("InMemoryRateLimitingService")
  class InMemoryRateLimitingServiceTests {

    private RateLimitingConfig.InMemoryRateLimitingService service;

    @BeforeEach
    void setUp() {
      service = new RateLimitingConfig.InMemoryRateLimitingService(config);
    }

    @Test
    @DisplayName("isAllowed returns true when rate limiting is disabled")
    void testIsAllowed_WhenDisabled_AlwaysReturnsTrue() {
      ReflectionTestUtils.setField(config, "rateLimitingEnabled", false);

      for (int i = 0; i < 100; i++) {
        assertThat(service.isAllowed("client-1")).isTrue();
      }
    }

    @Test
    @DisplayName("isAllowed returns true when within per-minute limit")
    void testIsAllowed_WithinMinuteLimit_ReturnsTrue() {
      for (int i = 0; i < 10; i++) {
        assertThat(service.isAllowed("client-2")).isTrue();
      }
    }

    @Test
    @DisplayName("isAllowed returns false when per-minute limit is exceeded")
    void testIsAllowed_ExceedsMinuteLimit_ReturnsFalse() {
      // Exhaust the per-minute limit of 10
      for (int i = 0; i < 10; i++) {
        assertThat(service.isAllowed("client-3")).isTrue();
      }

      // 11th request should be denied
      assertThat(service.isAllowed("client-3")).isFalse();
    }

    @Test
    @DisplayName("isAllowed returns false when per-hour limit is exceeded")
    void testIsAllowed_ExceedsHourLimit_ReturnsFalse() {
      // Set per-minute limit very high so only per-hour limit triggers
      ReflectionTestUtils.setField(config, "requestsPerMinute", 10000);
      ReflectionTestUtils.setField(config, "requestsPerHour", 5);

      service = new RateLimitingConfig.InMemoryRateLimitingService(config);

      for (int i = 0; i < 5; i++) {
        assertThat(service.isAllowed("client-4")).isTrue();
      }

      // 6th request should be denied by hour limit
      assertThat(service.isAllowed("client-4")).isFalse();
    }

    @Test
    @DisplayName("getRateLimitInfo returns full capacity when rate limiting is disabled")
    void testGetRateLimitInfo_WhenDisabled_ReturnsFullCapacity() {
      ReflectionTestUtils.setField(config, "rateLimitingEnabled", false);

      RateLimitingConfig.RateLimitInfo info = service.getRateLimitInfo("unknown-client");

      assertThat(info.getLimit()).isEqualTo(config.getRequestsPerMinute());
      assertThat(info.getRemaining()).isEqualTo(config.getRequestsPerMinute());
      assertThat(info.getResetTimeMs()).isEqualTo(60000);
    }

    @Test
    @DisplayName("getRateLimitInfo returns full capacity for unknown client")
    void testGetRateLimitInfo_UnknownClient_ReturnsFullCapacity() {
      RateLimitingConfig.RateLimitInfo info = service.getRateLimitInfo("never-seen-client");

      assertThat(info.getLimit()).isEqualTo(10);
      assertThat(info.getRemaining()).isEqualTo(10);
      assertThat(info.getResetTimeMs()).isEqualTo(60000);
    }

    @Test
    @DisplayName("getRateLimitInfo returns remaining correctly after some requests")
    void testGetRateLimitInfo_KnownClient_ReturnsRemainingCorrectly() {
      // Make 3 requests
      for (int i = 0; i < 3; i++) {
        service.isAllowed("client-5");
      }

      RateLimitingConfig.RateLimitInfo info = service.getRateLimitInfo("client-5");

      assertThat(info.getLimit()).isEqualTo(10);
      assertThat(info.getRemaining()).isEqualTo(7);
      assertThat(info.getResetTimeMs()).isGreaterThanOrEqualTo(0);
      assertThat(info.getResetTimeMs()).isLessThanOrEqualTo(60000);
    }

    @Test
    @DisplayName("cleanupOldEntries removes stale client data")
    void testCleanupOldEntries_RemovesStaleData() {
      // Make a request so client data exists
      service.isAllowed("stale-client");

      // Verify client is tracked (getRateLimitInfo returns non-full-capacity data)
      RateLimitingConfig.RateLimitInfo infoBefore = service.getRateLimitInfo("stale-client");
      assertThat(infoBefore.getRemaining()).isEqualTo(9); // one request used

      // Set lastCleanup to far in the past to trigger cleanup on next call
      ReflectionTestUtils.setField(service, "lastCleanup", 0L);

      // Set the client's lastAccess to more than 1 hour ago via reflection
      Object clientDataMap = ReflectionTestUtils.getField(service, "clientData");
      assertThat(clientDataMap).isInstanceOf(java.util.concurrent.ConcurrentHashMap.class);

      @SuppressWarnings("unchecked")
      java.util.concurrent.ConcurrentHashMap<String, Object> map =
          (java.util.concurrent.ConcurrentHashMap<String, Object>) clientDataMap;

      Object clientRateData = map.get("stale-client");
      assertThat(clientRateData).isNotNull();

      // Set lastAccess to more than 1 hour ago so cleanup removes it
      ReflectionTestUtils.setField(
          clientRateData, "lastAccess", System.currentTimeMillis() - 2 * 60 * 60 * 1000L);

      // Trigger cleanup via isAllowed (which calls cleanupOldEntries internally)
      service.isAllowed("fresh-client");

      // Stale client should have been cleaned up; getRateLimitInfo returns full capacity
      RateLimitingConfig.RateLimitInfo infoAfter = service.getRateLimitInfo("stale-client");
      assertThat(infoAfter.getRemaining()).isEqualTo(10); // full capacity = unknown client
    }

    @Test
    @DisplayName("cleanupOldEntries does not run before interval expires")
    void testCleanupOldEntries_DoesNotRunBeforeInterval() throws Exception {
      // Make a request
      service.isAllowed("persistent-client");

      // lastCleanup is set to now by constructor, so cleanup should NOT trigger
      // even if we make client data appear old, it should still be there
      Object clientDataMap = ReflectionTestUtils.getField(service, "clientData");
      @SuppressWarnings("unchecked")
      java.util.concurrent.ConcurrentHashMap<String, Object> map =
          (java.util.concurrent.ConcurrentHashMap<String, Object>) clientDataMap;

      Object clientRateData = map.get("persistent-client");
      // Use direct field access since ReflectionTestUtils doesn't resolve private inner class
      // fields
      java.lang.reflect.Field lastAccessField =
          clientRateData.getClass().getDeclaredField("lastAccess");
      lastAccessField.setAccessible(true);
      lastAccessField.set(clientRateData, System.currentTimeMillis() - 2 * 60 * 60 * 1000L);

      // Trigger another request but cleanup interval has not passed
      service.isAllowed("another-client");

      // persistent-client should still exist because cleanup did not run
      assertThat(map.containsKey("persistent-client")).isTrue();
    }
  }

  @Nested
  @DisplayName("RateLimitInfo")
  class RateLimitInfoTests {

    @Test
    @DisplayName("constructor and getters return correct values")
    void testConstructorAndGetters() {
      RateLimitingConfig.RateLimitInfo info = new RateLimitingConfig.RateLimitInfo(100, 75, 45000);

      assertThat(info.getLimit()).isEqualTo(100);
      assertThat(info.getRemaining()).isEqualTo(75);
      assertThat(info.getResetTimeMs()).isEqualTo(45000);
    }

    @Test
    @DisplayName("getResetTimeSeconds returns correct value")
    void testGetResetTimeSeconds() {
      RateLimitingConfig.RateLimitInfo info = new RateLimitingConfig.RateLimitInfo(100, 50, 30000);

      assertThat(info.getResetTimeSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("getResetTimeSeconds returns minimum of 1 for small values")
    void testGetResetTimeSeconds_MinimumOne() {
      RateLimitingConfig.RateLimitInfo info = new RateLimitingConfig.RateLimitInfo(100, 50, 500);

      assertThat(info.getResetTimeSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("getResetTimeSeconds returns 1 for zero milliseconds")
    void testGetResetTimeSeconds_ZeroMs() {
      RateLimitingConfig.RateLimitInfo info = new RateLimitingConfig.RateLimitInfo(100, 50, 0);

      assertThat(info.getResetTimeSeconds()).isEqualTo(1);
    }
  }
}
