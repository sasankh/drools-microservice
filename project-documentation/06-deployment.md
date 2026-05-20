# 🚀 Drools Rule Engine Microservice - Deployment Guide

## 📋 Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Environment Configuration](#environment-configuration)
- [Local Development](#local-development)
- [Production Deployment](#production-deployment)
- [AWS Deployment](#aws-deployment)
- [Docker Deployment](#docker-deployment)
- [Monitoring Setup](#monitoring-setup)
- [Troubleshooting](#troubleshooting)

---

## 🎯 Overview

This guide covers deployment options for the Drools Rule Engine Microservice, from local development to production environments.

### Key Features
- **High Performance**: 100-1000 RPS capability
- **Multi-tier Caching**: Local LRU → Redis → S3
- **Production Ready**: Health checks, metrics, circuit breakers
- **Security Hardened**: Admin authentication, input validation, rate limiting, CORS, DRL sandboxing, security headers

### Architecture
```
┌─────────────────┐    ┌────────────────────────────┐    ┌─────────────┐
│  Load Balancer  │───▶│      Application           │───▶│   AWS S3    │
│   (Optional)    │    │  Port 8080: /execute-rule  │    │   Rules     │
└─────────────────┘    │              /admin/*      │    └─────────────┘
                       │  Port 8081: /actuator/*    │            │
                       │              (mgmt only)   │    ┌─────────────┐
                       └────────────────┬───────────┘    │   Redis     │
                                        │                │   Cache     │
                                        └───────────────▶│  (optional) │
                                                         └─────────────┘
```

**Port architecture** (verified [application.yml:1-24](../src/main/resources/application.yml)):
- **Port 8080** — Main API (`POST /execute-rule`) **and** custom admin endpoints (`/admin/health`, `/admin/rules`, `/admin/refresh-rules`, `/admin/memory/*`, `/admin/thread-pools`, `/admin/info`). All on the same port.
- **Port 8081** — Spring Boot Actuator (`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`). Management port — typically firewalled off externally.

---

## 📋 Prerequisites

### System Requirements
- **Java**: 25 (enforced by Maven Enforcer Plugin — build will fail on other versions)
- **Memory**: Minimum 1GB RAM (2GB+ recommended for production)
- **CPU**: 2+ cores for production workloads
- **Disk**: 500MB+ available space

### External Dependencies
- **AWS S3**: Rule storage backend
- **Redis**: Distributed caching (optional but recommended)

### Development Tools
- **Maven**: 3.8+ for building
- **Docker**: 20.10+ (optional for containerized deployment)
- **AWS CLI**: For S3 bucket setup

---

## ⚙️ Environment Configuration

### Required Environment Variables

```bash
# === Rule Storage Configuration ===
RULE_SOURCE=s3                           # Options: s3, local, file. Default = local. (StorageFactory.java)
RULE_BUCKET_NAME=my-drools-rules          # S3 bucket name (used when RULE_SOURCE=s3)

# === AWS Configuration ===
AWS_REGION=us-east-1                      # AWS region
AWS_ACCESS_KEY_ID=your-access-key         # AWS credentials
AWS_SECRET_ACCESS_KEY=your-secret-key     # AWS credentials
# AWS_ENDPOINT=http://localhost:4566      # LocalStack for dev

# === Redis Configuration (cache decorator + pub/sub fan-out) ===
REDIS_ENABLED=true                        # Wraps base storage in RedisCachedRuleStorage
REDIS_URL=redis://localhost:6379          # Redis connection URL
# REDIS_PASSWORD=your-redis-password      # If authentication required
REDIS_DRL_RULES_TTL_MINUTES=15            # Cache TTL for DRL JSON
REDIS_DRL_RULES_KEY_PREFIX=drools:rule:   # Key prefix for SCAN+MGET
REDIS_PUBSUB_ENABLED=true                 # Cross-instance refresh fan-out
REDIS_PUBSUB_CHANNEL=drools:rule:events   # Pub/sub channel

# === Performance Tuning ===
RULE_EXECUTION_TIMEOUT_SECONDS=30         # Rule timeout
THREAD_POOL_RULE_EXECUTION_CORE_SIZE=10   # Thread pool core size
THREAD_POOL_RULE_EXECUTION_MAX_SIZE=50    # Thread pool max size

# === Security Configuration ===
DROOLS_VALIDATION_RULE_ID_MAX_LENGTH=255          # Rule ID validation
DROOLS_VALIDATION_DATA_MAX_FIELDS=100             # Data field limit
DROOLS_VALIDATION_REQUEST_MAX_SIZE_MB=10          # Request size limit
DROOLS_RATE_LIMITING_PER_MINUTE_LIMIT=1000        # Rate limit per minute
DROOLS_CORS_ALLOWED_ORIGINS=                         # CORS origins (empty = no CORS; set origins for production)
ADMIN_API_KEY=                                       # Admin endpoint API key (empty = auth disabled)

# === Circuit Breaker Configuration ===
RESILIENCE4J_CIRCUITBREAKER_S3_FAILURE_RATE_THRESHOLD=50
RESILIENCE4J_CIRCUITBREAKER_REDIS_FAILURE_RATE_THRESHOLD=50

# === Logging Configuration ===
LOGGING_LEVEL_ROOT=INFO                   # Root log level
LOGGING_LEVEL_COM_COMPANY_DROOLS=DEBUG    # Application log level
```

### Application Properties Template

Create `application-prod.yml` for production:

```yaml
# Production Configuration Template
server:
  port: 8080
  shutdown: graceful

management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,info,metrics,thread-pools
  endpoint:
    health:
      show-details: when-authorized

spring:
  application:
    name: drools-rule-engine
  profiles:
    active: prod

# Rule Storage
drools:
  rule-source: s3
  bucket-name: ${RULE_BUCKET_NAME}
  
# Caching (RedisCachedRuleStorage decorator + pub/sub fan-out)
redis:
  enabled: ${REDIS_ENABLED:true}
  url: ${REDIS_URL:redis://localhost:6379}
  drl-rules:
    ttl-minutes: ${REDIS_DRL_RULES_TTL_MINUTES:15}
    key-prefix: ${REDIS_DRL_RULES_KEY_PREFIX:drools:rule:}
  pubsub:
    enabled: ${REDIS_PUBSUB_ENABLED:true}
    channel: ${REDIS_PUBSUB_CHANNEL:drools:rule:events}

# Performance
thread-pools:
  rule-execution:
    core-size: ${THREAD_POOL_RULE_EXECUTION_CORE_SIZE:10}
    max-size: ${THREAD_POOL_RULE_EXECUTION_MAX_SIZE:50}
    queue-capacity: 100

# Security
drools:
  validation:
    rule-id:
      max-length: ${DROOLS_VALIDATION_RULE_ID_MAX_LENGTH:255}
    data:
      max-fields: ${DROOLS_VALIDATION_DATA_MAX_FIELDS:100}
    request:
      max-size-mb: ${DROOLS_VALIDATION_REQUEST_MAX_SIZE_MB:10}
  cors:
    allowed-origins: ${DROOLS_CORS_ALLOWED_ORIGINS:}
  rate-limiting:
    per-minute-limit: ${DROOLS_RATE_LIMITING_PER_MINUTE_LIMIT:1000}

# Circuit Breakers
resilience4j:
  circuitbreaker:
    instances:
      s3:
        failure-rate-threshold: ${RESILIENCE4J_CIRCUITBREAKER_S3_FAILURE_RATE_THRESHOLD:50}
        wait-duration-in-open-state: 30s
      redis:
        failure-rate-threshold: ${RESILIENCE4J_CIRCUITBREAKER_REDIS_FAILURE_RATE_THRESHOLD:50}
        wait-duration-in-open-state: 10s

# Logging
logging:
  level:
    root: ${LOGGING_LEVEL_ROOT:INFO}
    com.company.drools: ${LOGGING_LEVEL_COM_COMPANY_DROOLS:INFO}
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
  config: classpath:logback-spring.xml
```

---

## 🏠 Local Development

### 1. Clone and Setup

```bash
# Clone repository
git clone https://github.com/company/drools-microservice.git
cd drools-microservice

# Build project
mvn clean compile

# Verify Java version
java -version  # Should be 25
```

### 2. Start LocalStack & Redis (Docker Compose)

```bash
# Start development environment
docker-compose up -d

# Wait for services to start (30 seconds)
sleep 30

# Create S3 bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://local-rules

# Upload sample rules (if you have any)
# aws --endpoint-url=http://localhost:4566 s3 cp rules/ s3://local-rules/ --recursive
```

### 3. Configure Local Environment

```bash
# Set environment variables for local development
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=local-rules
export AWS_ENDPOINT=http://localhost:4566
export AWS_ACCESS_KEY_ID=localstack
export AWS_SECRET_ACCESS_KEY=localstack
export AWS_REGION=us-east-1
export REDIS_ENABLED=true
export REDIS_URL=redis://localhost:6379
```

### 4. Run Application

```bash
# Start application
mvn spring-boot:run -Dspring.profiles.active=local

# Or run with specific JVM options
mvn spring-boot:run \
  -Dspring.profiles.active=local \
  -Xmx2g \
  -XX:+UseG1GC
```

### 5. Verify Deployment

```bash
# Check application health
curl http://localhost:8080/admin/health

# Check system info
curl http://localhost:8080/admin/info

# Test rule execution (if you have rules)
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "test.rule", "data": {"amount": 100}}'
```

---

## 🏭 Production Deployment

### 1. Build Production JAR

```bash
# Clean build with production optimizations
mvn clean package -Pproduction -DskipTests

# Verify JAR creation
ls -la target/drools-rule-engine-*.jar
```

### 2. Setup Production Environment

```bash
# Create application user
sudo useradd -r -s /bin/false drools

# Create application directory
sudo mkdir -p /opt/drools-rule-engine
sudo mkdir -p /opt/drools-rule-engine/logs
sudo mkdir -p /opt/drools-rule-engine/config

# Copy JAR file
sudo cp target/drools-rule-engine-*.jar /opt/drools-rule-engine/
sudo chown -R drools:drools /opt/drools-rule-engine
```

### 3. Create Production Configuration

```bash
# Create environment file
sudo tee /opt/drools-rule-engine/config/application.env << 'EOF'
# Production Environment Variables
JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Application Configuration
RULE_SOURCE=s3
RULE_BUCKET_NAME=prod-drools-rules
AWS_REGION=us-east-1

# Redis Configuration (shared cache + cross-instance fan-out)
REDIS_ENABLED=true
REDIS_URL=redis://prod-redis.company.com:6379
REDIS_DRL_RULES_TTL_MINUTES=15
REDIS_PUBSUB_ENABLED=true

# Performance Tuning
THREAD_POOL_RULE_EXECUTION_CORE_SIZE=20
THREAD_POOL_RULE_EXECUTION_MAX_SIZE=100

# Security
DROOLS_RATE_LIMITING_PER_MINUTE_LIMIT=5000
DROOLS_CORS_ALLOWED_ORIGINS=https://app.company.com,https://admin.company.com
ADMIN_API_KEY=your-secure-production-api-key

# Logging
LOGGING_LEVEL_ROOT=WARN
LOGGING_LEVEL_COM_COMPANY_DROOLS=INFO
EOF

# Set secure permissions
sudo chmod 600 /opt/drools-rule-engine/config/application.env
sudo chown drools:drools /opt/drools-rule-engine/config/application.env
```

### 4. Create SystemD Service

```bash
# Create service file
sudo tee /etc/systemd/system/drools-rule-engine.service << 'EOF'
[Unit]
Description=Drools Rule Engine Microservice
After=network.target

[Service]
Type=simple
User=drools
Group=drools
WorkingDirectory=/opt/drools-rule-engine
EnvironmentFile=/opt/drools-rule-engine/config/application.env
ExecStart=/usr/bin/java $JAVA_OPTS -jar drools-rule-engine-1.0.0.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=drools-rule-engine

# Security settings
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/drools-rule-engine/logs

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd and start service
sudo systemctl daemon-reload
sudo systemctl enable drools-rule-engine
sudo systemctl start drools-rule-engine

# Check service status
sudo systemctl status drools-rule-engine
```

### 5. Setup Load Balancer (Nginx Example)

```bash
# Install Nginx
sudo apt update && sudo apt install nginx

# Create configuration
sudo tee /etc/nginx/sites-available/drools-rule-engine << 'EOF'
upstream drools_backend {
    server 127.0.0.1:8080;
    # Add more servers for HA:
    # server 10.0.1.2:8080;
    # server 10.0.1.3:8080;
}

upstream drools_admin {
    server 127.0.0.1:8080;
}

server {
    listen 80;
    server_name api.company.com;
    
    # Rate limiting
    limit_req_zone $binary_remote_addr zone=api:10m rate=100r/s;
    
    location / {
        limit_req zone=api burst=50 nodelay;
        
        proxy_pass http://drools_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # Timeouts
        proxy_connect_timeout 5s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
        
        # Health check
        proxy_next_upstream error timeout http_500 http_502 http_503 http_504;
    }
    
    location /health {
        proxy_pass http://drools_admin/admin/health;
        access_log off;
    }
}

server {
    listen 8080;
    server_name admin.company.com;
    
    # Restrict admin access
    allow 10.0.0.0/8;
    allow 192.168.0.0/16;
    deny all;
    
    location / {
        proxy_pass http://drools_admin;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
EOF

# Enable site and restart Nginx
sudo ln -s /etc/nginx/sites-available/drools-rule-engine /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

---

## ☁️ AWS Deployment

### 1. Setup S3 Bucket

```bash
# Create S3 bucket for rules
aws s3 mb s3://prod-drools-rules-company

# Set bucket policy (replace with your account ID)
aws s3api put-bucket-policy --bucket prod-drools-rules-company --policy '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DroolsRuleEngineAccess",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::ACCOUNT-ID:role/DroolsRuleEngineRole"
      },
      "Action": [
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::prod-drools-rules-company",
        "arn:aws:s3:::prod-drools-rules-company/*"
      ]
    }
  ]
}'

# Enable versioning for rule history
aws s3api put-bucket-versioning --bucket prod-drools-rules-company \
  --versioning-configuration Status=Enabled
```

### 2. Create IAM Role

```bash
# Create IAM role
aws iam create-role --role-name DroolsRuleEngineRole --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}'

# Create policy for S3 access
aws iam put-role-policy --role-name DroolsRuleEngineRole --policy-name S3RuleAccess --policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::prod-drools-rules-company",
        "arn:aws:s3:::prod-drools-rules-company/*"
      ]
    }
  ]
}'

# Create instance profile
aws iam create-instance-profile --instance-profile-name DroolsRuleEngineProfile
aws iam add-role-to-instance-profile --instance-profile-name DroolsRuleEngineProfile --role-name DroolsRuleEngineRole
```

### 3. Setup ElastiCache Redis

```bash
# Create Redis subnet group
aws elasticache create-cache-subnet-group \
  --cache-subnet-group-name drools-redis-subnet \
  --cache-subnet-group-description "Subnet group for Drools Redis" \
  --subnet-ids subnet-12345678 subnet-87654321

# Create Redis cluster
aws elasticache create-cache-cluster \
  --cache-cluster-id drools-redis-prod \
  --engine redis \
  --cache-node-type cache.r6g.large \
  --num-cache-nodes 1 \
  --cache-subnet-group-name drools-redis-subnet \
  --security-group-ids sg-12345678
```

### 4. EC2 Deployment

```bash
# Launch EC2 instance with IAM role
aws ec2 run-instances \
  --image-id ami-0abcdef1234567890 \
  --instance-type c5.xlarge \
  --key-name your-key-pair \
  --security-group-ids sg-12345678 \
  --subnet-id subnet-12345678 \
  --iam-instance-profile Name=DroolsRuleEngineProfile \
  --user-data file://user-data.sh

# user-data.sh example:
cat > user-data.sh << 'EOF'
#!/bin/bash
yum update -y
yum install -y java-25-amazon-corretto

# Download and install application
wget https://releases.company.com/drools-rule-engine-1.0.0.jar
# ... rest of setup
EOF
```

---

## 🐳 Docker Deployment

### 1. Build Docker Image

The repository ships an actual `Dockerfile` at the repo root. It is **multi-stage** (Maven build → Amazon Corretto Alpine runtime) and produces a ~347 MB image. The full file is at [`Dockerfile`](../Dockerfile); the structure below documents it.

```dockerfile
# ── Stage 1: Build ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B          # dependency caching layer
COPY src ./src
RUN mvn clean package -DskipTests
# Output: /app/target/drools-rule-engine-*.jar

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM amazoncorretto:25-alpine-jdk
RUN addgroup -g 1000 appgroup && adduser -D -u 1000 -G appgroup appuser
WORKDIR /app
COPY --from=build /app/target/drools-rule-engine-*.jar app.jar

# JVM tuning — applied even if docker-compose overrides JAVA_OPTS
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:MinRAMPercentage=50.0 \
    -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:G1HeapRegionSize=16m \
    -XX:InitiatingHeapOccupancyPercent=30 -XX:+UseStringDeduplication \
    -XX:+OptimizeStringConcat -XX:+UseCompressedOops -XX:+UseCompressedClassPointers \
    -XX:ThreadStackSize=1024 -XX:TieredStopAtLevel=4 -XX:+ExitOnOutOfMemoryError \
    -Ddrools.dateformat=yyyy-MM-dd -Ddrools.timezone=UTC \
    -Ddrools.multithreadEvaluation=true"

USER appuser

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/admin/health

EXPOSE 8080 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Why these choices**:
- **Amazon Corretto Alpine** (~180 MB base) over `openjdk:17-jre-slim` — smaller, AWS-tuned JVM, security patches via Amazon.
- **Multi-stage** — build artifacts (Maven cache, source) excluded from the runtime image.
- **Non-root user** — `appuser` (uid 1000), required by many container security policies.
- **`wget --spider`** — Alpine ships with `wget` but not `curl`; `--spider` does an HTTP HEAD without downloading.
- **`-XX:ExitOnOutOfMemoryError`** — fast crash on OOM rather than degraded service. The orchestrator restarts.

### 2. Build and Run

```bash
# Build image
docker build -t drools-rule-engine:1.0.0 .

# Run container
docker run -d \
  --name drools-rule-engine \
  -p 8080:8080 \
  -p 8081:8081 \
  -e RULE_SOURCE=s3 \
  -e RULE_BUCKET_NAME=prod-drools-rules \
  -e AWS_REGION=us-east-1 \
  -e REDIS_ENABLED=true \
  -e REDIS_URL=redis://redis:6379 \
  drools-rule-engine:1.0.0

# Check logs
docker logs drools-rule-engine

# Check health
docker exec drools-rule-engine curl http://localhost:8080/admin/health
```

### 3. Docker Compose Production

```yaml
# docker-compose.prod.yml
version: '3.8'

services:
  drools-rule-engine:
    image: drools-rule-engine:1.0.0
    ports:
      - "8080:8080"
      - "8081:8081"
    environment:
      - RULE_SOURCE=s3
      - RULE_BUCKET_NAME=prod-drools-rules
      - REDIS_ENABLED=true
      - REDIS_URL=redis://redis:6379
      - JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC
    depends_on:
      - redis
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/admin/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --maxmemory 1gb --maxmemory-policy allkeys-lru
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 3

  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - drools-rule-engine
    restart: unless-stopped
```

---

## 📊 Monitoring Setup

### 1. Metrics Collection

The application exports metrics via Micrometer. Configure your monitoring system:

```bash
# Prometheus scrape config
- job_name: 'drools-rule-engine'
  static_configs:
    - targets: ['localhost:8081']
  metrics_path: '/actuator/prometheus'
  scrape_interval: 15s
```

### 2. Key Metrics to Monitor

```yaml
# Application Metrics
- drools.rule.execution.count
- drools.rule.execution.time
- drools.cache.hits
- drools.cache.misses
- drools.storage.operations.count
- drools.circuit.breaker.state

# System Metrics
- jvm.memory.used
- jvm.gc.pause
- system.cpu.usage
- http.server.requests
```

### 3. Alerting Rules

```yaml
# Prometheus alerting rules
groups:
- name: drools-rule-engine
  rules:
  - alert: DroolsServiceDown
    expr: up{job="drools-rule-engine"} == 0
    for: 1m
    
  - alert: HighErrorRate
    expr: rate(http_server_requests_total{status=~"5.."}[5m]) > 0.1
    for: 2m
    
  - alert: HighMemoryUsage
    expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
    for: 5m
    
  - alert: CircuitBreakerOpen
    expr: resilience4j_circuitbreaker_state{state="open"} == 1
    for: 1m
```

---

## 🔧 Troubleshooting

### Common Issues

#### 1. Application Won't Start

```bash
# Check Java version
java -version

# Check port availability
netstat -tlnp | grep :8080

# Check logs
tail -f /opt/drools-rule-engine/logs/application.log

# Common causes:
# - Port already in use
# - Insufficient memory
# - Missing environment variables
# - Invalid AWS credentials
```

#### 2. S3 Connection Issues

```bash
# Test AWS credentials
aws sts get-caller-identity

# Test S3 access
aws s3 ls s3://your-bucket-name

# Check network connectivity
curl -v https://s3.amazonaws.com

# Common causes:
# - Invalid credentials
# - Bucket doesn't exist
# - Network/firewall issues
# - Wrong region configuration
```

#### 3. Redis Connection Issues

```bash
# Test Redis connectivity
redis-cli -h your-redis-host ping

# Check Redis logs
docker logs redis-container

# Common causes:
# - Redis not running
# - Wrong connection URL
# - Authentication issues
# - Network/firewall issues
```

#### 4. Performance Issues

```bash
# Check thread pool status
curl http://localhost:8080/admin/thread-pools

# Check cache statistics
curl http://localhost:8080/admin/health | jq '.components.cache'

# Check GC logs
# Add to JAVA_OPTS: -XX:+PrintGC -XX:+PrintGCDetails

# Common causes:
# - Insufficient memory
# - Poor cache hit rate
# - Thread pool exhaustion
# - Database connection pool issues
```

### Log Analysis

```bash
# View application logs
tail -f /opt/drools-rule-engine/logs/application.log

# Search for errors
grep ERROR /opt/drools-rule-engine/logs/application.log

# Search for specific rule executions
grep "rule_id" /opt/drools-rule-engine/logs/application.log

# Monitor access logs (if using Nginx)
tail -f /var/log/nginx/access.log
```

### Health Check Endpoints

```bash
# Overall health
curl http://localhost:8080/admin/health

# System information
curl http://localhost:8080/admin/info

# Thread pool statistics
curl http://localhost:8080/admin/thread-pools

# Rule list
curl http://localhost:8080/admin/rules
```

---

## 📚 Additional Resources

- [Configuration Guide](08-configuration.md)
- [Rule Development Guide](17-rule-development.md)
- [API Documentation](api-reference/openapi.yml)
- [Troubleshooting Guide](31-troubleshooting.md)

---

**Last Updated**: 2026-02-26
**Version**: 1.1.0