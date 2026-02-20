# 🔧 Drools Rule Engine - Troubleshooting Guide

## 📋 Table of Contents

- [Overview](#overview)
- [Startup Issues](#startup-issues)
- [Rule Execution Problems](#rule-execution-problems)
- [Storage Issues](#storage-issues)
- [Performance Issues](#performance-issues)
- [Configuration Problems](#configuration-problems)
- [Monitoring & Diagnostics](#monitoring--diagnostics)
- [Error Codes Reference](#error-codes-reference)
- [Common Solutions](#common-solutions)
- [Support Resources](#support-resources)

---

## 🎯 Overview

This guide helps diagnose and resolve common issues with the Drools Rule Engine Microservice.

### Quick Diagnosis Steps
1. **Check Health Endpoints**: `curl http://localhost:8080/admin/health`
2. **View Recent Logs**: `tail -f /var/log/drools-rule-engine/application.log`
3. **Test Basic Functionality**: Try simple rule execution
4. **Verify Configuration**: Check environment variables and application.yml
5. **Monitor System Resources**: CPU, memory, disk, network

### Emergency Contacts
- **Development Team**: dev-team@company.com
- **Operations Team**: ops-team@company.com
- **Emergency Hotline**: +1-800-SUPPORT

---

## 🚀 Startup Issues

### Application Won't Start

#### Symptom
Application fails to start or exits immediately.

#### Common Causes & Solutions

##### 1. Port Already in Use
```bash
# Check what's using the port
netstat -tlnp | grep :8080
lsof -i :8080

# Solution: Stop conflicting service or change port
export SERVER_PORT=8090
# or kill the conflicting process
sudo kill -9 <PID>
```

##### 2. Java Version Issues
```bash
# Check Java version
java -version

# Should show Java 17 or higher
# Solution: Install correct Java version
sudo apt install openjdk-17-jre-headless
# or
sudo yum install java-17-openjdk
```

##### 3. Missing Environment Variables
```bash
# Check required variables
echo $RULE_SOURCE
echo $RULE_BUCKET_NAME

# Solution: Set required variables
export RULE_SOURCE=s3
export RULE_BUCKET_NAME=your-bucket-name
```

##### 4. AWS Credentials Issues
```bash
# Test AWS credentials
aws sts get-caller-identity

# Solution: Configure credentials
aws configure
# or set environment variables
export AWS_ACCESS_KEY_ID=your-key
export AWS_SECRET_ACCESS_KEY=your-secret
```

##### 5. Insufficient Memory
```bash
# Check available memory
free -h

# Solution: Increase heap size
export JAVA_OPTS="-Xms1g -Xmx2g"
```

#### Logs to Check
```bash
# Application startup logs
tail -n 100 /var/log/drools-rule-engine/application.log

# System logs
journalctl -u drools-rule-engine.service -n 50

# Look for specific error patterns
grep -i "error\|exception\|failed" /var/log/drools-rule-engine/application.log
```

---

## ⚡ Rule Execution Problems

### Rules Not Found (404)

#### Symptom
```json
{
  "rule_id": "pricing.discount.vip",
  "result": null,
  "error": {
    "code": "RULE_NOT_FOUND",
    "message": "Rule not found"
  }
}
```

#### Diagnosis Steps
```bash
# 1. Check if rule exists in storage
aws s3 ls s3://your-bucket/pricing/discount/

# 2. Verify rule ID transformation
# Rule ID: pricing.discount.vip
# Should map to: pricing/discount/vip.drl

# 3. Check rule cache
curl http://localhost:8080/admin/rules | jq '.rules[] | select(.rule_id == "pricing.discount.vip")'

# 4. Try refreshing the rule
curl -X POST http://localhost:8080/admin/refresh-rules/pricing.discount.vip
```

#### Solutions
```bash
# 1. Upload missing rule to S3
aws s3 cp pricing/discount/vip.drl s3://your-bucket/pricing/discount/vip.drl

# 2. Refresh rule cache
curl -X POST http://localhost:8080/admin/refresh-rules

# 3. Check rule file syntax
# Ensure .drl file has proper Drools syntax
```

### Rule Compilation Errors

#### Symptom
```json
{
  "error": {
    "code": "RULE_COMPILATION_ERROR",
    "message": "Failed to compile rule",
    "details": "Syntax error at line 15, column 8"
  }
}
```

#### Common Compilation Issues

##### 1. Syntax Errors
```drools
// Bad: Missing semicolon
rule "Bad Rule"
when
    $data : Map()
then
    double amount = 100  // Missing semicolon
end

// Good: Proper syntax
rule "Good Rule"
when
    $data : Map()
then
    double amount = 100.0;
end
```

##### 2. Type Mismatches
```drools
// Bad: Comparing different types
eval($data.get("amount") > 100)  // Object vs int comparison

// Good: Proper type checking
eval($data.get("amount") instanceof Number)
eval(((Number) $data.get("amount")).doubleValue() > 100)
```

##### 3. Missing Imports
```drools
// Add required imports at top of rule file
package com.company.rules.pricing

import java.util.Map
import java.util.List
import java.math.BigDecimal
```

#### Debugging Compilation
```bash
# Check rule syntax locally
# Create test file and validate
cat > test-rule.drl << 'EOF'
package com.company.rules.test
import java.util.Map
rule "Test Rule"
when
    $data : Map()
then
    System.out.println("Test");
end
EOF

# Validate using Drools compiler (if available locally)
```

### Rule Execution Errors

#### Symptom
```json
{
  "error": {
    "code": "RULE_EXECUTION_ERROR",
    "message": "Runtime error during rule execution",
    "details": "NullPointerException at line 12"
  }
}
```

#### Common Runtime Issues

##### 1. Null Pointer Exceptions
```drools
// Bad: No null checks
rule "Unsafe Rule"
when
    $data : Map()
then
    String value = (String) $data.get("customer_name");
    int length = value.length();  // NPE if null
end

// Good: Null checks
rule "Safe Rule"
when
    $data : Map()
    eval($data.get("customer_name") != null)
then
    String value = (String) $data.get("customer_name");
    int length = value.length();
end
```

##### 2. ClassCast Exceptions
```drools
// Bad: Unsafe casting
int amount = (Integer) $data.get("amount");  // Exception if Double

// Good: Safe type handling
if ($data.get("amount") instanceof Number) {
    double amount = ((Number) $data.get("amount")).doubleValue();
}
```

---

## 💾 Storage Issues

### S3 Connection Problems

#### Symptom
- Health check shows storage as "DOWN"
- Rules fail to load with storage errors
- Timeouts on rule refresh operations

#### Diagnosis Steps
```bash
# 1. Test S3 connectivity
aws s3 ls s3://your-bucket-name

# 2. Check AWS credentials
aws sts get-caller-identity

# 3. Test bucket permissions
aws s3api head-bucket --bucket your-bucket-name

# 4. Check regional settings
aws configure get region

# 5. Test from application server
curl -v https://s3.amazonaws.com
```

#### Solutions

##### 1. Network Connectivity
```bash
# Check DNS resolution
nslookup s3.amazonaws.com

# Check firewall rules
iptables -L | grep -i aws

# Test HTTPS connectivity
openssl s_client -connect s3.amazonaws.com:443
```

##### 2. Permissions Issues
```json
// Required S3 IAM policy
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
        "arn:aws:s3:::your-bucket",
        "arn:aws:s3:::your-bucket/*"
      ]
    }
  ]
}
```

##### 3. Region Mismatches
```bash
# Check bucket region
aws s3api get-bucket-location --bucket your-bucket

# Set correct region
export AWS_REGION=us-west-2
```

### Redis Connection Issues

#### Symptom
```json
{
  "components": {
    "redis": {
      "status": "DOWN",
      "details": {
        "connected": false,
        "error": "Connection refused"
      }
    }
  }
}
```

#### Diagnosis Steps
```bash
# 1. Test Redis connectivity
redis-cli -h your-redis-host -p 6379 ping

# 2. Check Redis service status
systemctl status redis
# or for Docker
docker ps | grep redis

# 3. Check network connectivity
telnet your-redis-host 6379

# 4. Check Redis logs
tail -f /var/log/redis/redis-server.log
# or for Docker
docker logs redis-container
```

#### Solutions

##### 1. Redis Not Running
```bash
# Start Redis service
systemctl start redis
# or start Docker container
docker start redis-container
```

##### 2. Network Issues
```bash
# Check firewall rules
iptables -L | grep 6379

# Test connectivity
nc -zv redis-host 6379
```

##### 3. Authentication Issues
```bash
# If Redis requires auth
redis-cli -h host -p 6379 -a password ping

# Set password in configuration
export REDIS_PASSWORD=your-password
```

---

## 🐌 Performance Issues

### High Response Times

#### Symptom
- API responses taking > 500ms
- Health checks timing out
- Thread pool exhaustion warnings

#### Diagnosis Steps
```bash
# 1. Check thread pool status
curl http://localhost:8080/admin/thread-pools

# 2. Monitor cache hit rates
curl http://localhost:8080/admin/health | jq '.components.cache.details.statistics'

# 3. Check JVM memory usage
curl http://localhost:8081/actuator/metrics/jvm.memory.used

# 4. Monitor garbage collection
curl http://localhost:8081/actuator/metrics/jvm.gc.pause
```

#### Solutions

##### 1. Increase Thread Pool Size
```yaml
# application.yml
thread-pools:
  rule-execution:
    core-size: 20
    max-size: 100
```

##### 2. Optimize Cache Configuration
```yaml
cache:
  lru:
    max-size: 500  # Increase cache size
  redis:
    ttl-seconds: 7200  # Longer cache duration
```

##### 3. JVM Tuning
```bash
export JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"
```

### Memory Issues

#### Symptom
- OutOfMemoryError in logs
- High garbage collection frequency
- Application becomes unresponsive

#### Diagnosis
```bash
# Check memory usage
free -h
ps aux | grep java

# JVM memory metrics
curl http://localhost:8081/actuator/metrics/jvm.memory.used
curl http://localhost:8081/actuator/metrics/jvm.memory.max
```

#### Solutions

##### 1. Increase Heap Size
```bash
export JAVA_OPTS="-Xms2g -Xmx4g"
```

##### 2. Enable Memory Analysis
```bash
# Add JVM flags for memory dumps
JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError"
JAVA_OPTS="$JAVA_OPTS -XX:HeapDumpPath=/tmp/heapdump.hprof"
```

##### 3. Optimize Cache Size
```yaml
cache:
  lru:
    max-size: 200  # Reduce cache size if memory constrained
```

### Circuit Breaker Issues

#### Symptom
```json
{
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "External service 's3' is temporarily unavailable (open)"
  }
}
```

#### Diagnosis
```bash
# Check circuit breaker status
curl http://localhost:8080/admin/health | jq '.components."circuit-breakers"'

# Check failure rates
curl http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.state
```

#### Solutions

##### 1. Adjust Failure Thresholds
```yaml
resilience4j:
  circuitbreaker:
    instances:
      s3:
        failure-rate-threshold: 70  # Allow more failures
        wait-duration-in-open-state: 10s  # Shorter wait time
```

##### 2. Manual Reset
```bash
# Circuit breakers reset automatically, but you can restart the service
systemctl restart drools-rule-engine
```

---

## ⚙️ Configuration Problems

### Environment Variable Issues

#### Common Problems
```bash
# Missing variables
echo $RULE_SOURCE          # Should show: s3, local, or memory
echo $RULE_BUCKET_NAME     # Should show your bucket name

# Invalid values
export RULE_SOURCE=invalid  # Should be s3|local|memory
export LRU_CACHE_MAX_SIZE=invalid  # Should be positive integer
```

#### Validation Script
```bash
#!/bin/bash
# validate-env.sh

required_vars=("RULE_SOURCE" "RULE_BUCKET_NAME")
for var in "${required_vars[@]}"; do
  if [ -z "${!var}" ]; then
    echo "ERROR: $var is not set"
    exit 1
  fi
done

# Validate RULE_SOURCE
if [[ ! "$RULE_SOURCE" =~ ^(s3|local|memory)$ ]]; then
  echo "ERROR: RULE_SOURCE must be 's3', 'local', or 'memory'"
  exit 1
fi

# Validate numeric values
if ! [[ "$LRU_CACHE_MAX_SIZE" =~ ^[0-9]+$ ]]; then
  echo "ERROR: LRU_CACHE_MAX_SIZE must be a positive integer"
  exit 1
fi

echo "Environment validation passed"
```

### Profile Configuration Issues

#### Symptom
Application uses wrong configuration for environment.

#### Solution
```bash
# Check active profile
curl http://localhost:8080/admin/info | jq '.activeProfiles'

# Set correct profile
export SPRING_PROFILES_ACTIVE=prod
# or
java -Dspring.profiles.active=prod -jar app.jar
```

---

## 📊 Monitoring & Diagnostics

### Health Check Diagnostics

#### Comprehensive Health Check
```bash
# Detailed health information
curl -s http://localhost:8080/admin/health | jq '.'

# Check specific components
curl -s http://localhost:8080/admin/health | jq '.components.drools'
curl -s http://localhost:8080/admin/health | jq '.components.storage'
curl -s http://localhost:8080/admin/health | jq '.components.cache'
```

#### Health Status Meanings
- **UP**: Component is healthy and operational
- **DOWN**: Component has failed or is unavailable
- **UNKNOWN**: Component status cannot be determined

### Log Analysis

#### Key Log Patterns
```bash
# Find errors
grep -i "error\|exception\|failed" /var/log/drools-rule-engine/application.log

# Rule execution tracking
grep "rule_id" /var/log/drools-rule-engine/application.log

# Performance issues
grep -i "timeout\|slow\|performance" /var/log/drools-rule-engine/application.log

# Circuit breaker events
grep -i "circuit.*breaker\|open\|closed\|half.*open" /var/log/drools-rule-engine/application.log
```

#### Log Analysis Commands
```bash
# Show recent errors with context
grep -A 5 -B 5 "ERROR" /var/log/drools-rule-engine/application.log | tail -50

# Count error types
grep "ERROR" /var/log/drools-rule-engine/application.log | cut -d' ' -f5- | sort | uniq -c | sort -nr

# Monitor logs in real-time
tail -f /var/log/drools-rule-engine/application.log | grep --color=always -E "ERROR|WARN|Exception"
```

### Performance Monitoring

#### Key Metrics to Monitor
```bash
# Rule execution metrics
curl http://localhost:8081/actuator/metrics/drools.rule.execution.count
curl http://localhost:8081/actuator/metrics/drools.rule.execution.duration

# Cache performance
curl http://localhost:8081/actuator/metrics/drools.cache.hits
curl http://localhost:8081/actuator/metrics/drools.cache.misses

# JVM metrics
curl http://localhost:8081/actuator/metrics/jvm.memory.used
curl http://localhost:8081/actuator/metrics/jvm.gc.pause

# HTTP metrics
curl http://localhost:8081/actuator/metrics/http.server.requests
```

---

## 📚 Error Codes Reference

### Application Error Codes

| Code | Description | Typical Cause | Solution |
|------|-------------|---------------|----------|
| `RULE_NOT_FOUND` | Rule doesn't exist | Missing rule file or wrong ID | Upload rule to S3, refresh cache |
| `RULE_COMPILATION_ERROR` | Rule syntax error | Invalid Drools syntax | Fix rule syntax, check imports |
| `RULE_EXECUTION_ERROR` | Runtime error in rule | NPE, ClassCastException | Add null checks, fix type casting |
| `INVALID_INPUT` | Request validation failed | Missing/invalid data | Check request format and validation rules |
| `REQUEST_TOO_LARGE` | Payload exceeds limit | Large request body | Reduce request size or increase limit |
| `RATE_LIMIT_EXCEEDED` | Too many requests | Client exceeded rate limit | Slow down requests or increase limit |
| `TIMEOUT_ERROR` | Operation timed out | Slow rule execution or network | Optimize rules or increase timeout |
| `SERVICE_UNAVAILABLE` | Circuit breaker open | External service failures | Wait for service recovery |
| `STORAGE_ERROR` | S3/storage issue | S3 connectivity problem | Check AWS credentials and connectivity |
| `CACHE_ERROR` | Redis/cache issue | Redis connectivity problem | Check Redis status and connectivity |
| `INTERNAL_ERROR` | Unexpected error | System malfunction | Check logs and system health |

### HTTP Status Codes

| Status | Meaning | When It Occurs |
|--------|---------|----------------|
| 200 | Success | Rule executed successfully |
| 400 | Bad Request | Invalid input or rule error |
| 404 | Not Found | Rule doesn't exist |
| 408 | Request Timeout | Rule execution timeout |
| 413 | Payload Too Large | Request size exceeds limit |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | System error |
| 503 | Service Unavailable | Circuit breaker open |

---

## 🛠️ Common Solutions

### Quick Fixes Checklist

#### Service Not Responding
1. ✅ Check if process is running: `ps aux | grep java`
2. ✅ Check port binding: `netstat -tlnp | grep 8080`
3. ✅ Check logs for errors: `tail -f /var/log/drools-rule-engine/application.log`
4. ✅ Restart service: `systemctl restart drools-rule-engine`

#### Rules Not Loading
1. ✅ Verify S3 connectivity: `aws s3 ls s3://your-bucket`
2. ✅ Check AWS credentials: `aws sts get-caller-identity`
3. ✅ Refresh rule cache: `curl -X POST http://localhost:8080/admin/refresh-rules`
4. ✅ Validate rule syntax: Check .drl files for errors

#### Poor Performance
1. ✅ Check thread pool status: `curl http://localhost:8080/admin/thread-pools`
2. ✅ Monitor cache hit rate: Check health endpoint
3. ✅ Increase JVM memory: Adjust `JAVA_OPTS`
4. ✅ Optimize rule complexity: Simplify rule conditions

#### Memory Issues
1. ✅ Check memory usage: `free -h` and JVM metrics
2. ✅ Increase heap size: Set `-Xmx` parameter
3. ✅ Enable GC logging: Add GC flags to `JAVA_OPTS`
4. ✅ Reduce cache size: Lower `LRU_CACHE_MAX_SIZE`

### Emergency Recovery Procedures

#### Complete Service Recovery
```bash
#!/bin/bash
# emergency-recovery.sh

echo "Starting emergency recovery..."

# 1. Stop service
systemctl stop drools-rule-engine

# 2. Clear caches (if Redis is causing issues)
redis-cli FLUSHALL

# 3. Check and fix configuration
source /opt/drools-rule-engine/config/application.env

# 4. Start with minimal configuration
export REDIS_ENABLED=false
export LRU_CACHE_MAX_SIZE=50

# 5. Restart service
systemctl start drools-rule-engine

# 6. Wait for startup
sleep 30

# 7. Test basic functionality
curl -f http://localhost:8080/admin/health

echo "Emergency recovery completed"
```

#### Data Recovery (if S3 access is lost)
```bash
# Switch to local rule storage temporarily
export RULE_SOURCE=local
export RULE_LOCAL_PATH=/opt/drools-rule-engine/rules

# Copy rules from backup
mkdir -p $RULE_LOCAL_PATH
cp -r /backup/rules/* $RULE_LOCAL_PATH/

# Restart service with local storage
systemctl restart drools-rule-engine
```

---

## 📞 Support Resources

### Self-Service Tools
- **Health Check**: `http://localhost:8080/admin/health`
- **System Info**: `http://localhost:8080/admin/info`
- **Thread Pools**: `http://localhost:8080/admin/thread-pools`
- **Metrics**: `http://localhost:8081/actuator/metrics`

### Documentation Links
- [Configuration Guide](configuration.md)
- [Deployment Guide](deployment.md)
- [Rule Development Guide](rule-development.md)
- [API Documentation](../api-documentation.yml)

### Log Locations
- **Application Logs**: `/var/log/drools-rule-engine/application.log`
- **System Logs**: `journalctl -u drools-rule-engine.service`
- **Access Logs**: `/var/log/nginx/access.log` (if using Nginx)

### Useful Commands Reference
```bash
# Service management
systemctl status drools-rule-engine
systemctl start drools-rule-engine
systemctl stop drools-rule-engine
systemctl restart drools-rule-engine

# Log monitoring
tail -f /var/log/drools-rule-engine/application.log
journalctl -u drools-rule-engine.service -f

# Health checks
curl http://localhost:8080/admin/health
curl http://localhost:8080/admin/rules

# Cache management
curl -X POST http://localhost:8080/admin/refresh-rules
curl -X POST http://localhost:8080/admin/refresh-rules/specific.rule.id

# Performance monitoring
curl http://localhost:8080/admin/thread-pools
curl http://localhost:8081/actuator/metrics/jvm.memory.used
```

### When to Escalate
Contact support if:
- ❌ Service won't start after following troubleshooting steps
- ❌ Data corruption or loss is suspected
- ❌ Security incident or breach is detected
- ❌ Performance degrades significantly with no apparent cause
- ❌ Multiple components are failing simultaneously

### Support Information Template
When contacting support, please provide:

```
Environment: [dev/staging/prod]
Version: [application version]
Timestamp: [when issue occurred]
Symptoms: [what you observed]
Steps taken: [what you tried]
Logs: [relevant log snippets]
Health check: [output of /admin/health]
Configuration: [relevant env vars/config]
```

---

**Last Updated**: 2025-07-22  
**Version**: 1.0.0