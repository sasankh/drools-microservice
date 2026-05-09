# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 🛑 Read these files first (every session, before anything else)

### AI workflow protocols (required before responding to commands)
1. `ai-instructions/snap-memory-instructions.md` — how to handle the `snap-memory` command
2. `ai-instructions/ai-initial-context-instructions.md` — how to handle the `ai-context-update` command

### Project documentation (the canonical reference)
3. **`project-documentation/00-system-overview.md`** — entry point for all 39 docs. Has role-based reading paths.

The full documentation corpus is in `project-documentation/`. See [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md) for the index and reading paths by role.

The legacy consolidated context file at `.ai-workspace/ai-initial-context/ai-initial-context-latest.md` is no longer the primary reference — the structured `project-documentation/` corpus supersedes it.

## ⚠️ Important: Security Hardening Complete (2026-02-26)

**39/42 security findings addressed** across 9 phases. Key changes:

1. **Admin Authentication** — `/admin/*` endpoints protected by `X-Admin-API-Key` header (env: `ADMIN_API_KEY`)
2. **DRL Sandboxing** — `DrlSanitizer` blocks dangerous imports/classes/methods before compilation
3. **Security Headers** — 7 headers on all responses (CSP, HSTS, X-Frame-Options, etc.)
4. **CORS Default Changed** — Default is now empty (no CORS); wildcard only in local/dev/docker profiles
5. **Rate Limiting Hardened** — X-Forwarded-For ignored, uses `request.getRemoteAddr()` only; maxClients cap
6. **Jackson RCE Fixed** — `activateDefaultTyping()` with strict `BasicPolymorphicTypeValidator`
7. **Path Traversal Protection** — Defense-in-depth in LocalFileStorage and S3RuleStorage
8. **Non-blocking Compilation** — Rule compilation outside write lock in DroolsEngineService

### Previous Critical Fixes (2026-02-19)
- **Java 17 Enforcement**: Maven Enforcer Plugin
- **Memory Leak Fixed**: KieContainer disposal prevents OOM
- **Memory Monitoring**: `GET /admin/memory/info` endpoint

See [`project-documentation/14-security-architecture.md`](project-documentation/14-security-architecture.md) and [`project-documentation/30-runbooks-and-monitoring.md`](project-documentation/30-runbooks-and-monitoring.md) for full details.

## Project Overview

This is a Drools Rule Engine Microservice designed for high-performance business rule execution (100-1000 RPS). Rules are stored in AWS S3 and executed via REST API.

**Tech Stack**: Java 25 (enforced), Spring Boot 3.5.3, Drools 10.2.0, AWS S3, Redis (optional), Micrometer, Resilience4j, Docker & Docker Compose, AWS ECS

**Health Status**: 9/10 - 589 tests, 96%/90% coverage, 39/42 security fixes complete

## Common Commands

### Java 25 Setup (Required for Local Development)

**IMPORTANT**: Java 25 is required. Maven will enforce this automatically.

```bash
# Option 1: Use the setup script (temporary for current terminal)
source ./set-java-env.sh

# Option 2: Permanent setup (add to ~/.zshrc or ~/.bashrc)
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"

# Verify Java 25 is active
java -version   # Should show "openjdk version 25.x.x"
mvn -version    # Should show "Java version: 25.x.x"
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

1. **Current Status**: 39/42 security findings addressed (Phases 1–9 complete, 2026-02-26); 589 tests; 96% instruction / 90% branch coverage; documentation rebuild complete (2026-05-08, 39 numbered docs in [`project-documentation/`](project-documentation/)). Canonical overview: [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md).

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

All seven phases are shipped:
1. ✅ Core Infrastructure (Spring Boot + Drools)
2. ✅ Storage & Caching (S3 + Redis)
3. ✅ Production Readiness (Security, Performance, Monitoring)
4. ✅ Testing & Documentation
5. ✅ Deployment & Infrastructure
6. ✅ Critical Fixes — Java 17 enforcement, memory leak, monitoring (2026-02-19)
7. ✅ Security Hardening — 39/42 findings, Phases 1–9 (2026-02-26)

Current snapshot:
- **Health Score**: 9/10
- **Test Coverage**: 96.2% instruction / 89.7% branch (589 tests)
- **Security**: 39/42 findings addressed
- **Performance**: 100–1000 RPS target, P99 < 100ms cached / < 500ms cache miss

For the canonical narrative — phase history, ADRs, performance targets, testing strategy, runbooks — see [`project-documentation/00-system-overview.md`](project-documentation/00-system-overview.md) and the 39 numbered docs it indexes.

## Important Project Files

- [`project-documentation/`](project-documentation/) — full 39-doc corpus, the canonical reference
- [`set-java-env.sh`](set-java-env.sh) — Java 25 environment setup script
- [`docker-build-test.sh`](docker-build-test.sh) — automated Docker build and validation
- [`setup-dev-environment.sh`](setup-dev-environment.sh) — one-command local dev setup
- [`init-localstack.sh`](init-localstack.sh) — LocalStack bootstrap (reads from `sample-rules/`)
- [`sample-rules/`](sample-rules/) — 10 sample DRL files (single source of truth)
- `gc-logs/`, `heap-dumps/` — runtime diagnostics output (gitignored content)
- [`.ai-workspace/snap-memory/`](.ai-workspace/snap-memory/) — session memory files for `snap-memory` AI workflow

## Quick development setup

```bash
# One-command setup (recommended)
./setup-dev-environment.sh

# View logs and test
docker-compose logs -f app
curl http://localhost:8080/admin/health
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "pricing.discount.simple", "data": {"amount": 100}}'

# Stop
docker-compose down
```

For full deployment scenarios, configuration reference, troubleshooting, and runbooks, see [`project-documentation/`](project-documentation/) — start at [`00-system-overview.md`](project-documentation/00-system-overview.md).