# 14 · Security Architecture

| | |
|---|---|
| **Audience** | Architects, security reviewers, operators, developers |
| **Purpose** | Complete picture of how the service is defended in depth — eight layers, with code citations and threat-model rationale |
| **Last verified against** | All `api/filter/`, `core/engine/DrlSanitizer.java`, `common/LogSanitizer.java`, `config/CorsConfig.java`, `config/S3Config.java` on 2026-05-10 |
| **Related docs** | [15-admin-authentication.md](15-admin-authentication.md), [16-drl-sandboxing.md](16-drl-sandboxing.md), [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) |

---

## Threat model summary

The service runs as one tier inside a larger system. Threats it considers:

1. **Untrusted callers on `/execute-rule`** — anyone with network access can submit rule input. Goal: prevent abuse (DoS, code execution via rules), keep latency bounded.
2. **Compromised or malicious rule authors** — DRL is user-supplied content that becomes Java at runtime. Goal: prevent the rule engine from becoming an arbitrary-code-execution surface.
3. **Compromised internal actors with `/admin/*` access** — if someone gets past the API gateway, they shouldn't immediately have unfettered admin access.
4. **Sensitive data accidentally logged** — input data may contain credit cards, SSNs, tokens. Goal: never let those land in plaintext logs.
5. **Spoofed identity for rate limit bypass** — attacker manipulates `X-Forwarded-For` or other headers to consume someone else's rate-limit quota.
6. **SSRF via configurable S3 endpoint** — operator misconfiguration could point S3 client at an internal service. Goal: validate the endpoint.
7. **Path traversal via rule ID** — `..` segments could read files outside the rules directory.

What this service does **NOT** defend against:
- Network-layer attacks (TLS, DDoS, etc.) — that's the load balancer's job.
- Compromised JVM / container host — out of scope.
- Code-level supply-chain attacks (malicious dependency in pom.xml) — handled by Maven Central + dependency-review processes.

---

## The 8 layers (filter chain order)

```
HTTP request arrives
       │
       ▼
┌─────────────────────────────────────────────┐
│ LAYER 0  SecurityHeadersFilter   @Order(-1) │   adds 7 response headers
├─────────────────────────────────────────────┤
│ LAYER 1  Network                            │   CORS check (CorsConfig)
├─────────────────────────────────────────────┤
│ LAYER 2  AdminAuthFilter         @Order(0)  │   /admin/* requires X-Admin-API-Key
├─────────────────────────────────────────────┤
│ LAYER 3  RateLimitingFilter      @Order(1)  │   per-client multi-tier identification
├─────────────────────────────────────────────┤
│ LAYER 4  RequestSizeValidationFilter        │   body size + chunked-stream limit
├─────────────────────────────────────────────┤
│ LAYER 5  Bean validation                    │   @ValidRuleId, @ValidRuleData
└─────────────────────────────────────────────┘
       │  (request reaches controller)
       ▼  (rule engine path on /execute-rule)
┌─────────────────────────────────────────────┐
│ LAYER 6  DrlSanitizer  (compile-time)       │   blocks dangerous DRL content
├─────────────────────────────────────────────┤
│ LAYER 7  LogSanitizer  (cross-cutting)      │   masks sensitive data in logs
└─────────────────────────────────────────────┘
```

Each layer is independently effective. Removing one degrades posture but doesn't break the others.

---

## Layer 0 — Security headers

**File**: [`SecurityHeadersFilter.java`](../src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java) at `@Order(-1)`.

Adds 7 headers to **every** response. Verbatim from [`SecurityHeadersFilter.java:21-27`](../src/main/java/com/company/drools/api/filter/SecurityHeadersFilter.java#L21-L27):

| Header | Value | Why |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | Prevents browsers from MIME-sniffing JSON as HTML/JS |
| `X-Frame-Options` | `DENY` | Blocks framing → clickjacking protection |
| `X-XSS-Protection` | `0` | **Modern guidance is to disable this header.** The `1; mode=block` value is now considered harmful (introduces XSS via XSSAuditor side channels). Setting to `0` explicitly tells browsers to use their built-in protections. |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Limits Referer leakage |
| `Cache-Control` | `no-store` | API responses must not be cached by intermediaries (responses contain potentially sensitive rule-execution results) |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` | Maximally restrictive — this is a JSON API, no scripts/images/styles needed |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Force HTTPS for 1 year (only effective when served over HTTPS) |

These headers cost ~250 bytes per response. Acceptable.

**What this layer doesn't do**: it doesn't enforce HTTPS. The service speaks plain HTTP; HTTPS is the load balancer's responsibility. The `Strict-Transport-Security` header is only meaningful if a client also sees the `https://` scheme on the request — usually because the LB has already terminated TLS.

---

## Layer 1 — Network (CORS)

**File**: [`CorsConfig.java`](../src/main/java/com/company/drools/config/CorsConfig.java).

CORS controls which browser origins can call the API.

**Default behavior**: `DROOLS_CORS_ALLOWED_ORIGINS` is **empty by default**. With no origins configured, no `Access-Control-Allow-Origin` header is sent. Browser-side requests from any origin are blocked by the browser (server-side requests are unaffected).

**Profile-specific overrides** (see [05-environments-and-profiles.md](05-environments-and-profiles.md)):
- `local`/`dev`/`docker`: wildcard `*` for ergonomics
- `prod`: empty by default — must be set to your real client origins

**Production setup** must be explicit:
```bash
DROOLS_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
```

**Wildcard vs credentials**: if `DROOLS_CORS_ALLOW_CREDENTIALS=true`, you cannot also have `*` in `DROOLS_CORS_ALLOWED_ORIGINS`. Browser security rule. The default is `false`.

**SSRF protection** (`S3Config`): the AWS endpoint URL (`AWS_ENDPOINT`) is validated against a scheme + host allowlist before being used. Attackers can't redirect the S3 client at internal services via env var manipulation.

---

## Layer 2 — Admin authentication

**File**: [`AdminAuthFilter.java`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) at `@Order(0)`. See **[15-admin-authentication.md](15-admin-authentication.md)** for the full guide.

Summary:
- `/admin/*` paths require `X-Admin-API-Key` header when `ADMIN_API_KEY` env var is set.
- Empty/unset `ADMIN_API_KEY` = open admin endpoints + WARN log at startup.
- Mismatch → HTTP 401 with `UNAUTHORIZED` error code.

Defense in depth, behind upstream API gateway. Not a primary auth mechanism.

---

## Layer 3 — Rate limiting

**File**: [`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) at `@Order(1)`. See **[13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md)** for the full guide.

Summary:
- Per-client buckets, multi-tier identification: `X-API-Key` → `Authorization: Bearer` → `X-Client-Id` → IP fallback.
- `X-Forwarded-For` is **explicitly ignored** (spoofable).
- Default 1000 req/min, 10000 req/hour, burst 100.
- Admin endpoints (`/admin/*`) **exempt** from rate limiting.
- `max-clients=10000` cap prevents memory exhaustion via spoofed identities — at capacity, NEW clients are rejected outright.

---

## Layer 4 — Request size validation

**File**: [`RequestSizeValidationFilter`](../src/main/java/com/company/drools/api/filter/RequestSizeValidationFilter.java) (no `@Order`, runs last in the filter chain).

Two distinct enforcement paths:

1. **Content-Length check**: if the header says the body exceeds `DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES` (default 1 MiB = 1,048,576 bytes), reject with HTTP 413.

2. **Chunked transfer encoding wrapper**: when the client sends `Transfer-Encoding: chunked` with no Content-Length, the filter wraps the request input stream with a `SizeLimitedInputStream`. The wrapper counts bytes as Spring reads them; on exceeding the limit, it throws.

> **Why both**: a naive Content-Length check is bypassable by chunked encoding. A naive chunked check doesn't know the size in advance. Both together cover both cases.

The Tomcat-level limits (`MAX_HTTP_REQUEST_SIZE=10MB`, etc.) act as a backstop. The filter's 1 MiB is **lower** by intent — ordinary rule execution doesn't need 1 MB+ payloads.

Response on rejection: HTTP 413 with `REQUEST_TOO_LARGE` error code (see [12-error-code-catalog.md](12-error-code-catalog.md)).

### Other resource limits enforced after this layer

| Limit | Where | Default |
|---|---|---|
| Rule execution timeout | [`RuleExecutor`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java) | 30s; configurable. On timeout: `future.cancel(true)` |
| `maxRuleFirings` cap | [`RuleExecutor.java:22`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java#L22) | 10,000 firings per execution |
| Data field count | `@ValidRuleData` | 100 keys |
| String value length | `@ValidRuleData` | 10,000 chars |
| Number magnitude | `@ValidRuleData` | 1 billion (absolute) |

These cap the resources a single rule execution can consume. A maliciously huge `data` map can't blow up the JVM.

---

## Layer 5 — Bean validation (`@ValidRuleId`, `@ValidRuleData`)

**Files**:
- [`ValidRuleId`](../src/main/java/com/company/drools/api/validation/ValidRuleId.java) + [`RuleIdValidator`](../src/main/java/com/company/drools/api/validation/RuleIdValidator.java)
- [`ValidRuleData`](../src/main/java/com/company/drools/api/validation/ValidRuleData.java) + [`RuleDataValidator`](../src/main/java/com/company/drools/api/validation/RuleDataValidator.java)

These are Jakarta Bean Validation custom annotations triggered by `@Valid` on the controller signature.

### `@ValidRuleId`

Applied to the `rule_id` field of [`RuleExecutionRequest`](../src/main/java/com/company/drools/api/dto/RuleExecutionRequest.java). Verifies:

| Check | Reason |
|---|---|
| Not null, not empty | basic sanity |
| Length ≤ `DROOLS_VALIDATION_RULE_ID_MAX_LENGTH` (255) | DoS via huge IDs |
| Matches `^[a-zA-Z0-9._-]+$` | Constrain character set |
| Does not contain `..`, `/`, `\` | **Path traversal protection** — even before the ID reaches the storage layer |

### `@ValidRuleData`

Applied to the `data` field. Verifies:

| Check | Default | Reason |
|---|---|---|
| Not null | — | basic sanity |
| Field count ≤ 100 | `DROOLS_VALIDATION_DATA_MAX_FIELDS` | DoS via huge maps |
| Per-key: not empty, length ≤ 100, no dangerous chars | — | Defensive |
| String values: length ≤ 10,000 | `DROOLS_VALIDATION_DATA_MAX_STRING_LENGTH` | DoS via huge strings |
| String values: regex `^[^<>"';&|]*$` | — | Cheap injection-pattern filter (defense in depth — the rule engine doesn't actually interpret these as code, but blocking shell metacharacters is free protection) |
| Number values: |value| ≤ 1B | `DROOLS_VALIDATION_DATA_MAX_NUMBER_VALUE` | Sanity bound |
| Other types: stringify and re-check | — | Catch-all |

Validation failures bubble up as `MethodArgumentNotValidException` → `INVALID_INPUT` (HTTP 400) via [`GlobalExceptionHandler`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L46-L60).

### Storage-layer path traversal (defense in depth)

Even though `@ValidRuleId` rejects `..`, `/`, `\` in rule IDs, the storage backends **also** validate paths:

- [`S3RuleStorage.java:339-341`](../src/main/java/com/company/drools/storage/S3RuleStorage.java#L339-L341): rejects S3 keys containing `../` or starting with `/`.
- [`LocalFileStorage.java:161`](../src/main/java/com/company/drools/storage/LocalFileStorage.java#L161): `path.normalize().startsWith(rulesRoot)` check.

Two layers of path traversal defense.

---

## Layer 6 — DRL sandboxing

**File**: [`DrlSanitizer.java`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java). See **[16-drl-sandboxing.md](16-drl-sandboxing.md)** for the full guide.

Summary:
- Every rule's `.drl` content is text-scanned **before** Drools compilation.
- Allowlist of 20 import prefixes (`java.util.*`, `java.math.*`, `java.time.*`, `java.lang` numerics, `java.text` formatters).
- Blocklist of 19 import prefixes (`java.io.*`, `java.net.*`, reflection, scripting, JNDI, `sun.*`, etc.).
- 12 blocked class names (`Runtime`, `ProcessBuilder`, `Thread`, `ClassLoader`, etc.).
- 19 blocked method calls (`System.exit`, `Class.forName`, `Class.getMethod`, etc.).
- `eval()` is forbidden.
- Static imports are forbidden.

Verified by [`DrlSanitizerTest.java`](../src/test/java/com/company/drools/core/engine/DrlSanitizerTest.java) — 23 test cases.

This is the single most important security control in the service. Without it, DRL is a Turing-complete code-execution surface inside the JVM.

---

## Layer 7 — Log sanitization

**File**: [`LogSanitizer`](../src/main/java/com/company/drools/common/LogSanitizer.java). Used throughout the codebase to mask sensitive data before it reaches the logger.

### What gets masked

| Data type | Pattern | Result |
|---|---|---|
| Credit card | 13–19 digit numbers (with optional dashes/spaces) | `****-****-****-1234` (last 4 visible) |
| SSN | XXX-XX-XXXX format (separator required) | `***-**-1234` |
| Email | `local@domain` | `a***@example.com` (first char + domain) |
| Long opaque tokens (≥ 20 alphanumeric chars) | regex match | redacted unless detected as a UUID or Java class name (filters out false positives) |
| Sensitive keys in maps | key contains `password`, `secret`, `token`, `apikey`, `auth`, `credential`, `ssn`, etc. (word boundaries) | value replaced with `[REDACTED]` |

### What's intentionally NOT masked

- IP addresses
- Rule IDs
- Generic numeric values (timestamps, prices, etc.)
- Non-sensitive map keys

### Recursion

Nested maps are sanitized **recursively** up to depth 5. After depth 5, deeper nested maps are replaced with `[REDACTED]` outright.

### Word-boundary regex prevents false positives

A naive `pin` substring match would also flag `shipping`. The `LogSanitizer` uses `\bpin\b`, `\bauth\b`, etc. — proper word boundaries.

### Used in

- [`GlobalExceptionHandler`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java) sanitizes exception messages before logging.
- [`DroolsEngineService`](../src/main/java/com/company/drools/core/engine/DroolsEngineService.java) sanitizes rule input/output in execution logs.
- Anywhere the codebase logs user-provided data.

---

## Cross-cutting: generic error messages

The service deliberately **does not** leak internal details to clients:

| Scenario | What server logs | What client gets |
|---|---|---|
| Rule throws NPE at `MyRule.java:42` | full stack trace + correlation ID | `RULE_EXECUTION_ERROR` "Rule execution failed" |
| S3 returns AccessDenied | full AWS error + bucket name | `SERVICE_UNAVAILABLE` "External service 's3' is temporarily unavailable" |
| Validation fails on field `customerType` | which field, what constraint | `INVALID_INPUT` with sanitized details |
| Catch-all unexpected exception | full stack trace | `INTERNAL_ERROR` "An unexpected error occurred" |

This is intentional. An attacker probing for vulnerabilities cannot use error messages to map the internal architecture.

To debug, server-side: use the correlation ID (in `X-Correlation-ID` response header and in MDC log fields) to find the matching log entry.

---

## Pre-production security checklist

Before deploying to production:

- [ ] `ADMIN_API_KEY` is set to a long random string (32+ chars). Verified in startup log: `Admin endpoint authentication enabled`.
- [ ] `DROOLS_CORS_ALLOWED_ORIGINS` is set to your real origins (not `*`). If multi-origin, comma-separated.
- [ ] `DROOLS_CORS_ALLOW_CREDENTIALS` is `true` only if needed and explicitly required.
- [ ] `RULE_SOURCE=s3` and `RULE_BUCKET_NAME` is set to your production bucket.
- [ ] AWS credentials supplied via IAM role (no `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` in env).
- [ ] `AWS_ENDPOINT` is empty (i.e., real AWS, not LocalStack).
- [ ] `DROOLS_RATE_LIMITING_ENABLED=true` (default).
- [ ] Rate limit tuned to expected legitimate traffic (likely higher than the 1000/min default).
- [ ] HTTPS terminated at load balancer; service receives HTTP. `Strict-Transport-Security` header is then meaningful.
- [ ] Service exposes only port 8080 externally; port 8081 (Actuator) is internal only.
- [ ] `/actuator/*` is not externally accessible (firewall the management port).
- [ ] Sensitive env vars come from a secret manager (AWS Secrets Manager / SSM / Vault), not from a `.env` file in the image.
- [ ] Container runs as non-root (`USER appuser` in Dockerfile is satisfied by default).
- [ ] All sample DRL rules pass the sandbox (run `mvn test -Dtest=DrlSanitizerTest`).
- [ ] Production deploy includes restart-on-OOM (`-XX:+ExitOnOutOfMemoryError` is set in the Dockerfile JAVA_OPTS).
- [ ] Heap dumps and GC logs are not exposed via mounted volumes in production (only useful for debugging — and could leak data).
- [ ] Redis (when used) has authentication enabled. **Currently this project ships Redis without auth in dev** — production must add `requirepass` and TLS.
- [ ] Logs aggregated to centralized system (CloudWatch Logs / ELK). Don't rely on local stdout in prod.
- [ ] CloudWatch metrics enabled (`CLOUDWATCH_METRICS_ENABLED=true` in `prod` profile).
- [ ] Alerts configured for: WARN-level "Admin API key is not configured" log, breaker open events, 5xx error rate, P99 latency, heap usage > 80%, rate-limiter "client map at capacity" warning.

---

## What this service does NOT secure (consciously)

| Concern | Why we don't | Where it should be handled |
|---|---|---|
| User authentication | Out of scope; this is rule execution | API gateway / SSO / OAuth provider |
| Authorization (per-user, per-rule, RBAC) | We don't have user identity | Upstream — gateway can route or reject by user role |
| TLS termination | Stateless service, easier to do at LB | ALB / Nginx / Cloudflare |
| DDoS at L4 | Beyond a single JVM's defense | LB rate limiting / WAF / Cloudflare |
| Secret encryption at rest | Env vars are plaintext to the JVM | Secret manager (AWS Secrets, HashiCorp Vault) injects via env at deploy time |
| Audit trail of admin actions | Not currently implemented | Could be added: structured logs of every `/admin/*` access |
| Rule signing / integrity | Not implemented; assumes S3 access controls | S3 bucket policy + uploader identity |
| Cluster-wide rate limiting | Per-instance only | Would require Redis-backed bucket |

---

## Verification commands

```bash
# Layer 0 — security headers
curl -sI http://localhost:8080/admin/health | grep -E '^(X-|Cache-Control|Strict|Content-Security|Referrer)'

# Layer 2 — admin auth (set ADMIN_API_KEY first, restart, then try without header)
curl -i http://localhost:8080/admin/health | head -1   # → 401 if key set

# Layer 3 — rate limit headers (any /execute-rule call)
curl -sI -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | grep -i RateLimit

# Layer 4 — request size limit
dd if=/dev/zero bs=1 count=2000000 2>/dev/null | base64 \
  | curl -sX POST http://localhost:8080/execute-rule \
      -H 'Content-Type: application/json' \
      --data-binary @- \
  | jq '.error.code'   # → "REQUEST_TOO_LARGE"

# Layer 5 — input validation
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"../etc/passwd","data":{}}' \
  | jq '.error.code'   # → "INVALID_INPUT"

# Layer 6 — DRL sandbox: see 16-drl-sandboxing.md verification section

# Layer 7 — log sanitization: send a rule with sensitive data, grep server logs
# (the rule should never see plaintext SSN-like input in logs)
```

Every layer is observable, measurable, and verifiable. None of it is "trust me" — every claim above maps to code or a test.
