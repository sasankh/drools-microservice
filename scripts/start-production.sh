#!/bin/bash
# Production startup script with optimized JVM settings
# Usage: ./scripts/start-production.sh

# Production JVM Optimizations for Drools Rule Engine
export JAVA_OPTS="-Xms4g -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:G1HeapRegionSize=16m \
  -XX:G1ReservePercent=25 \
  -XX:InitiatingHeapOccupancyPercent=30 \
  -XX:G1MixedGCCountTarget=8 \
  -XX:G1OldCSetRegionThreshold=5 \
  -XX:G1MixedGCLiveThresholdPercent=85 \
  -XX:TieredStopAtLevel=4 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -XX:ThreadStackSize=1024 \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=16 \
  -XX:+UseCompressedOops \
  -XX:+UseCompressedClassPointers \
  -XX:CompressedClassSpaceSize=1g \
  -XX:+ExitOnOutOfMemoryError \
  -Ddrools.dateformat=yyyy-MM-dd \
  -Ddrools.timezone=UTC \
  -Ddrools.multithreadEvaluation=true"

# Spring Boot Profile
export SPRING_PROFILES_ACTIVE=prod

# Application settings
export SERVER_PORT=8080
export ADMIN_PORT=8081

# Performance monitoring (optional)
export JAVA_OPTS="$JAVA_OPTS -Xlog:gc*:logs/gc.log:time,tags,level"

echo "Starting Drools Rule Engine with production JVM optimizations..."
echo "JVM Settings: $JAVA_OPTS"
echo "Profile: $SPRING_PROFILES_ACTIVE"
echo "Server Port: $SERVER_PORT"
echo "Admin Port: $ADMIN_PORT"

# Start the application
java $JAVA_OPTS -jar target/drools-rule-engine-1.0.0.jar