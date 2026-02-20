# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## ⚠️ Important: Recent Critical Fixes (2026-02-19)

**CRITICAL**: Two major issues were fixed in this session:

1. **Java Version Enforcement** ✅
   - Maven Enforcer Plugin now enforces Java 17
   - Build will fail if using wrong Java version
   - Use `source ./set-java-env.sh` for local development

2. **Memory Leak Fixed** ✅
   - KieContainer disposal prevents OOM errors (exit code 137)
   - Fixed in `DroolsEngineService.java` lines 164-178
   - Memory now stable, can run indefinitely

3. **Memory Monitoring Added** ✅
   - New endpoint: `GET /admin/memory/info`
   - Real-time memory diagnostics with warnings
   - Heap dumps on OOM: `./heap-dumps/`
   - GC logs: `./gc-logs/`

**Files Modified**:
- `pom.xml` - Maven Enforcer Plugin added
- `docker-compose.yml` - Memory diagnostics configured
- `DroolsEngineService.java` - KieContainer disposal logic
- `MemoryController.java` - NEW monitoring endpoint

See `FIXES-SUMMARY.md` for complete details.

## Project Overview

This is a Drools Rule Engine Microservice designed for high-performance business rule execution (100-1000 RPS). Rules are stored in AWS S3 and executed via REST API.

**Tech Stack**: Java 17 (enforced), Spring Boot 3.x, Drools 8.44.0.Final, AWS S3, Redis (optional), Micrometer, Resilience4j, Docker & Docker Compose, AWS ECS

**Health Status**: 8.5/10 - 550 tests, 96%/90% coverage, all critical fixes complete

## Common Commands

### Java 17 Setup (Required for Local Development)

**IMPORTANT**: Java 17 is required. Maven will enforce this automatically.

```bash
# Option 1: Use the setup script (temporary for current terminal)
source ./set-java-env.sh

# Option 2: Permanent setup (add to ~/.zshrc or ~/.bashrc)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"

# Verify Java 17 is active
java -version   # Should show "openjdk version 17.x.x"
mvn -version    # Should show "Java version: 17.x.x"
```

### Build & Run (once project is initialized)

#### Docker Development (Recommended)
```bash
# Start complete development stack (LocalStack + Redis + Application)
docker-compose up -d

# Build optimized Docker image (347MB)
docker build -t drools-rule-engine .

# Test Docker build and validation
./docker-build-test.sh

# View application logs
docker-compose logs -f app

# Stop all services
docker-compose down
```

#### Local Java Development
```bash
# Build
mvn clean package

# Run locally with development profile
mvn spring-boot:run -Dspring.profiles.active=dev

# Run with specific profile
mvn spring-boot:run -Dspring.profiles.active=local
```

### Testing
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=DroolsEngineServiceTest

# Run with coverage
mvn test jacoco:report

# Integration tests only
mvn test -Dtest=*IntegrationTest

# Skip tests during build
mvn clean package -DskipTests
```

### Code Quality
```bash
# Format code
mvn spotless:apply

# Check formatting
mvn spotless:check

# Run static analysis
mvn compile spotbugs:check
```

## Architecture Overview

### Package Structure
```
com.company.drools/
├── api/               # REST endpoints, DTOs, validation, filters
│   ├── controller/    # REST endpoints
│   ├── dto/          # Data transfer objects
│   ├── exception/    # Exception handlers
│   ├── validation/   # Custom validation annotations
│   └── filter/       # Security and request filters
├── core/              # Business logic and rule engine
├── storage/           # S3 and file storage implementations  
├── cache/             # LRU and Redis caching
├── common/            # Shared utilities (log sanitization)
└── config/            # Spring configuration classes
```

### Key Architectural Decisions

1. **Rule Storage**: Rules are stored as .drl files in S3 with hierarchical organization (e.g., `pricing/discount/black-friday.drl`)

2. **Caching Strategy**: 
   - S3 → Redis (optional) → Local LRU Cache → Rule Execution
   - Compiled KieBase objects cached locally for performance

3. **API Design**:
   - Main API on port 8080 (`/execute-rule`)
   - Admin API on port 8080 (`/admin/*`) — same port as main API
   - Actuator API on port 8081 (`/actuator/*`) — Spring Boot management port

4. **Thread Safety**: Each rule execution uses a new KieSession (stateless)

5. **Rule ID Transformation**: `pricing.discount.black-friday` → `pricing/discount/black-friday.drl`

6. **Security Architecture**: 
   - Multi-layer input validation with custom annotations
   - Rate limiting with configurable per-client limits
   - Request size protection and CORS configuration
   - Log sanitization to prevent sensitive data exposure

7. **Performance Architecture**:
   - Connection pooling for S3 and HTTP clients
   - Custom thread pools for rule execution and storage operations
   - Circuit breakers for external service fault tolerance
   - Request timeout handling and monitoring

8. **Monitoring Architecture**:
   - Vendor-agnostic metrics with Micrometer
   - Structured JSON logging with correlation IDs
   - Component health checks (Drools, S3, Redis, Circuit Breakers)
   - Thread pool and performance monitoring endpoints

9. **Docker Architecture**:
   - Multi-stage Docker build (Maven build → Amazon Corretto Alpine runtime)
   - Optimized image size: ~347MB with security hardening
   - Non-root user execution, health checks, JVM container optimization
   - Complete docker-compose dev stack with LocalStack S3 and Redis

## Environment Variables

Critical environment variables for local development:
```bash
# Rule source
RULE_SOURCE=s3                    # or 'local' for development
RULE_BUCKET_NAME=local-rules      # S3 bucket name
AWS_ENDPOINT=http://localhost:4566 # LocalStack endpoint

# Redis (optional)
REDIS_ENABLED=false
REDIS_URL=redis://localhost:6379

# Performance
LRU_CACHE_MAX_SIZE=100
RULE_EXECUTION_TIMEOUT_SECONDS=30
DROOLS_THREAD_POOL_MAX_SIZE=50
AWS_S3_MAX_CONNECTIONS=50

# Security
DROOLS_VALIDATION_DATA_MAX_FIELDS=100
DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=1000
DROOLS_CORS_ALLOWED_ORIGINS=*
MAX_HTTP_REQUEST_SIZE=10MB

# Monitoring
DROOLS_CB_S3_FAILURE_RATE=50
DROOLS_HTTP_CONNECTION_TIMEOUT=10

# Docker
SPRING_PROFILES_ACTIVE=docker        # Container profile
JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

## Development Workflow

1. **Current Status**: Phase 5.2 Complete! Full deployment infrastructure with Docker containerization, LocalStack S3 integration, 10 sample rules, and one-command development setup. Ready for project completion or additional phases. See `project.progress.md` for details.

2. **One-Command Development Environment**: Complete automated setup with validation
   ```bash
   # Complete automated setup (recommended)
   ./setup-dev-environment.sh
   
   # Or manual setup
   docker-compose up -d
   
   # Initialize LocalStack S3 with 10 sample rules
   ./init-localstack.sh
   
   # Validate LocalStack setup
   ./test-localstack.sh
   
   # Check all services status
   docker-compose ps
   ```

3. **Rule Development**: Rules are Drools .drl files. Example structure:
   ```drools
   package com.company.rules.pricing.discount
   
   rule "Rule Name"
   when
       $data : Map(...)
   then
       // Business logic
   end
   ```

4. **Docker Validation**: Comprehensive testing and validation
   ```bash
   # Build and test Docker image
   ./docker-build-test.sh

   # Manual Docker validation steps
   docker build -t drools-rule-engine:latest .
   docker images drools-rule-engine:latest  # Should be ~347MB

   # Test container startup and health
   docker run -d --name test-container -p 9080:8080 -p 9081:8081 \
     -e RULE_SOURCE=memory drools-rule-engine:latest
   curl http://localhost:9081/admin/health
   docker stop test-container && docker rm test-container
   ```

5. **Memory Monitoring** (NEW): Real-time memory diagnostics
   ```bash
   # Check current memory status
   curl http://localhost:8080/admin/memory/info | jq

   # Monitor heap usage
   curl -s http://localhost:8080/admin/memory/info | jq '.heap'

   # Real-time monitoring (updates every 5 seconds)
   watch -n 5 'curl -s http://localhost:8080/admin/memory/info | jq ".heap.usagePercent"'

   # Test memory leak fix (should remain stable)
   for i in {1..10}; do
       echo "Refresh $i/10"
       curl -X POST http://localhost:8080/admin/refresh-rules
       sleep 3
       curl -s http://localhost:8080/admin/memory/info | jq '.heap.usedMB'
   done
   # Memory should NOT grow by 10-100MB each refresh

   # Check GC logs
   tail -f gc-logs/gc.log

   # Check heap dumps (if OOM occurred)
   ls -lh heap-dumps/
   ```

## Implementation Status

Check `project.progress.md` and `FIXES-SUMMARY.md` for current status. Project follows these phases:
1. ✅ Core Infrastructure (Spring Boot + Drools setup) - COMPLETED
2. ✅ Storage & Caching (S3 + Redis) - COMPLETED
3. ✅ Production Readiness (Security, Performance, Monitoring) - COMPLETED
4. ✅ Testing & Documentation (Phase 4.4 only) - COMPLETED
5. ✅ Deployment & Infrastructure - COMPLETED
6. ✅ Critical Fixes (Java 17, Memory Leak, Monitoring) - COMPLETED 2026-02-19

**Current Status**:
- **Health Score**: 8.5/10
- **Critical Issues**: FIXED ✅ (Java version, memory leak)
- **Test Coverage**: 96.2% instruction / 89.7% branch (550 tests) ✅
- **Scripts**: `init-localstack.sh` refactored — reads from `sample-rules/` (no hardcoded DRL)

## Performance Targets

- 100-1000 requests/second
- P99 latency < 100ms (cached rules)
- P99 latency < 500ms (cache miss)
- Support 1000+ concurrent rules

## Testing Strategy

- Unit tests: 70% (focus on rule compilation, caching, transformations)
- Integration tests: 20% (LocalStack for S3, embedded Redis)
- Performance tests: 10% (JMeter for load testing)

## Important Project Files

**Project Planning**:
- `project.checklist.md`: Detailed task breakdown (85+ core tasks, 80 completed)
- `project.progress.md`: Track implementation progress (Phase 5.2 complete)
- `project.documentation.md`: Comprehensive project specifications
- `project.prompt.md`: Original implementation requirements
- `project-improvement-plan.md`: 3-week improvement roadmap (NEW)

**Recent Fixes** (2026-02-19):
- `FIXES-SUMMARY.md`: Summary of critical fixes applied (NEW)
- `MEMORY-LEAK-ANALYSIS.md`: Deep dive into memory leak issue (NEW)
- `project-revisit-analysis.md`: Complete project health assessment (NEW)
- `set-java-env.sh`: Java 17 environment setup script (NEW)

**Session Documentation**:
- `snap-memory/`: Session memory files documenting implementation progress
- `snap-memory/snap-memory-1771549384.md`: Latest session (critical fixes)

**Docker & Validation**:
- `docker-build-test.sh`: Automated Docker build and validation script
- `docker-validation.md`: Comprehensive Docker validation checklist
- `heap-dumps/`: OOM heap dumps for analysis
- `gc-logs/`: GC logs for performance tuning

## Recent Completions 

### Phase 5 - Deployment & Infrastructure (COMPLETED)

#### Phase 5.1 - Docker Setup ✅
- **Multi-stage Docker Build**: Maven build stage + Amazon Corretto Alpine runtime
- **Optimized Image**: 347MB final image size with security hardening
- **Container Security**: Non-root user execution, health checks, resource limits
- **JVM Optimization**: Container-aware memory settings with G1GC
- **Docker Compose Stack**: Complete development environment with LocalStack + Redis
- **Validation Automation**: `docker-build-test.sh` for automated testing and health checks
- **Docker Configuration**: Enhanced `.dockerignore` with security exclusions

#### Phase 5.2 - Local Development Environment ✅
- **LocalStack S3 Integration**: Complete S3 emulation with automatic bucket setup
- **10 Sample Business Rules**: Comprehensive rule suite (pricing, validation, seasonal)
- **Automation Scripts**: `init-localstack.sh` (reads from `sample-rules/`), `test-localstack.sh`, `setup-dev-environment.sh`
- **One-Command Setup**: Complete development environment with single script execution
- **Sample Rule Documentation**: Complete usage guide with API examples

### Phase 4.4 - Documentation (COMPLETED)
- **Complete Documentation Suite**: ~3,900 lines across 5 comprehensive guides
- **OpenAPI 3.0 Specification**: All 8 endpoints with schemas and examples
- **Deployment Guide**: Local, Docker, AWS deployment scenarios
- **Configuration Reference**: 60+ environment variables documented
- **Rule Development Guide**: 5 complete rule examples with best practices
- **Troubleshooting Guide**: 50+ solutions with emergency recovery procedures

### Phase 3 - Production Readiness (COMPLETED)

### Security Hardening (Phase 3.4)
- **Input Validation Framework**: Custom Spring Boot validation annotations with environment-configurable limits
- **Rate Limiting System**: In-memory rate limiting with per-client tracking and HTTP headers
- **Request Size Protection**: Multi-layer size limits (Spring Boot + custom filters)
- **CORS Configuration**: Flexible cross-origin policy (allow-all default, production configurable)
- **Log Sanitization**: Comprehensive sensitive data detection and masking (credit cards, SSNs, etc.)

### Performance Optimization (Phase 3.3)
- **Connection Pooling**: AWS S3 client with Apache HTTP client connection pooling
- **Thread Pool Management**: Custom thread pools for rule execution and storage operations
- **JVM Optimization**: G1GC configuration with environment-specific tuning
- **Request Timeout Handling**: Comprehensive timeout management with monitoring
- **Circuit Breakers**: Resilience4j integration for S3 and Redis fault tolerance

### Monitoring & Observability (Phase 3.2)
- **Vendor-agnostic Metrics**: Micrometer integration supporting CloudWatch, Grafana, Datadog
- **Structured JSON Logging**: Correlation IDs, MDC context, and environment profiles
- **Enhanced Health Checks**: Component-level monitoring (Drools, S3, Redis, Circuit Breakers)
- **Thread Pool Monitoring**: Real-time statistics via `/admin/thread-pools` endpoint
- **Performance Metrics**: Rule execution timing, cache statistics, error tracking

### Development Setup

#### Option 1: One-Command Setup (Recommended)
```bash
# Complete automated setup (build + validate + start)
./setup-dev-environment.sh

# View application logs
docker-compose logs -f app

# Test health and admin endpoints
curl http://localhost:8080/admin/health           # Enhanced health with components
curl http://localhost:8080/admin/rules            # Rule list with metadata and 10 sample rules
curl http://localhost:8080/admin/thread-pools     # Thread pool statistics

# Test main API with sample rules
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100}}'

# Test VIP customer rule
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.vip", "data": {"customerType": "VIP", "amount": 100}}'

# Stop all services
docker-compose down
```

#### Option 2: Manual Docker Compose Setup
```bash
# Start infrastructure services first
docker-compose up -d localstack redis

# Initialize LocalStack with sample rules
./init-localstack.sh

# Validate LocalStack setup
./test-localstack.sh

# Start application service
docker-compose up -d app

# Or run application locally
mvn compile && mvn spring-boot:run -Dspring.profiles.active=dev

# Same testing commands as above
```

#### Option 3: Local Java Development
```bash
# Start infrastructure services only
docker-compose up -d localstack redis

# Initialize LocalStack with sample rules
./init-localstack.sh

# Build and run application locally
mvn compile && mvn spring-boot:run -Dspring.profiles.active=dev

# Test with sample rules
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100}}'
```

#### Option 4: Docker Build Validation
```bash
# Build and validate Docker image
./docker-build-test.sh

# Manual validation
docker build -t drools-rule-engine:latest .
docker images drools-rule-engine:latest  # Check size (~347MB)
```