# 🚀 Simple Start Guide - Testing Generated Rules Locally

This guide shows you exactly where to put your generated Drools rules and how to test them locally.

## 📁 Where to Put Your Generated Rule

### 1. File Location
```bash
# Put your rule in the sample-rules directory following this structure:
sample-rules/
├── {domain}/
│   └── {category}/
│       └── {your-rule-name}.drl

# Example:
sample-rules/
├── pricing/
│   └── discount/
│       └── loyalty-discount.drl
```

### 2. Create Your Rule File
```bash
# Create the directory structure
mkdir -p sample-rules/pricing/discount

# Create your rule file
cat > sample-rules/pricing/discount/loyalty-discount.drl << 'EOF'
package com.company.rules.pricing.discount

import java.util.Map

rule "Loyalty Discount Rule"
    salience 100
    no-loop true
when
    $data : Map()
    eval($data.get("amount") != null)
    eval($data.get("loyaltyYears") != null)
    eval(((Number) $data.get("loyaltyYears")).intValue() >= 2)
then
    Double amount = ((Number) $data.get("amount")).doubleValue();
    int years = ((Number) $data.get("loyaltyYears")).intValue();
    
    double discountPercent = years >= 5 ? 0.15 : 0.10;
    double discount = amount * discountPercent;
    
    $data.put("discount", discount);
    $data.put("finalAmount", amount - discount);
    $data.put("discountReason", years + " year loyalty discount");
end
EOF
```

### 3. Upload to LocalStack S3
```bash
# Make sure LocalStack is running
docker-compose up -d localstack

# Wait a few seconds for it to start
sleep 5

# Upload your rule to LocalStack S3
aws --endpoint-url=http://localhost:4566 \
    s3 cp sample-rules/pricing/discount/loyalty-discount.drl \
    s3://local-rules/pricing/discount/loyalty-discount.drl

# Verify it uploaded
aws --endpoint-url=http://localhost:4566 \
    s3 ls s3://local-rules/pricing/discount/
```

### 4. Start the Application
```bash
# Start the full stack (if not already running)
docker-compose up -d

# Or start just the application with S3 configuration
RULE_SOURCE=s3 \
RULE_BUCKET_NAME=local-rules \
AWS_ENDPOINT=http://localhost:4566 \
mvn spring-boot:run
```

### 5. Test Your Rule
```bash
# Execute your rule via API
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{
    "rule_id": "pricing.discount.loyalty-discount",
    "data": {
      "amount": 100.0,
      "loyaltyYears": 3
    }
  }'

# Expected response:
{
  "rule_id": "pricing.discount.loyalty-discount",
  "result": {
    "amount": 100.0,
    "loyaltyYears": 3,
    "discount": 10.0,
    "finalAmount": 90.0,
    "discountReason": "3 year loyalty discount"
  },
  "error": null,
  "execution_time_ms": 25
}
```

### 6. If Rules Don't Load Automatically
```bash
# Refresh all rules
curl -X POST http://localhost:8081/admin/refresh-rules

# Or refresh just your specific rule
curl -X POST http://localhost:8081/admin/refresh-rules/pricing.discount.loyalty-discount
```

## 🔍 Quick Testing Checklist

1. **Rule file created in**: `sample-rules/{domain}/{category}/{name}.drl` ✓
2. **LocalStack running**: `docker-compose ps` shows localstack UP ✓
3. **Rule uploaded to S3**: Check with `aws --endpoint-url=http://localhost:4566 s3 ls` ✓
4. **Application running**: Main API on port 8080, Admin on 8081 ✓
5. **Test rule execution**: Use curl command with your rule ID ✓

## 💡 Rule ID Mapping

Your rule ID maps to the file path:
- **Rule ID**: `pricing.discount.loyalty-discount`
- **File Path**: `pricing/discount/loyalty-discount.drl`
- **S3 Path**: `s3://local-rules/pricing/discount/loyalty-discount.drl`

## 🚀 One-Command Test Setup

If you want to test quickly:
```bash
# This runs the complete setup including your new rule
./setup-dev-environment.sh --skip-tests
```

## 📝 Quick Rule Template

Here's a minimal rule template to get started:

```drools
package com.company.rules.{domain}.{category}

import java.util.Map

rule "Your Rule Name"
    salience 100
    no-loop true
when
    $data : Map()
    eval($data.get("yourField") != null)
    // Add your conditions here
then
    // Add your actions here
    $data.put("result", "value");
end
```

## 🎯 Common Rule Patterns

### Discount Rule
```drools
rule "Percentage Discount"
when
    $data : Map()
    eval($data.get("amount") != null)
    eval(((Number) $data.get("amount")).doubleValue() > 100)
then
    Double amount = ((Number) $data.get("amount")).doubleValue();
    double discount = amount * 0.10; // 10% discount
    $data.put("discount", discount);
    $data.put("finalAmount", amount - discount);
end
```

### Validation Rule
```drools
rule "Age Validation"
when
    $data : Map()
    eval($data.get("age") != null)
then
    int age = ((Number) $data.get("age")).intValue();
    if (age < 18) {
        $data.put("valid", false);
        $data.put("reason", "Must be 18 or older");
    } else {
        $data.put("valid", true);
    }
end
```

### Conditional Logic Rule
```drools
rule "Customer Type Classification"
when
    $data : Map()
    eval($data.get("purchaseCount") != null)
then
    int purchases = ((Number) $data.get("purchaseCount")).intValue();
    String customerType = "STANDARD";
    
    if (purchases >= 50) {
        customerType = "PLATINUM";
    } else if (purchases >= 20) {
        customerType = "GOLD";
    } else if (purchases >= 10) {
        customerType = "SILVER";
    }
    
    $data.put("customerType", customerType);
end
```

## 🛠️ Troubleshooting

**Rule not found?**
- Check rule ID matches file path pattern
- Verify S3 upload was successful
- Try refreshing rules via admin endpoint

**Rule not firing?**
- Check conditions are met with your test data
- Verify field names match exactly (case-sensitive)
- Check for null values in required fields

**Application won't start?**
- Ensure ports 8080/8081 are free
- Check Docker is running
- Verify LocalStack is healthy

That's it! You're ready to test your generated rules locally.