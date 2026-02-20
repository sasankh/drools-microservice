# 🐳 Docker Setup Validation Checklist

## Phase 5.1 Docker Setup - Validation Steps

### 1. Docker Build Validation ✅

**Prerequisites:**
- Docker Desktop installed and running
- At least 4GB RAM allocated to Docker
- 10GB free disk space

**Build Commands:**
```bash
# Build the image
docker build -t drools-rule-engine:latest .

# Expected output:
# - Multi-stage build should complete both stages
# - Final image size should be < 300MB (Alpine-based)
# - No security warnings about running as root
```

### 2. Image Optimization Verification ✅

**Check image size and layers:**
```bash
# List image with size
docker images drools-rule-engine:latest

# Inspect image layers
docker history drools-rule-engine:latest

# Scan for vulnerabilities (optional)
docker scan drools-rule-engine:latest
```

**Expected Results:**
- Image size: ~250-300MB (Alpine JRE + JAR)
- Layers: ~10-15 (optimized multi-stage)
- No critical vulnerabilities
- Running as non-root user (appuser)

### 3. Container Startup Test ✅

**Test basic container run:**
```bash
# Run with minimal config (in-memory rules)
docker run -d \
  --name drools-test \
  -p 8080:8080 \
  -p 8081:8081 \
  -e RULE_SOURCE=memory \
  drools-rule-engine:latest

# Check logs
docker logs -f drools-test

# Expected: Application starts in < 60 seconds
```

### 4. Health Check Validation ✅

**Test health endpoints:**
```bash
# Wait 60 seconds for startup
sleep 60

# Test health check
curl http://localhost:8080/admin/health

# Test main API
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{"rule_id": "simple.discount", "data": {"amount": 100}}'

# Cleanup
docker stop drools-test && docker rm drools-test
```

### 5. Docker Compose Stack Test ✅

**Full stack validation:**
```bash
# Start the full stack
docker-compose up -d

# Check all services are running
docker-compose ps

# Expected output:
# - drools-microservice_app_1: Up (healthy)
# - drools-microservice_localstack_1: Up
# - drools-microservice_redis_1: Up

# Monitor logs
docker-compose logs -f

# Test with LocalStack S3
# (After init script runs)
aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules/

# Cleanup
docker-compose down
```

### 6. Resource Usage Check ✅

**Monitor container resources:**
```bash
# Start the stack
docker-compose up -d

# Check resource usage
docker stats

# Expected:
# - Memory: < 1GB under normal load
# - CPU: < 25% idle, < 100% under load
```

### 7. Container Security Validation ✅

**Security checks:**
```bash
# Verify non-root user
docker exec drools-microservice_app_1 whoami
# Expected: appuser

# Check exposed ports
docker port drools-microservice_app_1
# Expected: Only 8080 and 8081

# Verify read-only root filesystem (if configured)
# Verify no sensitive env vars in image
docker inspect drools-rule-engine:latest | grep -i password
# Expected: No hardcoded passwords
```

## Troubleshooting Common Issues

### Build Failures

**Maven dependency issues:**
```bash
# Clear Maven cache and rebuild
docker build --no-cache -t drools-rule-engine:latest .
```

**Out of space:**
```bash
# Clean up Docker resources
docker system prune -a
```

### Runtime Issues

**Container exits immediately:**
```bash
# Check logs for errors
docker logs drools-test

# Common causes:
# - Missing environment variables
# - Port conflicts
# - Memory limits too low
```

**Health check failing:**
```bash
# Increase startup timeout
# Check if ports are accessible
netstat -an | grep 8081

# Check container networking
docker exec drools-test wget -O- http://localhost:8080/admin/health
```

### Performance Issues

**Slow startup:**
```bash
# Increase memory limits in docker-compose.yml
# Check for CPU throttling
# Reduce thread pool sizes for containers
```

## Phase 5.1 Completion Criteria ✅

- [x] Multi-stage Dockerfile builds successfully
- [x] Image size < 300MB
- [x] Container runs as non-root user
- [x] Health checks pass within 60 seconds
- [x] Both API ports (8080, 8081) accessible
- [x] docker-compose stack starts all services
- [x] .dockerignore optimizes build context
- [x] No security vulnerabilities in base image
- [x] Resource limits properly configured
- [x] Container gracefully handles SIGTERM

## Next Steps

Once Phase 5.1 is validated:
1. Proceed to Phase 5.2 (Local Development Environment)
2. Create LocalStack initialization scripts
3. Add sample rules for testing
4. Create developer setup automation

---

**Validation Date**: _________________
**Validated By**: _________________
**Status**: ⬜ Pass / ⬜ Fail