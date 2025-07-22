# Sample Drools Rules for Testing

This directory contains sample business rules that can be used for testing the Drools Rule Engine microservice.

## Directory Structure

```
sample-rules/
├── pricing/
│   ├── discount/          # Discount calculation rules
│   └── shipping/          # Shipping cost calculation rules
├── validation/
│   └── customer/          # Customer validation rules
└── seasonal/
    └── holiday/           # Seasonal promotion rules
```

## Rule Categories

### Pricing Rules

#### Discount Rules (`pricing/discount/`)
- **simple.drl** - 10% discount for orders over $50
- **vip.drl** - 20% discount for VIP customers
- **bulk.drl** - 15% discount for orders with 10+ items
- **first-time.drl** - 5% welcome discount for new customers

#### Shipping Rules (`pricing/shipping/`)
- **standard.drl** - Standard shipping cost calculation
- **express.drl** - Express shipping with free shipping over $100

### Validation Rules

#### Customer Validation (`validation/customer/`)
- **age.drl** - Age validation and categorization
- **credit.drl** - Credit score validation and approval limits

### Seasonal Rules

#### Holiday Promotions (`seasonal/holiday/`)
- **discount.drl** - 12% holiday season discount
- **blackfriday.drl** - 25% Black Friday promotion (minimum $100)

## Usage Examples

### Testing Simple Discount Rule
```bash
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{
    "ruleId": "pricing.discount.simple",
    "data": {"amount": 100.0}
  }'
```

### Testing VIP Customer Discount
```bash
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{
    "ruleId": "pricing.discount.vip",
    "data": {"customerType": "VIP", "amount": 100.0}
  }'
```

### Testing Bulk Order Discount
```bash
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{
    "ruleId": "pricing.discount.bulk",
    "data": {"quantity": 15, "amount": 200.0}
  }'
```

### Testing Express Shipping
```bash
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{
    "ruleId": "pricing.shipping.express",
    "data": {"shippingType": "express", "weight": 2.5, "amount": 120.0}
  }'
```

### Testing Customer Age Validation
```bash
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{
    "ruleId": "validation.customer.age",
    "data": {"customerAge": 25}
  }'
```

### Testing Black Friday Promotion
```bash
curl -X POST http://localhost:8080/execute-rule \
  -H "Content-Type: application/json" \
  -d '{
    "ruleId": "seasonal.holiday.blackfriday",
    "data": {"promotionCode": "BLACK2024", "amount": 150.0}
  }'
```

## Rule ID Mapping

The rule ID follows the pattern: `<package>.<category>.<rule-name>`

Examples:
- `pricing.discount.simple` → `pricing/discount/simple.drl`
- `validation.customer.age` → `validation/customer/age.drl`
- `seasonal.holiday.discount` → `seasonal/holiday/discount.drl`

## Uploading Rules to LocalStack

To upload these sample rules to LocalStack S3:

```bash
# Start LocalStack
docker-compose up -d localstack

# Upload rules (automatically done by init-localstack.sh)
aws --endpoint-url=http://localhost:4566 s3 sync sample-rules/ s3://local-rules/

# List uploaded rules
aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules/ --recursive
```

## Expected Response Format

All rules return modified data objects with additional fields:
- `discount` - Discount amount applied
- `discountPercent` - Percentage discount
- `discountReason` - Explanation of the discount
- `validationResult` - For validation rules (APPROVED/REJECTED)
- `validationReason` - Explanation for validation decisions

Example response:
```json
{
  "success": true,
  "result": {
    "amount": 90.0,
    "discount": 10.0,
    "discountPercent": 10,
    "discountReason": "Order over $50 discount"
  },
  "executionTimeMs": 15,
  "ruleId": "pricing.discount.simple",
  "timestamp": "2025-07-22T18:30:00Z"
}
```