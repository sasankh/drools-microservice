#!/bin/bash

# Test LocalStack S3 setup for Drools Rule Engine
# This script validates that LocalStack is properly initialized with sample rules

set -e

# Configuration
BUCKET_NAME="local-rules"
AWS_ENDPOINT="http://localhost:4566"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo "🧪 Testing LocalStack S3 setup for Drools Rule Engine..."

# Function to check if awslocal is available, fallback to aws
check_aws_cli() {
    if command -v awslocal > /dev/null 2>&1; then
        echo "awslocal"
    elif command -v aws > /dev/null 2>&1; then
        echo "aws --endpoint-url=${AWS_ENDPOINT}"
    else
        echo -e "${RED}❌ Neither awslocal nor aws CLI found${NC}"
        exit 1
    fi
}

AWS_CMD=$(check_aws_cli)

# Test 1: Check if LocalStack is running
echo -e "${YELLOW}1. Testing LocalStack connectivity...${NC}"
if $AWS_CMD s3 ls > /dev/null 2>&1; then
    echo -e "${GREEN}✅ LocalStack is running and accessible${NC}"
else
    echo -e "${RED}❌ LocalStack is not accessible${NC}"
    echo "   Make sure LocalStack is running: docker-compose up -d localstack"
    exit 1
fi

# Test 2: Check if bucket exists
echo -e "${YELLOW}2. Testing S3 bucket existence...${NC}"
if $AWS_CMD s3 ls s3://${BUCKET_NAME} > /dev/null 2>&1; then
    echo -e "${GREEN}✅ S3 bucket '${BUCKET_NAME}' exists${NC}"
else
    echo -e "${RED}❌ S3 bucket '${BUCKET_NAME}' does not exist${NC}"
    echo "   Run the initialization script: ./init-localstack.sh"
    exit 1
fi

# Test 3: Check if rules are uploaded
echo -e "${YELLOW}3. Testing rule files in S3...${NC}"
RULE_COUNT=$($AWS_CMD s3 ls s3://${BUCKET_NAME}/ --recursive | grep '\.drl$' | wc -l)
if [ "$RULE_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✅ Found ${RULE_COUNT} rule files in S3${NC}"
    echo "Rules found:"
    $AWS_CMD s3 ls s3://${BUCKET_NAME}/ --recursive | grep '\.drl$' | sed 's/^/   /'
else
    echo -e "${RED}❌ No rule files found in S3${NC}"
    exit 1
fi

# Test 4: Download and validate a sample rule
echo -e "${YELLOW}4. Testing rule file content...${NC}"
SAMPLE_RULE="pricing/discount/simple.drl"
if $AWS_CMD s3 cp s3://${BUCKET_NAME}/${SAMPLE_RULE} /tmp/test-rule.drl > /dev/null 2>&1; then
    if grep -q "Simple Discount Rule" /tmp/test-rule.drl; then
        echo -e "${GREEN}✅ Sample rule content is valid${NC}"
    else
        echo -e "${RED}❌ Sample rule content is invalid${NC}"
    fi
    rm -f /tmp/test-rule.drl
else
    echo -e "${RED}❌ Could not download sample rule${NC}"
    exit 1
fi

# Test 5: Check if Drools application can access LocalStack (if running)
echo -e "${YELLOW}5. Testing Drools application connectivity (optional)...${NC}"
if curl -s http://localhost:8080/admin/health > /dev/null 2>&1; then
    echo -e "${GREEN}✅ Drools application is running${NC}"
    
    # Test rule execution
    RULE_TEST=$(curl -s -X POST http://localhost:8080/execute-rule \
        -H "Content-Type: application/json" \
        -d '{"ruleId": "pricing.discount.simple", "data": {"amount": 100}}' | jq -r '.success' 2>/dev/null)
    
    if [ "$RULE_TEST" = "true" ]; then
        echo -e "${GREEN}✅ Rule execution test passed${NC}"
    else
        echo -e "${YELLOW}⚠️  Rule execution test failed (rule may not be loaded yet)${NC}"
    fi
else
    echo -e "${YELLOW}⚠️  Drools application is not running${NC}"
    echo "   Start with: docker-compose up -d app"
fi

echo ""
echo -e "${GREEN}🎉 LocalStack S3 setup test completed!${NC}"
echo ""
echo "Next steps:"
echo "1. Start full stack: docker-compose up -d"
echo "2. Test rule execution:"
echo "   curl -X POST http://localhost:8080/execute-rule \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"ruleId\": \"pricing.discount.simple\", \"data\": {\"amount\": 100}}'"
echo ""