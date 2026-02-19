# 📚 Drools Rule Engine Microservice - Complete Project Documentation

## Table of Contents
1. [Executive Summary](#executive-summary)
2. [Project Overview](#project-overview)
3. [System Architecture](#system-architecture)
4. [Functional Requirements](#functional-requirements)
5. [Non-Functional Requirements](#non-functional-requirements)
6. [Technical Specifications](#technical-specifications)
7. [API Documentation](#api-documentation)
8. [Rule Management](#rule-management)
9. [Infrastructure & Deployment](#infrastructure-deployment)
10. [Security & Compliance](#security-compliance)
11. [Monitoring & Operations](#monitoring-operations)
12. [Development Guidelines](#development-guidelines)
13. [Testing Strategy](#testing-strategy)
14. [Project Timeline](#project-timeline)
15. [Appendices](#appendices)

---

## 1. Executive Summary <a id="executive-summary"></a>

### Project Vision
Build a high-performance, scalable microservice that enables dynamic business rule execution without code deployments. The service will allow business teams to modify logic through Drools rules stored in S3, while maintaining enterprise-grade performance and reliability.

### Key Benefits
- **Dynamic Business Logic**: Update rules without service deployment
- **High Performance**: Support 100-1000 requests per second
- **Scalability**: Horizontal scaling via AWS ECS
- **Maintainability**: Clear separation of business logic from application code
- **Cost Efficiency**: Pay-per-use model with efficient resource utilization

### Success Criteria
- Process 1000+ RPS with p99 latency < 100ms
- 99.9% uptime SLA
- Zero-downtime rule updates
- Support 1000+ concurrent rules

---

## 2. Project Overview <a id="project-overview"></a>

### Business Context
Organizations need to rapidly adapt business rules for:
- **Pricing Strategies**: Dynamic discounts, surge pricing, promotional offers
- **Compliance Checks**: Regulatory requirements, sanctions screening
- **Risk Assessment**: Credit scoring, fraud detection
- **Eligibility Verification**: Product access, feature flags
- **Workflow Decisions**: Approval chains, routing logic

### Technical Solution
A RESTful microservice that:
1. Loads Drools rules from AWS S3
2. Caches compiled rules for performance
3. Executes rules based on API requests
4. Returns structured results
5. Supports hot-reloading of rules

### Stakeholders
- **Development Team**: Build and maintain the service
- **DevOps Team**: Deploy and monitor
- **Business Analysts**: Write and test rules
- **API Consumers**: Internal services requiring rule execution
- **Security Team**: Ensure compliance and security

---

## 3. System Architecture <a id="system-architecture"></a>

### High-Level Architecture

```
┌─────────────────┐       ┌──────────────────┐
│   API Gateway   │──────▶│  Load Balancer   │
└─────────────────┘       └──────────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
              ┌─────▼─────┐                ┌─────▼─────┐
              │  ECS Task │                │  ECS Task │
              │  Instance │                │  Instance │
              └─────┬─────┘                └─────┬─────┘
                    │                             │
         ┌──────────┴──────────┬─────────────────┘
         │                     │
    ┌────▼────┐          ┌────▼────┐
    │   S3    │          │  Redis  │
    │ (Rules) │          │ (Cache) │
    └─────────┘          └─────────┘
```

### Component Architecture

```
Drools Rule Engine Service
├── API Layer
│   ├── REST Controllers
│   ├── Request/Response DTOs
│   └── Exception Handlers
├── Core Engine
│   ├── Drools Integration
│   ├── Rule Compiler
│   └── Execution Context
├── Caching Layer
│   ├── Local LRU Cache
│   ├── Redis Integration
│   └── Cache Warming
├── Storage Layer
│   ├── S3 Client
│   ├── Local File System
│   └── Storage Abstraction
└── Infrastructure
    ├── Health Checks
    ├── Metrics Collection
    └── Configuration Management
```

### Data Flow

1. **Rule Loading Flow**
   ```
   S3 Bucket → Redis Cache → Local LRU Cache → Rule Execution
   ```

2. **Request Processing Flow**
   ```
   API Request → Validation → Cache Lookup → Rule Execution → Response
   ```

3. **Rule Refresh Flow**
   ```
   Admin Trigger → S3 Fetch → Validation → Cache Update → Notification
   ```

---

## 4. Functional Requirements <a id="functional-requirements"></a>

### FR-1: Rule Execution
- **FR-1.1**: Execute business rules via REST API
- **FR-1.2**: Accept rule ID and input data in JSON format
- **FR-1.3**: Return execution results or detailed errors
- **FR-1.4**: Support concurrent rule execution
- **FR-1.5**: Enforce execution timeout (configurable)

### FR-2: Rule Management
- **FR-2.1**: Load rules from AWS S3 on startup
- **FR-2.2**: Support hierarchical rule organization
- **FR-2.3**: Transform rule IDs to file paths
- **FR-2.4**: Validate rules during loading
- **FR-2.5**: Skip invalid rules without affecting others

### FR-3: Rule Refresh
- **FR-3.1**: Refresh all rules via admin endpoint
- **FR-3.2**: Refresh specific rule by ID
- **FR-3.3**: Return refresh status and errors
- **FR-3.4**: Support automatic refresh (configurable)
- **FR-3.5**: Maintain service availability during refresh

### FR-4: Rule Discovery
- **FR-4.1**: List all loaded rules
- **FR-4.2**: Show rule metadata (load time, status)
- **FR-4.3**: Filter rules by status or pattern
- **FR-4.4**: Export rule inventory

### FR-5: Health Monitoring
- **FR-5.1**: Provide health check endpoint
- **FR-5.2**: Include rule engine status
- **FR-5.3**: Report cache statistics
- **FR-5.4**: Check external dependencies

---

## 5. Non-Functional Requirements <a id="non-functional-requirements"></a>

### Performance Requirements
- **NFR-1.1**: Support 100-1000 requests per second
- **NFR-1.2**: P99 latency < 100ms for cached rules
- **NFR-1.3**: P99 latency < 500ms for cache miss
- **NFR-1.4**: Rule compilation < 200ms
- **NFR-1.5**: Startup time < 60 seconds

### Scalability Requirements
- **NFR-2.1**: Horizontal scaling via ECS tasks
- **NFR-2.2**: Support 1000+ concurrent rules
- **NFR-2.3**: Linear performance scaling
- **NFR-2.4**: Auto-scaling based on CPU/memory

### Reliability Requirements
- **NFR-3.1**: 99.9% uptime SLA
- **NFR-3.2**: Graceful degradation on failures
- **NFR-3.3**: Circuit breaker for external calls
- **NFR-3.4**: Automatic recovery from crashes
- **NFR-3.5**: No data loss during updates

### Security Requirements
- **NFR-4.1**: TLS encryption in transit
- **NFR-4.2**: IAM-based S3 access
- **NFR-4.3**: No sensitive data in logs
- **NFR-4.4**: Input validation and sanitization
- **NFR-4.5**: Admin endpoints on separate port

### Operational Requirements
- **NFR-5.1**: Structured JSON logging
- **NFR-5.2**: CloudWatch metrics integration
- **NFR-5.3**: Zero-downtime deployments
- **NFR-5.4**: Configuration via environment variables
- **NFR-5.5**: Docker containerization

---

## 6. Technical Specifications <a id="technical-specifications"></a>

### Technology Stack

| Component | Technology | Version | Justification |
|-----------|------------|---------|---------------|
| Language | Java | 17 | LTS, performance, Drools compatibility |
| Framework | Spring Boot | 3.2.x | Industry standard, cloud-native |
| Rule Engine | Drools | 8.44.0.Final | Latest stable, performance improvements |
| Build Tool | Maven | 3.9.x | Dependency management, plugins |
| Containerization | Docker | 24.x | Industry standard |
| Cache | Redis | 7.x | Distributed caching, high performance |
| Storage | AWS S3 | - | Scalable, durable object storage |
| Deployment | AWS ECS | - | Container orchestration |
| Monitoring | CloudWatch | - | Native AWS integration |

### Dependencies

```xml
<dependencies>
    <!-- Core -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    
    <!-- Drools -->
    <dependency>
        <groupId>org.drools</groupId>
        <artifactId>drools-core</artifactId>
        <version>8.44.0.Final</version>
    </dependency>
    <dependency>
        <groupId>org.drools</groupId>
        <artifactId>drools-compiler</artifactId>
        <version>8.44.0.Final</version>
    </dependency>
    <dependency>
        <groupId>org.kie</groupId>
        <artifactId>kie-spring</artifactId>
        <version>8.44.0.Final</version>
    </dependency>
    
    <!-- AWS -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>s3</artifactId>
    </dependency>
    
    <!-- Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    
    <!-- Monitoring -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-cloudwatch2</artifactId>
    </dependency>
    
    <!-- Testing -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>localstack</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Configuration Schema

```yaml
# application.yml
server:
  port: ${SERVER_PORT:8080}
  
management:
  server:
    port: ${ADMIN_PORT:8081}
  endpoints:
    web:
      exposure:
        include: health,metrics,refresh-rules

drools:
  rule-source: ${RULE_SOURCE:s3}
  s3:
    bucket-name: ${RULE_BUCKET_NAME}
    region: ${AWS_REGION:us-east-1}
  cache:
    lru-max-size: ${LRU_CACHE_MAX_SIZE:100}
    execution-timeout-seconds: ${RULE_EXECUTION_TIMEOUT_SECONDS:30}
  refresh:
    auto-enabled: ${AUTO_REFRESH_ENABLED:false}
    interval-minutes: ${AUTO_REFRESH_INTERVAL_MINUTES:5}

redis:
  enabled: ${REDIS_ENABLED:false}
  url: ${REDIS_URL:redis://localhost:6379}
  ttl-minutes: 60

logging:
  level:
    root: INFO
    com.company.drools: ${LOG_LEVEL:INFO}
  pattern:
    console: '{"timestamp":"%d{ISO8601}","level":"%level","logger":"%logger","message":"%message","thread":"%thread"}%n'
```

---

## 7. API Documentation <a id="api-documentation"></a>

### Base URLs
- **Application**: `http://service:8080`
- **Admin**: `http://service:8081`

### Endpoints

#### 7.1 Execute Rule
Executes a business rule with provided input data.

**Request**
```http
POST /execute-rule
Content-Type: application/json

{
  "rule_id": "pricing.discount.black-friday-2024",
  "data": {
    "amount": 150.00,
    "customer_tier": "gold",
    "region": "US",
    "items_count": 3
  }
}
```

**Response - Success**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "rule_id": "pricing.discount.black-friday-2024",
  "result": {
    "amount": 150.00,
    "discount": 37.50,
    "discount_percentage": 25,
    "final_amount": 112.50,
    "applied_rules": ["black-friday-2024", "gold-tier-bonus"]
  },
  "error": null
}
```

**Response - Error**
```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "rule_id": "pricing.discount.black-friday-2024",
  "result": null,
  "error": {
    "code": "RULE_EXECUTION_ERROR",
    "message": "Failed to execute rule: Missing required field 'customer_tier'",
    "details": "The rule requires 'customer_tier' field to be present in the input data",
    "timestamp": "2024-01-20T10:30:45Z"
  }
}
```

#### 7.2 Refresh All Rules
Reloads all rules from S3.

**Request**
```http
POST /admin/refresh-rules
```

**Response**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "completed",
  "rules_loaded": 145,
  "rules_failed": 3,
  "duration_ms": 2847,
  "errors": [
    {
      "rule_id": "pricing.discount.invalid-syntax",
      "error": "Compilation failed: Syntax error at line 12, column 8"
    }
  ],
  "cache_updated_at": "2024-01-20T10:31:00Z"
}
```

#### 7.3 Refresh Specific Rule
Reloads a specific rule from S3.

**Request**
```http
POST /admin/refresh-rules/pricing.discount.black-friday-2024
```

**Response**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "rule_id": "pricing.discount.black-friday-2024",
  "status": "success",
  "previous_version": "2024-01-19T08:00:00Z",
  "current_version": "2024-01-20T10:31:00Z",
  "compilation_time_ms": 45
}
```

#### 7.4 List Loaded Rules
Returns all currently loaded rules.

**Request**
```http
GET /admin/rules
```

**Response**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "total_rules": 148,
  "rules": [
    {
      "rule_id": "pricing.discount.black-friday-2024",
      "status": "active",
      "loaded_at": "2024-01-20T09:00:00Z",
      "last_executed": "2024-01-20T10:29:00Z",
      "execution_count": 1847,
      "avg_execution_time_ms": 12.5,
      "cache_hits": 1823,
      "errors": 2
    }
  ]
}
```

#### 7.5 Health Check
Service health status.

**Request**
```http
GET /admin/health
```

**Response**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "UP",
  "components": {
    "drools": {
      "status": "UP",
      "details": {
        "rules_loaded": 148,
        "cache_size": 95,
        "cache_hit_rate": 0.94
      }
    },
    "s3": {
      "status": "UP",
      "details": {
        "bucket": "prod-drools-rules",
        "accessible": true
      }
    },
    "redis": {
      "status": "UP",
      "details": {
        "connected": true,
        "memory_used_mb": 245
      }
    }
  },
  "timestamp": "2024-01-20T10:32:00Z"
}
```

### Error Codes

| Code | Description | HTTP Status |
|------|-------------|-------------|
| RULE_NOT_FOUND | Rule ID does not exist | 404 |
| RULE_COMPILATION_ERROR | Rule has syntax errors | 500 |
| RULE_EXECUTION_ERROR | Runtime error during execution | 400 |
| RULE_TIMEOUT | Execution exceeded timeout | 408 |
| INVALID_INPUT | Request validation failed | 400 |
| STORAGE_ERROR | S3/Redis access failed | 503 |
| INTERNAL_ERROR | Unexpected server error | 500 |

---

## 8. Rule Management <a id="rule-management"></a>

### Rule Structure

#### Example Rule: Black Friday Discount
```drools
package com.company.rules.pricing.discount

import java.util.Map
import java.util.HashMap
import java.time.LocalDate

global org.slf4j.Logger logger

rule "Black Friday 2024 - Gold Tier"
    salience 100
    when
        $data : Map(
            this["customer_tier"] == "gold",
            this["amount"] != null,
            (Double)this["amount"] > 50.0
        )
        eval(LocalDate.now().getMonthValue() == 11)
    then
        logger.info("Applying Black Friday Gold Tier discount");
        
        Map result = new HashMap();
        Double amount = (Double) $data.get("amount");
        Double discount = amount * 0.25; // 25% discount
        
        result.put("amount", amount);
        result.put("discount", discount);
        result.put("discount_percentage", 25);
        result.put("final_amount", amount - discount);
        result.put("applied_rules", "black-friday-gold");
        
        $data.put("result", result);
end

rule "Black Friday 2024 - Standard"
    salience 50
    when
        $data : Map(
            this["amount"] != null,
            (Double)this["amount"] > 25.0,
            this.get("result") == null
        )
        eval(LocalDate.now().getMonthValue() == 11)
    then
        logger.info("Applying Black Friday Standard discount");
        
        Map result = new HashMap();
        Double amount = (Double) $data.get("amount");
        Double discount = amount * 0.15; // 15% discount
        
        result.put("amount", amount);
        result.put("discount", discount);
        result.put("discount_percentage", 15);
        result.put("final_amount", amount - discount);
        result.put("applied_rules", "black-friday-standard");
        
        $data.put("result", result);
end
```

### Rule Organization

```
s3://prod-drools-rules/
├── pricing/
│   ├── discount/
│   │   ├── black-friday-2024.drl
│   │   ├── cyber-monday-2024.drl
│   │   └── loyalty-program.drl
│   ├── surge/
│   │   ├── peak-hours.drl
│   │   └── holiday-surge.drl
│   └── tax/
│       ├── us-sales-tax.drl
│       └── vat-calculator.drl
├── compliance/
│   ├── sanctions/
│   │   ├── ofac-screening.drl
│   │   └── pep-check.drl
│   └── kyc/
│       ├── identity-verification.drl
│       └── document-validation.drl
└── risk/
    ├── fraud/
    │   ├── transaction-scoring.drl
    │   └── velocity-checks.drl
    └── credit/
        ├── credit-scoring.drl
        └── limit-calculation.drl
```

### Rule Lifecycle

1. **Development**
   - Write rule in .drl format
   - Test locally with sample data
   - Validate syntax and logic

2. **Deployment**
   - Upload to S3 bucket
   - Trigger rule refresh
   - Verify successful loading

3. **Monitoring**
   - Track execution metrics
   - Monitor error rates
   - Analyze performance

4. **Updates**
   - Modify rule file
   - Upload new version
   - Refresh without downtime

---

## 9. Infrastructure & Deployment <a id="infrastructure-deployment"></a>

### Docker Configuration

#### Multi-Stage Dockerfile
```dockerfile
# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Add non-root user
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -s /bin/sh -D appuser

# Copy artifact
COPY --from=build /app/target/drools-rule-engine-*.jar app.jar

# Configure JVM
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8081/admin/health || exit 1

# Switch to non-root user
USER appuser

# Expose ports
EXPOSE 8080 8081

# Start application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### AWS ECS Configuration

#### Task Definition
```json
{
  "family": "drools-rule-engine",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "containerDefinitions": [
    {
      "name": "drools-engine",
      "image": "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/drools-rule-engine:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        },
        {
          "containerPort": 8081,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "RULE_SOURCE",
          "value": "s3"
        },
        {
          "name": "RULE_BUCKET_NAME",
          "value": "${ENVIRONMENT}-drools-rules"
        },
        {
          "name": "REDIS_ENABLED",
          "value": "true"
        },
        {
          "name": "REDIS_URL",
          "value": "redis://${REDIS_ENDPOINT}:6379"
        },
        {
          "name": "LRU_CACHE_MAX_SIZE",
          "value": "500"
        },
        {
          "name": "AUTO_REFRESH_ENABLED",
          "value": "true"
        },
        {
          "name": "AUTO_REFRESH_INTERVAL_MINUTES",
          "value": "5"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/drools-rule-engine",
          "awslogs-region": "${AWS_REGION}",
          "awslogs-stream-prefix": "ecs"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:8081/admin/health || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      }
    }
  ],
  "taskRoleArn": "arn:aws:iam::${AWS_ACCOUNT_ID}:role/drools-engine-task-role",
  "executionRoleArn": "arn:aws:iam::${AWS_ACCOUNT_ID}:role/drools-engine-execution-role"
}
```

#### Service Configuration
```yaml
apiVersion: v1
kind: Service
metadata:
  name: drools-rule-engine
spec:
  launchType: FARGATE
  cluster: production-cluster
  taskDefinition: drools-rule-engine:latest
  desiredCount: 4
  deploymentConfiguration:
    maximumPercent: 200
    minimumHealthyPercent: 100
    deploymentCircuitBreaker:
      enable: true
      rollback: true
  networkConfiguration:
    awsvpcConfiguration:
      subnets:
        - subnet-private-1a
        - subnet-private-1b
      securityGroups:
        - sg-drools-engine
  loadBalancers:
    - targetGroupArn: arn:aws:elasticloadbalancing:region:account:targetgroup/drools-api/xxx
      containerName: drools-engine
      containerPort: 8080
    - targetGroupArn: arn:aws:elasticloadbalancing:region:account:targetgroup/drools-admin/xxx
      containerName: drools-engine
      containerPort: 8081
  serviceRegistries:
    - registryArn: arn:aws:servicediscovery:region:account:service/srv-drools
```

### Infrastructure as Code (Terraform)

```hcl
# S3 Bucket
resource "aws_s3_bucket" "drools_rules" {
  bucket = "${var.environment}-drools-rules"
  
  versioning {
    enabled = true
  }
  
  lifecycle_rule {
    id      = "expire-old-versions"
    enabled = true
    
    noncurrent_version_expiration {
      days = 30
    }
  }
  
  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        sse_algorithm = "AES256"
      }
    }
  }
}

# Redis Cluster
resource "aws_elasticache_cluster" "drools_cache" {
  cluster_id           = "${var.environment}-drools-cache"
  engine              = "redis"
  node_type           = "cache.r6g.large"
  num_cache_nodes     = 1
  parameter_group_name = "default.redis7"
  port                = 6379
  
  subnet_group_name = aws_elasticache_subnet_group.cache.name
  security_group_ids = [aws_security_group.redis.id]
}

# Auto Scaling
resource "aws_appautoscaling_target" "ecs_target" {
  max_capacity       = 10
  min_capacity       = 2
  resource_id        = "service/${var.cluster_name}/${var.service_name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu_scaling" {
  name               = "cpu-autoscaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs_target.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs_target.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs_target.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value = 70.0
  }
}
```

---

## 10. Security & Compliance <a id="security-compliance"></a>

### Security Architecture

#### Network Security
- VPC with private subnets for ECS tasks
- Security groups with least privilege
- TLS 1.3 for all external communication
- API Gateway for public endpoint

#### Access Control
- IAM roles for service-to-service
- S3 bucket policies
- Redis AUTH enabled
- Admin endpoints on separate port

#### Data Security
- Encryption at rest (S3, Redis)
- Encryption in transit (TLS)
- No sensitive data in logs
- Input sanitization

### IAM Policies

#### Task Role Policy
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::${ENVIRONMENT}-drools-rules",
        "arn:aws:s3:::${ENVIRONMENT}-drools-rules/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutMetricData"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:*:*:*"
    }
  ]
}
```

### Compliance Considerations

- **PCI DSS**: No credit card data in rules
- **GDPR**: No PII in logs or metrics
- **SOC 2**: Audit trails for rule changes
- **HIPAA**: Encryption for healthcare rules

---

## 11. Monitoring & Operations <a id="monitoring-operations"></a>

### Metrics Collection

#### Business Metrics
- Rules executed per minute
- Success/failure rates by rule
- Average execution time by rule
- Cache hit rates
- Rule refresh frequency

#### Technical Metrics
- CPU utilization
- Memory usage
- Thread pool statistics
- GC pause times
- Network I/O

### CloudWatch Dashboard

```json
{
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["DroolsEngine", "RuleExecutions", {"stat": "Sum"}],
          [".", "RuleErrors", {"stat": "Sum"}],
          [".", "CacheHits", {"stat": "Sum"}],
          [".", "CacheMisses", {"stat": "Sum"}]
        ],
        "period": 300,
        "stat": "Average",
        "region": "us-east-1",
        "title": "Rule Execution Metrics"
      }
    },
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["AWS/ECS", "CPUUtilization", "ServiceName", "drools-rule-engine"],
          [".", "MemoryUtilization", ".", "."]
        ],
        "period": 300,
        "stat": "Average",
        "region": "us-east-1",
        "title": "Resource Utilization"
      }
    }
  ]
}
```

### Alerting Strategy

#### Critical Alerts
- Service down (health check failures)
- Error rate > 5%
- P99 latency > 500ms
- Memory usage > 90%

#### Warning Alerts
- Error rate > 1%
- P99 latency > 200ms
- Cache hit rate < 80%
- Rule refresh failures

### Operational Procedures

#### Deployment Process
1. Build Docker image
2. Push to ECR
3. Update task definition
4. Rolling deployment
5. Health check validation
6. Rollback if needed

#### Incident Response
1. Alert triggered
2. Check CloudWatch dashboard
3. Review recent deployments
4. Check rule changes
5. Scale if needed
6. Rollback if necessary

---

## 12. Development Guidelines <a id="development-guidelines"></a>

### Code Standards

#### Package Structure
```
com.company.drools
├── api           # REST endpoints
├── core          # Business logic
├── storage       # External storage
├── cache         # Caching layer
├── config        # Configuration
└── common        # Shared utilities
```

#### Coding Conventions
- Java 17 features encouraged
- Lombok for boilerplate reduction
- Immutable DTOs
- Constructor injection
- Comprehensive JavaDoc

### Rule Development Guidelines

#### Best Practices
1. **Naming**: Use descriptive rule names
2. **Salience**: Higher values execute first
3. **Conditions**: Keep simple and readable
4. **Actions**: Avoid side effects
5. **Logging**: Use provided logger

#### Testing Rules
```java
@Test
public void testBlackFridayDiscount() {
    // Given
    Map<String, Object> input = new HashMap<>();
    input.put("amount", 100.0);
    input.put("customer_tier", "gold");
    
    // When
    RuleExecutionResult result = ruleEngine.execute(
        "pricing.discount.black-friday-2024", 
        input
    );
    
    // Then
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getResult())
        .containsEntry("discount", 25.0)
        .containsEntry("final_amount", 75.0);
}
```

### Local Development

#### Docker Compose Setup
```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
      - "8081:8081"
    environment:
      - RULE_SOURCE=s3
      - RULE_BUCKET_NAME=local-rules
      - AWS_ENDPOINT=http://localstack:4566
      - AWS_ACCESS_KEY_ID=test
      - AWS_SECRET_ACCESS_KEY=test
      - REDIS_ENABLED=true
      - REDIS_URL=redis://redis:6379
    depends_on:
      - localstack
      - redis
    volumes:
      - ./local-rules:/app/rules

  localstack:
    image: localstack/localstack:2.3
    ports:
      - "4566:4566"
    environment:
      - SERVICES=s3
      - DEBUG=1
      - DATA_DIR=/tmp/localstack/data
    volumes:
      - "./init-aws.sh:/etc/localstack/init/ready.d/init-aws.sh"
      - "localstack-data:/tmp/localstack"

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis-data:/data

volumes:
  localstack-data:
  redis-data:
```

---

## 13. Testing Strategy <a id="testing-strategy"></a>

### Test Pyramid

#### Unit Tests (70%)
- Rule compilation tests
- Cache behavior tests
- Transformation logic tests
- Error handling tests

#### Integration Tests (20%)
- S3 integration with LocalStack
- Redis integration
- API endpoint tests
- Rule execution tests

#### End-to-End Tests (10%)
- Full workflow tests
- Performance benchmarks
- Load testing
- Chaos testing

### Test Examples

#### Unit Test
```java
@Test
void shouldTransformRuleIdToPath() {
    // Given
    String ruleId = "pricing.discount.black-friday-2024";
    
    // When
    String path = RulePathTransformer.toPath(ruleId);
    
    // Then
    assertThat(path).isEqualTo("pricing/discount/black-friday-2024.drl");
}
```

#### Integration Test
```java
@SpringBootTest
@AutoConfigureMockMvc
class RuleExecutionIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void shouldExecuteRule() throws Exception {
        // Given
        String request = """
            {
                "rule_id": "test.simple.rule",
                "data": {"value": 100}
            }
            """;
        
        // When & Then
        mockMvc.perform(post("/execute-rule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists())
                .andExpect(jsonPath("$.error").isEmpty());
    }
}
```

#### Performance Test
```java
@Test
void shouldHandle1000RequestsPerSecond() {
    // Given
    int threads = 100;
    int requestsPerThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    
    // When
    long startTime = System.currentTimeMillis();
    List<Future<RuleExecutionResult>> futures = new ArrayList<>();
    
    for (int i = 0; i < threads; i++) {
        futures.add(executor.submit(() -> {
            for (int j = 0; j < requestsPerThread; j++) {
                executeRule("test.rule", testData());
            }
        }));
    }
    
    // Wait for completion
    futures.forEach(f -> f.get());
    long duration = System.currentTimeMillis() - startTime;
    
    // Then
    double rps = (threads * requestsPerThread * 1000.0) / duration;
    assertThat(rps).isGreaterThan(1000);
}
```

---

## 14. Project Timeline <a id="project-timeline"></a>

### Development Phases

#### Phase 1: Foundation (Week 1-2)
- [ ] Project setup and configuration
- [ ] Basic Spring Boot structure
- [ ] Drools integration
- [ ] Simple rule execution
- [ ] Unit test framework

#### Phase 2: Core Features (Week 3-4)
- [ ] S3 integration
- [ ] Rule loading and caching
- [ ] API endpoints
- [ ] Error handling
- [ ] Integration tests

#### Phase 3: Performance (Week 5-6)
- [ ] Redis integration
- [ ] LRU cache implementation
- [ ] Performance optimization
- [ ] Load testing
- [ ] Monitoring setup

#### Phase 4: Production Ready (Week 7-8)
- [ ] Docker optimization
- [ ] ECS deployment
- [ ] Security hardening
- [ ] Documentation
- [ ] Operational procedures

### Milestones

| Milestone | Date | Deliverables |
|-----------|------|--------------|
| M1: POC Complete | Week 2 | Basic rule execution working |
| M2: Alpha Release | Week 4 | All core features implemented |
| M3: Beta Release | Week 6 | Performance targets met |
| M4: Production Release | Week 8 | Fully deployed and monitored |

---

## 15. Appendices <a id="appendices"></a>

### Appendix A: Glossary

| Term | Definition |
|------|------------|
| Drools | Open-source business rules management system |
| KieBase | Knowledge base containing compiled rules |
| KieSession | Runtime session for rule execution |
| LRU | Least Recently Used cache eviction policy |
| Salience | Rule priority (higher executes first) |
| DRL | Drools Rule Language |

### Appendix B: References

1. [Drools Documentation](https://docs.jboss.org/drools/release/latest-final/drools-docs/html_single/)
2. [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/html/)
3. [AWS ECS Best Practices](https://docs.aws.amazon.com/AmazonECS/latest/bestpracticesguide/)
4. [Redis Documentation](https://redis.io/documentation)

### Appendix C: Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2024-01-15 | Use Drools 8.44.0 | Latest stable version with performance improvements |
| 2024-01-16 | Redis for distributed cache | Better than Hazelcast for our use case |
| 2024-01-17 | Multi-stage Docker build | Reduces image size by 60% |
| 2024-01-18 | Separate admin port | Security isolation for admin endpoints |

### Appendix D: Risk Register

| Risk | Probability | Impact | Mitigation |
|------|-------------|---------|------------|
| Rule compilation failures | Medium | High | Validation on refresh, fallback to previous version |
| Redis downtime | Low | Medium | Fallback to S3, graceful degradation |
| Memory exhaustion | Medium | High | LRU cache limits, monitoring, auto-scaling |
| S3 rate limiting | Low | High | Exponential backoff, caching layer |

---

## Document Control

- **Version**: 1.0
- **Date**: January 2024
- **Author**: Engineering Team
- **Review**: Architecture Board
- **Approval**: CTO

This document serves as the comprehensive guide for the Drools Rule Engine Microservice project. It should be updated as the project evolves and requirements change.