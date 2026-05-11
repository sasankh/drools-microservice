package com.company.drools.api.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Memory monitoring and diagnostics controller. Provides endpoints to monitor JVM memory usage,
 * trigger garbage collection, and view memory pool statistics.
 *
 * <p>This controller is essential for:
 *
 * <ul>
 *   <li>Investigating memory leaks (exit code 137 - OOM)
 *   <li>Monitoring heap usage in production
 *   <li>Triggering manual GC for diagnostics
 * </ul>
 */
@RestController
@RequestMapping("/admin/memory")
public class MemoryController {

  private static final Logger logger = LoggerFactory.getLogger(MemoryController.class);

  private static final String KEY_USED_MB       = "usedMB";
  private static final String KEY_MAX_MB        = "maxMB";
  private static final String KEY_USAGE_PERCENT = "usagePercent";
  private static final String KEY_COMMITTED_MB  = "committedMB";

  /**
   * Get comprehensive memory information including heap, non-heap, and memory pool statistics.
   *
   * @return Memory statistics including used, free, max memory, and usage percentages
   */
  @SuppressWarnings("java:S2629") // String.format() inside debug log is intentional
  @GetMapping("/info")
  public ResponseEntity<Map<String, Object>> getMemoryInfo() {
    Runtime runtime = Runtime.getRuntime();
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    // Heap memory
    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
    long heapUsed = heapUsage.getUsed();
    long heapMax = heapUsage.getMax();
    long heapCommitted = heapUsage.getCommitted();

    // Non-heap memory (Metaspace, Code Cache, etc.)
    MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

    // Runtime information
    long maxMemory = runtime.maxMemory();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;

    Map<String, Object> memoryInfo = new HashMap<>();

    // Heap statistics (in MB)
    Map<String, Object> heap = new HashMap<>();
    heap.put(KEY_USED_MB,heapUsed / 1024 / 1024);
    heap.put(KEY_COMMITTED_MB,heapCommitted / 1024 / 1024);
    heap.put(KEY_MAX_MB,heapMax / 1024 / 1024);
    heap.put(KEY_USAGE_PERCENT,String.format("%.2f", (heapUsed * 100.0) / heapMax));
    memoryInfo.put("heap", heap);

    // Non-heap statistics
    Map<String, Object> nonHeap = new HashMap<>();
    nonHeap.put(KEY_USED_MB,nonHeapUsage.getUsed() / 1024 / 1024);
    nonHeap.put(KEY_COMMITTED_MB,nonHeapUsage.getCommitted() / 1024 / 1024);
    nonHeap.put(KEY_MAX_MB,nonHeapUsage.getMax() / 1024 / 1024);
    memoryInfo.put("nonHeap", nonHeap);

    // Runtime statistics
    Map<String, Object> runtimeStats = new HashMap<>();
    runtimeStats.put("maxMemoryMB", maxMemory / 1024 / 1024);
    runtimeStats.put("totalMemoryMB", totalMemory / 1024 / 1024);
    runtimeStats.put("usedMemoryMB", usedMemory / 1024 / 1024);
    runtimeStats.put("freeMemoryMB", freeMemory / 1024 / 1024);
    runtimeStats.put(KEY_USAGE_PERCENT,String.format("%.2f", (usedMemory * 100.0) / maxMemory));
    memoryInfo.put("runtime", runtimeStats);

    // Memory pools (Eden, Survivor, Old Gen, Metaspace, etc.)
    List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
    List<Map<String, Object>> pools =
        memoryPools.stream()
            .map(
                pool -> {
                  MemoryUsage usage = pool.getUsage();
                  Map<String, Object> poolInfo = new HashMap<>();
                  poolInfo.put("name", pool.getName());
                  poolInfo.put("type", pool.getType().toString());
                  poolInfo.put(KEY_USED_MB,usage.getUsed() / 1024 / 1024);
                  poolInfo.put(KEY_MAX_MB,usage.getMax() > 0 ? usage.getMax() / 1024 / 1024 : -1);
                  poolInfo.put(
                      KEY_USAGE_PERCENT,
                      usage.getMax() > 0
                          ? String.format("%.2f", (usage.getUsed() * 100.0) / usage.getMax())
                          : "N/A");
                  return poolInfo;
                })
            .toList();
    memoryInfo.put("memoryPools", pools);

    // Garbage collection statistics
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    List<Map<String, Object>> gcStats =
        gcBeans.stream()
            .map(
                gc -> {
                  Map<String, Object> gcInfo = new HashMap<>();
                  gcInfo.put("name", gc.getName());
                  gcInfo.put("collectionCount", gc.getCollectionCount());
                  gcInfo.put("collectionTimeMs", gc.getCollectionTime());
                  return gcInfo;
                })
            .toList();
    memoryInfo.put("garbageCollectors", gcStats);

    // Warning flags
    double heapUsagePercent = (heapUsed * 100.0) / heapMax;
    memoryInfo.put("warnings", getMemoryWarnings(heapUsagePercent, heapUsed, heapMax));

    logger.debug(
        "Memory info requested - Heap usage: {}/{} MB ({}%)",
        heapUsed / 1024 / 1024, heapMax / 1024 / 1024, String.format("%.2f", heapUsagePercent));

    return ResponseEntity.ok(memoryInfo);
  }

  /**
   * Trigger garbage collection manually. Useful for diagnostics but should NOT be used in
   * production under load.
   *
   * @return Message indicating GC was triggered
   */
  @SuppressFBWarnings(
      value = "DM_GC",
      justification = "Admin diagnostic endpoint; explicit GC trigger is the entire purpose")
  @PostMapping("/gc")
  public ResponseEntity<Map<String, Object>> triggerGC() {
    logger.warn("Manual garbage collection triggered via API");

    // Get memory before GC
    Runtime runtime = Runtime.getRuntime();
    long usedBefore = runtime.totalMemory() - runtime.freeMemory();

    // Suggest GC (JVM may or may not comply)
    System.gc();

    // Wait a bit for GC to complete
    try {
      Thread.sleep(100);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }

    // Get memory after GC
    long usedAfter = runtime.totalMemory() - runtime.freeMemory();
    long freedMemory = usedBefore - usedAfter;

    Map<String, Object> result = new HashMap<>();
    result.put("message", "Garbage collection triggered");
    result.put("usedBeforeMB", usedBefore / 1024 / 1024);
    result.put("usedAfterMB", usedAfter / 1024 / 1024);
    result.put("freedMemoryMB", freedMemory / 1024 / 1024);
    result.put("note", "This is a suggestion to JVM, actual GC timing is not guaranteed");

    logger.info("GC triggered - Freed approximately {} MB", Math.max(0, freedMemory / 1024 / 1024));

    return ResponseEntity.ok(result);
  }

  /**
   * Get memory usage trend (requires multiple samples over time). This would be better implemented
   * with metrics export to Prometheus/Grafana.
   *
   * @return Current snapshot for trending
   */
  @GetMapping("/snapshot")
  public ResponseEntity<Map<String, Object>> getMemorySnapshot() {
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();

    Map<String, Object> snapshot = new HashMap<>();
    snapshot.put("timestamp", System.currentTimeMillis());
    snapshot.put("heapUsedMB", heapUsage.getUsed() / 1024 / 1024);
    snapshot.put("heapMaxMB", heapUsage.getMax() / 1024 / 1024);
    snapshot.put("heapUsagePercent", (heapUsage.getUsed() * 100.0) / heapUsage.getMax());

    return ResponseEntity.ok(snapshot);
  }

  private List<String> getMemoryWarnings(double heapUsagePercent, long heapUsed, long heapMax) {
    List<String> warnings = new java.util.ArrayList<>();

    if (heapUsagePercent > 90) {
      warnings.add("CRITICAL: Heap usage above 90% - OOM risk is HIGH!");
    } else if (heapUsagePercent > 80) {
      warnings.add("WARNING: Heap usage above 80% - consider increasing heap size");
    } else if (heapUsagePercent > 70) {
      warnings.add("CAUTION: Heap usage above 70% - monitor closely");
    }

    // Check if max heap is too small for production
    long heapMaxMB = heapMax / 1024 / 1024;
    if (heapMaxMB < 1024) {
      warnings.add("INFO: Heap max is less than 1GB - may be too small for production load");
    }

    return warnings;
  }
}
