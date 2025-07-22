#!/bin/bash
# Docker Build and Test Script for Drools Rule Engine Microservice

set -e  # Exit on error

echo "======================================"
echo "🐳 Docker Build Test Script"
echo "======================================"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}❌ Docker is not running. Please start Docker Desktop.${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Docker is running${NC}"

# Build the Docker image
echo -e "\n${YELLOW}📦 Building Docker image...${NC}"
docker build -t drools-rule-engine:latest . || {
    echo -e "${RED}❌ Docker build failed${NC}"
    exit 1
}

echo -e "${GREEN}✅ Docker image built successfully${NC}"

# Check image size
echo -e "\n${YELLOW}📊 Checking image size...${NC}"
docker images drools-rule-engine:latest

# Test run the container
echo -e "\n${YELLOW}🚀 Testing container startup...${NC}"
docker run -d \
  --name drools-test \
  -p 9080:8080 \
  -p 9081:8081 \
  -e RULE_SOURCE=memory \
  -e LOG_LEVEL=INFO \
  drools-rule-engine:latest || {
    echo -e "${RED}❌ Container failed to start${NC}"
    exit 1
}

echo -e "${GREEN}✅ Container started successfully${NC}"

# Wait for application to start
echo -e "\n${YELLOW}⏳ Waiting for application to start (60 seconds)...${NC}"
sleep 60

# Check health endpoint
echo -e "\n${YELLOW}🏥 Checking health endpoint...${NC}"
if curl -f http://localhost:9081/admin/health > /dev/null 2>&1; then
    echo -e "${GREEN}✅ Health check passed${NC}"
    curl -s http://localhost:9081/admin/health | jq '.' || echo "Health response received"
else
    echo -e "${RED}❌ Health check failed${NC}"
    echo "Container logs:"
    docker logs drools-test
fi

# Cleanup
echo -e "\n${YELLOW}🧹 Cleaning up...${NC}"
docker stop drools-test > /dev/null 2>&1
docker rm drools-test > /dev/null 2>&1
echo -e "${GREEN}✅ Cleanup complete${NC}"

echo -e "\n${GREEN}🎉 Docker build test completed!${NC}"
echo "
Next steps:
1. Run 'docker-compose up' to start the full stack with LocalStack and Redis
2. Use 'docker-compose logs -f' to monitor logs
3. Access the API at http://localhost:8080
4. Access admin endpoints at http://localhost:8081

Docker Test Results:
- Container tested on ports 9080 (API) and 9081 (Admin)
- Production docker-compose uses standard ports 8080/8081
"