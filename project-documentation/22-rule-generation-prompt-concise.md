# Drools Rule Generation Assistant Prompt

## Quick Copy Version for AI Assistants

Copy this entire section and provide it to any AI assistant along with your sample payload and requirements:

---

**INSTRUCTION TO AI ASSISTANT:**

You are a Drools rule expert. Help me create a business rule by following these steps:

1. **ANALYZE** my sample payload and understand all fields
2. **ASK CLARIFYING QUESTIONS** about:
   - Exact conditions when rule should fire
   - Calculations or logic to perform  
   - What to add/modify in the response
   - Edge cases and error handling
   - Rule priority vs other rules

3. **SUGGEST NAMING** in format:
   - Rule Name: "Descriptive Name With Spaces"
   - Rule ID: `domain.category.specific` (e.g., `pricing.discount.bulk`)
   - File Path: `domain/category/specific.drl`

4. **SHOW TEST SCENARIOS** before writing code:
   ```
   Scenario 1: [description]
   Input: {payload}
   Expected: {output}
   Rule fires: Yes/No
   ```

5. **GENERATE RULE** using this template:
   ```drools
   package com.company.rules.domain.category
   
   import java.util.Map
   
   rule "Rule Name"
       salience 100  // priority
       no-loop true  // prevent infinite loops
   when
       $data : Map()
       eval($data.get("field") != null)  // null safety
       eval(/* your conditions */)
   then
       // Safe type handling
       Double amount = (Double) $data.get("amount");
       
       // Your business logic
       $data.put("result", value);
       
       // Audit trail
       $data.put("ruleApplied", "rule.id");
   end
   ```

6. **PROVIDE INTEGRATION STEPS**:
   - Where to save the file
   - How to upload to S3/LocalStack
   - How to test via API
   - Expected response format

**IMPORTANT PATTERNS TO FOLLOW:**
- Always check for null values
- Use proper type casting (Double, Integer, String)
- Add rule identification to output
- Handle missing fields gracefully
- Use meaningful field names in output

**COMMON FIELD PATTERNS:**
- Input amount: `amount`, `orderAmount`, `totalAmount`
- Customer info: `customerType`, `customerId`, `membershipLevel`
- Output fields: `discount`, `discountReason`, `finalAmount`
- Validation: `validationResult`, `validationReason`, `errors`

Now, please provide:
1. Your sample payload
2. Description of what the rule should do
3. Any specific business constraints

---

## Usage Example

Here's how to use this prompt:

### Step 1: Copy the prompt above

### Step 2: Add your specific request:
```
=== MY RULE REQUEST ===

SAMPLE PAYLOAD:
{
  "orderId": "ORD-123",
  "customerId": "CUST-456", 
  "orderAmount": 250.00,
  "itemCount": 5,
  "customerTier": "SILVER",
  "isFirstOrder": false
}

WHAT I NEED:
Create a rule that gives a 10% discount to Silver tier customers when they order more than 3 items and the order is over $200. For their first order, make it 15% instead.

BUSINESS CONSTRAINTS:
- Maximum discount should not exceed $50
- This discount should not stack with other discounts
- Should track which discount was applied for reporting
```

### Step 3: The AI will then:
1. Analyze your payload structure
2. Ask clarifying questions about edge cases
3. Suggest appropriate naming
4. Show test scenarios for validation
5. Generate the complete rule file
6. Provide testing instructions

---

## Sample AI Response Structure

The AI should respond with:

```markdown
## Understanding Your Requirement

I'll create a tiered discount rule for Silver customers. Let me clarify a few things:

### 📊 Payload Analysis
- orderId: String identifier
- customerId: String identifier  
- orderAmount: Double (base for discount)
- itemCount: Integer (condition check)
- customerTier: String enum (SILVER check)
- isFirstOrder: Boolean (discount modifier)

### ❓ Clarification Questions
1. If a customer qualifies for multiple discounts, should we track all of them or just apply this one?
2. Should the $50 maximum apply before or after calculating 15% for first orders?
3. What should happen if customerTier is null or not "SILVER"?

### 🏷️ Suggested Naming
- Rule Name: "Silver Tier Volume Discount"
- Rule ID: `pricing.discount.silver-volume`
- File Path: `pricing/discount/silver-volume.drl`

### 🧪 Test Scenarios
[Test cases here]

### 📝 Generated Rule
[Complete Drools rule]

### 🚀 Integration Instructions
[How to deploy and test]
```

---

## Quick Reference Card

Include this with your request for best results:

**MY ENVIRONMENT:**
- Drools Version: 10.2.0
- Spring Boot: 3.5.3
- API Endpoint: POST http://localhost:8080/execute-rule
- S3 Bucket: local-rules (LocalStack)

**PREFERRED OUTPUT STYLE:**
- Add "Applied" suffix to boolean flags (e.g., discountApplied)
- Add "Reason" fields for audit (e.g., discountReason)
- Include original values when modifying (e.g., originalAmount)
- Use camelCase for all field names

This ensures the AI generates rules that match your system's conventions.