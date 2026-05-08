# 18 · Rule ID Format and Storage Layout

| | |
|---|---|
| **Audience** | Rule authors, operators uploading rules |
| **Purpose** | How rule IDs map to file paths and S3 keys, what the format constraints are, and how to organize rules in storage |
| **Last verified against** | [`StorageFactory.java`](../src/main/java/com/company/drools/storage/StorageFactory.java), [`S3RuleStorage.java`](../src/main/java/com/company/drools/storage/S3RuleStorage.java), [`LocalFileStorage.java`](../src/main/java/com/company/drools/storage/LocalFileStorage.java), [`RuleIdValidator.java`](../src/main/java/com/company/drools/api/validation/RuleIdValidator.java) on 2026-05-08 |
| **Related docs** | [16-drl-sandboxing.md](16-drl-sandboxing.md), [17-rule-development.md](17-rule-development.md), [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md), [09-environment-variables-reference.md](09-environment-variables-reference.md) |

---

## TL;DR

- **Rule ID format**: `^[a-zA-Z0-9._-]+$`, max 255 chars. Dots separate the hierarchy.
- **Path mapping** (in both directions):
  - rule ID `pricing.discount.vip` ↔ S3 key `pricing/discount/vip.drl`
  - rule ID `pricing.discount.vip` ↔ Java package `com.company.rules.pricing.discount`
  - rule ID `pricing.discount.vip` ↔ rule name (the human-readable label inside `.drl`)
- **Three storage backends**, selected by `RULE_SOURCE` env var: `local` (in-memory), `file` (filesystem), `s3` (AWS S3 / LocalStack).
- **Path traversal protection** at two layers: `@ValidRuleId` rejects `..`, `/`, `\` *and* the storage layer re-checks via path normalization.

---

## The four mappings

A rule has four "names" that all map 1:1:

| Name | Format | Example |
|---|---|---|
| **Rule ID** (in API) | dot-separated, lowercase | `pricing.discount.vip` |
| **S3 key / file path** | slash-separated, `.drl` suffix | `pricing/discount/vip.drl` |
| **DRL package declaration** | `com.company.rules.{rule-id-with-dots}` | `com.company.rules.pricing.discount` |
| **Rule name** (in DRL `rule "..."`) | free text, human-readable | `"VIP Customer Discount - 20% Off"` |

### Why these four

- **Rule ID** is the wire format — what clients send in `POST /execute-rule` and what S3/admin endpoints reference.
- **S3 key** is the storage representation — slashes give S3 a hierarchical layout for browsing and bucket policies.
- **Package** is what Drools requires inside the `.drl` file — it must be a valid Java package, so dots and lowercase only.
- **Rule name** is human-readable, used in logs and Drools error messages. Quote it; spaces are allowed inside the quotes.

### The transformation in code

[`S3RuleStorage.java:333-343`](../src/main/java/com/company/drools/storage/S3RuleStorage.java#L333-L343):

```java
private String ruleIdToS3Key(String ruleId) {
  String s3Key = ruleId.replace(".", "/") + ".drl";
  if (s3Key.contains("../") || s3Key.startsWith("/")) {
    throw new IllegalArgumentException("Invalid rule ID: path traversal detected");
  }
  return s3Key;
}
```

[`S3RuleStorage.java:345-355`](../src/main/java/com/company/drools/storage/S3RuleStorage.java#L345-L355) (reverse):

```java
private String s3KeyToRuleId(String s3Key) {
  String ruleId = s3Key;
  if (ruleId.endsWith(".drl")) {
    ruleId = ruleId.substring(0, ruleId.length() - 4);
  }
  return ruleId.replace("/", ".");
}
```

The `LocalFileStorage` does the same dot-to-slash transformation for filesystem paths ([`LocalFileStorage.java:157-165`](../src/main/java/com/company/drools/storage/LocalFileStorage.java#L157-L165)).

---

## Rule ID format rules

### Allowed characters

`^[a-zA-Z0-9._-]+$` — alphanumeric, dot, hyphen, underscore. **No other characters.**

### Examples

| Rule ID | Valid? | Why |
|---|---|---|
| `pricing.discount.simple` | ✅ | only allowed chars, < 256 chars |
| `pricing.discount.vip-tier-1` | ✅ | hyphens are allowed |
| `pricing.discount.first_time` | ✅ | underscores are allowed |
| `Pricing.Discount.VIP` | ✅ | mixed case is allowed (but discouraged — see conventions) |
| `pricing` | ✅ | a single segment is technically valid (flat namespace) |
| `pricing.discount.vip ` (trailing space) | ⚠️ | Validator silently `trim()`s before pattern check, so it passes validation as `pricing.discount.vip`. But the **untrimmed** ID is then sent to storage, where the S3 key `pricing/discount/vip .drl` (with space) doesn't exist → returns 404 `RULE_NOT_FOUND`. Effectively rejected, but for the wrong reason. **Don't send whitespace.** (See `CODE_FINDINGS.md` F-032.) |
| `pricing/discount/vip` | ❌ | slashes not allowed in IDs |
| `pricing..discount.vip` | ❌ | contains `..` (path traversal) |
| `/pricing.discount.vip` | ❌ | starts with `/` (path traversal) |
| `pricing.discount.vip\n` | ❌ | newline not in allowlist |
| `pricing.discount.<script>` | ❌ | angle brackets not allowed |
| (empty string) | ❌ | not null, not empty enforced |
| 256-char rule ID | ❌ | exceeds default `DROOLS_VALIDATION_RULE_ID_MAX_LENGTH=255` |

### Where these are enforced

Validation happens in **three places** for defense-in-depth:

1. **`@ValidRuleId`** annotation on `RuleExecutionRequest.ruleId` ([`RuleIdValidator.java:23-56`](../src/main/java/com/company/drools/api/validation/RuleIdValidator.java#L23-L56)). Rejects with HTTP 400 `INVALID_INPUT` before any business logic runs.
2. **`@ValidRuleId`** on `AdminController` path variable for `/admin/refresh-rules/{ruleId}`.
3. **Storage layer** path-traversal check in `S3RuleStorage.ruleIdToS3Key()` and `LocalFileStorage.getRuleFilePath()` — second-line defense in case validation is bypassed.

### What the limits mean operationally

- **255 char limit** prevents huge rule IDs that could cause downstream problems (very long S3 keys, long log lines, cache key bloat).
- **No special chars** prevents URL-encoding ambiguity, log injection, command-line escaping issues, and YAML parsing surprises.
- **No path-traversal patterns** prevents a malicious or buggy rule ID from accessing files outside the rules directory.

---

## Naming conventions

The format rules above are *enforced*. The conventions below are *recommended* — followed by all 10 sample rules in [`sample-rules/`](../sample-rules/).

### Three-segment hierarchy: `{domain}.{category}.{specific}`

| Segment | Convention | Examples |
|---|---|---|
| 1st (domain) | Business area | `pricing`, `validation`, `seasonal`, `compliance`, `inventory` |
| 2nd (category) | Sub-area within domain | `discount`, `shipping`, `customer`, `holiday` |
| 3rd (specific) | The concrete rule | `simple`, `vip`, `bulk`, `first-time`, `blackfriday` |

Examples from [`sample-rules/`](../sample-rules/):
- `pricing.discount.simple` → 10% off orders ≥ $50
- `pricing.discount.vip` → 20% off VIP customers
- `pricing.shipping.express` → weight-based express shipping
- `validation.customer.age` → age ≥ 18 verification
- `seasonal.holiday.blackfriday` → 25% off with promo code

### Lowercase

Mixed case is *allowed* by the regex but *discouraged*. S3 keys are case-sensitive — `pricing.Discount.VIP` and `pricing.discount.vip` are **different rules** in storage. Pick one casing convention per project. The sample rules use all lowercase.

### Dashes for word separation, not underscores or camelCase

Sample rules use `first-time`, not `firstTime` or `first_time`. Match the convention.

### Don't use environment-specific names in rule IDs

Bad: `pricing.discount.simple-prod`, `pricing.discount.simple-test`.
Good: same rule ID across all environments; use S3 buckets to separate environments (`prod-rules`, `dev-rules`).

### Don't use version numbers in rule IDs

Bad: `pricing.discount.simple.v2`.
Good: keep one rule ID, use S3 versioning for history. (`version` field in the API response is currently always `"1.0"` — versioning is roadmap.)

---

## Storage backend selection

Three backends, selected by the `RULE_SOURCE` env var.

[`StorageFactory.java`](../src/main/java/com/company/drools/storage/StorageFactory.java):

```java
return switch (ruleSource.toLowerCase()) {
  case "local" -> InMemoryRuleStorageAdapter   // built-in sample rules
  case "file"  -> localFileStorage             // filesystem
  case "s3"    -> s3RuleStorage                // AWS S3 / LocalStack
  default      -> InMemoryRuleStorageAdapter   // (unknown values fall through with warning)
};
```

| `RULE_SOURCE` | Backend | When to use |
|---|---|---|
| `local` (default) | [`InMemoryRuleStorage`](../src/main/java/com/company/drools/storage/InMemoryRuleStorage.java) via adapter | Pure local dev, no Docker. Built-in sample rules; you cannot customize content. |
| `file` | [`LocalFileStorage`](../src/main/java/com/company/drools/storage/LocalFileStorage.java) | Local dev with custom rules from a directory. Set `LOCAL_RULES_DIRECTORY`. |
| `s3` | [`S3RuleStorage`](../src/main/java/com/company/drools/storage/S3RuleStorage.java) | Production, dev with LocalStack, docker-compose stack. |

Unknown values (`memory`, `database`, anything else) fall through to in-memory with a WARN log line:
```
Unknown rule source: {value}, defaulting to in-memory storage
```

This is **silent partial failure** — your rule files will not load if you typo `RULE_SOURCE=S3` (case sensitivity is OK due to `.toLowerCase()`, but `RULE_SOURCE=s3-bucket` fails). Always check the startup log.

---

## File system layout (`file` and `s3` backends)

Same hierarchical layout for both, just different roots.

### File backend (`RULE_SOURCE=file`)

Default root: `src/main/resources/rules` (configurable via `LOCAL_RULES_DIRECTORY`).

```
{LOCAL_RULES_DIRECTORY}/
├── pricing/
│   ├── discount/
│   │   ├── simple.drl
│   │   ├── vip.drl
│   │   ├── bulk.drl
│   │   └── first-time.drl
│   └── shipping/
│       ├── standard.drl
│       └── express.drl
├── seasonal/
│   └── holiday/
│       ├── discount.drl
│       └── blackfriday.drl
└── validation/
    └── customer/
        ├── age.drl
        └── credit.drl
```

This is exactly the layout in [`sample-rules/`](../sample-rules/) — that directory is the canonical example.

### S3 backend (`RULE_SOURCE=s3`)

Same layout, but as S3 keys under a bucket. Bucket name comes from `RULE_BUCKET_NAME` (default `local-rules`).

```
s3://{RULE_BUCKET_NAME}/
├── pricing/discount/simple.drl
├── pricing/discount/vip.drl
├── ...
└── validation/customer/credit.drl
```

S3 keys use `/` as the separator (matching filesystem semantics). The service uses the AWS SDK's standard `ListObjectsV2` paginator to enumerate all `.drl` keys at startup and on `POST /admin/refresh-rules`.

### Files are loaded from arbitrary depth

Both backends recursively walk the rule directory. There is no cap on hierarchy depth — `pricing.discount.tiers.bronze.minimum.threshold` (6 segments) becomes `pricing/discount/tiers/bronze/minimum/threshold.drl` and works fine. Just keep your hierarchy reasonable (3–4 levels is the norm).

### Files NOT loaded

- Anything not ending in `.drl`
- README files in any directory
- Hidden files (`.foo`)

`init-localstack.sh` syncs `sample-rules/` to S3 with `--exclude README.md` to skip the README.

---

## Path traversal — defense in depth

Path traversal is defended at three layers:

### Layer 1: `@ValidRuleId` annotation

[`RuleIdValidator.java:48-52`](../src/main/java/com/company/drools/api/validation/RuleIdValidator.java#L48-L52) explicitly rejects rule IDs containing:
- `..`
- `/`
- `\`

Plus the regex `^[a-zA-Z0-9._-]+$` already excludes most other path-relevant characters.

### Layer 2: S3RuleStorage path check

[`S3RuleStorage.java:339-341`](../src/main/java/com/company/drools/storage/S3RuleStorage.java#L339-L341):
```java
if (s3Key.contains("../") || s3Key.startsWith("/")) {
  throw new IllegalArgumentException("Invalid rule ID: path traversal detected");
}
```

This is *after* the dot-to-slash transformation. So if the validator missed something (or if the rule ID came from a different code path), this catches it.

### Layer 3: LocalFileStorage path normalization

[`LocalFileStorage.java:159-163`](../src/main/java/com/company/drools/storage/LocalFileStorage.java#L159-L163):
```java
Path filePath = Paths.get(rulesDirectory, relativePath).normalize();
Path rulesRoot = Paths.get(rulesDirectory).normalize();
if (!filePath.startsWith(rulesRoot)) {
  throw new IllegalArgumentException("Invalid rule ID: path traversal detected");
}
```

This is the strongest of the three: it *resolves* the path (collapsing any `..` segments) and verifies the resolved path is still inside the rules root. Even an OS-specific path-traversal trick (Windows-style backslashes, Unicode escapes, double-encoded sequences) is caught here.

### Why three layers

Any single check could be bypassed by a code-evolution mistake (someone refactors validation into a different path, or the rule ID enters via a new code path that skips the validator). Three independent checks ensure that even if one fails, the other two stop the attack.

---

## Uploading rules

### To LocalStack S3 (dev)

The `init-localstack.sh` script handles this automatically when LocalStack starts (mounted as a `ready.d` hook). For manual upload:

```bash
# Sync entire directory
aws --endpoint-url=http://localhost:4566 \
    s3 sync sample-rules/ s3://local-rules/ --exclude README.md

# Single file
aws --endpoint-url=http://localhost:4566 \
    s3 cp sample-rules/pricing/discount/vip.drl s3://local-rules/pricing/discount/vip.drl

# Verify
aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules/ --recursive
```

After upload, refresh the running service:
```bash
curl -X POST -H "X-Admin-API-Key: ${ADMIN_API_KEY}" \
  http://localhost:8080/admin/refresh-rules
```

### To real AWS S3 (production)

```bash
# Assumes IAM role / credentials configured
aws s3 sync local-rules/ s3://prod-drools-rules/

# Or single file
aws s3 cp pricing/discount/vip.drl s3://prod-drools-rules/pricing/discount/vip.drl
```

### To filesystem (`file` backend)

```bash
# Just write the file at the right path
mkdir -p $LOCAL_RULES_DIRECTORY/pricing/discount
cp my-vip-rule.drl $LOCAL_RULES_DIRECTORY/pricing/discount/vip.drl

# Then refresh
curl -X POST -H "X-Admin-API-Key: ${ADMIN_API_KEY}" \
  http://localhost:8080/admin/refresh-rules
```

---

## Listing rules from storage

The service does this automatically on startup and on every `POST /admin/refresh-rules`. To see what's loaded:

```bash
curl -H "X-Admin-API-Key: ${ADMIN_API_KEY}" http://localhost:8080/admin/rules \
  | jq '.rules[].rule_id'
```

To see what's *in* S3 directly (may differ from what's loaded if the service hasn't been refreshed):

```bash
# LocalStack
aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules/ --recursive

# Real AWS
aws s3 ls s3://prod-drools-rules/ --recursive
```

If `/admin/rules` shows fewer rules than S3 has, you need to call `POST /admin/refresh-rules`.

---

## Renaming or moving a rule

There is no atomic "rename" operation. To rename `old.id` to `new.id`:

1. Upload the rule to the new path.
2. Refresh: `POST /admin/refresh-rules`. Both `old.id` and `new.id` are now available.
3. Update all clients to use `new.id`.
4. After clients are migrated, delete the old key from S3.
5. Refresh again to clear the old rule from cache.

There is **no atomic guarantee** between upload and refresh. During the migration window, both IDs work.

---

## Common operational issues

### "I uploaded a rule but it doesn't appear"

Most likely you forgot `POST /admin/refresh-rules`. Rules are loaded into the `KieContainer` cache at startup or on explicit refresh. Uploading to S3 alone doesn't trigger a reload (unless `AUTO_REFRESH_ENABLED=true` and you wait the configured interval).

### "I get RULE_NOT_FOUND but I see it in S3"

Check that:
1. The S3 key matches the expected transformation: `pricing.discount.vip` → `pricing/discount/vip.drl`. Subtle errors like uppercase/lowercase mismatches break the lookup.
2. The file ends in `.drl` (lowercase). Files ending in `.DRL`, `.drools`, etc. are not loaded.
3. The service has refreshed since the upload.

### "I get INVALID_INPUT 'path traversal detected'"

Your rule ID contains `..`, `/`, or `\`, or starts with `/`. Even valid-looking rule IDs like `..test` (which is `^[a-zA-Z0-9._-]+$` compliant) are rejected.

### "Rules from a subdirectory don't load"

Both file and S3 backends recurse. If a subdirectory's rules aren't appearing, check:
- The `.drl` file exists at the expected path
- The file's package declaration matches the path (`pricing/discount/vip.drl` should declare `package com.company.rules.pricing.discount`)
- DrlSanitizer didn't reject it — check the response of `POST /admin/refresh-rules` for `errors[]`

---

## Verification commands

```bash
# Check what's loaded
curl -fsS http://localhost:8080/admin/rules | jq '.total_rules'   # → 10
curl -fsS http://localhost:8080/admin/rules | jq '.rules[].rule_id'

# Check what's in storage (S3 / LocalStack)
aws --endpoint-url=http://localhost:4566 s3 ls s3://local-rules/ --recursive | grep .drl

# Verify the transformation in both directions
echo "pricing.discount.vip" | sed 's/\./\//g' | sed 's/$/.drl/'
# → pricing/discount/vip.drl

echo "pricing/discount/vip.drl" | sed 's/\.drl$//' | sed 's/\//./g'
# → pricing.discount.vip

# Trigger the path-traversal check (should fail with INVALID_INPUT)
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"../etc/passwd","data":{}}' | jq '.error.code'
# → "INVALID_INPUT"
```
