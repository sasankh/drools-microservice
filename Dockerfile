# Multi-stage Docker build for Drools Rule Engine Microservice

# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies by copying pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build application
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM amazoncorretto:17-alpine-jdk
WORKDIR /app

# Add non-root user for security
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -s /bin/sh -D appuser

# Copy the built JAR from build stage
COPY --from=build /app/target/drools-rule-engine-*.jar app.jar

# Configure JVM for containerized environment with performance optimizations
ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:InitialRAMPercentage=50.0 \
  -XX:MaxRAMPercentage=75.0 \
  -XX:MinRAMPercentage=50.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=30 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -XX:+UseCompressedOops \
  -XX:+UseCompressedClassPointers \
  -XX:ThreadStackSize=1024 \
  -XX:TieredStopAtLevel=4 \
  -XX:+ExitOnOutOfMemoryError \
  -Ddrools.dateformat=yyyy-MM-dd \
  -Ddrools.timezone=UTC \
  -Ddrools.multithreadEvaluation=true"

# Health check configuration
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8081/admin/health || exit 1

# Switch to non-root user
USER appuser

# Expose application ports
EXPOSE 8080 8081

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]