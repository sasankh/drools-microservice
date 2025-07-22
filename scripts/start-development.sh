#!/bin/bash
# Development startup script with optimized JVM settings
# Usage: ./scripts/start-development.sh

# Development JVM Optimizations (faster startup, smaller footprint)
export JAVA_OPTS="-Xms1g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:TieredStopAtLevel=1 \
  -XX:ThreadStackSize=1024 \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=4 \
  -Ddrools.dateformat=yyyy-MM-dd \
  -Ddrools.timezone=UTC"

# Development Profile
export SPRING_PROFILES_ACTIVE=local

# Application settings
export SERVER_PORT=8080
export ADMIN_PORT=8081

# Debug settings (optional)
# export JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"

echo "Starting Drools Rule Engine with development JVM optimizations..."
echo "JVM Settings: $JAVA_OPTS"
echo "Profile: $SPRING_PROFILES_ACTIVE"
echo "Server Port: $SERVER_PORT"
echo "Admin Port: $ADMIN_PORT"

# Start with Spring Boot Maven plugin (development)
if [ -f "target/drools-rule-engine-1.0.0.jar" ]; then
  echo "Starting from JAR file..."
  java $JAVA_OPTS -jar target/drools-rule-engine-1.0.0.jar
else
  echo "Starting with Maven (will compile first)..."
  mvn spring-boot:run -Dspring-boot.run.jvmArguments="$JAVA_OPTS"
fi