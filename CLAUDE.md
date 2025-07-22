# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Drools Rule Engine Microservice designed for high-performance business rule execution (100-1000 RPS). Rules are stored in AWS S3 and executed via REST API.

**Tech Stack**: Java 17, Spring Boot 3.x, Drools 8.44.0.Final, AWS S3, Redis (optional), Docker, AWS ECS

## Common Commands

### Build & Run (once project is initialized)
```bash
# Build
mvn clean package

# Run locally
mvn spring-boot:run

# Run with specific profile
mvn spring-boot:run -Dspring.profiles.active=local

# Build Docker image
docker build -t drools-rule-engine .

# Run with docker-compose (includes LocalStack and Redis)
docker-compose up
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
├── api/               # REST endpoints and DTOs
├── core/              # Business logic and rule engine
├── storage/           # S3 and file storage implementations  
├── cache/             # LRU and Redis caching
└── config/            # Spring configuration classes
```

### Key Architectural Decisions

1. **Rule Storage**: Rules are stored as .drl files in S3 with hierarchical organization (e.g., `pricing/discount/black-friday.drl`)

2. **Caching Strategy**: 
   - S3 → Redis (optional) → Local LRU Cache → Rule Execution
   - Compiled KieBase objects cached locally for performance

3. **API Design**:
   - Main API on port 8080 (`/execute-rule`)
   - Admin API on port 8081 (`/admin/*`)

4. **Thread Safety**: Each rule execution uses a new KieSession (stateless)

5. **Rule ID Transformation**: `pricing.discount.black-friday` → `pricing/discount/black-friday.drl`

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
```

## Development Workflow

1. **Current Status**: Phase 2 Complete! Ready for Phase 3 (Production Readiness). See `project.progress.md` for details.

2. **LocalStack Setup**: Required for S3 testing locally
   ```bash
   # Will be in docker-compose.yml
   aws --endpoint-url=http://localhost:4566 s3 mb s3://local-rules
   aws --endpoint-url=http://localhost:4566 s3 cp rules/ s3://local-rules/ --recursive
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

## Implementation Status

Check `project.progress.md` for current status. Project follows these phases:
1. ✅ Core Infrastructure (Spring Boot + Drools setup) - COMPLETED
2. ✅ Storage & Caching (S3 + Redis) - COMPLETED  
3. 🔄 Production Readiness (Monitoring, metrics) - IN PROGRESS
4. ⬜ Testing & Documentation - PENDING
5. ⬜ Deployment & Infrastructure - PENDING

**Current Focus**: Phase 3.1 - Health & Monitoring (5 tasks)

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

- `project.checklist.md`: Detailed task breakdown (114 tasks total, 52 completed)
- `project.progress.md`: Track implementation progress (Phase 2 complete)
- `project.documentation.md`: Comprehensive project specifications
- `project.prompt.md`: Original implementation requirements
- `snap-memory/`: Session memory files documenting implementation progress

## Recent Completions (Phase 2)

### Storage & Caching Architecture
- **RuleStorage Interface**: Abstract storage layer with S3, LocalFile, InMemory implementations
- **S3Integration**: AWS SDK v2 with LocalStack for development, retry policies
- **Multi-tier Caching**: Local LRU → Redis → S3 with statistics tracking
- **Admin Endpoints**: Complete rule management API on port 8081

### Key Features Added
- Storage factory pattern for backend selection via `RULE_SOURCE` env var
- Thread-safe LRU cache with configurable size and eviction
- Redis distributed caching with JSON serialization
- Rule ID path transformation: `pricing.discount.vip` → `pricing/discount/vip.drl`
- Docker compose setup with LocalStack S3 and Redis for local development
- Admin APIs: `/admin/health`, `/admin/rules`, `/admin/refresh-rules`

### Development Setup
```bash
# Start local development environment
docker-compose up

# Create S3 bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://local-rules

# Build and run application
mvn compile && mvn spring-boot:run

# Test admin endpoints
curl http://localhost:8081/admin/health
curl http://localhost:8081/admin/rules
```