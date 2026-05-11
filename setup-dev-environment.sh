#!/bin/bash

# Development Environment Setup Script for Drools Rule Engine
# This script automates the complete local development environment setup

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="${SCRIPT_DIR}/setup.log"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

# Logging function
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "${LOG_FILE}"
}

echo "🚀 Drools Rule Engine - Development Environment Setup"
echo "==============================================="
log "Starting development environment setup"

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to check Docker daemon
check_docker_daemon() {
    if ! docker info >/dev/null 2>&1; then
        echo -e "${RED}❌ Docker daemon is not running${NC}"
        echo "Please start Docker Desktop or Docker daemon before continuing."
        exit 1
    fi
}

# Function to wait for service health
wait_for_service() {
    local service_name="$1"
    local health_url="$2"
    local max_attempts=30
    local attempt=1
    
    echo -e "${YELLOW}⏳ Waiting for $service_name to be healthy...${NC}"
    
    while [ $attempt -le $max_attempts ]; do
        if curl -s "$health_url" >/dev/null 2>&1; then
            echo -e "${GREEN}✅ $service_name is healthy${NC}"
            return 0
        fi
        
        echo "Attempt $attempt/$max_attempts - waiting for $service_name..."
        sleep 10
        attempt=$((attempt + 1))
    done
    
    echo -e "${RED}❌ $service_name failed to become healthy within $(($max_attempts * 10)) seconds${NC}"
    return 1
}

# Function to validate environment
validate_environment() {
    echo -e "${BLUE}🔍 Validating environment...${NC}"
    
    # Check required commands
    for cmd in docker docker-compose mvn curl; do
        if ! command_exists "$cmd"; then
            echo -e "${RED}❌ Required command '$cmd' not found${NC}"
            exit 1
        fi
        echo -e "${GREEN}✅ $cmd is available${NC}"
    done
    
    # Check Docker daemon
    check_docker_daemon
    echo -e "${GREEN}✅ Docker daemon is running${NC}"
    
    # Check Java version
    if command_exists java; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1-2)
        echo -e "${GREEN}✅ Java version: $JAVA_VERSION${NC}"
    else
        echo -e "${YELLOW}⚠️  Java not found in PATH (Docker build will use container Java)${NC}"
    fi
    
    log "Environment validation completed"
}

# Function to clean up previous setup
cleanup_previous() {
    echo -e "${BLUE}🧹 Cleaning up previous setup...${NC}"
    
    # Stop existing containers
    docker-compose down -v 2>/dev/null || true
    
    # Remove test containers
    docker rm -f drools-test 2>/dev/null || true
    
    # Clean up Docker build cache for this project
    docker system prune -f --filter "label=project=drools-rule-engine" 2>/dev/null || true
    
    log "Previous setup cleaned up"
    echo -e "${GREEN}✅ Previous setup cleaned up${NC}"
}

# Function to build application
build_application() {
    echo -e "${BLUE}🔨 Building Drools Rule Engine application...${NC}"
    
    # Clean and build with Maven
    log "Starting Maven build"
    mvn clean package -DskipTests -q
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Maven build completed successfully${NC}"
        log "Maven build completed successfully"
    else
        echo -e "${RED}❌ Maven build failed${NC}"
        log "Maven build failed"
        exit 1
    fi
}

# Function to build Docker image
build_docker_image() {
    echo -e "${BLUE}🐳 Building Docker image...${NC}"
    
    log "Starting Docker image build"
    docker build -t drools-rule-engine:latest . --label "project=drools-rule-engine"
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Docker image built successfully${NC}"
        log "Docker image built successfully"
        
        # Show image info
        IMAGE_SIZE=$(docker images drools-rule-engine:latest --format "{{.Size}}")
        echo -e "${GREEN}   Image size: $IMAGE_SIZE${NC}"
    else
        echo -e "${RED}❌ Docker image build failed${NC}"
        log "Docker image build failed"
        exit 1
    fi
}

# Function to start development stack
start_development_stack() {
    echo -e "${BLUE}🚀 Starting development stack...${NC}"
    
    log "Starting Docker Compose stack"
    docker-compose up -d
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Development stack started${NC}"
        log "Development stack started successfully"
    else
        echo -e "${RED}❌ Failed to start development stack${NC}"
        log "Failed to start development stack"
        exit 1
    fi
}

# Function to verify services
verify_services() {
    echo -e "${BLUE}🔍 Verifying services...${NC}"
    
    # Wait for LocalStack
    echo -e "${YELLOW}⏳ Waiting for LocalStack to initialize...${NC}"
    sleep 20
    
    # Check LocalStack S3
    for i in {1..12}; do
        if curl -s http://localhost:4566/health | grep -q "s3.*available" 2>/dev/null; then
            echo -e "${GREEN}✅ LocalStack S3 is available${NC}"
            break
        fi
        if [ $i -eq 12 ]; then
            echo -e "${RED}❌ LocalStack S3 failed to start${NC}"
            exit 1
        fi
        echo "Waiting for LocalStack S3... (attempt $i/12)"
        sleep 10
    done
    
    # Check Redis
    if wait_for_service "Redis" "redis://localhost:6379"; then
        log "Redis is healthy"
    else
        echo -e "${RED}❌ Redis health check failed${NC}"
        exit 1
    fi
    
    # Wait longer for application
    echo -e "${YELLOW}⏳ Waiting for Drools application to start (this may take up to 2 minutes)...${NC}"
    sleep 30
    
    # Check Drools application
    if wait_for_service "Drools Application" "http://localhost:8080/admin/health"; then
        log "Drools application is healthy"
    else
        echo -e "${RED}❌ Drools application health check failed${NC}"
        echo "Check logs with: docker-compose logs app"
        exit 1
    fi
}

# Function to run integration tests
run_integration_tests() {
    echo -e "${BLUE}🧪 Running integration tests...${NC}"
    
    # Test LocalStack setup
    if [ -f "./scripts/test-localstack.sh" ]; then
        log "Running LocalStack tests"
        chmod +x ./scripts/test-localstack.sh
        ./scripts/test-localstack.sh
        
        if [ $? -eq 0 ]; then
            echo -e "${GREEN}✅ LocalStack integration tests passed${NC}"
            log "LocalStack integration tests passed"
        else
            echo -e "${YELLOW}⚠️  LocalStack integration tests had issues${NC}"
            log "LocalStack integration tests had issues"
        fi
    fi
    
    # Test rule execution
    echo -e "${YELLOW}🔧 Testing rule execution...${NC}"
    
    # Test simple discount rule
    RULE_TEST=$(curl -s -X POST http://localhost:8080/execute-rule \
        -H "Content-Type: application/json" \
        -d '{"ruleId": "pricing.discount.simple", "data": {"amount": 100}}' \
        | grep -o '"success"[[:space:]]*:[[:space:]]*true' || true)
    
    if [ -n "$RULE_TEST" ]; then
        echo -e "${GREEN}✅ Rule execution test passed${NC}"
        log "Rule execution test passed"
    else
        echo -e "${YELLOW}⚠️  Rule execution test failed or rule not loaded${NC}"
        log "Rule execution test failed"
    fi
}

# Function to show status and next steps
show_completion_status() {
    echo ""
    echo "🎉 Development Environment Setup Complete!"
    echo "========================================"
    echo ""
    echo -e "${GREEN}✅ Services Running:${NC}"
    echo "   • Drools Rule Engine:  http://localhost:8080"
    echo "   • Admin API:          http://localhost:8080/admin"
    echo "   • LocalStack S3:      http://localhost:4566"
    echo "   • Redis:              redis://localhost:6379"
    echo ""
    echo -e "${BLUE}📋 Quick Test Commands:${NC}"
    echo "   # Check application health"
    echo "   curl http://localhost:8080/admin/health"
    echo ""
    echo "   # List available rules"
    echo "   curl http://localhost:8080/admin/rules"
    echo ""
    echo "   # Test simple discount rule"
    echo "   curl -X POST http://localhost:8080/execute-rule \\"
    echo "     -H 'Content-Type: application/json' \\"
    echo "     -d '{\"ruleId\": \"pricing.discount.simple\", \"data\": {\"amount\": 100}}'"
    echo ""
    echo -e "${BLUE}🔧 Management Commands:${NC}"
    echo "   # View logs"
    echo "   docker-compose logs -f app"
    echo ""
    echo "   # Stop services"
    echo "   docker-compose down"
    echo ""
    echo "   # Restart services"
    echo "   docker-compose restart"
    echo ""
    echo -e "${BLUE}📁 Important Files:${NC}"
    echo "   • Setup log:          ${LOG_FILE}"
    echo "   • Sample rules:       ./sample-rules/"
    echo "   • Docker compose:     ./docker-compose.yml"
    echo "   • LocalStack test:    ./scripts/test-localstack.sh"
    echo ""
    
    # Show container status
    echo -e "${BLUE}🐳 Container Status:${NC}"
    docker-compose ps
    echo ""
    
    log "Development environment setup completed successfully"
}

# Function to handle cleanup on exit
cleanup_on_exit() {
    if [ $? -ne 0 ]; then
        echo ""
        echo -e "${RED}❌ Setup failed. Check the log file: ${LOG_FILE}${NC}"
        echo ""
        echo "Troubleshooting:"
        echo "1. Check Docker daemon is running"
        echo "2. Ensure ports 8080, 8081, 4566, 6379 are available"
        echo "3. Review logs: docker-compose logs"
        echo "4. Clean up and retry: docker-compose down -v"
    fi
}

# Set trap for cleanup
trap cleanup_on_exit EXIT

# Main execution flow
main() {
    # Parse command line options
    SKIP_BUILD=false
    SKIP_TESTS=false
    FORCE_REBUILD=false
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --skip-build)
                SKIP_BUILD=true
                shift
                ;;
            --skip-tests)
                SKIP_TESTS=true
                shift
                ;;
            --force-rebuild)
                FORCE_REBUILD=true
                shift
                ;;
            -h|--help)
                echo "Usage: $0 [OPTIONS]"
                echo "Options:"
                echo "  --skip-build     Skip Maven and Docker build steps"
                echo "  --skip-tests     Skip integration tests"
                echo "  --force-rebuild  Force rebuild of Docker images"
                echo "  -h, --help       Show this help message"
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                echo "Use --help for usage information"
                exit 1
                ;;
        esac
    done
    
    # Create log file
    touch "${LOG_FILE}"
    
    echo "Setup options:"
    echo "  Skip build: $SKIP_BUILD"
    echo "  Skip tests: $SKIP_TESTS"
    echo "  Force rebuild: $FORCE_REBUILD"
    echo ""
    
    # Execute setup steps
    validate_environment
    
    if [ "$FORCE_REBUILD" = true ]; then
        cleanup_previous
    fi
    
    if [ "$SKIP_BUILD" != true ]; then
        build_application
        build_docker_image
    else
        echo -e "${YELLOW}⚠️  Skipping build steps${NC}"
        log "Build steps skipped"
    fi
    
    start_development_stack
    verify_services
    
    if [ "$SKIP_TESTS" != true ]; then
        run_integration_tests
    else
        echo -e "${YELLOW}⚠️  Skipping integration tests${NC}"
        log "Integration tests skipped"
    fi
    
    show_completion_status
}

# Execute main function with all arguments
main "$@"