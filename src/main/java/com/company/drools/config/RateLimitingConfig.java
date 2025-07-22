package com.company.drools.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration for API rate limiting
 */
@Configuration
public class RateLimitingConfig {

    @Value("${drools.rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    @Value("${drools.rate-limiting.requests-per-minute:1000}")
    private int requestsPerMinute;

    @Value("${drools.rate-limiting.requests-per-hour:10000}")
    private int requestsPerHour;

    @Value("${drools.rate-limiting.burst-size:100}")
    private int burstSize;

    @Value("${drools.rate-limiting.cleanup-interval-minutes:5}")
    private int cleanupIntervalMinutes;

    public boolean isRateLimitingEnabled() {
        return rateLimitingEnabled;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public int getRequestsPerHour() {
        return requestsPerHour;
    }

    public int getBurstSize() {
        return burstSize;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }

    @Bean
    public InMemoryRateLimitingService rateLimitingService() {
        return new InMemoryRateLimitingService(this);
    }

    /**
     * Simple in-memory rate limiting service
     */
    public static class InMemoryRateLimitingService {
        private final RateLimitingConfig config;
        private final ConcurrentHashMap<String, ClientRateData> clientData;
        private volatile long lastCleanup;

        public InMemoryRateLimitingService(RateLimitingConfig config) {
            this.config = config;
            this.clientData = new ConcurrentHashMap<>();
            this.lastCleanup = System.currentTimeMillis();
        }

        public boolean isAllowed(String clientId) {
            if (!config.isRateLimitingEnabled()) {
                return true;
            }

            long now = System.currentTimeMillis();
            cleanupOldEntries(now);

            ClientRateData data = clientData.computeIfAbsent(clientId, k -> new ClientRateData());
            return data.isAllowed(now, config);
        }

        public RateLimitInfo getRateLimitInfo(String clientId) {
            if (!config.isRateLimitingEnabled()) {
                return new RateLimitInfo(config.getRequestsPerMinute(), config.getRequestsPerMinute(), 60000);
            }

            ClientRateData data = clientData.get(clientId);
            if (data == null) {
                return new RateLimitInfo(config.getRequestsPerMinute(), config.getRequestsPerMinute(), 60000);
            }

            long now = System.currentTimeMillis();
            return data.getRateLimitInfo(now, config);
        }

        private void cleanupOldEntries(long now) {
            // Only cleanup every configured interval to avoid performance impact
            if (now - lastCleanup > config.getCleanupIntervalMinutes() * 60 * 1000L) {
                lastCleanup = now;
                long oneHourAgo = now - 60 * 60 * 1000L;
                
                clientData.entrySet().removeIf(entry -> 
                    entry.getValue().getLastAccess() < oneHourAgo
                );
            }
        }

        private static class ClientRateData {
            private final AtomicLong requestCountMinute = new AtomicLong(0);
            private final AtomicLong requestCountHour = new AtomicLong(0);
            private volatile long minuteWindowStart;
            private volatile long hourWindowStart;
            private volatile long lastAccess;

            public boolean isAllowed(long now, RateLimitingConfig config) {
                lastAccess = now;
                
                // Reset windows if needed
                if (now - minuteWindowStart >= 60 * 1000L) {
                    minuteWindowStart = now;
                    requestCountMinute.set(0);
                }
                
                if (now - hourWindowStart >= 60 * 60 * 1000L) {
                    hourWindowStart = now;
                    requestCountHour.set(0);
                }

                long currentMinuteCount = requestCountMinute.get();
                long currentHourCount = requestCountHour.get();

                // Check limits
                if (currentMinuteCount >= config.getRequestsPerMinute()) {
                    return false;
                }
                
                if (currentHourCount >= config.getRequestsPerHour()) {
                    return false;
                }

                // Increment counters
                requestCountMinute.incrementAndGet();
                requestCountHour.incrementAndGet();
                return true;
            }

            public RateLimitInfo getRateLimitInfo(long now, RateLimitingConfig config) {
                // Reset windows if needed (same logic as isAllowed)
                if (now - minuteWindowStart >= 60 * 1000L) {
                    minuteWindowStart = now;
                    requestCountMinute.set(0);
                }

                long currentMinuteCount = requestCountMinute.get();
                long remaining = Math.max(0, config.getRequestsPerMinute() - currentMinuteCount);
                long resetTime = minuteWindowStart + 60 * 1000L - now;

                return new RateLimitInfo(config.getRequestsPerMinute(), remaining, Math.max(0, resetTime));
            }

            public long getLastAccess() {
                return lastAccess;
            }
        }
    }

    /**
     * Rate limit information for HTTP headers
     */
    public static class RateLimitInfo {
        private final long limit;
        private final long remaining;
        private final long resetTimeMs;

        public RateLimitInfo(long limit, long remaining, long resetTimeMs) {
            this.limit = limit;
            this.remaining = remaining;
            this.resetTimeMs = resetTimeMs;
        }

        public long getLimit() {
            return limit;
        }

        public long getRemaining() {
            return remaining;
        }

        public long getResetTimeMs() {
            return resetTimeMs;
        }

        public long getResetTimeSeconds() {
            return Math.max(1, resetTimeMs / 1000);
        }
    }
}