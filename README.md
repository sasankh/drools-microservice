# Drools Rule Engine Microservice

A high-performance business rule execution microservice built with Spring Boot and Drools. Designed to handle 100-1000 requests per second with sub-100ms latency for cached rules.

## 🚀 Features

- **High Performance**: Sub-100ms P99 latency for cached rules, supports 100-1000 RPS
- **Scalable Storage**: AWS S3 backend with hierarchical rule organization
- **Multi-tier Caching**: Local LRU + Redis distributed caching for optimal performance
- **Rule Management**: REST APIs for hot-reloading and monitoring rules
- **Production Ready**: Health checks, metrics, and monitoring capabilities
- **Development Friendly**: LocalStack integration for offline S3 testing

## 🏗️ Architecture

```
Client Request → REST API → Rule Engine → Cache Layer → Storage Layer
                    ↓           ↓            ↓           ↓
               Controller → Drools KIE → LRU/Redis → S3/Local/Memory
```

### Tech Stack

- **Java 17** - Runtime platform
- **Spring Boot 3.2.5** - Application framework
- **Drools 8.44.0.Final** - Business rules engine
- **AWS S3** - Rule storage (with LocalStack for development)
- **Redis** - Distributed caching (optional)
- **Docker** - Containerization and local development
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

- Java 17 or higher
- Maven 3.6+
- Docker and Docker Compose
- AWS CLI (for S3 setup)

### 1. Clone the Repository

```bash
git clone <repository-url>
cd drools-microservice
```

### 2. Start Local Development Environment

```bash
# Start LocalStack (S3) and Redis services
docker-compose up -d

# Wait for services to start (about 10 seconds)
sleep 10

# Create S3 bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://local-rules
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

```bash
# Build the application
mvn clean compile

# Run the application
mvn spring-boot:run

# Or with specific profile
mvn spring-boot:run -Dspring.profiles.active=local
```

### 5. Verify Installation

```bash
# Check application health
curl http://localhost:8081/admin/health

# Test rule execution (using built-in sample rules)
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"ruleId": "simple.discount", "data": {"amount": 100}}'
```

Expected response:
```json
{
  "success": true,
  "result": {"amount": 90},
  "executionTimeMs": 15,
  "ruleId": "simple.discount"
}
```

## 🔧 Installation

### System Requirements

- **Java**: OpenJDK 17 or Oracle JDK 17+
- **Memory**: Minimum 2GB RAM, recommended 4GB+
- **Storage**: 1GB free disk space
- **Network**: Internet access for Maven dependencies

### Dependencies Installation

#### Java 17
```bash
# Ubuntu/Debian
sudo apt update && sudo apt install openjdk-17-jdk

# macOS with Homebrew
brew install openjdk@17

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
  "ruleId": "pricing.discount.vip",
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
  "ruleId": "pricing.discount.vip",
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

### Rule Development

Rules are written in Drools (.drl) format and stored with hierarchical organization:

#### Example Rule Structure
```
s3://my-rules-bucket/
├── pricing/
│   ├── discount/
│   │   ├── vip.drl
│   │   ├── regular.drl
│   │   └── bulk.drl
│   └── shipping/
│       ├── express.drl
│       └── standard.drl
└── validation/
    ├── customer.drl
    └── product.drl
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
- **Admin API**: `http://localhost:8081/admin`

### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/execute-rule` | Execute a business rule |
| `GET` | `/admin/health` | Application health check |
| `GET` | `/admin/info` | System information |
| `GET` | `/admin/rules` | List all loaded rules |
| `POST` | `/admin/refresh-rules` | Refresh all rules from storage |
| `POST` | `/admin/refresh-rules/{ruleId}` | Refresh specific rule |

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

Common error codes:
- `RULE_NOT_FOUND`: Rule ID does not exist
- `RULE_EXECUTION_ERROR`: Runtime error during rule execution
- `RULE_COMPILATION_ERROR`: Rule compilation failed
- `STORAGE_ERROR`: Storage backend unavailable
- `CACHE_ERROR`: Cache operation failed

## 🔬 Development

### Project Structure

```
src/
├── main/java/com/company/drools/
│   ├── api/                    # REST controllers and DTOs
│   │   ├── controller/         # REST endpoints
│   │   ├── dto/               # Data transfer objects
│   │   └── exception/         # Exception handlers
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
│   └── config/               # Spring configuration
└── main/resources/
    ├── application.yml       # Configuration
    └── logback-spring.xml   # Logging configuration
```

### Local Development Setup

1. **Start Infrastructure Services**
   ```bash
   docker-compose up -d
   ```

2. **Create S3 Bucket**
   ```bash
   aws --endpoint-url=http://localhost:4566 s3 mb s3://local-rules
   ```

3. **Upload Sample Rules** (optional)
   ```bash
   aws --endpoint-url=http://localhost:4566 s3 cp rules/ s3://local-rules/ --recursive
   ```

4. **Set Development Environment**
   ```bash
   export RULE_SOURCE=s3
   export AWS_ENDPOINT=http://localhost:4566
   export REDIS_ENABLED=true
   ```

5. **Run Application**
   ```bash
   mvn spring-boot:run -Dspring.profiles.active=local
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
curl -X POST http://localhost:8081/admin/refresh-rules/pricing.discount.new-rule

# Or refresh all rules
curl -X POST http://localhost:8081/admin/refresh-rules
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

### Performance Testing
```bash
# Load testing with JMeter
mvn jmeter:jmeter

# Manual performance test
for i in {1..100}; do
  curl -X POST http://localhost:8080/execute-rule \
    -H "Content-Type: application/json" \
    -d '{"ruleId": "simple.discount", "data": {"amount": 100}}'
done
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

```bash
# Build Docker image
docker build -t drools-rule-engine:latest .

# Run with environment variables
docker run -p 8080:8080 -p 8081:8081 \
  -e RULE_SOURCE=s3 \
  -e RULE_BUCKET_NAME=prod-rules \
  -e AWS_ACCESS_KEY_ID=your-key \
  -e AWS_SECRET_ACCESS_KEY=your-secret \
  drools-rule-engine:latest
```

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
export JAVA_OPTS="-Xmx2g -Xms1g"

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
# Application health
curl http://localhost:8081/admin/health

# Cache statistics
curl http://localhost:8081/admin/health | jq '.cache.statistics'

# Rule performance
curl http://localhost:8081/admin/rules | jq '.rules[].avg_execution_time_ms'
```

### Performance Targets

- **Throughput**: 100-1000 requests/second
- **Latency**: P99 < 100ms (cached), P99 < 500ms (cache miss)
- **Availability**: 99.9% uptime
- **Cache Hit Ratio**: > 90%

### Alerting

Monitor these metrics:
- High error rate (> 1%)
- High latency (P99 > 200ms)
- Low cache hit ratio (< 80%)
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

### Common Issues

#### 1. Application won't start
```bash
# Check Java version
java -version  # Should be 17+

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
```

#### 3. Redis connection issues
```bash
# Check Redis is running
docker ps | grep redis

# Test Redis connectivity
redis-cli -h localhost -p 6379 ping

# Disable Redis if problematic
export REDIS_ENABLED=false
```

#### 4. Rule compilation errors
```bash
# Check rule syntax
curl http://localhost:8081/admin/rules

# View detailed error logs
tail -f logs/application.log | grep ERROR

# Refresh specific problematic rule
curl -X POST http://localhost:8081/admin/refresh-rules/problematic.rule.id
```

### Getting Help

- Check existing issues: [GitHub Issues](https://github.com/your-repo/issues)
- Review logs: `tail -f logs/application.log`
- Verify configuration: `curl http://localhost:8081/admin/health`
- Test with memory storage: `export RULE_SOURCE=memory`

## 📞 Support

For support and questions:
- Create an issue in this repository
- Check the [project documentation](project.documentation.md)
- Review [implementation progress](project.progress.md)