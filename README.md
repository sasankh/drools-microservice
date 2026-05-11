# Drools Rule Engine Microservice

A high-performance business rule execution microservice built with Spring Boot and Drools. Designed to handle 100-1000 requests per second with sub-100ms latency for cached rules.

> ## 📚 Full documentation
>
> The full documentation lives in [`project-documentation/`](project-documentation/) — 40 docs covering architecture, APIs, security, deployment, operations, and rule authoring.
>
> **Start here**: [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md) — the entry point with role-based reading paths.
>
> **Quickstart**: [`project-documentation/32-getting-started.md`](project-documentation/32-getting-started.md) — running locally in under 30 minutes.

## 🚀 Features

- **High Performance**: Sub-100ms P99 latency for cached rules, supports 100-1000 RPS
- **Scalable Storage**: AWS S3 backend with hierarchical rule organization
- **Multi-tier Caching**: Local LRU + Redis distributed caching for optimal performance
- **Rule Management**: REST APIs for hot-reloading and monitoring rules
- **Production Ready**: Health checks, metrics, monitoring, and comprehensive security
- **Memory Stable**: Proper resource disposal prevents memory leaks and OOM errors
- **Real-time Monitoring**: Memory diagnostics endpoint with automatic warnings
- **Security Hardened**: Admin authentication, DRL sandboxing, security headers, input validation, rate limiting, CORS, and sensitive data protection
- **Performance Optimized**: Connection pooling, thread pools, circuit breakers, and JVM tuning
- **Development Friendly**: LocalStack integration for offline S3 testing, one-command setup

## 🏗️ Architecture

```
Client Request → REST API → Rule Engine → Cache Layer → Storage Layer
                    ↓           ↓            ↓           ↓
               Controller → Drools KIE → LRU/Redis → S3/Local/Memory
```

### Tech Stack

- **Java 25** - Runtime platform (enforced by Maven Enforcer Plugin)
- **Spring Boot 3.5.3** - Application framework with security and validation
- **Drools 10.2.0** - Business rules engine
- **AWS S3** - Rule storage (with LocalStack for development)
- **Redis** - Distributed caching (optional)
- **Micrometer** - Vendor-agnostic metrics and monitoring
- **Resilience4j** - Circuit breakers and fault tolerance
- **Docker & Docker Compose** - Containerization with full local dev stack
- **Maven** - Build and dependency management

## 📋 Table of Contents

- [Quick Start](#-quick-start)
- [Installation](#-installation)
- [Configuration](#-configuration)
- [Usage](#-usage)
- [API Documentation](#-api-documentation)
- [Development](#-development)
- [Testing](#-testing)
- [Deployment](#-deployment)
- [Contributing](#-contributing)
- [License](#-license)

## ⚡ Quick Start

### Prerequisites

- **Java 25** (Required - enforced by Maven Enforcer Plugin)
- Maven 3.8+
- Docker and Docker Compose
- AWS CLI (for S3 setup)

**Important**: This project requires **Java 25** specifically. The build will fail if using a different Java version.

### 1. Clone the Repository

```bash
git clone <repository-url>
cd drools-microservice
```

### 2. Set Up Java 25 Environment

For local development (not needed for Docker-only):

```bash
# Option 1: Use the provided setup script (temporary for current terminal)
source ./set-java-env.sh

# Option 2: Permanent setup (add to ~/.zshrc or ~/.bashrc)
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"

# Verify Java 25 is active
java -version   # Should show "openjdk version 25.x.x"
mvn -version    # Should show "Java version: 25.x.x"
```

**Note**: The Maven Enforcer Plugin will automatically verify you're using Java 25 and fail the build with a clear error message if not.

### 3. One-Command Development Environment Setup

```bash
# Complete automated setup (builds, starts services, validates everything)
./setup-dev-environment.sh

# Or start services manually
docker-compose up -d

# Wait for services to start (about 30 seconds)
sleep 30

# Verify all services are running and healthy
docker-compose ps
curl http://localhost:8080/admin/health
```

### 3. Set Environment Variables

```bash
# Copy environment template
cp .env.example .env

# Edit .env file with your configuration
# For local development, default values should work

# The .env file will be automatically loaded by the application
# You can also set environment variables directly if preferred
```

### 4. Build and Run

#### Option A: Using Docker Compose (Recommended for Development)
```bash
# Everything runs in containers - no local Java/Maven needed
docker-compose up -d

# View application logs
docker-compose logs -f app
```

#### Option B: Local Java Development
```bash
# Build the application (requires Java 25 + Maven)
mvn clean compile

# Run the application
mvn spring-boot:run

# Or with specific profile
mvn spring-boot:run -Dspring.profiles.active=dev
```

#### Option C: Docker Build and Test
```bash
# Build optimized Docker image
docker build -t drools-rule-engine:latest .

# Run comprehensive Docker validation
./scripts/docker-build-test.sh

# Run the container
docker run -p 8080:8080 -p 8081:8081 \
  -e RULE_SOURCE=memory \
  drools-rule-engine:latest
```

### 5. Verify Installation

```bash
# Check application health (with comprehensive component status)
curl http://localhost:8080/admin/health

# List available sample rules
curl http://localhost:8080/admin/rules

# Test rule execution with sample rules
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100}}'

# Test VIP customer rule
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.vip", "data": {"customerType": "VIP", "amount": 100}}'
```

Expected response:
```json
{
  "success": true,
  "result": {
    "amount": 90.0,
    "discount": 10.0,
    "discountPercent": 10
  },
  "executionTimeMs": 15,
  "rule_id": "pricing.discount.simple",
  "timestamp": "2025-07-22T18:45:00Z"
}
```

## 🔧 Installation

### System Requirements

- **Java**: OpenJDK 25 or Oracle JDK 25+
- **Memory**: Minimum 2GB RAM, recommended 4GB+
- **Storage**: 1GB free disk space
- **Network**: Internet access for Maven dependencies

### Dependencies Installation

#### Java 25
```bash
# Ubuntu/Debian
sudo apt update && sudo apt install openjdk-25-jdk

# macOS with Homebrew
brew install openjdk@25

# Windows
# Download from https://adoptium.net/temurin/releases/
```

#### Maven
```bash
# Ubuntu/Debian
sudo apt install maven

# macOS with Homebrew
brew install maven

# Windows
# Download from https://maven.apache.org/download.cgi
```

#### Docker
```bash
# Ubuntu/Debian
sudo apt install docker.io docker-compose

# macOS with Homebrew
brew install docker docker-compose

# Windows
# Download Docker Desktop from https://www.docker.com/products/docker-desktop
```

#### AWS CLI (for S3 operations)
```bash
# Ubuntu/Debian
sudo apt install awscli

# macOS with Homebrew
brew install awscli

# Windows
# Download from https://aws.amazon.com/cli/
```

## ⚙️ Configuration

### Environment Variables

The application supports multiple configuration methods (in priority order):

1. **System Environment Variables** (highest priority)
2. **`.env` file** (automatically loaded if present)
3. **`application.yml`** (default values)

**Note**: The application automatically loads `.env` files from the project root directory.

### Required Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `RULE_SOURCE` | Storage backend: `s3`, `local`, or `memory` | `memory` | Yes |
| `RULE_BUCKET_NAME` | S3 bucket name for rules | `local-rules` | If RULE_SOURCE=s3 |
| `AWS_ENDPOINT` | S3 endpoint URL (LocalStack: http://localhost:4566) | AWS default | If using LocalStack |

### Optional Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_ENABLED` | Enable Redis distributed caching | `false` |
| `REDIS_URL` | Redis connection URL | `redis://localhost:6379` |
| `LRU_CACHE_MAX_SIZE` | Local LRU cache size | `100` |
| `RULE_EXECUTION_TIMEOUT_SECONDS` | Rule execution timeout | `30` |
| `LOG_LEVEL` | Application log level | `INFO` |

### Security Configuration Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DROOLS_VALIDATION_RULE_ID_MAX_LENGTH` | Maximum rule ID length | `255` |
| `DROOLS_VALIDATION_DATA_MAX_FIELDS` | Maximum data fields per request | `100` |
| `DROOLS_VALIDATION_DATA_MAX_STRING_LENGTH` | Maximum string field length | `10000` |
| `DROOLS_CORS_ALLOWED_ORIGINS` | CORS allowed origins | *(empty)* |
| `ADMIN_API_KEY` | API key for admin endpoint auth | *(empty/disabled)* |
| `DROOLS_RATE_LIMITING_ENABLED` | Enable rate limiting | `true` |
| `DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE` | Rate limit per minute | `1000` |
| `MAX_HTTP_REQUEST_SIZE` | Maximum HTTP request size | `10MB` |

### Performance Configuration Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DROOLS_THREAD_POOL_MAX_SIZE` | Rule execution thread pool size | `50` |
| `AWS_S3_MAX_CONNECTIONS` | S3 connection pool size | `50` |
| `DROOLS_CB_S3_FAILURE_RATE` | S3 circuit breaker failure threshold | `50` |
| `DROOLS_HTTP_CONNECTION_TIMEOUT` | HTTP connection timeout (seconds) | `10` |

### Storage Backend Configuration

#### 1. Memory Storage (Development)
```bash
export RULE_SOURCE=memory
```
Uses built-in sample rules. No additional setup required.

#### 2. Local File Storage (Development)
```bash
export RULE_SOURCE=local
export RULE_BASE_PATH=/path/to/rules
```

#### 3. S3 Storage (Production)
```bash
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=my-rules-bucket
export AWS_ACCESS_KEY_ID=your-access-key
export AWS_SECRET_ACCESS_KEY=your-secret-key
export AWS_REGION=us-east-1
```

#### 4. S3 with LocalStack (Local Development)
```bash
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=local-rules
export AWS_ENDPOINT=http://localhost:4566
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
```

### Redis Configuration

```bash
# Enable Redis caching
export REDIS_ENABLED=true
export REDIS_URL=redis://localhost:6379

# Redis with authentication
export REDIS_URL=redis://username:password@localhost:6379

# Redis cluster
export REDIS_URL=redis://node1:6379,redis://node2:6379
```

## 🎯 Usage

### Rule Execution API

#### Execute a Rule
```bash
POST /execute-rule
Content-Type: application/json

{
  "rule_id": "pricing.discount.vip",
  "data": {
    "customerType": "VIP",
    "amount": 100,
    "quantity": 5
  }
}
```

Response:
```json
{
  "success": true,
  "result": {
    "amount": 80,
    "discount": 20,
    "discountPercent": 20
  },
  "executionTimeMs": 15,
  "rule_id": "pricing.discount.vip",
  "timestamp": "2025-07-21T17:30:00Z"
}
```

### Admin APIs

#### Health Check
```bash
GET /admin/health

{
  "status": "UP",
  "timestamp": "2025-07-21T17:30:00Z",
  "drools": {
    "loaded_rules": 25,
    "active_rules": 25
  },
  "cache": {
    "enabled": true,
    "size": 15,
    "max_size": 100,
    "statistics": {
      "hits": 1250,
      "misses": 45,
      "evictions": 2
    }
  },
  "storage": {
    "type": "S3RuleStorage",
    "total_rules": 25
  }
}
```

#### List All Rules
```bash
GET /admin/rules

{
  "total_rules": 25,
  "rules": [
    {
      "rule_id": "pricing.discount.vip",
      "status": "ACTIVE",
      "loaded_at": "2025-07-21T17:00:00Z",
      "execution_count": 150,
      "avg_execution_time_ms": 12.5,
      "cached": true,
      "version": "1.0"
    }
  ]
}
```

#### Refresh All Rules
```bash
POST /admin/refresh-rules

{
  "status": "completed",
  "rules_loaded": 25,
  "rules_failed": 0,
  "duration_ms": 1250,
  "errors": []
}
```

#### Refresh Single Rule
```bash
POST /admin/refresh-rules/pricing.discount.vip

{
  "rule_id": "pricing.discount.vip",
  "status": "success",
  "previous_version": "2025-07-21T16:00:00Z",
  "current_version": "2025-07-21T17:00:00Z",
  "compilation_time_ms": 45
}
```

#### Memory Monitoring (NEW)

Real-time memory diagnostics and monitoring endpoints to track JVM memory usage and prevent OOM errors.

**Get Comprehensive Memory Info**
```bash
GET /admin/memory/info

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

**Warning Levels**:
- `CRITICAL`: Heap usage > 90% - OOM risk is HIGH
- `WARNING`: Heap usage > 80% - consider increasing heap size
- `CAUTION`: Heap usage > 70% - monitor closely

**Trigger Garbage Collection (Diagnostics Only)**
```bash
POST /admin/memory/gc

{
  "message": "Garbage collection triggered",
  "usedBeforeMB": 450,
  "usedAfterMB": 250,
  "freedMemoryMB": 200,
  "note": "This is a suggestion to JVM, actual GC timing is not guaranteed"
}
```

**Get Quick Memory Snapshot**
```bash
GET /admin/memory/snapshot

{
  "timestamp": 1708390000000,
  "heapUsedMB": 245,
  "heapMaxMB": 2048,
  "heapUsagePercent": 11.96
}
```

**Usage Example**:
```bash
# Monitor memory in real-time (every 5 seconds)
watch -n 5 'curl -s http://localhost:8080/admin/memory/info | jq ".heap.usagePercent"'

# Check for memory warnings
curl -s http://localhost:8080/admin/memory/info | jq '.warnings'

# Verify memory is stable after rule refreshes
for i in {1..10}; do
    curl -X POST http://localhost:8080/admin/refresh-rules
    sleep 3
    curl -s http://localhost:8080/admin/memory/info | jq '.heap.usedMB'
done
```

### Rule Development

Rules are written in Drools (.drl) format and stored with hierarchical organization:

#### Rule Structure (17 Sample Rules Included)
```
sample-rules/                          # Single source of truth for all .drl files
├── pricing/
│   ├── discount/
│   │   ├── simple.drl                 # Basic 10% discount
│   │   ├── vip.drl                    # VIP customer discount
│   │   ├── bulk.drl                   # Bulk order discount
│   │   └── first-time.drl            # First-time customer discount
│   └── shipping/
│       ├── standard.drl               # Standard shipping calculation
│       └── express.drl                # Express shipping calculation
├── seasonal/
│   └── holiday/
│       ├── discount.drl               # Holiday season discount
│       └── blackfriday.drl            # Black Friday promotion
└── validation/
    └── customer/
        ├── age.drl                    # Customer age validation
        └── credit.drl                 # Credit score validation
```

#### Sample Rule (pricing/discount/vip.drl)
```drools
package com.company.rules.pricing.discount

rule "VIP Customer Discount"
when
    $data : Map(this["customerType"] == "VIP", $amount : this["amount"])
then
    $data.put("discount", ((Double)$amount) * 0.20);
    $data.put("amount", ((Double)$amount) * 0.80);
    $data.put("discountPercent", 20);
end
```

#### Rule ID Mapping
- Rule ID: `pricing.discount.vip`
- S3 Path: `pricing/discount/vip.drl`
- Package: `com.company.rules.pricing.discount`

## 📚 API Documentation

### Base URLs

- **Main API**: `http://localhost:8080`
- **Admin API**: `http://localhost:8080/admin`

### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/execute-rule` | Execute a business rule |
| `GET` | `/admin/health` | Application health check with component status |
| `GET` | `/admin/info` | System information |
| `GET` | `/admin/rules` | List all loaded rules with metadata |
| `GET` | `/admin/thread-pools` | Thread pool statistics |
| `POST` | `/admin/refresh-rules` | Refresh all rules from storage |
| `POST` | `/admin/refresh-rules/{ruleId}` | Refresh specific rule |

### Security Features

The API includes comprehensive security features (39/42 security findings addressed):

- **Admin Authentication**: API key protection for `/admin/*` endpoints via `X-Admin-API-Key` header
- **DRL Sandboxing**: Blocklist-based rule content scanning prevents arbitrary code execution
- **Security Headers**: 7 security headers on all responses (CSP, HSTS, X-Frame-Options, etc.)
- **Input Validation**: All requests are validated for proper format, size limits, and security patterns
- **Rate Limiting**: Configurable per-client rate limits with standard HTTP headers (per remote IP)
- **CORS Protection**: Configurable cross-origin request policies (empty default, restrictive in production)
- **Request Size Limits**: Multi-layer protection against large payloads (including chunked transfer)
- **Path Traversal Protection**: Defense-in-depth in storage layers
- **Log Sanitization**: Automatic removal of sensitive data from logs (word-boundary patterns)

### Error Responses

```json
{
  "success": false,
  "error": "Rule not found",
  "errorCode": "RULE_NOT_FOUND",
  "timestamp": "2025-07-21T17:30:00Z",
  "path": "/execute-rule"
}
```

The 10 distinct error codes (full catalog in [`12-error-code-catalog.md`](project-documentation/12-error-code-catalog.md)):

| HTTP | Code | When |
|---:|---|---|
| 400 | `RULE_EXECUTION_ERROR` | Rule fired but threw at runtime |
| 400 | `INVALID_INPUT` | Validation failed or malformed argument |
| 401 | `UNAUTHORIZED` | Admin endpoint accessed without/wrong API key |
| 404 | `RULE_NOT_FOUND` | Rule ID does not exist in storage |
| 404 | `NOT_FOUND` | Path does not match any handler |
| 408 | `TIMEOUT_ERROR` | Operation exceeded its timeout |
| 413 | `REQUEST_TOO_LARGE` | Request body exceeds size cap |
| 429 | `RATE_LIMIT_EXCEEDED` | Per-client rate limit hit |
| 500 | `INTERNAL_ERROR` | Anything not caught by a more specific handler |
| 503 | `SERVICE_UNAVAILABLE` | Circuit breaker open (S3 or Redis) |

## 🔬 Development

### Project Structure

```
src/
├── main/java/com/company/drools/
│   ├── api/                    # REST controllers and DTOs
│   │   ├── controller/         # REST endpoints
│   │   ├── dto/               # Data transfer objects
│   │   ├── exception/         # Exception handlers
│   │   ├── validation/        # Custom validation annotations
│   │   └── filter/            # Security and request filters
│   ├── core/                  # Business logic
│   │   ├── engine/           # Drools engine integration
│   │   └── model/            # Domain models
│   ├── storage/              # Storage implementations
│   │   ├── RuleStorage.java  # Storage interface
│   │   ├── S3RuleStorage.java # S3 implementation
│   │   └── LocalFileStorage.java # File system implementation
│   ├── cache/                # Caching implementations
│   │   ├── RuleCache.java    # Cache interface
│   │   ├── LocalLRUCache.java # LRU cache
│   │   └── RedisRuleCache.java # Redis cache
│   ├── common/               # Shared utilities
│   │   └── LogSanitizer.java # Log sanitization
│   └── config/               # Spring configuration
└── main/resources/
    ├── application.yml       # Configuration
    └── logback-spring.xml   # Logging configuration
```

### Local Development Setup

#### Quick Start with One-Command Setup (Recommended)
```bash
# Complete automated setup (Maven build + Docker + LocalStack + validation)
./setup-dev-environment.sh

# View all services status
docker-compose ps

# Access application
curl http://localhost:8080/admin/health
```

#### Manual Docker Compose Setup
```bash
# Start complete development stack
docker-compose up -d

# Initialize LocalStack (creates bucket and uploads sample rules from sample-rules/)
./init-localstack.sh

# Test LocalStack integration
./scripts/test-localstack.sh

# View all services status
docker-compose ps
```

#### Manual Setup
1. **Start Infrastructure Services**
   ```bash
   docker-compose up -d localstack redis
   ```

2. **Create S3 Bucket**
   ```bash
   aws --endpoint-url=http://localhost:4566 s3 mb s3://local-rules
   ```

3. **Upload Sample Rules** (automatic with docker-compose)
   ```bash
   # Rules are automatically uploaded from sample-rules/ via init-localstack.sh
   # Or upload manually:
   aws --endpoint-url=http://localhost:4566 s3 sync sample-rules/ s3://local-rules/

   # Verify rules uploaded (should show 17 .drl files)
   aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules/ --recursive
   ```

4. **Set Development Environment**
   ```bash
   export RULE_SOURCE=s3
   export AWS_ENDPOINT=http://localhost:4566
   export REDIS_ENABLED=true
   ```

5. **Run Application**
   ```bash
   mvn spring-boot:run -Dspring.profiles.active=dev
   ```

### Code Quality

```bash
# Format code
mvn spotless:apply

# Check formatting
mvn spotless:check

# Static analysis
mvn compile spotbugs:check

# Dependency vulnerabilities
mvn dependency-check:check
```

### Hot Reloading Rules

Rules can be updated without restarting the application:

```bash
# Upload new rule to S3
aws --endpoint-url=http://localhost:4566 s3 cp new-rule.drl s3://local-rules/pricing/discount/

# Refresh specific rule
curl -X POST http://localhost:8080/admin/refresh-rules/pricing.discount.new-rule

# Or refresh all rules
curl -X POST http://localhost:8080/admin/refresh-rules
```

## 🧪 Testing

### Unit Tests
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=DroolsEngineServiceTest

# Run with coverage
mvn test jacoco:report
```

### Integration Tests
```bash
# Run integration tests
mvn test -Dtest=*IntegrationTest

# Run with LocalStack
mvn test -Dtest=S3StorageIntegrationTest
```

### Quick E2E & Load Test

Run this whenever you want to verify the full stack is healthy — rules loading from S3, execution working, hot reload uninterrupted, memory stable.

#### One-command option

The script [`scripts/e2e-load-test.sh`](scripts/e2e-load-test.sh) runs the entire sequence below unattended:

```bash
# Full run (tears down stack at end)
./scripts/e2e-load-test.sh

# Keep stack running after test (useful for debugging)
./scripts/e2e-load-test.sh --no-teardown

# Skip docker compose build if image is already current
./scripts/e2e-load-test.sh --skip-build
```

The script patches `docker-compose.yml` to disable rate limiting for the load test and restores it automatically on exit (even on failure or Ctrl-C). Exits non-zero if any check fails — CI-friendly.

**Baseline from 2026-05-11**: 157,754 requests, **0 errors (0%)**, ~518 RPS, heap stable 180–340 MB, hot reload at 2 min with 0 dropped requests.

---

Or run the steps manually:

#### Step 1 — Start the stack

```bash
docker compose up -d
```

Wait for the app to be ready (usually instant if images are cached):

```bash
curl http://localhost:8081/actuator/health   # should return {"status":"UP"}
```

#### Step 2 — Upload sample rules to LocalStack S3

```bash
./init-localstack.sh
```

This creates the `local-rules` bucket and uploads all 17 sample DRL files.

#### Step 3 — Load rules into the engine

```bash
curl -s -X POST http://localhost:8080/admin/refresh-rules \
  -H "X-Admin-API-Key: admin-secret" | jq '{status, rules_loaded, rules_failed}'
# Expected: { "status": "completed", "rules_loaded": 17, "rules_failed": 0 }
```

#### Step 4 — Smoke test a few rules

```bash
# Simple discount
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":150,"customer_tier":"gold"}}' | jq .

# Bulk discount
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id":"pricing.discount.bulk","data":{"amount":500,"quantity":20}}' | jq .

# Age validation
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id":"validation.customer.age","data":{"age":25,"customer_id":"cust-001"}}' | jq .
```

#### Step 5 — Hot reload test

```bash
# Reload all rules and verify 0 failures
curl -s -X POST http://localhost:8080/admin/refresh-rules \
  -H "X-Admin-API-Key: admin-secret" | jq '{status, rules_loaded, rules_failed, duration_ms}'

# Confirm rules still execute after reload
curl -s -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100,"customer_tier":"gold"}}' | jq .
```

#### Step 6 — Memory stability check (5 rapid reloads)

```bash
for i in 1 2 3 4 5; do
  curl -s -X POST http://localhost:8080/admin/refresh-rules \
    -H "X-Admin-API-Key: admin-secret" > /dev/null
  sleep 2
  curl -s http://localhost:8080/admin/memory/info | \
    python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Reload $i: heap={d[\"heap\"][\"usedMB\"]}MB ({d[\"heap\"][\"usagePercent\"]}%)')"
done
```

Heap should oscillate (G1GC collecting between reloads) — not grow monotonically. After 5 reloads, trigger a GC and verify:

```bash
curl -s -X POST http://localhost:8080/admin/memory/gc -H "X-Admin-API-Key: admin-secret" > /dev/null
sleep 3
curl -s http://localhost:8080/admin/memory/info | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Post-GC: {d[\"heap\"][\"usedMB\"]}MB / {d[\"heap\"][\"maxMB\"]}MB ({d[\"heap\"][\"usagePercent\"]}%)')"
# Expected: < 100MB after GC (no leak)
```

#### Step 7 — 5-minute load test with hot reload mid-run

> **Note:** The default rate limit is 1000 req/min per client. For load testing from a single machine (all requests share one IP), temporarily disable it:
>
> In `docker-compose.yml`, add `- DROOLS_RATE_LIMITING_ENABLED=false` under the app's `environment:` block, then `docker compose stop app && docker compose up -d app`. Remove it again when done.

```bash
# Save this as /tmp/loadtest.sh and run: bash /tmp/loadtest.sh
DURATION=300; WORKERS=20; BASE_URL="http://localhost:8080/execute-rule"
PAYLOADS=(
  '{"rule_id":"pricing.discount.simple","data":{"amount":150,"customer_tier":"gold"}}'
  '{"rule_id":"pricing.discount.bulk","data":{"amount":500,"quantity":20}}'
  '{"rule_id":"seasonal.holiday.blackfriday","data":{"amount":200,"customer_tier":"silver"}}'
  '{"rule_id":"validation.customer.age","data":{"age":30,"customer_id":"cust-100"}}'
  '{"rule_id":"pricing.shipping.standard","data":{"amount":80,"weight":2.5}}'
  '{"rule_id":"pricing.discount.vip","data":{"amount":300,"customer_tier":"vip"}}'
)
TMPDIR_LT=$(mktemp -d); START=$(date +%s); END=$((START + DURATION)); RELOAD_DONE=0

worker() {
  local pidx=$(( $1 % 6 )); local payload="${PAYLOADS[$pidx]}"
  while [ $(date +%s) -lt $END ]; do
    HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL" \
      -H "Content-Type: application/json" -d "$payload" --max-time 5)
    if [ "$HTTP" = "200" ]; then echo 1 >> $TMPDIR_LT/s; else echo "$HTTP" >> $TMPDIR_LT/e; fi
  done
}

echo "=== Load test: $WORKERS workers × ${DURATION}s ==="
for w in $(seq 1 $WORKERS); do worker $w & done

LAST=0
while [ $(date +%s) -lt $END ]; do
  NOW=$(date +%s); ELAPSED=$((NOW - START))
  if [ $RELOAD_DONE -eq 0 ] && [ $ELAPSED -ge 120 ]; then
    echo ""; echo "  >>> HOT RELOAD at ${ELAPSED}s <<<"
    curl -s -X POST http://localhost:8080/admin/refresh-rules \
      -H "X-Admin-API-Key: admin-secret" | \
      python3 -c "import sys,json; d=json.load(sys.stdin); print(f'rules={d[\"rules_loaded\"]} failed={d[\"rules_failed\"]} ms={d[\"duration_ms\"]}')"
    RELOAD_DONE=1
  fi
  if [ $((NOW - LAST)) -ge 30 ] && [ $ELAPSED -gt 0 ]; then
    SUC=$(wc -l < $TMPDIR_LT/s 2>/dev/null | tr -d ' '); SUC=${SUC:-0}
    ERR=$(wc -l < $TMPDIR_LT/e 2>/dev/null | tr -d ' '); ERR=${ERR:-0}
    TOT=$((SUC + ERR)); RPS=$((TOT / ELAPSED))
    MEM=$(curl -s http://localhost:8080/admin/memory/info 2>/dev/null | \
      python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['heap']['usedMB']}MB ({d['heap']['usagePercent']}%)\")" 2>/dev/null || echo "?")
    echo "  [${ELAPSED}s] success=$SUC errors=$ERR rps~$RPS heap=$MEM"; LAST=$NOW
  fi
  sleep 5
done

wait; ELAPSED=$(($(date +%s) - START))
SUC=$(wc -l < $TMPDIR_LT/s 2>/dev/null | tr -d ' '); SUC=${SUC:-0}
ERR=$(wc -l < $TMPDIR_LT/e 2>/dev/null | tr -d ' '); ERR=${ERR:-0}
TOT=$((SUC + ERR))
ERR_PCT=$(echo "scale=2; $ERR * 100 / $TOT" | bc 2>/dev/null || echo "0")
echo ""; echo "=== RESULTS: ${ELAPSED}s | total=$TOT success=$SUC errors=$ERR ($ERR_PCT%) rps~$((TOT/ELAPSED)) ==="
echo "Final heap: $(curl -s http://localhost:8080/admin/memory/info | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['heap']['usedMB']}MB ({d['heap']['usagePercent']}%)\")")"
rm -rf $TMPDIR_LT
```

#### Step 8 — Tear down

```bash
docker compose down
```

### Testing Different Storage Backends

```bash
# Test with memory storage
export RULE_SOURCE=memory
mvn spring-boot:run

# Test with file storage
export RULE_SOURCE=local
export RULE_BASE_PATH=./test-rules
mvn spring-boot:run

# Test with S3 storage
export RULE_SOURCE=s3
mvn spring-boot:run
```

## 🚀 Deployment

### Docker Deployment

#### Option 1: Quick Docker Run

```bash
# Build Docker image (optimized 347MB)
docker build -t drools-rule-engine:latest .

# Test Docker build (validates image and health checks)
./scripts/docker-build-test.sh

# Run with environment variables
docker run -p 8080:8080 -p 8081:8081 \
  -e RULE_SOURCE=s3 \
  -e RULE_BUCKET_NAME=prod-rules \
  -e AWS_ACCESS_KEY_ID=your-key \
  -e AWS_SECRET_ACCESS_KEY=your-secret \
  drools-rule-engine:latest
```

#### Option 2: Full Stack with Docker Compose

```bash
# Start complete development environment (LocalStack + Redis + Application)
docker-compose up -d

# Initialize LocalStack S3 bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://local-rules

# Check all services are running
docker-compose ps

# View logs
docker-compose logs -f

# Stop services
docker-compose down
```

#### Docker Image Details

- **Base Image**: Amazon Corretto 25 Alpine (JDK)
- **Final Image Size**: ~347MB (optimized multi-stage build)
- **Security**: Runs as non-root user (`appuser`)
- **Health Checks**: Built-in HTTP health endpoint monitoring
- **JVM Optimization**: Container-aware memory settings with G1GC

### Production Environment Variables

```bash
# Storage
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=production-rules
export AWS_REGION=us-east-1

# Caching
export REDIS_ENABLED=true
export REDIS_URL=redis://prod-redis:6379
export LRU_CACHE_MAX_SIZE=500

# Performance
export RULE_EXECUTION_TIMEOUT_SECONDS=10
export DROOLS_THREAD_POOL_MAX_SIZE=100
export AWS_S3_MAX_CONNECTIONS=100
export JAVA_OPTS="-Xmx2g -Xms1g -XX:+UseG1GC"

# Security
export DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=5000
export DROOLS_VALIDATION_DATA_MAX_FIELDS=500
export MAX_HTTP_REQUEST_SIZE=50MB

# Monitoring
export LOG_LEVEL=WARN
export MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics
```

### Health Checks

Configure your load balancer to use:
- **Liveness**: `GET /admin/health`
- **Readiness**: `GET /admin/health` (same endpoint)

### Scaling Considerations

- **Horizontal Scaling**: Stateless design supports multiple instances
- **Cache Warming**: Use Redis for shared cache across instances
- **Rule Updates**: Use `/admin/refresh-rules` across all instances
- **Storage**: S3 provides unlimited rule storage capacity

## 📈 Monitoring

### Metrics Endpoints

```bash
# Application health with component status
curl http://localhost:8080/admin/health

# Thread pool statistics
curl http://localhost:8080/admin/thread-pools

# Cache statistics
curl http://localhost:8080/admin/health | jq '.components.cache.details'

# Circuit breaker status
curl http://localhost:8080/admin/health | jq '.components."circuit-breakers"'

# Rule performance metrics
curl http://localhost:8080/admin/rules | jq '.rules[].avg_execution_time_ms'
```

### Performance Targets

- **Throughput**: 100-1000 requests/second
- **Latency**: P99 < 100ms (cached), P99 < 500ms (cache miss)
- **Availability**: 99.9% uptime
- **Cache Hit Ratio**: > 90%

### Rule Capacity & Memory Sizing

There are two limits to understand: the **LRU cache cap** (how many compiled rules stay hot in memory) and the **heap limit** (how many can fit before GC pressure becomes a problem).

**LRU cache cap** — controlled by `LRU_CACHE_MAX_SIZE` (default `100`). Rules beyond this are evicted and re-compiled on next access. Raise this freely; the only real constraint is heap.

**Heap capacity** — compiled `KieBase` objects vary in size by rule complexity. With the default `-Xmx2048m` (2 GB):

| Rule complexity | Approx compiled KieBase size | Comfortable fit in 2 GB heap |
|---|---|---|
| Simple (1–2 conditions) | ~0.5 MB | ~1,500–2,000 rules |
| Medium (accumulate, joins) | ~2–3 MB | ~400–600 rules |
| Complex (forall, multi-join, salience chains) | ~5–10 MB | ~150–300 rules |

**Load-test baseline (2026-05-11):** 1,000 synthetic rules compiled in ~46s, heap settled at ~47 MB post-GC, peak during compilation ~400–600 MB.

To increase capacity, raise both `LRU_CACHE_MAX_SIZE` and `-Xmx` together:

```bash
# Example: 500 complex rules on a 4 vCPU / 8 GB instance
LRU_CACHE_MAX_SIZE=500
JAVA_OPTS="-Xms1g -Xmx6g -XX:+UseG1GC"
```

S3 storage has no rule count limit — only the JVM heap constrains what is compiled and cached locally per instance.

### Alerting

Monitor these metrics:
- High error rate (> 1%)
- High latency (P99 > 200ms)
- Low cache hit ratio (< 80%)
- Circuit breakers open/half-open
- Thread pool exhaustion
- Rate limiting violations
- Storage connectivity issues
- Memory usage (> 80%)

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/new-feature`
3. Commit changes: `git commit -am 'Add new feature'`
4. Push to branch: `git push origin feature/new-feature`
5. Submit a pull request

### Development Guidelines

- Follow Java coding standards
- Write unit tests for new features
- Update documentation
- Run `mvn spotless:apply` before committing
- Ensure all tests pass: `mvn test`

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Troubleshooting

### Docker Issues

#### 1. Docker build fails
```bash
# Clear Docker cache and rebuild
docker system prune -a
docker build --no-cache -t drools-rule-engine:latest .

# Check Docker daemon is running
docker info

# Verify Dockerfile syntax
./scripts/docker-build-test.sh
```

#### 2. Container startup issues
```bash
# Check container logs
docker logs <container-id>

# Run container with debugging
docker run -it --entrypoint /bin/sh drools-rule-engine:latest

# Check port conflicts
lsof -i :8080
lsof -i :8081
```

#### 3. Docker Compose issues
```bash
# Check all services status
docker-compose ps

# View service logs
docker-compose logs app
docker-compose logs localstack
docker-compose logs redis

# Restart specific service
docker-compose restart app

# Clean restart
docker-compose down && docker-compose up -d
```

### Common Issues

#### 1. Application won't start
```bash
# Using Docker Compose (recommended)
docker-compose logs app

# Local development - check Java version
java -version  # Should be 25

# Check if ports are available
lsof -i :8080
lsof -i :8081

# Check LocalStack is running
docker ps | grep localstack
```

#### 2. S3 connection errors
```bash
# Verify LocalStack is accessible
aws --endpoint-url=http://localhost:4566 s3 ls

# Check bucket exists
aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules

# Verify environment variables
echo $AWS_ENDPOINT
echo $RULE_BUCKET_NAME

# Test with docker-compose services
docker-compose exec app curl http://localstack:4566
```

#### 3. Redis connection issues
```bash
# Check Redis is running
docker ps | grep redis

# Test Redis connectivity
redis-cli -h localhost -p 6379 ping

# Test from application container
docker-compose exec app wget -qO- redis:6379

# Disable Redis if problematic
export REDIS_ENABLED=false
```

#### 4. Rule compilation errors
```bash
# Check rule syntax via Docker
docker-compose exec app curl http://localhost:8080/admin/rules

# View detailed error logs
docker-compose logs app | grep ERROR

# Refresh specific problematic rule
curl -X POST http://localhost:8080/admin/refresh-rules/problematic.rule.id
```

#### 5. Image size or performance issues
```bash
# Check Docker image size (should be ~347MB)
docker images drools-rule-engine:latest

# Monitor container resources
docker stats

# Check JVM memory usage in container
docker-compose exec app jstat -gc 1
```

### Getting Help

- Check existing issues: [GitHub Issues](https://github.com/your-repo/issues)
- Review logs: `tail -f logs/application.log`
- Verify configuration: `curl http://localhost:8080/admin/health`
- Test with memory storage: `export RULE_SOURCE=memory`

## 📞 Support

For support and questions:
- Create an issue in this repository
- Check the [project documentation](project-documentation/00-system-overview.md) — entry point for all 40 docs
- Review the [troubleshooting guide](project-documentation/31-troubleshooting.md) and [FAQ](project-documentation/35-faq.md)