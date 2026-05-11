package com.company.drools.api.controller;

import com.company.drools.api.dto.HealthCheckResponse;
import com.company.drools.api.dto.HealthCheckResponse.ComponentHealth;
import com.company.drools.api.dto.RefreshRuleResponse;
import com.company.drools.api.dto.RefreshRulesResponse;
import com.company.drools.api.dto.RuleListResponse;
import com.company.drools.api.validation.ValidRuleId;
import com.company.drools.cache.CacheStatistics;
import com.company.drools.cache.RedisRuleCache;
import com.company.drools.cache.RuleCache;
import com.company.drools.common.LogSanitizer;
import com.company.drools.config.ThreadPoolConfig;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.S3RuleStorage;
import com.company.drools.storage.StorageFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Admin controller for rule management operations. Serves on the main application port (8080).
 * Note: Spring Boot Actuator endpoints (/actuator/*) use the management port (8081).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  private static final String METRIC_API_RESPONSE_TIME    = "drools.api.response.time";
  private static final String TAG_ENDPOINT                = "endpoint";
  private static final String TAG_STATUS                  = "status";
  private static final String TAG_ERROR_TYPE              = "error_type";
  private static final String STATUS_UP                   = "UP";
  private static final String STATUS_DOWN                 = "DOWN";
  private static final String STATUS_SUCCESS              = "success";
  private static final String STATUS_ERROR                = "error";
  private static final String ENDPOINT_ADMIN_HEALTH       = "/admin/health";
  private static final String ENDPOINT_ADMIN_THREAD_POOLS = "/admin/thread-pools";

  private final DroolsEngineService droolsEngineService;
  private final StorageFactory storageFactory;
  private final RuleCache ruleCache;
  private final MeterRegistry meterRegistry;
  private final ThreadPoolConfig threadPoolConfig;

  @Value("${drools.rule-source:memory}")
  private String ruleSource;

  @Value("${redis.enabled:false}")
  private boolean redisEnabled;

  @Autowired(required = false)
  private RedisConnectionFactory redisConnectionFactory;

  @Autowired(required = false)
  private S3Client s3Client;

  @Value("${drools.s3.bucket-name:}")
  private String s3BucketName;

  @Autowired(required = false)
  @Qualifier("s3CircuitBreaker")
  private CircuitBreaker s3CircuitBreaker;

  @Autowired(required = false)
  @Qualifier("redisCircuitBreaker")
  private CircuitBreaker redisCircuitBreaker;

  public AdminController(
      DroolsEngineService droolsEngineService,
      StorageFactory storageFactory,
      RuleCache ruleCache,
      MeterRegistry meterRegistry,
      ThreadPoolConfig threadPoolConfig) {
    this.droolsEngineService = droolsEngineService;
    this.storageFactory = storageFactory;
    this.ruleCache = ruleCache;
    this.meterRegistry = meterRegistry;
    this.threadPoolConfig = threadPoolConfig;
  }

  /** Enhanced health check endpoint with component status. */
  @GetMapping("/health")
  public ResponseEntity<HealthCheckResponse> health() {
    // Start timing the health check
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      // Record API request
      meterRegistry.counter("drools.api.requests", TAG_ENDPOINT, ENDPOINT_ADMIN_HEALTH).increment();

      Map<String, ComponentHealth> components = new HashMap<>();
      String overallStatus = STATUS_UP;

      // Check Drools engine health
      ComponentHealth droolsHealth = checkDroolsHealth();
      components.put("drools", droolsHealth);
      if (!STATUS_UP.equals(droolsHealth.getStatus())) {
        overallStatus = STATUS_DOWN;
      }

      // Check storage health (S3/Local/Memory)
      ComponentHealth storageHealth = checkStorageHealth();
      components.put("storage", storageHealth);
      if (!STATUS_UP.equals(storageHealth.getStatus())) {
        overallStatus = STATUS_DOWN;
      }

      // Check cache health
      ComponentHealth cacheHealth = checkCacheHealth();
      components.put("cache", cacheHealth);
      // Cache being down is not critical, so don't affect overall status

      // Check Redis health if enabled
      if (redisEnabled) {
        ComponentHealth redisHealth = checkRedisHealth();
        components.put("redis", redisHealth);
        // Redis being down is not critical if local cache works
      }

      // Check circuit breaker health
      ComponentHealth circuitBreakerHealth = checkCircuitBreakerHealth();
      components.put("circuit-breakers", circuitBreakerHealth);
      // Circuit breakers being open don't affect overall health (they are protection mechanism)

      HealthCheckResponse response = new HealthCheckResponse(overallStatus, components);

      // Record successful response timing
      sample.stop(
          Timer.builder(METRIC_API_RESPONSE_TIME)
              .tag(TAG_ENDPOINT, ENDPOINT_ADMIN_HEALTH)
              .tag(TAG_STATUS, STATUS_SUCCESS)
              .register(meterRegistry));

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      // Record error response timing
      meterRegistry
          .counter("drools.api.errors", TAG_ENDPOINT, ENDPOINT_ADMIN_HEALTH, TAG_ERROR_TYPE, "unexpected")
          .increment();
      sample.stop(
          Timer.builder(METRIC_API_RESPONSE_TIME)
              .tag(TAG_ENDPOINT, ENDPOINT_ADMIN_HEALTH)
              .tag(TAG_STATUS, STATUS_ERROR)
              .register(meterRegistry));
      throw e;
    }
  }

  private ComponentHealth checkDroolsHealth() {
    Map<String, Object> details = new HashMap<>();
    try {
      long loadedRules = droolsEngineService.getLoadedRulesCount();
      long activeRules = droolsEngineService.getActiveRulesCount();

      details.put("loaded_rules", loadedRules);
      details.put("active_rules", activeRules);
      details.put("cache_hit_rate", calculateCacheHitRate());

      // Check if we have at least one rule loaded
      String status = loadedRules > 0 ? STATUS_UP : STATUS_DOWN;
      if (loadedRules == 0) {
        details.put(STATUS_ERROR,"No rules loaded");
      }

      return new ComponentHealth(status, details);
    } catch (Exception e) {
      details.put(STATUS_ERROR,e.getMessage());
      return new ComponentHealth(STATUS_DOWN,details);
    }
  }

  private ComponentHealth checkStorageHealth() {
    Map<String, Object> details = new HashMap<>();
    try {
      RuleStorage storage = storageFactory.createRuleStorage();
      details.put("type", storage.getClass().getSimpleName());
      details.put("rule_source", ruleSource);

      // For S3, check bucket accessibility
      if (storage instanceof S3RuleStorage && s3Client != null && !s3BucketName.isEmpty()) {
        try {
          s3Client.headBucket(builder -> builder.bucket(s3BucketName));
          details.put("s3_bucket", s3BucketName);
          details.put("s3_accessible", true);
        } catch (Exception e) {
          details.put("s3_bucket", s3BucketName);
          details.put("s3_accessible", false);
          details.put("s3_error", e.getMessage());
          return new ComponentHealth(STATUS_DOWN,details);
        }
      }

      // Try to get rule count
      long ruleCount = storage.getTotalRuleCount();
      details.put("total_rules", ruleCount);

      return new ComponentHealth(STATUS_UP,details);
    } catch (Exception e) {
      details.put(STATUS_ERROR,e.getMessage());
      return new ComponentHealth(STATUS_DOWN,details);
    }
  }

  private ComponentHealth checkCacheHealth() {
    Map<String, Object> details = new HashMap<>();
    try {
      details.put("enabled", ruleCache.isEnabled());
      details.put("size", ruleCache.size());
      details.put("max_size", ruleCache.maxSize());

      if (ruleCache.isEnabled()) {
        CacheStatistics stats = ruleCache.getStatistics();
        Map<String, Object> statsMap = new HashMap<>();
        statsMap.put("hits", stats.getHits());
        statsMap.put("misses", stats.getMisses());
        statsMap.put("evictions", stats.getEvictions());
        statsMap.put("hit_rate", String.format("%.2f%%", stats.getHitRate() * 100));
        details.put("statistics", statsMap);
      }

      return new ComponentHealth(STATUS_UP,details);
    } catch (Exception e) {
      details.put(STATUS_ERROR,e.getMessage());
      return new ComponentHealth(STATUS_DOWN,details);
    }
  }

  private ComponentHealth checkRedisHealth() {
    Map<String, Object> details = new HashMap<>();
    try {
      if (redisConnectionFactory == null) {
        details.put("enabled", false);
        return new ComponentHealth(STATUS_UP,details);
      }

      // Try to ping Redis (close connection to prevent leak)
      var connection = redisConnectionFactory.getConnection();
      try {
        connection.ping();
      } finally {
        connection.close();
      }
      details.put("connected", true);

      // Get Redis info if it's RedisRuleCache
      if (ruleCache instanceof RedisRuleCache) {
        details.put("cache_type", "RedisRuleCache");
      }

      return new ComponentHealth(STATUS_UP,details);
    } catch (Exception e) {
      details.put("connected", false);
      details.put(STATUS_ERROR,e.getMessage());
      return new ComponentHealth(STATUS_DOWN,details);
    }
  }

  private ComponentHealth checkCircuitBreakerHealth() {
    Map<String, Object> details = new HashMap<>();

    try {
      // Check S3 circuit breaker if available
      if (s3CircuitBreaker != null) {
        details.put("s3_state", s3CircuitBreaker.getState().toString());
        details.put(
            "s3_metrics",
            Map.of(
                "failure_rate", s3CircuitBreaker.getMetrics().getFailureRate(),
                "successful_calls", s3CircuitBreaker.getMetrics().getNumberOfSuccessfulCalls(),
                "failed_calls", s3CircuitBreaker.getMetrics().getNumberOfFailedCalls(),
                "not_permitted_calls",
                    s3CircuitBreaker.getMetrics().getNumberOfNotPermittedCalls()));
      }

      // Check Redis circuit breaker if available
      if (redisCircuitBreaker != null) {
        details.put("redis_state", redisCircuitBreaker.getState().toString());
        details.put(
            "redis_metrics",
            Map.of(
                "failure_rate", redisCircuitBreaker.getMetrics().getFailureRate(),
                "successful_calls", redisCircuitBreaker.getMetrics().getNumberOfSuccessfulCalls(),
                "failed_calls", redisCircuitBreaker.getMetrics().getNumberOfFailedCalls(),
                "not_permitted_calls",
                    redisCircuitBreaker.getMetrics().getNumberOfNotPermittedCalls()));
      }

      details.put(
          "circuit_breakers_enabled", s3CircuitBreaker != null || redisCircuitBreaker != null);
      return new ComponentHealth(STATUS_UP,details);

    } catch (Exception e) {
      details.put(STATUS_ERROR,e.getMessage());
      return new ComponentHealth(STATUS_UP,details); // Circuit breaker errors don't affect health
    }
  }

  private double calculateCacheHitRate() {
    if (!ruleCache.isEnabled()) {
      return 0.0;
    }

    CacheStatistics stats = ruleCache.getStatistics();
    return stats.getHitRate();
  }

  /** Get basic system info. */
  @GetMapping("/info")
  public ResponseEntity<Map<String, Object>> info() {
    Map<String, Object> info = new HashMap<>();
    info.put("application", "Drools Rule Engine Microservice");
    info.put("version", "1.0.0");
    info.put("java_version", System.getProperty("java.version"));
    info.put("timestamp", Instant.now());

    return ResponseEntity.ok(info);
  }

  /** Get thread pool statistics for monitoring performance. */
  @GetMapping("/thread-pools")
  public ResponseEntity<Map<String, Object>> threadPoolStats() {
    // Start timing the request
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      // Record API request
      meterRegistry.counter("drools.api.requests", TAG_ENDPOINT, ENDPOINT_ADMIN_THREAD_POOLS).increment();

      Map<String, Object> stats = new HashMap<>();
      stats.put("rule_execution_pool", threadPoolConfig.getRuleExecutionPoolStats());
      stats.put("storage_pool", threadPoolConfig.getStoragePoolStats());
      stats.put("timestamp", Instant.now());

      // Record successful response
      sample.stop(
          Timer.builder(METRIC_API_RESPONSE_TIME)
              .tag(TAG_ENDPOINT, ENDPOINT_ADMIN_THREAD_POOLS)
              .tag(TAG_STATUS, STATUS_SUCCESS)
              .register(meterRegistry));

      return ResponseEntity.ok(stats);

    } catch (Exception e) {
      log.error("Error retrieving thread pool statistics", e);
      // Record error response
      meterRegistry
          .counter(
              "drools.api.errors", TAG_ENDPOINT, ENDPOINT_ADMIN_THREAD_POOLS, TAG_ERROR_TYPE, "unexpected")
          .increment();
      sample.stop(
          Timer.builder(METRIC_API_RESPONSE_TIME)
              .tag(TAG_ENDPOINT, ENDPOINT_ADMIN_THREAD_POOLS)
              .tag(TAG_STATUS, STATUS_ERROR)
              .register(meterRegistry));
      throw e;
    }
  }

  /** Refresh all rules from storage. */
  @PostMapping("/refresh-rules")
  public ResponseEntity<RefreshRulesResponse> refreshAllRules() {
    log.info("Refreshing all rules via admin endpoint");
    long startTime = System.currentTimeMillis();

    List<RefreshRulesResponse.RuleError> errors = new ArrayList<>();
    int rulesLoaded = 0;
    int rulesFailed = 0;

    try {
      // Load rules from storage
      RuleStorage storage = storageFactory.createRuleStorage();
      List<Rule> rules = storage.getAllRules();

      // Clear cache before reloading
      ruleCache.clear();

      // Reload rules into engine
      boolean success = droolsEngineService.loadRules(rules);

      if (success) {
        rulesLoaded = rules.size();

        // Warm up cache with new rules
        if (ruleCache.isEnabled()) {
          ruleCache.warmUp(rules);
        }

        log.info("Successfully refreshed {} rules", rulesLoaded);
      } else {
        rulesFailed = rules.size();
        errors.add(new RefreshRulesResponse.RuleError("ALL", "Failed to load rules into engine"));
      }

    } catch (Exception e) {
      log.error("Error during rule refresh", e);
      errors.add(new RefreshRulesResponse.RuleError("SYSTEM", e.getMessage()));
      rulesFailed = 1;
    }

    long duration = System.currentTimeMillis() - startTime;
    String status = rulesFailed == 0 ? "completed" : "completed_with_errors";

    RefreshRulesResponse response =
        new RefreshRulesResponse(status, rulesLoaded, rulesFailed, duration, errors);
    return ResponseEntity.ok(response);
  }

  /** Refresh a specific rule by ID. */
  @PostMapping("/refresh-rules/{ruleId}")
  public ResponseEntity<RefreshRuleResponse> refreshRule(@PathVariable @ValidRuleId String ruleId) {
    log.info("Refreshing rule: {}", LogSanitizer.sanitizeMessage(ruleId));
    long startTime = System.currentTimeMillis();

    try {
      // Get current rule metadata
      RuleMetadata currentMetadata = droolsEngineService.getRuleMetadata(ruleId);
      Instant previousVersion =
          currentMetadata != null && currentMetadata.getLastModified() != null
              ? currentMetadata.getLastModified().atZone(java.time.ZoneOffset.UTC).toInstant()
              : null;

      // Load rule from storage
      RuleStorage storage = storageFactory.createRuleStorage();
      Optional<Rule> ruleOpt = storage.getRule(ruleId);

      if (ruleOpt.isEmpty()) {
        RefreshRuleResponse response = new RefreshRuleResponse(ruleId, "not_found");
        response.setError("Rule not found in storage");
        return ResponseEntity.notFound().build();
      }

      Rule rule = ruleOpt.get();

      // Remove from cache
      ruleCache.remove(ruleId);

      // Reload rule into engine. loadOrReplaceRule merges the new rule into the current loaded
      // set before recompiling, so other rules continue to fire after a single-rule refresh.
      // (Previously this called loadRules(List.of(rule)) which silently replaced the entire
      // KieContainer with a single-rule one — see e2e-validation-findings.md Finding #1.)
      boolean success = droolsEngineService.loadOrReplaceRule(rule);

      if (success) {
        // Add back to cache
        ruleCache.put(rule);

        long compilationTime = System.currentTimeMillis() - startTime;
        Instant currentVersion =
            rule.getMetadata().getLastModified() != null
                ? rule.getMetadata().getLastModified().atZone(java.time.ZoneOffset.UTC).toInstant()
                : null;

        RefreshRuleResponse response =
            new RefreshRuleResponse(
                ruleId, STATUS_SUCCESS, previousVersion, currentVersion, compilationTime);

        log.info("Successfully refreshed rule: {}", LogSanitizer.sanitizeMessage(ruleId));
        return ResponseEntity.ok(response);
      } else {
        RefreshRuleResponse response = new RefreshRuleResponse(ruleId, STATUS_ERROR);
        response.setError("Failed to compile rule");
        return ResponseEntity.internalServerError().body(response);
      }

    } catch (Exception e) {
      log.error("Error refreshing rule: {}", LogSanitizer.sanitizeMessage(ruleId), e);
      RefreshRuleResponse response = new RefreshRuleResponse(ruleId, STATUS_ERROR);
      response.setError(LogSanitizer.sanitizeMessage(e.getMessage()));
      return ResponseEntity.internalServerError().body(response);
    }
  }

  /** List all loaded rules with their metadata. */
  @GetMapping("/rules")
  public ResponseEntity<RuleListResponse> listRules() {
    log.debug("Listing all loaded rules");

    try {
      Map<String, RuleMetadata> allMetadata = droolsEngineService.getAllRuleMetadata();

      List<RuleListResponse.RuleInfo> ruleInfos =
          allMetadata.entrySet().stream()
              .map(
                  entry -> {
                    String ruleId = entry.getKey();
                    RuleMetadata metadata = entry.getValue();
                    boolean cached = ruleCache.contains(ruleId);

                    return new RuleListResponse.RuleInfo(
                        ruleId,
                        metadata.getStatus().toString(),
                        metadata.getLoadedAt() != null
                            ? metadata.getLoadedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                            : null,
                        metadata.getLastModified() != null
                            ? metadata
                                .getLastModified()
                                .atZone(java.time.ZoneOffset.UTC)
                                .toInstant()
                            : null,
                        metadata.getExecutionCount(),
                        metadata.getAverageExecutionTimeMs(),
                        cached,
                        metadata.getVersion());
                  })
              .collect(Collectors.toList());

      RuleListResponse response = new RuleListResponse(ruleInfos);
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error listing rules", e);
      return ResponseEntity.internalServerError().build();
    }
  }
}
