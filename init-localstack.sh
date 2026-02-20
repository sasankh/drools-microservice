#!/bin/bash

# LocalStack S3 Initialization Script for Drools Rule Engine
# This script runs when LocalStack is ready and sets up the development environment

set -e

echo "🚀 Starting LocalStack S3 initialization for Drools Rule Engine..."

# Configuration
BUCKET_NAME="local-rules"
AWS_ENDPOINT="http://localhost:4566"
AWS_REGION="us-east-1"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

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

# Wait for LocalStack to be fully ready
echo -e "${YELLOW}⏳ Waiting for LocalStack S3 to be ready...${NC}"
for i in {1..30}; do
    if $AWS_CMD s3 ls > /dev/null 2>&1; then
        echo -e "${GREEN}✅ LocalStack S3 is ready${NC}"
        break
    fi
    echo "Waiting... (attempt $i/30)"
    sleep 2
done

if ! $AWS_CMD s3 ls > /dev/null 2>&1; then
    echo -e "${RED}❌ Failed to connect to LocalStack S3${NC}"
    exit 1
fi

# Create S3 bucket
echo -e "${YELLOW}📦 Creating S3 bucket: ${BUCKET_NAME}...${NC}"
if $AWS_CMD s3 mb s3://${BUCKET_NAME} --region ${AWS_REGION} 2>/dev/null; then
    echo -e "${GREEN}✅ S3 bucket '${BUCKET_NAME}' created successfully${NC}"
else
    echo -e "${YELLOW}⚠️  S3 bucket '${BUCKET_NAME}' may already exist${NC}"
fi

# Verify bucket exists
if $AWS_CMD s3 ls s3://${BUCKET_NAME} > /dev/null 2>&1; then
    echo -e "${GREEN}✅ S3 bucket '${BUCKET_NAME}' is accessible${NC}"
else
    echo -e "${RED}❌ Failed to access S3 bucket '${BUCKET_NAME}'${NC}"
    exit 1
fi

# Resolve sample-rules directory
# In Docker (docker-compose mount): /tmp/sample-rules
# Standalone (run from repo root): ./sample-rules relative to script location
if [ -d "/tmp/sample-rules" ]; then
    RULES_DIR="/tmp/sample-rules"
else
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    RULES_DIR="${SCRIPT_DIR}/sample-rules"
fi

if [ ! -d "$RULES_DIR" ]; then
    echo -e "${RED}❌ Sample rules directory not found at ${RULES_DIR}${NC}"
    echo -e "${RED}   Make sure sample-rules/ exists in the project root${NC}"
    exit 1
fi

# Upload all .drl files from sample-rules directory
echo -e "${YELLOW}📤 Uploading sample rules from ${RULES_DIR}...${NC}"
$AWS_CMD s3 sync "${RULES_DIR}/" s3://${BUCKET_NAME}/ --exclude "README.md"

# List all uploaded rules
echo -e "${YELLOW}📋 Listing all rules in S3...${NC}"
TOTAL_RULES=$($AWS_CMD s3 ls s3://${BUCKET_NAME}/ --recursive | grep '\.drl$' | wc -l)
$AWS_CMD s3 ls s3://${BUCKET_NAME}/ --recursive | grep '\.drl$'

# Set bucket policy for development (optional - for easier access)
echo -e "${YELLOW}🔓 Setting development-friendly bucket policy...${NC}"
cat << EOF > /tmp/bucket-policy.json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": "*",
            "Action": [
                "s3:GetObject",
                "s3:PutObject",
                "s3:DeleteObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::${BUCKET_NAME}",
                "arn:aws:s3:::${BUCKET_NAME}/*"
            ]
        }
    ]
}
EOF

$AWS_CMD s3api put-bucket-policy \
    --bucket ${BUCKET_NAME} \
    --policy file:///tmp/bucket-policy.json > /dev/null 2>&1 || echo -e "${YELLOW}⚠️  Could not set bucket policy (may not be supported)${NC}"
rm -f /tmp/bucket-policy.json

echo -e "${GREEN}✅ Development bucket policy applied${NC}"

# Final verification
echo -e "${YELLOW}🔍 Final verification...${NC}"
if $AWS_CMD s3 ls s3://${BUCKET_NAME}/ > /dev/null 2>&1; then
    echo -e "${GREEN}✅ LocalStack S3 initialization completed successfully${NC}"
    echo -e "${GREEN}   Bucket: ${BUCKET_NAME}${NC}"
    echo -e "${GREEN}   Rules: ${TOTAL_RULES} files${NC}"
    echo -e "${GREEN}   Endpoint: ${AWS_ENDPOINT}${NC}"
    echo ""
    echo -e "${YELLOW}🎯 Ready for Drools Rule Engine development!${NC}"
    echo ""
    echo "Test commands:"
    echo "  $AWS_CMD s3 ls s3://${BUCKET_NAME}/"
    echo "  curl http://localhost:8080/admin/health"
    echo "  curl -X POST http://localhost:8080/execute-rule \\"
    echo "    -H 'Content-Type: application/json' \\"
    echo "    -d '{\"ruleId\": \"pricing.discount.simple\", \"data\": {\"amount\": 100}}'"
else
    echo -e "${RED}❌ LocalStack S3 initialization failed${NC}"
    exit 1
fi

echo -e "${GREEN}🎉 LocalStack S3 initialization complete!${NC}"
