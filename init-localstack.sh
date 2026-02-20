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

# Create directory structure for rules
echo -e "${YELLOW}📁 Creating rule directory structure...${NC}"

# Create sample directories in S3
$AWS_CMD s3api put-object \
    --bucket ${BUCKET_NAME} \
    --key pricing/discount/ \
    --content-length 0 > /dev/null 2>&1 || true

$AWS_CMD s3api put-object \
    --bucket ${BUCKET_NAME} \
    --key pricing/shipping/ \
    --content-length 0 > /dev/null 2>&1 || true

$AWS_CMD s3api put-object \
    --bucket ${BUCKET_NAME} \
    --key validation/customer/ \
    --content-length 0 > /dev/null 2>&1 || true

$AWS_CMD s3api put-object \
    --bucket ${BUCKET_NAME} \
    --key seasonal/holiday/ \
    --content-length 0 > /dev/null 2>&1 || true

echo -e "${GREEN}✅ Rule directory structure created${NC}"

# Check for mounted sample rules directory
RULES_DIR="/tmp/sample-rules"
if [ -d "$RULES_DIR" ]; then
    echo -e "${YELLOW}📤 Found mounted sample rules directory, uploading files...${NC}"
    $AWS_CMD s3 sync ${RULES_DIR}/ s3://${BUCKET_NAME}/ --exclude "README.md"
    RULE_COUNT=$($AWS_CMD s3 ls s3://${BUCKET_NAME}/ --recursive | grep '\.drl$' | wc -l)
    echo -e "${GREEN}✅ Uploaded ${RULE_COUNT} sample rule files from mounted directory${NC}"
else
    echo -e "${YELLOW}📝 No mounted sample rules found, creating basic sample rules...${NC}"

# Simple discount rule (compatible with existing DroolsEngineService)
cat > /tmp/simple-discount.drl << 'EOF'
package com.company.rules.pricing.discount

rule "Simple Discount Rule"
when
    $data : Map(this["amount"] != null)
then
    double amount = ((Number) $data.get("amount")).doubleValue();
    $data.put("discount", amount * 0.10);
    $data.put("amount", amount * 0.90);
    $data.put("discountPercent", 10);
end
EOF

# VIP customer rule (compatible with existing DroolsEngineService)
cat > /tmp/vip-discount.drl << 'EOF'
package com.company.rules.pricing.discount

rule "VIP Customer Discount"
when
    $data : Map(this["customerType"] == "VIP", this["amount"] != null)
then
    double amount = ((Number) $data.get("amount")).doubleValue();
    $data.put("discount", amount * 0.20);
    $data.put("amount", amount * 0.80);
    $data.put("discountPercent", 20);
end
EOF

# Bulk order rule 
cat > /tmp/bulk-order.drl << 'EOF'
package com.company.rules.pricing.discount

rule "Bulk Order Discount"
when
    $data : Map(this["quantity"] != null, this["amount"] != null)
then
    int quantity = ((Number) $data.get("quantity")).intValue();
    double amount = ((Number) $data.get("amount")).doubleValue();
    if (quantity >= 10) {
        $data.put("discount", amount * 0.15);
        $data.put("amount", amount * 0.85);
        $data.put("discountPercent", 15);
        $data.put("discountReason", "Bulk order discount");
    }
end
EOF

# Seasonal holiday rule
cat > /tmp/holiday-discount.drl << 'EOF'
package com.company.rules.seasonal.holiday

rule "Holiday Season Discount"
when
    $data : Map(this["isHolidaySeason"] == true, this["amount"] != null)
then
    double amount = ((Number) $data.get("amount")).doubleValue();
    $data.put("discount", amount * 0.12);
    $data.put("amount", amount * 0.88);
    $data.put("discountPercent", 12);
    $data.put("discountReason", "Holiday season special");
end
EOF

# Customer validation rule
cat > /tmp/customer-validation.drl << 'EOF'
package com.company.rules.validation.customer

rule "Customer Age Validation"
when
    $data : Map(this["customerAge"] != null)
then
    Integer age = (Integer) $data.get("customerAge");
    if (age < 18) {
        $data.put("validationResult", "REJECTED");
        $data.put("validationReason", "Customer must be 18 or older");
    } else {
        $data.put("validationResult", "APPROVED");
        $data.put("ageGroup", age >= 65 ? "Senior" : age >= 25 ? "Adult" : "Young Adult");
    }
end
EOF

    # Upload basic sample rules to S3
    echo -e "${YELLOW}📤 Uploading basic sample rules to S3...${NC}"

    # Upload discount rules
    $AWS_CMD s3 cp /tmp/simple-discount.drl s3://${BUCKET_NAME}/pricing/discount/simple.drl
    $AWS_CMD s3 cp /tmp/vip-discount.drl s3://${BUCKET_NAME}/pricing/discount/vip.drl
    $AWS_CMD s3 cp /tmp/bulk-order.drl s3://${BUCKET_NAME}/pricing/discount/bulk.drl

    # Upload seasonal rules
    $AWS_CMD s3 cp /tmp/holiday-discount.drl s3://${BUCKET_NAME}/seasonal/holiday/discount.drl

    # Upload validation rules
    $AWS_CMD s3 cp /tmp/customer-validation.drl s3://${BUCKET_NAME}/validation/customer/age.drl

    # Cleanup temp files
    rm -f /tmp/simple-discount.drl /tmp/vip-discount.drl /tmp/bulk-order.drl /tmp/holiday-discount.drl /tmp/customer-validation.drl
    
    echo -e "${GREEN}✅ Created and uploaded 5 basic sample rules${NC}"
fi

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
    echo "  curl http://localhost:8081/admin/health"
    echo "  curl -X POST http://localhost:8080/execute-rule \\"
    echo "    -H 'Content-Type: application/json' \\"
    echo "    -d '{\"ruleId\": \"pricing.discount.simple\", \"data\": {\"amount\": 100}}'"
else
    echo -e "${RED}❌ LocalStack S3 initialization failed${NC}"
    exit 1
fi

# Clean up temporary files
rm -f /tmp/simple-discount.drl /tmp/vip-discount.drl /tmp/bulk-order.drl /tmp/holiday-discount.drl /tmp/customer-validation.drl

echo -e "${GREEN}🎉 LocalStack S3 initialization complete!${NC}"