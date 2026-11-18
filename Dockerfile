# Multi-stage Docker build for Drools Rule Engine Microservice

# Build stage — base image pinned by digest for reproducible builds (S9). Tag kept in the comment
# for readability; update both together (e.g. via Renovate/Dependabot).
# maven:3.9-eclipse-temurin-25
FROM maven:3.9-eclipse-temurin-25@sha256:1c3a703ab39fee7ac0880f46e6ccd22c0d701f17f0616e6e66a258ddc1c637d2 AS build
WORKDIR /app

# Cache dependencies by copying pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build application
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage — base image pinned by digest for reproducible builds (S9).
# amazoncorretto:25-alpine-jdk
FROM amazoncorretto:25-alpine-jdk@sha256:027310590da693629c2cf704d2f87e9359c33ee2f02bcaa777680b2f4b94f4c7
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

# Health check configuration. /admin/* now requires the admin key (AdminAuthFilter), so send it via
# the runtime ADMIN_API_KEY env. When the key is blank (local/dev), admin is open and the empty
# header is harmless; when set (docker/prod), it authenticates the probe. (P1)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -q --tries=1 --header="X-Admin-API-Key: ${ADMIN_API_KEY}" -O /dev/null http://localhost:8080/admin/health || exit 1

# Switch to non-root user
USER appuser

# Expose application ports
EXPOSE 8080 8081

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]