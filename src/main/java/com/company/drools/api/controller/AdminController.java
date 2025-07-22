package com.company.drools.api.controller;

import com.company.drools.api.dto.RefreshRuleResponse;
import com.company.drools.api.dto.RefreshRulesResponse;
import com.company.drools.api.dto.RuleListResponse;
import com.company.drools.cache.RuleCache;
import com.company.drools.core.engine.DroolsEngineService;
import com.company.drools.core.model.Rule;
import com.company.drools.core.model.RuleMetadata;
import com.company.drools.storage.RuleStorage;
import com.company.drools.storage.StorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin controller for rule management operations.
 * Available on management port (8081) as configured in application.yml.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  private final DroolsEngineService droolsEngineService;
  private final StorageFactory storageFactory;
  private final RuleCache ruleCache;

  public AdminController(DroolsEngineService droolsEngineService, 
                        StorageFactory storageFactory,
                        RuleCache ruleCache) {
    this.droolsEngineService = droolsEngineService;
    this.storageFactory = storageFactory;
    this.ruleCache = ruleCache;
  }

  /**
   * Health check endpoint for admin interface.
   */
  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> health() {
    Map<String, Object> health = new HashMap<>();
    health.put("status", "UP");
    health.put("timestamp", Instant.now());
    
    // Drools engine status
    Map<String, Object> droolsStatus = new HashMap<>();
    droolsStatus.put("loaded_rules", droolsEngineService.getLoadedRulesCount());
    droolsStatus.put("active_rules", droolsEngineService.getActiveRulesCount());
    health.put("drools", droolsStatus);
    
    // Cache status
    Map<String, Object> cacheStatus = new HashMap<>();
    cacheStatus.put("enabled", ruleCache.isEnabled());
    cacheStatus.put("size", ruleCache.size());
    cacheStatus.put("max_size", ruleCache.maxSize());
    if (ruleCache.isEnabled()) {
      cacheStatus.put("statistics", ruleCache.getStatistics());
    }
    health.put("cache", cacheStatus);
    
    // Storage status
    Map<String, Object> storageStatus = new HashMap<>();
    try {
      RuleStorage storage = storageFactory.createRuleStorage();
      storageStatus.put("type", storage.getClass().getSimpleName());
      storageStatus.put("total_rules", storage.getTotalRuleCount());
    } catch (Exception e) {
      storageStatus.put("error", e.getMessage());
    }
    health.put("storage", storageStatus);
    
    return ResponseEntity.ok(health);
  }

  /**
   * Get basic system info.
   */
  @GetMapping("/info")
  public ResponseEntity<Map<String, Object>> info() {
    Map<String, Object> info = new HashMap<>();
    info.put("application", "Drools Rule Engine Microservice");
    info.put("version", "1.0.0");
    info.put("java_version", System.getProperty("java.version"));
    info.put("timestamp", Instant.now());
    
    return ResponseEntity.ok(info);
  }

  /**
   * Refresh all rules from storage.
   */
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
    
    RefreshRulesResponse response = new RefreshRulesResponse(status, rulesLoaded, rulesFailed, duration, errors);
    return ResponseEntity.ok(response);
  }

  /**
   * Refresh a specific rule by ID.
   */
  @PostMapping("/refresh-rules/{ruleId}")
  public ResponseEntity<RefreshRuleResponse> refreshRule(@PathVariable String ruleId) {
    log.info("Refreshing rule: {}", ruleId);
    long startTime = System.currentTimeMillis();
    
    try {
      // Get current rule metadata
      RuleMetadata currentMetadata = droolsEngineService.getRuleMetadata(ruleId);
      Instant previousVersion = currentMetadata != null && currentMetadata.getLastModified() != null 
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
      
      // Reload rule into engine
      boolean success = droolsEngineService.loadRules(List.of(rule));
      
      if (success) {
        // Add back to cache
        ruleCache.put(rule);
        
        long compilationTime = System.currentTimeMillis() - startTime;
        Instant currentVersion = rule.getMetadata().getLastModified() != null
            ? rule.getMetadata().getLastModified().atZone(java.time.ZoneOffset.UTC).toInstant()
            : null;
        
        RefreshRuleResponse response = new RefreshRuleResponse(
            ruleId, "success", previousVersion, currentVersion, compilationTime);
        
        log.info("Successfully refreshed rule: {}", ruleId);
        return ResponseEntity.ok(response);
      } else {
        RefreshRuleResponse response = new RefreshRuleResponse(ruleId, "error");
        response.setError("Failed to compile rule");
        return ResponseEntity.internalServerError().body(response);
      }
      
    } catch (Exception e) {
      log.error("Error refreshing rule: {}", ruleId, e);
      RefreshRuleResponse response = new RefreshRuleResponse(ruleId, "error");
      response.setError(e.getMessage());
      return ResponseEntity.internalServerError().body(response);
    }
  }

  /**
   * List all loaded rules with their metadata.
   */
  @GetMapping("/rules")
  public ResponseEntity<RuleListResponse> listRules() {
    log.debug("Listing all loaded rules");
    
    try {
      Map<String, RuleMetadata> allMetadata = droolsEngineService.getAllRuleMetadata();
      
      List<RuleListResponse.RuleInfo> ruleInfos = allMetadata.entrySet().stream()
          .map(entry -> {
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
                    ? metadata.getLastModified().atZone(java.time.ZoneOffset.UTC).toInstant()
                    : null,
                metadata.getExecutionCount(),
                metadata.getAverageExecutionTimeMs(),
                cached,
                metadata.getVersion()
            );
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