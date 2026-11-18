# 19 · Sample Rules Cookbook

| | |
|---|---|
| **Audience** | Rule authors, partners, AI agents |
| **Purpose** | Live-tested catalog of all 17 sample rules with reproducible curl examples — the canonical way to learn what each rule does |
| **Last verified against** | The original 10 rules were verified against the running stack on 2026-05-08; the 7 added on 2026-05-10 are verified by the `RuleExecutionIntegrationTest$SampleRulesExecution` integration tests. Example values updated 2026-11-18 for per-`rule_id` isolation ([ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)); they follow from the DRL and are value-checked by `scripts/run-docker-test-plan.sh`. |
| **Related docs** | [10-api-reference.md](10-api-reference.md), [16-drl-sandboxing.md](16-drl-sandboxing.md), [17-rule-development.md](17-rule-development.md), [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md) |

---

## Rules are isolated per `rule_id`

A request runs **only the rules in the DRL file for its `rule_id`** ([ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)). Other loaded rules never fire, even when their conditions match the same data. `pricing.discount.vip` with `amount=100` returns 80 (20% off), not 72.

Before 2026-11-18 every loaded rule whose conditions matched fired, so discounts stacked (see [History](#history-rule-stacking-before-2026-11-18)). That behaviour is gone and there is no fallback. To combine discounts, call each `rule_id` yourself.

Every example below shows the result of the requested rule alone. The response `result` is the input map plus whatever the rule wrote.

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

## The 17 rules at a glance

The original 10 rules cover the most common business-logic shapes (numeric thresholds, string equality, boolean flags). The **7 rules added on 2026-05-10** demonstrate Drools-specific patterns previously missing from the cookbook (`accumulate`, `exists`, `not`, `salience`, compound `&&`/`||` LHS, date comparison via `java.time`, `forall`).

| Rule ID | What it does | When it fires | Pattern |
|---|---|---|---|
| [`pricing.discount.simple`](#pricingdiscountsimple) | 10% off | `amount >= 50` | basic numeric threshold |
| [`pricing.discount.vip`](#pricingdiscountvip) | 20% VIP discount | `customerType == "VIP"` | string equality |
| [`pricing.discount.bulk`](#pricingdiscountbulk) | 15% bulk discount | `quantity >= 10` | int threshold |
| [`pricing.discount.first-time`](#pricingdiscountfirst-time) | 5% first-time customer discount | `isFirstTimeCustomer == true` | boolean flag |
| [`pricing.shipping.standard`](#pricingshippingstandard) | Standard shipping cost | `shippingType == "standard"` | tiered numeric |
| [`pricing.shipping.express`](#pricingshippingexpress) | Express shipping cost (free over $100) | `shippingType == "express"` | conditional discount |
| [`seasonal.holiday.discount`](#seasonalholidaydiscount) | 12% holiday season discount | `isHolidaySeason == true` | boolean flag |
| [`seasonal.holiday.blackfriday`](#seasonalholidayblackfriday) | 25% Black Friday | `promotionCode == "BLACK2024"` and `amount >= 100` | promo code + threshold |
| [`validation.customer.age`](#validationcustomerage) | Age-based eligibility + age group | `customerAge` set | comparison + categorization |
| [`validation.customer.credit`](#validationcustomercredit) | Credit-tier approval | `creditScore` and `requestedAmount` set | multi-tier classification |
| [`pricing.bundle.accumulate`](#pricingbundleaccumulate) | 10% bundle discount when summed item prices > $100 | `items: [...]` with prices summing > 100 | **`accumulate`** (sum aggregation) |
| [`inventory.warning.exists`](#inventorywarningexists) | Low-stock warning if any item has stockLevel < 5 | `items: [...]` with at least one stockLevel < 5 | **`exists`** quantifier |
| [`validation.cart.notempty`](#validationcartnotempty) | Reject when items is missing or empty | items missing OR empty list | **`not`** pattern |
| [`pricing.loyalty.salience`](#pricingloyaltysalience) | 15% loyalty discount with priority firing | `loyaltyMember == true`, `amount` set | **`salience 100`** priority override |
| [`validation.email.compound`](#validationemailcompound) | Validate email by customer-type rules | (internal + corp email) OR (external + any valid email) | **compound `&&`/`||`** + `matches` regex |
| [`seasonal.expiry.temporal`](#seasonalexpirytemporal) | Promo ACTIVE/EXPIRED + days remaining/overdue | `currentDate` + `expiryDate` set (ISO-8601) | **date comparison** via `java.time` |
| [`validation.cart.forall`](#validationcartforall) | All items in stock (universal quantification) | `items: [...]` with every item's stockLevel > 0 | **`forall`** universal quantification |

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

> **Note**: `simple` only runs when you request `pricing.discount.simple`. It does not apply on top of other discount rules.

---

## `pricing.discount.vip`

**File**: [`sample-rules/pricing/discount/vip.drl`](../sample-rules/pricing/discount/vip.drl)
**What it does**: 20% off if `customerType == "VIP"`.
**Inputs**: `customerType` (string, must equal `"VIP"`), `amount` (number, required).

### Example: VIP customer with $100 order

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.vip","data":{"customerType":"VIP","amount":100}}' | jq '.result'
```

Response:
```json
{
  "amount": 80.0,
  "customerTier": "VIP",
  "customerType": "VIP",
  "discount": 20.0,
  "discountPercent": 20,
  "discountReason": "VIP customer exclusive discount"
}
```

Only `vip.drl` runs: `100 × 0.80 = 80`. `pricing.discount.simple` would also match this data (amount ≥ 50), but it is a different `rule_id`, so it does not fire. To combine the VIP and simple discounts, call `pricing.discount.simple` with the VIP result as a second request.

---

## `pricing.discount.bulk`

**File**: [`sample-rules/pricing/discount/bulk.drl`](../sample-rules/pricing/discount/bulk.drl)
**What it does**: 15% off when `quantity >= 10`.
**Inputs**: `quantity` (number ≥ 10), `amount` (number).

### Example: bulk order of 15 items

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.bulk","data":{"quantity":15,"amount":200}}' | jq '.result'
```

Response:
```json
{
  "amount": 170.0,
  "discount": 30.0,
  "discountPercent": 15,
  "discountReason": "Bulk order discount (10+ items)",
  "quantity": 15,
  "qualifyingQuantity": 15
}
```

**Math**: `200 × 0.85 = 170`; `discount` is `200 × 0.15 = 30`.

### Example: only 5 items — no bulk discount

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.bulk","data":{"quantity":5,"amount":200}}' | jq '.result'
```

Response:
```json
{
  "amount": 200,
  "quantity": 5
}
```

The bulk rule's pattern matched (both fields non-null) and its `then` block ran, but `if (quantity >= 10)` was false, so nothing was written. The input comes back unchanged.

---

## `pricing.discount.first-time`

**File**: [`sample-rules/pricing/discount/first-time.drl`](../sample-rules/pricing/discount/first-time.drl)
**What it does**: 5% off if `isFirstTimeCustomer == true`.
**Inputs**: `isFirstTimeCustomer` (boolean true), `amount` (number).

### Example: first-time customer with $100

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.first-time","data":{"isFirstTimeCustomer":true,"amount":100}}' | jq '.result'
```

Response:
```json
{
  "amount": 95.0,
  "customerStatus": "First Time",
  "discount": 5.0,
  "discountPercent": 5,
  "discountReason": "Welcome discount for first-time customers",
  "isFirstTimeCustomer": true
}
```

**Math**: `100 × 0.95 = 95`; `discount` is `100 × 0.05 = 5`.

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

### Example: paid shipping (order $50, weight 0.5 lb)

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.shipping.express","data":{"shippingType":"express","weight":0.5,"amount":50}}' | jq '.result'
```

Response:
```json
{
  "amount": 50,
  "estimatedDays": 2,
  "shippingCost": 12.99,
  "shippingMethod": "Express",
  "shippingType": "express",
  "weight": 0.5
}
```

The order is under $100 and the package is ≤ 1 lb, so shipping costs $12.99. The rule does not change `amount`.

### Example: free shipping (order $150)

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.shipping.express","data":{"shippingType":"express","weight":3,"amount":150}}' | jq '.result'
```

```json
{
  "amount": 150,
  "estimatedDays": 2,
  "freeShipping": true,
  "freeShippingReason": "Express shipping free over $100",
  "shippingCost": 0.0,
  "shippingMethod": "Express",
  "shippingType": "express",
  "weight": 3
}
```

Free shipping; `amount` is unchanged.

---

## `seasonal.holiday.discount`

**File**: [`sample-rules/seasonal/holiday/discount.drl`](../sample-rules/seasonal/holiday/discount.drl)
**What it does**: 12% off when `isHolidaySeason == true`.
**Inputs**: `isHolidaySeason` (boolean true), `amount` (number).

### Example: holiday $100 order

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"seasonal.holiday.discount","data":{"isHolidaySeason":true,"amount":100}}' | jq '.result'
```

```json
{
  "amount": 88.0,
  "discount": 12.0,
  "discountPercent": 12,
  "discountReason": "Holiday season special",
  "isHolidaySeason": true,
  "seasonalPromotion": "Holiday 2024"
}
```

`100 × 0.88 = 88`.

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
  "amount": 150.0,
  "discount": 50.0,
  "discountPercent": 25,
  "discountReason": "Black Friday 2024 Special",
  "promotionCode": "BLACK2024",
  "promotionValid": true
}
```

`200 × 0.75 = 150`.

### Example: under minimum ($50) — promotion message instead of discount

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"seasonal.holiday.blackfriday","data":{"promotionCode":"BLACK2024","amount":50}}' | jq '.result'
```

```json
{
  "amount": 50,
  "promotionCode": "BLACK2024",
  "promotionMessage": "Minimum $100 required for Black Friday discount",
  "promotionValid": false
}
```

The pattern matches, but `if (amount >= 100.0)` is false, so the rule sets `promotionValid=false` and `promotionMessage` and leaves `amount` unchanged.

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

### 1. Only the requested `rule_id` runs

A request fires only the rules in its own DRL file. Other rule IDs never fire, whatever the data contains.

### 2. Last-writer-wins for `Map.put` inside one file

If two rules **in the same file** write the same key, the later one wins. Use `salience` (and `activation-group` if only one should fire) to control the order inside a file.

### 3. Patterns with multiple required fields are restrictive

A rule like `Map(this["amount"] != null, this["customerType"] == "VIP")` only fires if BOTH conditions are satisfied. If either is null/missing, the rule's `when` doesn't match — the `then` block never runs.

### 4. The `then` block can have additional gates

Several rules include `if (amount >= 50.0)` inside the `then` block. The pattern matched (so the rule entered `then`), but the inner check decided whether to actually do anything. This is one way to make rules conditional without affecting the agenda.

---

## History: rule stacking before 2026-11-18

Before [ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18), all loaded rules were compiled into one KieBase and every request ran all of them. Any rule whose `when` matched fired, so `simple`'s 10% stacked multiplicatively on VIP, bulk, first-time, holiday and Black Friday (VIP $100 → 72, bulk 15 × $200 → 153). Rules also crashed on each other's inputs: the bundle rule threw an NPE on forall requests whose items had no `price`.

The workarounds were `salience` plus `activation-group`, mutually exclusive `when` clauses, or a "discount applied" flag. None of that is needed across files now; a request only runs its own file.

---

## Reproducing the entire suite at once

```bash
#!/usr/bin/env bash
# Run a representative subset of the 17 sample rules with sample inputs (covers the original 10 — see the dedicated sections above for the 7 added 2026-05-10)

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

---

# Phase 1 cookbook expansion (2026-05-10) — 7 advanced patterns

The 7 rules below were added to expand the cookbook beyond simple field-equality / threshold patterns. Each demonstrates a distinct Drools construct previously not represented. All are verified by `RuleExecutionIntegrationTest$SampleRulesExecution`.

---

## `pricing.bundle.accumulate`

**File**: [`sample-rules/pricing/bundle/accumulate.drl`](../sample-rules/pricing/bundle/accumulate.drl)
**What it does**: Sums `price` across all items in `items[]`. If the total exceeds $100, applies a 10% bundle discount.
**Inputs**: `items` (list of objects with a numeric `price` field).
**Pattern demonstrated**: Drools' **`accumulate`** with a `sum(...)` accumulator function. The threshold check is expressed as a constraint on the accumulator result (`Number(doubleValue > 100.0)`) rather than via the `eval()` keyword (which is blocked by `DrlSanitizer`).

### Example: bundle over threshold

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.bundle.accumulate","data":{"items":[{"price":50},{"price":60}]}}' | jq
```

Response (selected fields):
```json
{
  "result": {
    "items": [{"price":50}, {"price":60}],
    "bundleTotal": 110.0,
    "bundleDiscount": 11.0,
    "bundleFinalAmount": 99.0,
    "appliedRule": "bundle-accumulate"
  }
}
```

### Example: bundle under threshold (rule does not fire)

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.bundle.accumulate","data":{"items":[{"price":40}]}}' | jq
```

The accumulator's constraint `doubleValue > 100.0` fails, so the rule does not fire. Result is the input unchanged.

---

## `inventory.warning.exists`

**File**: [`sample-rules/inventory/warning/exists.drl`](../sample-rules/inventory/warning/exists.drl)
**What it does**: Sets a `lowStockWarning` flag if **any** item in `items[]` has `stockLevel < 5`.
**Inputs**: `items` (list of objects with a numeric `stockLevel` field).
**Pattern demonstrated**: **`exists`** quantifier. Without `exists`, the rule fires once per matching item (i.e. multiple times). With `exists`, it fires once regardless of how many items match.

### Example: at least one low-stock item

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"inventory.warning.exists","data":{"items":[{"stockLevel":10},{"stockLevel":3}]}}' | jq
```

Response (selected fields):
```json
{
  "result": {
    "lowStockWarning": true,
    "warningType": "STOCK_LOW",
    "validationMethod": "exists-pattern"
  }
}
```

---

## `validation.cart.notempty`

**File**: [`sample-rules/validation/cart/notempty.drl`](../sample-rules/validation/cart/notempty.drl)
**What it does**: Rejects requests where the cart's `items` list is missing or empty.
**Inputs**: an optional `items` (list).
**Pattern demonstrated**: **`not`** — fires when a pattern is **absent** from working memory. The rule checks "no Map exists with `items != null` AND `items.size() > 0`" — equivalent to "the cart is empty/missing".

### Example: empty items list

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.cart.notempty","data":{"items":[]}}' | jq
```

Response (selected fields):
```json
{
  "result": {
    "validationResult": "REJECTED",
    "reason": "EMPTY_CART",
    "validationMethod": "not-pattern"
  }
}
```

> **Note**: this works because the project inserts a single Map fact per request. `not Map(...)` checks for the absence of any Map matching the constraint; since `$data` is the only Map in working memory, the not-pattern fires iff `$data` itself does not satisfy the inner constraints.

---

## `pricing.loyalty.salience`

**File**: [`sample-rules/pricing/loyalty/salience.drl`](../sample-rules/pricing/loyalty/salience.drl)
**What it does**: 15% loyalty discount. Has `salience 100`, so it fires **before** default-salience (0) rules **in the same file**. This file has one rule, so here it only demonstrates the syntax.
**Inputs**: `loyaltyMember` (boolean, must be `true`), `amount` (number).
**Pattern demonstrated**: **`salience N`** — explicit firing-order priority. Higher salience fires first.

### Example: loyalty member, amount over $50

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.loyalty.salience","data":{"loyaltyMember":true,"amount":100}}' | jq
```

Response (selected fields):
```json
{
  "result": {
    "amount": 85.0,
    "loyaltyDiscount": 15.0,
    "loyaltyApplied": true,
    "discountReason": "Loyalty member - 15% off (salience 100)"
  }
}
```

> **Note**: `salience` only orders rules within one `rule_id`. `pricing.discount.simple` never fires on a `pricing.loyalty.salience` request, so the result is 85 with no extra 10% on top. This rule writes `loyaltyDiscount`, not `discount`.

---

## `validation.email.compound`

**File**: [`sample-rules/validation/email/compound.drl`](../sample-rules/validation/email/compound.drl)
**What it does**: Validates an email differently based on customer type:
- Internal customers must have an `@company.com` email.
- External customers can have any string matching `.+@.+\..+` (basic well-formed pattern).

**Inputs**: `email` (string), `customerType` (`"internal"` or `"external"`).
**Pattern demonstrated**: **compound LHS** using `&&` / `||` operators inside a single `Map(...)` pattern, plus the **`matches`** regex operator.

> **Important syntax note**: the Drools-keyword `and` / `or` operators combine **whole patterns** (e.g. `(Map(...) and Map(...))`); for compound logic **inside a single pattern's constraint list** you use the Java-style `&&` / `||` operators. Mixing these up causes parser errors. This rule was originally planned to use `eval()`, but the project's `DrlSanitizer` blocks the `eval` keyword for security; compound LHS gives equivalent expressive power within the security model.

### Example: internal customer with corporate email

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.email.compound","data":{"email":"alice@company.com","customerType":"internal"}}' | jq
```

Response (selected fields):
```json
{
  "result": {
    "emailValidationResult": "VALID",
    "validationMethod": "compound-lhs"
  }
}
```

### Example: external customer with any valid email

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.email.compound","data":{"email":"bob@gmail.com","customerType":"external"}}' | jq
```

Response: also VALID. The first branch fails (not internal); the second branch matches.

---

## `seasonal.expiry.temporal`

**File**: [`sample-rules/seasonal/expiry/temporal.drl`](../sample-rules/seasonal/expiry/temporal.drl)
**What it does**: Validates a promo's expiry. Returns `promoStatus: ACTIVE` with `daysRemaining`, or `promoStatus: EXPIRED` with `daysOverdue`.
**Inputs**: `currentDate` (string, ISO-8601 `yyyy-MM-dd`), `expiryDate` (string, ISO-8601).
**Pattern demonstrated**: **date comparison** using `java.time.LocalDate`. Drools' built-in temporal operators (`before`, `after`, `coincides`) target CEP-style `@role(event)` facts; for stateless one-shot rules over `Map<String,Object>` data, the canonical approach is to do the date math in the RHS using `java.time` (allowed by `DrlSanitizer`'s import allowlist).

### Example: within expiry

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"seasonal.expiry.temporal","data":{"currentDate":"2026-05-10","expiryDate":"2026-12-31"}}' | jq
```

Response (selected fields):
```json
{
  "result": {
    "promoValid": true,
    "promoStatus": "ACTIVE",
    "daysRemaining": 235
  }
}
```

### Example: expired

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"seasonal.expiry.temporal","data":{"currentDate":"2026-05-10","expiryDate":"2026-01-01"}}' | jq
```

Response: `promoStatus: "EXPIRED"`, `daysOverdue: 129`.

---

## `validation.cart.forall`

**File**: [`sample-rules/validation/cart/forall.drl`](../sample-rules/validation/cart/forall.drl)
**What it does**: Sets `allItemsInStock: true` only when **every** item in `items[]` has `stockLevel > 0`.
**Inputs**: `items` (list of objects with a numeric `stockLevel` field).
**Pattern demonstrated**: **`forall`** universal quantification — fires when every fact matching the base pattern also matches the additional pattern. Functionally equivalent to `not(... NOT condition)` (see `validation.cart.notempty` for the not-form), but with an explicit universal-quantifier reading.

### Example: all items in stock

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.cart.forall","data":{"items":[{"stockLevel":10},{"stockLevel":20}]}}' | jq
```

Response (selected fields):
```json
{
  "result": {
    "allItemsInStock": true,
    "validationMethod": "forall"
  }
}
```

### Example: one item out of stock (rule does not fire)

Request:
```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"validation.cart.forall","data":{"items":[{"stockLevel":10},{"stockLevel":0}]}}' | jq
```

The forall is not satisfied (the second item violates `stockLevel > 0`), so `allItemsInStock` is not set. The input is returned unchanged.

---

## Where to go next

- Want to write your own rules? Read [16-drl-sandboxing.md](16-drl-sandboxing.md) for the constraints, then [17-rule-development.md](17-rule-development.md) for the patterns.
- Need to deploy a rule to production? See [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).
- Want AI-assisted rule authoring? Use [20-rule-generation-prompt.md](20-rule-generation-prompt.md) with Claude/ChatGPT.
- Hit an error? Find the code in [12-error-code-catalog.md](12-error-code-catalog.md).
