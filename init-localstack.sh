#!/bin/bash

echo "Initializing LocalStack S3 environment..."

# Wait for LocalStack to be ready
sleep 5

# Create the S3 bucket
awslocal s3 mb s3://local-rules

# Create directory structure and upload sample rules
echo "Creating sample rules..."

# Sample discount rule
cat > /tmp/simple-discount.drl << 'EOF'
package com.company.rules.pricing.discount

import java.util.Map
import java.util.HashMap

rule "Simple Discount Rule - 10%"
when
    $data : Map(this["amount"] != null, (Double)this["amount"] > 50.0)
then
    Map result = new HashMap();
    Double amount = (Double) $data.get("amount");
    Double discount = amount * 0.10; // 10% discount
    
    result.put("amount", amount);
    result.put("discount", discount);
    result.put("final_amount", amount - discount);
    result.put("applied_rule", "simple-discount");
    
    $data.put("result", result);
end
EOF

# VIP customer rule
cat > /tmp/vip-discount.drl << 'EOF'
package com.company.rules.pricing.discount

import java.util.Map
import java.util.HashMap

rule "VIP Customer Rule - 20%"
when
    $data : Map(
        this["customer_tier"] == "vip",
        this["amount"] != null,
        (Double)this["amount"] > 25.0
    )
then
    Map result = new HashMap();
    Double amount = (Double) $data.get("amount");
    Double discount = amount * 0.20; // 20% discount for VIP
    
    result.put("amount", amount);
    result.put("discount", discount);
    result.put("final_amount", amount - discount);
    result.put("applied_rule", "vip-discount");
    result.put("customer_tier", "vip");
    
    $data.put("result", result);
end
EOF

# Black Friday promotion rule
cat > /tmp/black-friday.drl << 'EOF'
package com.company.rules.pricing.promotion

import java.util.Map
import java.util.HashMap

rule "Black Friday 2024 - 25% Off"
when
    $data : Map(
        this["amount"] != null,
        (Double)this["amount"] > 100.0,
        this["promotion_code"] == "BLACK2024"
    )
then
    Map result = new HashMap();
    Double amount = (Double) $data.get("amount");
    Double discount = amount * 0.25; // 25% discount
    
    result.put("amount", amount);
    result.put("discount", discount);
    result.put("final_amount", amount - discount);
    result.put("applied_rule", "black-friday-2024");
    result.put("promotion", "Black Friday 2024");
    
    $data.put("result", result);
end
EOF

# Upload rules to S3 with hierarchical structure
echo "Uploading sample rules to S3..."

# Upload discount rules
awslocal s3 cp /tmp/simple-discount.drl s3://local-rules/pricing/discount/simple.drl
awslocal s3 cp /tmp/vip-discount.drl s3://local-rules/pricing/discount/vip.drl

# Upload promotion rules
awslocal s3 cp /tmp/black-friday.drl s3://local-rules/pricing/promotion/black-friday-2024.drl

# List uploaded files
echo "S3 bucket contents:"
awslocal s3 ls s3://local-rules --recursive

echo "LocalStack S3 initialization complete!"

# Clean up temporary files
rm -f /tmp/simple-discount.drl /tmp/vip-discount.drl /tmp/black-friday.drl