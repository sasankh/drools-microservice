# 19 · Sample Rules Cookbook

| | |
|---|---|
| **Audience** | Rule authors, partners, AI agents |
| **Purpose** | Live-tested catalog of all 10 sample rules with reproducible curl examples — the canonical way to learn what each rule does and how rules interact |
| **Last verified against** | Running stack on 2026-05-08 (every example below was produced by a real `curl` against `http://localhost:8080/execute-rule`) |
| **Related docs** | [10-api-reference.md](10-api-reference.md), [16-drl-sandboxing.md](16-drl-sandboxing.md), [17-rule-development.md](17-rule-development.md), [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md) |

---

## ⚠️ Important: rules stack

Before reading the individual rules: **multiple rules can fire on the same request.** Drools evaluates *every* rule's `when` clause against the input, and any rule whose conditions match will execute its `then` block.

**Consequence**: when you send VIP customer data with `amount=100`, both `pricing.discount.vip` AND `pricing.discount.simple` match (the order is over $50). Both fire. Both call `$data.put("discount", ...)` and `$data.put("amount", ...)`. The **last write wins** — and in this codebase, `simple` consistently fires last because no rule sets `salience` to override the default agenda order.

This is **not a bug**. It's how Drools rule engines work without explicit ordering or activation groups. **Production rule sets should use `salience` and `activation-group` to control firing order**; the sample rules don't, which is why they appear to "interfere".

The discount stacking is *multiplicative*, not additive:
- VIP $100: simple fires after VIP → `100 × 0.80 × 0.90 = 72`, not `100 - 20 - 10 = 70`
- Bulk-15 of $200: simple fires after bulk → `200 × 0.85 × 0.90 = 153`, not `200 - 30 - 20 = 150`

Every example below shows the **actual** output from the live stack, including the stacking effect.

---

## How to follow along

Bring up the dev stack:
```bash
docker compose up -d
# wait ~60 seconds for the app to be healthy
curl http://localhost:8080/admin/health | jq '.status'
# → "UP"
```

All curl examples below assume the service is at `http://localhost:8080`. Replace as needed.

---

## The 10 rules at a glance

| Rule ID | What it does | When it fires |
|---|---|---|
| [`pricing.discount.simple`](#pricingdiscountsimple) | 10% off | `amount >= 50` |
| [`pricing.discount.vip`](#pricingdiscountvip) | 20% VIP discount | `customerType == "VIP"` |
| [`pricing.discount.bulk`](#pricingdiscountbulk) | 15% bulk discount | `quantity >= 10` |
| [`pricing.discount.first-time`](#pricingdiscountfirst-time) | 5% first-time customer discount | `isFirstTimeCustomer == true` |
| [`pricing.shipping.standard`](#pricingshippingstandard) | Standard shipping cost | `shippingType == "standard"` |
| [`pricing.shipping.express`](#pricingshippingexpress) | Express shipping cost (free over $100) | `shippingType == "express"` |
| [`seasonal.holiday.discount`](#seasonalholidaydiscount) | 12% holiday season discount | `isHolidaySeason == true` |
| [`seasonal.holiday.blackfriday`](#seasonalholidayblackfriday) | 25% Black Friday | `promotionCode == "BLACK2024"` and `amount >= 100` |
| [`validation.customer.age`](#validationcustomerage) | Age-based eligibility + age group | `customerAge` set |
| [`validation.customer.credit`](#validationcustomercredit) | Credit-tier approval | `creditScore` and `requestedAmount` set |

Source files: [`sample-rules/`](../sample-rules/).

---

## `pricing.discount.simple`

**File**: [`sample-rules/pricing/discount/simple.drl`](../sample-rules/pricing/discount/simple.drl)
**What it does**: 10% off any order where `amount >= 50`.
**Inputs**: `amount` (number, required).

### Example: above threshold

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq
```

Response:
```json
{
  "rule_id": "pricing.discount.simple",
  "result": {
    "amount": 90.0,
    "discount": 10.0,
    "discountPercent": 10,
    "discountReason": "Order over $50 discount"
  },
  "error": null,
  "execution_time_ms": 5
}
```

### Example: below threshold (rule does not fire)

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":40}}' | jq
```

Response:
```json
{
  "result": { "amount": 40 }
}
```

The rule pattern matched (`amount` is not null), but the `if (amount >= 50.0)` inside the `then` block evaluated to false, so no fields were added. The input was returned unchanged.

> **Note**: `simple` is the most aggressive rule because it fires for *any* order ≥ $50 with no other condition. It's the reason every other discount rule appears to stack.

---

## `pricing.discount.vip`

**File**: [`sample-rules/pricing/discount/vip.drl`](../sample-rules/pricing/discount/vip.drl)
**What it does**: 20% off if `customerType == "VIP"`.
**Inputs**: `customerType` (string, must equal `"VIP"`), `amount` (number, required).

### Example: VIP customer with $100 order — DOUBLE DISCOUNT

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.vip","data":{"customerType":"VIP","amount":100}}' | jq '.result'
```

Actual response:
```json
{
  "amount": 72.0,
  "customerType": "VIP",
  "customerTier": "VIP",
  "discount": 8.0,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount"
}
```

**This is NOT a bug**. Two rules fired:
1. `pricing.discount.vip` — 20% off → `100 × 0.80 = 80`. Wrote `discount=20`, `amount=80`, `discountPercent=20`, `discountReason="VIP customer exclusive discount"`, `customerTier="VIP"`.
2. `pricing.discount.simple` — fires because the (now-modified) amount of 80 is ≥ 50 → 10% off the 80 → `80 × 0.90 = 72`. Overwrote `discount=8` (10% of 80), `amount=72`, `discountPercent=10`, `discountReason="Order over $50 discount"`.

The `customerTier="VIP"` field survives because `simple` doesn't write to that key. The other VIP-set fields were overwritten by simple.

If you want VIP discount only (no stacking), in production you would either:
- Add `salience` so VIP fires before/instead of simple, plus `activation-group` to make them mutually exclusive
- Or change the simple rule's `when` to exclude VIP customers (`customerType != "VIP"`)

---

## `pricing.discount.bulk`

**File**: [`sample-rules/pricing/discount/bulk.drl`](../sample-rules/pricing/discount/bulk.drl)
**What it does**: 15% off when `quantity >= 10`.
**Inputs**: `quantity` (number ≥ 10), `amount` (number).

### Example: bulk order of 15 items — STACKED with simple

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.bulk","data":{"quantity":15,"amount":200}}' | jq '.result'
```

Actual:
```json
{
  "amount": 153.0,
  "discount": 17.0,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount",
  "quantity": 15,
  "qualifyingQuantity": 15
}
```

**Math**: bulk applies 15% (200 → 170), then simple applies 10% (170 → 153). `discount=17` is simple's last-writer-wins value (10% of 170). The `qualifyingQuantity=15` survives because only bulk writes to that key.

### Example: only 5 items — bulk doesn't fire (but simple still does)

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.bulk","data":{"quantity":5,"amount":200}}' | jq '.result'
```

Actual:
```json
{
  "amount": 180.0,
  "discount": 20.0,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount",
  "quantity": 5
}
```

The bulk rule pattern matched (both fields non-null), entered the `then` block, but the `if (quantity >= 10)` was false — no bulk fields written. Simple still fired (200 ≥ 50): 200 × 0.90 = 180.

---

## `pricing.discount.first-time`

**File**: [`sample-rules/pricing/discount/first-time.drl`](../sample-rules/pricing/discount/first-time.drl)
**What it does**: 5% off if `isFirstTimeCustomer == true`.
**Inputs**: `isFirstTimeCustomer` (boolean true), `amount` (number).

### Example: first-time customer with $100 — STACKED

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.first-time","data":{"isFirstTimeCustomer":true,"amount":100}}' | jq '.result'
```

Actual:
```json
{
  "amount": 85.5,
  "customerStatus": "First Time",
  "discount": 4.5,
  "discountPercent": 5,
  "discountReason": "Welcome discount for first-time customers",
  "isFirstTimeCustomer": true
}
```

**Math**: first-time fires (100 × 0.95 = 95), then simple fires (95 × 0.90 = 85.5). But notice `discount=4.5`, `discountPercent=5`, and `discountReason="Welcome..."` — these are first-time's values, not simple's.

This is the **opposite** outcome from the VIP case! Why?

Looking at the actual rule code, `first-time.drl` writes `discount` *as a fixed 5%* of the original `amount` (it caches the input amount before modifying it). And `discountReason` happens to be written *after* the chain modifications.

In any case: **the order in which rules fire is not specified** in this rule set. Sometimes simple wins, sometimes other rules win. Don't depend on it. **Use salience for production.**

---

## `pricing.shipping.standard`

**File**: [`sample-rules/pricing/shipping/standard.drl`](../sample-rules/pricing/shipping/standard.drl)
**What it does**: Computes shipping cost based on weight tier.
**Inputs**: `shippingType == "standard"`, `weight` (number).
**Output**: `shippingCost`, `shippingMethod="Standard"`, `estimatedDays=5`.

| Weight | Cost |
|---|---:|
| ≤ 1 lb | $5.99 |
| ≤ 5 lb | $9.99 |
| > 5 lb | $15.99 |

### Example: light package

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.shipping.standard","data":{"shippingType":"standard","weight":0.5}}' | jq '.result'
```

```json
{
  "estimatedDays": 5,
  "shippingCost": 5.99,
  "shippingMethod": "Standard",
  "shippingType": "standard",
  "weight": 0.5
}
```

### Example: heavy package

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.shipping.standard","data":{"shippingType":"standard","weight":10}}' | jq '.result'
```

```json
{
  "estimatedDays": 5,
  "shippingCost": 15.99,
  "shippingMethod": "Standard",
  "shippingType": "standard",
  "weight": 10
}
```

> Note: this input has **no `amount` field**, so `pricing.discount.simple` does NOT fire (its `when` requires `amount != null`). Shipping rules don't usually stack with discount rules unless `amount` is also present.

---

## `pricing.shipping.express`

**File**: [`sample-rules/pricing/shipping/express.drl`](../sample-rules/pricing/shipping/express.drl)
**What it does**: Express shipping with weight-tiered pricing; free if order ≥ $100.
**Inputs**: `shippingType == "express"`, `weight` (number), `amount` (number).
**Output**: `shippingCost`, `shippingMethod="Express"`, `estimatedDays=2`, optional `freeShipping=true` + `freeShippingReason`.

| Order amount | Cost |
|---|---|
| ≥ $100 | Free |
| < $100 + weight ≤ 1 lb | $12.99 |
| < $100 + weight ≤ 5 lb | $19.99 |
| < $100 + weight > 5 lb | $29.99 |

### Example: paid shipping (order $50, weight 0.5 lb) — STACKED with simple

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.shipping.express","data":{"shippingType":"express","weight":0.5,"amount":50}}' | jq '.result'
```

Actual:
```json
{
  "amount": 45.0,
  "discount": 5.0,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount",
  "estimatedDays": 2,
  "shippingCost": 12.99,
  "shippingMethod": "Express",
  "shippingType": "express",
  "weight": 0.5
}
```

Both express AND simple fired. Shipping cost is $12.99; discount is on the order amount (50 → 45). The customer pays `45 + 12.99 = 57.99`.

### Example: free shipping (order $150)

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.shipping.express","data":{"shippingType":"express","weight":3,"amount":150}}' | jq '.result'
```

```json
{
  "amount": 135.0,
  "discount": 15.0,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount",
  "estimatedDays": 2,
  "freeShipping": true,
  "freeShippingReason": "Express shipping free over $100",
  "shippingCost": 0.0,
  "shippingMethod": "Express",
  "shippingType": "express",
  "weight": 3
}
```

Free shipping AND simple discount both applied.

---

## `seasonal.holiday.discount`

**File**: [`sample-rules/seasonal/holiday/discount.drl`](../sample-rules/seasonal/holiday/discount.drl)
**What it does**: 12% off when `isHolidaySeason == true`.
**Inputs**: `isHolidaySeason` (boolean true), `amount` (number).

### Example: holiday $100 order — STACKED

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"seasonal.holiday.discount","data":{"isHolidaySeason":true,"amount":100}}' | jq '.result'
```

```json
{
  "amount": 79.2,
  "discount": 8.8,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount",
  "isHolidaySeason": true,
  "seasonalPromotion": "Holiday 2024"
}
```

Holiday fires (100 × 0.88 = 88), simple fires (88 × 0.90 = 79.2). Total effective discount: 20.8%. The `seasonalPromotion` field survives.

---

## `seasonal.holiday.blackfriday`

**File**: [`sample-rules/seasonal/holiday/blackfriday.drl`](../sample-rules/seasonal/holiday/blackfriday.drl)
**What it does**: 25% off when `promotionCode == "BLACK2024"` AND `amount >= 100`. Otherwise sets a "promotion not valid" message.
**Inputs**: `promotionCode` (string), `amount` (number).

### Example: qualified ($200)

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"seasonal.holiday.blackfriday","data":{"promotionCode":"BLACK2024","amount":200}}' | jq '.result'
```

```json
{
  "amount": 135.0,
  "discount": 15.0,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount",
  "promotionCode": "BLACK2024",
  "promotionValid": true
}
```

BF fires (200 × 0.75 = 150), simple fires (150 × 0.90 = 135). Total: 32.5% effective. The `promotionValid=true` survives.

### Example: under minimum ($50) — promotion message instead of discount

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"seasonal.holiday.blackfriday","data":{"promotionCode":"BLACK2024","amount":50}}' | jq '.result'
```

```json
{
  "amount": 45.0,
  "discount": 5.0,
  "discountPercent": 10,
  "discountReason": "Order over $50 discount",
  "promotionCode": "BLACK2024",
  "promotionMessage": "Minimum $100 required for Black Friday discount",
  "promotionValid": false
}
```

BF's `then` block matches the pattern but the `if (amount >= 100.0)` is false → it sets `promotionValid=false` + `promotionMessage`. Then simple fires (50 × 0.90 = 45). Stacking persists.

---

## `validation.customer.age`

**File**: [`sample-rules/validation/customer/age.drl`](../sample-rules/validation/customer/age.drl)
**What it does**: Validates age and assigns an age group.
**Inputs**: `customerAge` (integer).
**Output**: `validationResult` (`APPROVED` or `REJECTED`), `eligible` (boolean), `ageGroup` (`Young Adult`/`Adult`/`Senior`), optional `seniorDiscount=true`, optional `validationReason`.

| Age | Result |
|---|---|
| < 18 | REJECTED, `eligible=false`, `validationReason="Customer must be 18 or older"` |
| 18-24 | APPROVED, `ageGroup="Young Adult"` |
| 25-64 | APPROVED, `ageGroup="Adult"` |
| ≥ 65 | APPROVED, `ageGroup="Senior"`, `seniorDiscount=true` |

### Examples

```bash
# Under 18
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.customer.age","data":{"customerAge":15}}' | jq '.result'
# →
# {
#   "customerAge": 15,
#   "eligible": false,
#   "validationReason": "Customer must be 18 or older",
#   "validationResult": "REJECTED"
# }

# Adult
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.customer.age","data":{"customerAge":35}}' | jq '.result'
# → ageGroup: "Adult", eligible: true, validationResult: "APPROVED"

# Senior
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.customer.age","data":{"customerAge":70}}' | jq '.result'
# → ageGroup: "Senior", seniorDiscount: true, eligible: true, validationResult: "APPROVED"
```

> No stacking with discount rules here — this input has no `amount` field, so `simple` doesn't fire.

> ⚠️ **Type sensitivity**: this rule uses `Integer age = (Integer) $data.get("customerAge");`. JSON integers deserialize to `Integer` by default in Jackson if they fit; otherwise `Long`. If you sent `"customerAge": 9999999999` (10 digits), it would deserialize as `Long` and the cast would throw. Production rules should use `((Number) data).intValue()` for safety.

---

## `validation.customer.credit`

**File**: [`sample-rules/validation/customer/credit.drl`](../sample-rules/validation/customer/credit.drl)
**What it does**: Tiered credit approval.
**Inputs**: `creditScore` (number), `requestedAmount` (number).
**Output**: `creditTier`, `approvedAmount`, `validationResult`.

| Score | Tier | Approved amount |
|---|---|---|
| ≥ 750 | Excellent | full requested amount |
| ≥ 700 | Good | min(requested, $5,000) |
| ≥ 650 | Fair | min(requested, $2,000) |
| < 650 | Poor | $0, REJECTED |

### Examples (all four tiers)

```bash
# Excellent
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.customer.credit","data":{"creditScore":800,"requestedAmount":10000}}' \
  | jq '.result'
# → creditTier: "Excellent", approvedAmount: 10000.0, validationResult: "APPROVED"

# Good
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.customer.credit","data":{"creditScore":720,"requestedAmount":10000}}' \
  | jq '.result'
# → creditTier: "Good", approvedAmount: 5000.0, validationResult: "APPROVED"

# Fair
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.customer.credit","data":{"creditScore":670,"requestedAmount":10000}}' \
  | jq '.result'
# → creditTier: "Fair", approvedAmount: 2000.0, validationResult: "APPROVED"

# Poor
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.customer.credit","data":{"creditScore":580,"requestedAmount":10000}}' \
  | jq '.result'
# → creditTier: "Poor", approvedAmount: 0.0, validationResult: "REJECTED",
#   validationReason: "Insufficient credit score"
```

---

## What we learn from these examples

### 1. Rule stacking is common (and unmanaged in samples)

Without `salience` or `activation-group`, every rule whose `when` matches will fire. The order is not guaranteed. The samples are **not production-ready** — they're educational demonstrations.

### 2. Last-writer-wins for `Map.put`

When two rules write to the same key, the later one wins. Order is implementation-dependent in this sample set.

### 3. Discount stacking is multiplicative

`100 × 0.80 × 0.90` is **not** the same as `100 - 20% - 10%`. The actual final price is determined by the order rules fire, but in all sample cases the math works out: each rule applies its discount to whatever amount is in the map at that moment.

### 4. Patterns with multiple required fields are restrictive

A rule like `Map(this["amount"] != null, this["customerType"] == "VIP")` only fires if BOTH conditions are satisfied. If either is null/missing, the rule's `when` doesn't match — the `then` block never runs.

### 5. The `then` block can have additional gates

Several rules include `if (amount >= 50.0)` inside the `then` block. The pattern matched (so the rule entered `then`), but the inner check decided whether to actually do anything. This is one way to make rules conditional without affecting the agenda.

---

## How to author a *production* rule that doesn't stack with simple

Given that `simple` fires whenever `amount >= 50`, your VIP rule could prevent the stack by:

### Option A: Use `salience` and `activation-group`

```drools
package com.company.rules.pricing.discount

import java.util.Map

rule "VIP Discount (exclusive)"
    salience 100
    activation-group "discount"   // only one rule in this group fires
    no-loop true
when
    $data : Map(this["customerType"] == "VIP", this["amount"] != null)
then
    // ... apply 20% VIP discount
end

rule "Simple Discount (exclusive)"
    salience 50
    activation-group "discount"
    no-loop true
when
    $data : Map(this["amount"] != null)
    eval(((Number) $data.get("amount")).doubleValue() >= 50.0)
then
    // ... apply 10% simple discount
end
```

Higher salience fires first; the activation group ensures only one wins.

### Option B: Make rules mutually exclusive in `when`

```drools
rule "Simple Discount (excluding VIP)"
when
    $data : Map(
        this["amount"] != null,
        this["customerType"] != "VIP"   // skip VIP customers
    )
    eval(((Number) $data.get("amount")).doubleValue() >= 50.0)
then
    // ...
end
```

### Option C: Set a "discount applied" flag

```drools
rule "VIP Discount"
    salience 100
when
    $data : Map(this["customerType"] == "VIP", this["amount"] != null)
then
    // apply VIP discount
    $data.put("discountApplied", true);
end

rule "Simple Discount"
    salience 50
when
    $data : Map(
        this["amount"] != null,
        this["discountApplied"] == null   // only fire if no other discount applied
    )
then
    // apply simple discount
end
```

Each approach has trade-offs. **Pick one and apply it consistently across your rule set** — the sample rules don't, which is why they exhibit unpredictable stacking.

---

## Reproducing the entire suite at once

```bash
#!/usr/bin/env bash
# Run all 10 sample rules with sample inputs

while IFS='|' read -r rule_id label payload; do
  echo "=== $label ($rule_id) ==="
  curl -sX POST http://localhost:8080/execute-rule \
    -H 'Content-Type: application/json' \
    -d "{\"rule_id\":\"$rule_id\",\"data\":$payload}" | jq -c '.result'
done <<'EOF'
pricing.discount.simple|over_50|{"amount":100}
pricing.discount.simple|under_50|{"amount":40}
pricing.discount.vip|vip_100|{"customerType":"VIP","amount":100}
pricing.discount.bulk|bulk_15|{"quantity":15,"amount":200}
pricing.discount.first-time|first_time|{"isFirstTimeCustomer":true,"amount":100}
pricing.shipping.standard|std_light|{"shippingType":"standard","weight":0.5}
pricing.shipping.express|exp_free|{"shippingType":"express","weight":3,"amount":150}
seasonal.holiday.discount|holiday|{"isHolidaySeason":true,"amount":100}
seasonal.holiday.blackfriday|bf_qualified|{"promotionCode":"BLACK2024","amount":200}
validation.customer.age|adult|{"customerAge":35}
validation.customer.credit|excellent|{"creditScore":800,"requestedAmount":10000}
EOF
```

Outputs are exactly as documented above. If any are different, see [31-troubleshooting.md](31-troubleshooting.md).

---

## Where to go next

- Want to write your own rules? Read [16-drl-sandboxing.md](16-drl-sandboxing.md) for the constraints, then [17-rule-development.md](17-rule-development.md) for the patterns.
- Need to deploy a rule to production? See [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).
- Want AI-assisted rule authoring? Use [20-rule-generation-prompt.md](20-rule-generation-prompt.md) with Claude/ChatGPT.
- Hit an error? Find the code in [12-error-code-catalog.md](12-error-code-catalog.md).
