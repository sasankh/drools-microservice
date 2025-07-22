# 🚀 JVM Optimization Guide for Drools Rule Engine

## Overview
This guide provides JVM optimization settings for high-throughput rule processing (100-1000 RPS).

## Recommended JVM Settings

### Production Environment (8GB+ heap)
```bash
# Heap Settings
-Xms4g -Xmx8g

# Garbage Collection (G1GC for low latency)
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-XX:G1HeapRegionSize=16m
-XX:G1ReservePercent=25
-XX:InitiatingHeapOccupancyPercent=30

# GC Tuning for throughput
-XX:G1MixedGCCountTarget=8
-XX:G1OldCSetRegionThreshold=5
-XX:G1MixedGCLiveThresholdPercent=85

# JIT Compilation
-XX:TieredStopAtLevel=4
-XX:+UseStringDeduplication
-XX:+OptimizeStringConcat

# Thread and Stack Settings
-XX:ThreadStackSize=1024
-Djava.util.concurrent.ForkJoinPool.common.parallelism=16

# Memory Management
-XX:+UseCompressedOops
-XX:+UseCompressedClassPointers
-XX:CompressedClassSpaceSize=1g

# Monitoring and Debugging (disable in production if not needed)
-XX:+UnlockExperimentalVMOptions
-XX:+UseCGroupMemoryLimitForHeap
-XX:+ExitOnOutOfMemoryError
```

### Development Environment (2GB+ heap)
```bash
# Heap Settings
-Xms1g -Xmx2g

# Garbage Collection
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200

# Development convenience
-XX:+PrintGCDetails
-XX:+PrintGCTimeStamps
-Xloggc:gc.log
```

### Docker Container Settings
```bash
# Container-aware settings
-XX:+UseContainerSupport
-XX:InitialRAMPercentage=50.0
-XX:MaxRAMPercentage=75.0
-XX:MinRAMPercentage=50.0
```

## Environment-Specific Configurations

### Local Development
```bash
export JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### Staging
```bash
export JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=150 -XX:InitiatingHeapOccupancyPercent=30"
```

### Production
```bash
export JAVA_OPTS="-Xms4g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:G1HeapRegionSize=16m -XX:InitiatingHeapOccupancyPercent=30 -XX:+UseStringDeduplication -Djava.util.concurrent.ForkJoinPool.common.parallelism=16"
```

## Application-Specific Optimizations

### Drools-Specific Settings
```bash
# Optimize for rule compilation and execution
-Ddrools.dateformat="yyyy-MM-dd"
-Ddrools.timezone="UTC"

# Disable unnecessary Drools features for better performance
-Ddrools.compiler.disable.DRL=false
-Ddrools.multithreadEvaluation=true
```

### Spring Boot Optimizations
```bash
# Disable JMX if not needed
-Dspring.jmx.enabled=false

# Optimize class loading
-Dspring.aot.enabled=true
-XX:+TieredCompilation
-XX:TieredStopAtLevel=1  # For faster startup, use 4 for better steady-state performance
```

## Monitoring and Profiling

### GC Monitoring
```bash
# Enable detailed GC logging
-Xlog:gc*:gc.log:time,tags,level

# For older Java versions (8-10)
-XX:+PrintGC
-XX:+PrintGCDetails
-XX:+PrintGCTimeStamps
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=5
-XX:GCLogFileSize=100M
```

### JVM Metrics
```bash
# Enable JFR (Java Flight Recorder)
-XX:+FlightRecorder
-XX:StartFlightRecording=duration=60s,filename=app-profile.jfr

# Enable advanced JVM metrics
-XX:+UnlockDiagnosticVMOptions
-XX:+LogVMOutput
```

## Performance Testing Commands

### Memory Usage Testing
```bash
# Test with limited memory
java -Xmx1g -XX:+PrintGCDetails -jar drools-rule-engine.jar

# Monitor memory usage
jstat -gc -t <pid> 5s
```

### Throughput Testing
```bash
# Test with optimized settings
java -Xms4g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -jar drools-rule-engine.jar

# Monitor performance
jstat -gccapacity <pid>
jstat -gcutil <pid> 5s
```

## Container Deployment

### Docker Run Example
```bash
docker run -d \
  --name drools-engine \
  --memory=8g \
  --cpus=4 \
  -e JAVA_OPTS="-Xms4g -Xmx6g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
  -p 8080:8080 \
  drools-rule-engine:latest
```

### Kubernetes Deployment
```yaml
resources:
  requests:
    memory: "4Gi"
    cpu: "2"
  limits:
    memory: "8Gi"
    cpu: "4"
env:
- name: JAVA_OPTS
  value: "-Xms4g -Xmx6g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

## Performance Targets with Optimizations

### Expected Performance Improvements
- **Startup Time**: 30-50% faster with optimized JIT settings
- **GC Pause Time**: <100ms P99 with G1GC tuning
- **Throughput**: 2-3x improvement with proper heap sizing
- **Memory Usage**: 20-30% reduction with compressed OOPs and string deduplication

### Benchmarking Results (Expected)
- **100 RPS**: <50ms P99 latency with 2GB heap
- **500 RPS**: <100ms P99 latency with 4GB heap
- **1000 RPS**: <200ms P99 latency with 8GB heap

## Troubleshooting

### Common Issues
1. **OutOfMemoryError**: Increase heap size or optimize G1 settings
2. **High GC pause times**: Reduce MaxGCPauseMillis or tune G1 parameters
3. **Poor startup performance**: Use -XX:TieredStopAtLevel=1 for dev, 4 for prod
4. **Thread contention**: Adjust ForkJoinPool parallelism

### Monitoring Commands
```bash
# Monitor JVM metrics
jcmd <pid> VM.info
jcmd <pid> GC.run_finalization
jcmd <pid> Thread.print

# Memory analysis
jmap -histo <pid>
jstack <pid>
```

## Integration with Application

These settings can be applied through:
1. **Environment Variables**: `JAVA_OPTS`
2. **JVM Arguments**: Command line `-X` and `-XX:` flags
3. **Spring Boot**: `spring.application.admin.enabled=false`
4. **Docker**: Environment variables in Dockerfile or docker-compose
5. **Kubernetes**: Container environment variables

## Validation

Test the optimizations with:
```bash
# Load test
ab -n 10000 -c 100 http://localhost:8080/execute-rule

# Memory pressure test
stress-ng --vm 1 --vm-bytes 75% --timeout 60s

# GC analysis
java -jar gcviewer.jar gc.log
```