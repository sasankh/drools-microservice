# Memory Monitoring Guide

**Version**: 1.2.1
**Last Updated**: 2026-08-20
**Status**: Production Ready

---

## Table of Contents

1. [Overview](#overview)
2. [Memory Monitoring Endpoints](#memory-monitoring-endpoints)
3. [Understanding Memory Metrics](#understanding-memory-metrics)
4. [Monitoring Strategies](#monitoring-strategies)
5. [Warning Levels](#warning-levels)
6. [Troubleshooting Memory Issues](#troubleshooting-memory-issues)
7. [Best Practices](#best-practices)
8. [Integration with Monitoring Tools](#integration-with-monitoring-tools)

---

## Overview

### Why Memory Monitoring Matters

The Drools Rule Engine Microservice includes comprehensive memory monitoring capabilities to:

- **Prevent OOM Errors**: Detect memory growth before OutOfMemoryError occurs
- **Verify Memory Stability**: Ensure memory remains stable after rule refreshes
- **Diagnose Memory Leaks**: Identify and resolve memory leak issues
- **Optimize Performance**: Fine-tune JVM settings based on actual usage
- **Production Readiness**: Monitor memory health in real-time

### Critical Fix history

**2026-02-19** — initial fix: original `KieContainer` instances were not being disposed during the two-container atomic-swap, leading to exit-code-137 OOM after ~6h. Added explicit `KieContainer.dispose()` on the swapped-out container.

**2026-05-10** — Drools 10 re-engineering: the service now holds a **single long-lived `KieContainer`** updated in place via `KieContainer.updateToVersion(ReleaseId)`. Old `KieModule`s are explicitly removed from the `KieRepository` after each swap (Drools 10 does **not** auto-clean — verified by 1000-rule load test). The 2026-02-19 `dispose()` pattern was superseded. See [ADR-003 2026-05-10 update](36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal) and [`DroolsEngineService.loadOrReplaceRule`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java).

**Net result**:
- No `KieModule` / `ProjectClassLoader` leak across refreshes
- Memory does not grow by 10-100MB per rule refresh
- Application can run indefinitely without OOM errors

---

## Memory Monitoring Endpoints

> **Admin auth required.** All `/admin/memory/*` endpoints live under `/admin/*`, so when `ADMIN_API_KEY` is set (always in `prod`/`docker` — the app won't start there without it) every request must carry the `X-Admin-API-Key` header, or it returns **HTTP 401**. The examples below pass `-H "X-Admin-API-Key: admin-secret"`; substitute your real key. Export it once — `export ADMIN_API_KEY=admin-secret` — and the `watch`/loop snippets further down that omit the header for brevity still need it in any environment where the key is configured. See [15-admin-authentication.md](15-admin-authentication.md).

### 1. GET /admin/memory/info

**Purpose**: Get comprehensive memory information including heap, non-heap, memory pools, and garbage collection statistics.

**Usage**:
```bash
curl -H "X-Admin-API-Key: admin-secret" http://localhost:8080/admin/memory/info | jq
```

**Response Structure**:
```json
{
  "heap": {
    "usedMB": 245,
    "committedMB": 512,
    "maxMB": 2048,
    "usagePercent": "11.96"
  },
  "nonHeap": {
    "usedMB": 85,
    "committedMB": 90,
    "maxMB": 512
  },
  "runtime": {
    "maxMemoryMB": 2048,
    "totalMemoryMB": 512,
    "usedMemoryMB": 245,
    "freeMemoryMB": 267,
    "usagePercent": "11.96"
  },
  "memoryPools": [
    {
      "name": "G1 Old Gen",
      "type": "HEAP",
      "usedMB": 156,
      "maxMB": 2048,
      "usagePercent": "7.62"
    },
    {
      "name": "G1 Eden Space",
      "type": "HEAP",
      "usedMB": 45,
      "maxMB": -1,
      "usagePercent": "N/A"
    },
    {
      "name": "Metaspace",
      "type": "NON_HEAP",
      "usedMB": 65,
      "maxMB": 512,
      "usagePercent": "12.70"
    }
  ],
  "garbageCollectors": [
    {
      "name": "G1 Young Generation",
      "collectionCount": 12,
      "collectionTimeMs": 45
    },
    {
      "name": "G1 Old Generation",
      "collectionCount": 2,
      "collectionTimeMs": 15
    }
  ],
  "warnings": []
}
```

**When to Use**:
- Initial health check after deployment
- Periodic monitoring (every 5-15 minutes)
- After rule refreshes to verify stability
- When investigating performance issues
- Before and after load tests

---

### 2. POST /admin/memory/gc

**Purpose**: Manually trigger garbage collection for diagnostic purposes.

**⚠️ WARNING**: This is a suggestion to the JVM, not a guarantee. Should NOT be used in production under load.

**Usage**:
```bash
curl -X POST -H "X-Admin-API-Key: admin-secret" http://localhost:8080/admin/memory/gc | jq
```

**Response**:
```json
{
  "message": "Garbage collection triggered",
  "usedBeforeMB": 450,
  "usedAfterMB": 250,
  "freedMemoryMB": 200,
  "note": "This is a suggestion to JVM, actual GC timing is not guaranteed"
}
```

**When to Use**:
- Diagnostic troubleshooting only
- Memory leak investigation
- Before taking heap dumps
- Testing GC behavior

**When NOT to Use**:
- Production under load (can cause pauses)
- Automated scripts (unpredictable timing)
- Performance testing (invalidates results)

---

### 3. GET /admin/memory/snapshot

**Purpose**: Get a lightweight memory snapshot for trending and frequent polling.

**Usage**:
```bash
curl -H "X-Admin-API-Key: admin-secret" http://localhost:8080/admin/memory/snapshot | jq
```

**Response**:
```json
{
  "timestamp": 1708390000000,
  "heapUsedMB": 245,
  "heapMaxMB": 2048,
  "heapUsagePercent": 11.96
}
```

**When to Use**:
- High-frequency polling (every 1-5 seconds)
- Building trending graphs
- Lightweight health checks
- Minimizing monitoring overhead

---

## Understanding Memory Metrics

### Heap Memory

**What it is**: Memory used for Java objects (rules, data, KieContainers, etc.)

**Key Metrics**:
- `usedMB`: Currently allocated to objects
- `committedMB`: Guaranteed available by OS
- `maxMB`: Maximum heap size (-Xmx setting)
- `usagePercent`: used / max * 100

**Healthy Range**: 50-80% under normal load

**Warning Signs**:
- Consistently > 80%: Need more heap
- Consistently < 20%: Heap oversized
- Rapid growth: Potential memory leak
- Sawtooth pattern: Normal (GC working)

---

### Memory Pools

#### G1 Old Gen (Tenured)
- **What**: Long-lived objects (cached rules, compiled KieBases)
- **Healthy**: Grows slowly, stable after warmup
- **Warning**: Continuous growth = memory leak

#### G1 Eden Space (Young)
- **What**: Recently allocated objects
- **Healthy**: Frequent collection, high turnover
- **Warning**: Not being collected = too small

#### Metaspace (Non-Heap)
- **What**: Class metadata, compiled methods
- **Healthy**: Stable after application warmup
- **Warning**: Continuous growth = classloader leak

---

### Garbage Collection Metrics

**Young Generation GC**:
- **Frequency**: Every few seconds to minutes
- **Duration**: 10-50ms typical
- **Impact**: Minor pauses, normal

**Old Generation GC**:
- **Frequency**: Every few minutes to hours
- **Duration**: 50-200ms typical (G1GC)
- **Impact**: Longer pauses, monitor closely

**Warning Signs**:
- GC time > 5% of total time
- Pause times > 500ms
- Old Gen GC every few seconds
- GC not freeing memory

---

## Monitoring Strategies

### Strategy 1: Real-Time Monitoring

**Purpose**: Watch memory during active development/testing

```bash
# Monitor heap usage every 5 seconds
watch -n 5 'curl -s http://localhost:8080/admin/memory/info | jq ".heap"'

# Alternative: Just show usage percentage
watch -n 5 'curl -s http://localhost:8080/admin/memory/info | jq ".heap.usagePercent"'
```

**When to Use**:
- During development
- Testing rule refreshes
- Load testing
- Investigating issues

---

### Strategy 2: Memory Stability Test

**Purpose**: Verify memory remains stable after rule refreshes

```bash
#!/bin/bash
echo "Testing memory stability with 10 rule refreshes..."

for i in {1..10}; do
    echo "Refresh $i/10"

    # Get memory before
    before=$(curl -s http://localhost:8080/admin/memory/info | jq -r '.heap.usedMB')
    echo "  Memory before: ${before} MB"

    # Refresh rules
    curl -X POST http://localhost:8080/admin/refresh-rules -s > /dev/null

    # Wait for GC
    sleep 3

    # Get memory after
    after=$(curl -s http://localhost:8080/admin/memory/info | jq -r '.heap.usedMB')
    echo "  Memory after:  ${after} MB"

    # Calculate change
    change=$((after - before))
    echo "  Change: ${change} MB"

    if [ $change -gt 50 ]; then
        echo "  ⚠️  WARNING: Memory grew by ${change} MB!"
    else
        echo "  ✅ Memory stable"
    fi

    echo ""
done

echo "Test complete!"
```

**Expected Result**: Memory should remain stable (±10-20MB variance)

**Failure Indicator**: Memory grows by >50MB per refresh

---

### Strategy 3: Continuous Logging

**Purpose**: Log memory metrics to file for analysis

```bash
#!/bin/bash
LOG_FILE="memory-log-$(date +%Y%m%d-%H%M%S).csv"

echo "timestamp,heapUsedMB,heapMaxMB,heapPercent,warnings" > $LOG_FILE

while true; do
    timestamp=$(date +%s)
    memory=$(curl -s http://localhost:8080/admin/memory/snapshot)

    heapUsed=$(echo $memory | jq -r '.heapUsedMB')
    heapMax=$(echo $memory | jq -r '.heapMaxMB')
    heapPercent=$(echo $memory | jq -r '.heapUsagePercent')

    warnings=$(curl -s http://localhost:8080/admin/memory/info | jq -r '.warnings | join("; ")')

    echo "$timestamp,$heapUsed,$heapMax,$heapPercent,$warnings" >> $LOG_FILE

    sleep 60  # Log every minute
done
```

**Analysis**:
```bash
# Plot with gnuplot
gnuplot <<EOF
set datafile separator ","
set xlabel "Time"
set ylabel "Heap Usage (MB)"
plot 'memory-log.csv' using 1:2 with lines title "Used", '' using 1:3 with lines title "Max"
EOF
```

---

### Strategy 4: Alerting

**Purpose**: Get notified when memory issues occur

```bash
#!/bin/bash
SLACK_WEBHOOK="https://hooks.slack.com/services/YOUR/WEBHOOK/URL"

while true; do
    info=$(curl -s http://localhost:8080/admin/memory/info)

    percent=$(echo $info | jq -r '.heap.usagePercent' | cut -d. -f1)
    warnings=$(echo $info | jq -r '.warnings[]')

    if [ $percent -gt 90 ]; then
        message="🚨 CRITICAL: Memory at ${percent}% - OOM risk HIGH!"
        curl -X POST $SLACK_WEBHOOK -H 'Content-Type: application/json' \
            -d "{\"text\": \"$message\"}"
    elif [ $percent -gt 80 ]; then
        message="⚠️  WARNING: Memory at ${percent}%"
        curl -X POST $SLACK_WEBHOOK -H 'Content-Type: application/json' \
            -d "{\"text\": \"$message\"}"
    fi

    sleep 300  # Check every 5 minutes
done
```

---

## Warning Levels

### CRITICAL (Heap > 90%)

**Status**: 🔴 Immediate action required
**Risk**: OOM imminent (minutes to hours)

**Actions**:
1. Check for memory leak: `curl -s http://localhost:8080/admin/memory/info | jq '.warnings'`
2. Trigger manual GC: `curl -X POST http://localhost:8080/admin/memory/gc`
3. Review recent changes (new rules, code deployments)
4. If no improvement, restart application
5. Increase heap size if pattern repeats

**Investigation**:
```bash
# Check if memory is freeable
curl -X POST http://localhost:8080/admin/memory/gc

# Wait 10 seconds
sleep 10

# Check again
curl -s http://localhost:8080/admin/memory/info | jq '.heap'

# If usage still > 90%, it's not GC'able → leak or undersized heap
```

---

### WARNING (Heap > 80%)

**Status**: 🟡 Monitor closely
**Risk**: May lead to OOM under load

**Actions**:
1. Increase monitoring frequency
2. Review memory trends (growing or stable?)
3. Plan for heap increase if sustained
4. Investigate if unexpected

---

### CAUTION (Heap > 70%)

**Status**: 🟢 Normal under load, watch for trends
**Risk**: Low, but monitor

**Actions**:
1. Normal during load testing
2. Should drop after load decreases
3. If sustained, consider optimization

---

## Troubleshooting Memory Issues

### Issue 1: Memory Grows After Rule Refreshes

**Symptoms**:
- Heap usage increases by 10-100MB per refresh
- Eventually leads to OOM (exit code 137)

**Root Cause history**:
- Fixed in 2026-02-19 by adding `KieContainer.dispose()` to a two-container atomic-swap.
- Re-engineered on 2026-05-10 (Drools 10 migration): the service now holds a **single long-lived `KieContainer`** that is updated in place via `KieContainer.updateToVersion(ReleaseId)`. Old `KieModule`s are explicitly removed from the `KieRepository` after the swap (Drools 10 does **not** auto-clean them — verified by load test 2026-05-10).

**Solution**: Ensure using latest code with the post-2026-05-10 pattern:
```java
// DroolsEngineService.loadRules — current pattern
ReleaseId newReleaseId = ruleCompiler.compile(allRules);  // outside write lock
ReleaseId oldReleaseId = currentReleaseId;

writeLock.lock();
try {
    kieContainer.updateToVersion(newReleaseId);   // in-place version swap
    currentReleaseId = newReleaseId;
} finally {
    writeLock.unlock();
}

if (oldReleaseId != null && !oldReleaseId.equals(newReleaseId)) {
    kieRepository.removeKieModule(oldReleaseId);  // critical — prevents KieModule pile-up
}
```
See [04-architecture.md](04-architecture.md), [ADR-003 2026-05-10 update](36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal), and [39-load-test-findings.md](39-load-test-findings.md).

**Verification**:
```bash
# Test memory stability
for i in {1..10}; do
    curl -X POST http://localhost:8080/admin/refresh-rules
    sleep 3
    curl -s http://localhost:8080/admin/memory/info | jq '.heap.usedMB'
done
# Memory should remain stable
```

---

### Issue 2: Heap Usage Constantly High

**Symptoms**:
- Heap usage > 80% even without load
- Frequent GC cycles

**Possible Causes**:
1. Heap size too small for workload
2. Too many rules loaded
3. Large rule payloads
4. Cache size too large

**Investigation**:
```bash
# Check loaded rules
curl -s http://localhost:8080/admin/rules | jq '.total_rules'

# Check cache statistics (Redis decorator only — LocalLRU was removed 2026-05-20)
curl -s http://localhost:8080/admin/health | jq '.components.cache.details.statistics'

# Check memory pools
curl -s http://localhost:8080/admin/memory/info | jq '.memoryPools'
```

**Solution**:
```bash
# Increase heap size in docker-compose.yml
JAVA_OPTS=-Xms512m -Xmx4096m  # Increase from 2048 to 4096

# Memory pressure typically comes from compiled rules (kieContainer) — not from
# the Redis decorator (DRL JSON lives in Redis, not the JVM heap). Reduce
# pressure by sharding rules across more instances or trimming rule complexity.
```

---

### Issue 3: Metaspace Growing

**Symptoms**:
- Metaspace usage increasing over time
- "OutOfMemoryError: Metaspace" in logs

**Root Cause**: Classloader leak (rare with Drools)

**Investigation**:
```bash
# Check Metaspace specifically
curl -s http://localhost:8080/admin/memory/info | \
    jq '.memoryPools[] | select(.name == "Metaspace")'
```

**Solution**:
```bash
# Increase Metaspace in docker-compose.yml
JAVA_OPTS=-XX:MaxMetaspaceSize=512m
```

---

### Issue 4: Exit Code 137 (OOM Kill)

**Symptoms**:
- Application terminates unexpectedly
- Exit code 137 in logs
- Docker restarts container

**Root Cause**: Out of memory, killed by OS

**Investigation**:
```bash
# Check heap dumps (if configured)
ls -lh heap-dumps/

# Analyze heap dump with jhat or VisualVM
jhat heap-dumps/heapdump.hprof

# Check GC logs
tail -100 gc-logs/gc.log
```

**Solution**:
1. Ensure latest code (Drools 10 `updateToVersion` + `KieRepository.removeKieModule` pattern, post-2026-05-10 — the older `dispose()` approach is superseded; see [ADR-003 2026-05-10 update](36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal))
2. Increase heap size
3. Reduce memory usage (fewer rules)

---

## Best Practices

### 1. Set Explicit Heap Limits

**Recommended**:
```yaml
# docker-compose.yml
JAVA_OPTS=-Xms512m -Xmx2048m
```

**Guidelines**:
- Minimum (Xms): 25% of max
- Maximum (Xmx): 50-75% of container memory
- Leave room for non-heap (Metaspace, native memory)

---

### 2. Enable Heap Dumps on OOM

**Configuration**:
```yaml
JAVA_OPTS=-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap-dumps/
```

**Benefits**:
- Automatic dump when OOM occurs
- Can analyze to find root cause
- No performance impact (only on OOM)

---

### 3. Enable GC Logging

**Configuration**:
```yaml
JAVA_OPTS=-Xlog:gc*:file=/tmp/gc-logs/gc.log:time,uptime,level,tags
```

**Analysis**:
```bash
# Check GC activity
tail -f gc-logs/gc.log

# Look for patterns:
# - Frequent full GCs
# - Long pause times
# - Memory not being freed
```

---

### 4. Monitor Regularly

**Frequency**:
- Development: Every 5 seconds (real-time)
- Staging: Every 1 minute
- Production: Every 5 minutes

**Tools**:
- Prometheus + Grafana (recommended)
- CloudWatch metrics
- DataDog
- New Relic

---

### 5. Set Up Alerts

**Recommended Thresholds**:
```yaml
# Heap usage
- CRITICAL: > 90%
- WARNING: > 80%
- CAUTION: > 70%

# GC time
- WARNING: > 5% of total time

# Pause times
- WARNING: > 500ms
```

---

## Integration with Monitoring Tools

### Prometheus Integration

**Endpoint**: `GET /actuator/prometheus`

**Sample Queries**:
```promql
# Heap usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# Alert rule
- alert: HighMemoryUsage
  expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9
  for: 5m
  annotations:
    summary: "High memory usage on {{ $labels.instance }}"
```

---

### Grafana Dashboard

**Panel 1: Heap Usage**
- Metric: Heap used vs max
- Visualization: Time series
- Threshold: 80% warning, 90% critical

**Panel 2: Memory Pools**
- Metric: G1 Old Gen, Eden, Metaspace
- Visualization: Stacked area chart

**Panel 3: GC Activity**
- Metric: GC count and time
- Visualization: Bar chart

---

### CloudWatch

**Custom Metrics**:
```bash
# Send to CloudWatch
aws cloudwatch put-metric-data \
    --namespace "DroolsEngine" \
    --metric-name "HeapUsagePercent" \
    --value $(curl -s http://localhost:8080/admin/memory/snapshot | jq '.heapUsagePercent')
```

---

## Summary

### Quick Reference

**Commands**:
```bash
# Check memory
curl http://localhost:8080/admin/memory/info | jq

# Monitor real-time
watch -n 5 'curl -s http://localhost:8080/admin/memory/snapshot | jq'

# Test stability
for i in {1..10}; do
    curl -X POST http://localhost:8080/admin/refresh-rules
    sleep 3
    curl -s http://localhost:8080/admin/memory/info | jq '.heap.usedMB'
done
```

**Thresholds**:
- CRITICAL: > 90% heap
- WARNING: > 80% heap
- CAUTION: > 70% heap

**Files**:
- Heap dumps: `./heap-dumps/`
- GC logs: `./gc-logs/`

---

**Last Updated**: 2026-05-24
**Status**: Production Ready
**Related Docs**: troubleshooting.md, configuration.md, deployment.md
