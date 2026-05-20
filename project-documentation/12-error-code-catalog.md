# 12 · Error Code Catalog

| | |
|---|---|
| **Audience** | Developers, operators, partners, AI agents |
| **Purpose** | Single-page reference for every error code the service emits — what triggers it, what the response looks like, how to fix it |
| **Last verified against** | [`GlobalExceptionHandler.java`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java), [`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java), [`AdminAuthFilter.java`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java), [`RequestSizeValidationFilter.java`](../src/main/java/com/company/drools/api/filter/RequestSizeValidationFilter.java) on 2026-05-10 |
| **Related docs** | [10-api-reference.md](10-api-reference.md), [11-integration-guide.md](11-integration-guide.md), [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md), [31-troubleshooting.md](31-troubleshooting.md) |

---

## Error response shape

All errors return a JSON object in this shape:

```json
{
  "rule_id": "<rule_id or null>",
  "result": null,
  "error": {
    "code": "<ERROR_CODE>",
    "message": "<human-readable>",
    "details": "<optional supplementary text>",
    "timestamp": "<ISO-8601>"
  }
}
```

The exact field set depends on which layer emits the error:
- **Controller-layer errors** (via [`GlobalExceptionHandler`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java)) use `RuleExecutionResponse.failure(...)` and include the rule_id when known.
- **Filter-layer errors** (rate limit, admin auth, request size) emit a smaller error envelope written directly to the response.

---

## Quick lookup

| HTTP | Code | When |
|---:|---|---|
| 400 | `RULE_EXECUTION_ERROR` | Rule fired but threw at runtime |
| 400 | `INVALID_INPUT` | Validation failed OR malformed argument |
| 401 | `UNAUTHORIZED` | Admin endpoint accessed without/wrong API key |
| 404 | `RULE_NOT_FOUND` | Rule ID does not exist in storage |
| 404 | `NOT_FOUND` | Path doesn't match any handler (404 fallback) |
| 408 | `TIMEOUT_ERROR` | Operation exceeded its timeout |
| 413 | `REQUEST_TOO_LARGE` | Request body exceeds size cap |
| 429 | `RATE_LIMIT_EXCEEDED` | Per-client rate limit hit |
| 500 | `INTERNAL_ERROR` | Anything not caught by a more specific handler |
| 503 | `SERVICE_UNAVAILABLE` | Circuit breaker open (S3 or Redis) |

---

## RULE_NOT_FOUND (404)

**When**: Client requests a rule that isn't loaded.

```bash
curl -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"nonexistent.rule","data":{}}'
```

**Response**:
```json
{
  "rule_id": "nonexistent.rule",
  "result": null,
  "error": {
    "code": "RULE_NOT_FOUND",
    "message": "Rule not found: nonexistent.rule",
    "timestamp": "2026-05-10T08:57:10Z"
  }
}
```

**Code path**: [`GlobalExceptionHandler.java:22-31`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L22-L31). Triggered by [`RuleNotFoundException`](../src/main/java/com/company/drools/api/exception/RuleNotFoundException.java) thrown from `DroolsEngineService.executeRule()` when `loadedRules.get(ruleId) == null`.

**Fix**:
1. Verify the rule exists in storage:
   ```bash
   curl http://localhost:8080/admin/rules | jq '.rules[].rule_id'
   ```
2. If missing, upload the `.drl` file to S3 (or your `RULE_SOURCE` backend) and refresh:
   ```bash
   curl -X POST http://localhost:8080/admin/refresh-rules \
     -H "X-Admin-API-Key: $ADMIN_API_KEY"
   ```
3. Verify the rule_id format matches the file path. `pricing.discount.vip` ↔ `pricing/discount/vip.drl`. See [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).

---

## RULE_EXECUTION_ERROR (400)

**When**: A rule was found and compiled, but threw an exception during firing.

**Response**:
```json
{
  "rule_id": "pricing.discount.broken",
  "result": null,
  "error": {
    "code": "RULE_EXECUTION_ERROR",
    "message": "Rule execution failed",
    "timestamp": "..."
  }
}
```

> Note: the *internal* error message is logged with full detail server-side; the client gets a generic message. This is intentional (avoids leaking internal class names, line numbers, etc.). See `LogSanitizer` integration in [`GlobalExceptionHandler.java:36-44`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L36-L44).

**Common causes**:
- Rule's `then` block threw a `NullPointerException` because input data is missing a key the rule reads. Always null-check in DRL: `eval($data.get("amount") != null)`.
- `ClassCastException` because the rule assumes a type that doesn't match the input. Use `((Number) $data.get("x")).doubleValue()` instead of `(Double) $data.get("x")`.
- `ArithmeticException` (division by zero, etc.).
- Rule modified data in a way that triggered an infinite loop and was capped — but this surfaces as `TIMEOUT_ERROR`, not `RULE_EXECUTION_ERROR`.

**Fix**:
1. Tail the server log for the actual stack trace:
   ```bash
   docker compose logs app | grep -A 20 'Rule execution failed'
   ```
2. Apply DRL safety patterns from [17-rule-development.md](17-rule-development.md):
   - Null-check every field: `eval($data.get("x") != null)`
   - Use `Number` casts: `((Number) $data.get("x")).doubleValue()`
   - Add `no-loop true` if the rule modifies its own match conditions

---

## INVALID_INPUT (400)

**When**: Request validation failed. Two distinct triggers:
1. **Bean validation** ([`MethodArgumentNotValidException`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L46-L60)) — `@ValidRuleId`, `@ValidRuleData`, or other JSR-380 annotations rejected the input.
2. **`IllegalArgumentException`** ([line 98-107](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L98-L107)) — typically from path-traversal checks in storage layer or other defensive validation.

**Response (validation)**:
```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "INVALID_INPUT",
    "message": "Request validation failed",
    "details": "rule_id: must match pattern '^[a-zA-Z0-9._-]+$', data: must not contain more than 100 fields",
    "timestamp": "..."
  }
}
```

**Response (illegal argument)**:
```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "Invalid request parameter",
    "timestamp": "..."
  }
}
```

**Common causes**:
- `rule_id` contains characters outside `^[a-zA-Z0-9._-]+$` (e.g., spaces, slashes).
- `rule_id` exceeds `DROOLS_VALIDATION_RULE_ID_MAX_LENGTH` (default 255).
- `rule_id` contains path-traversal patterns (`..`, `/`, `\`).
- `data` has more than `DROOLS_VALIDATION_DATA_MAX_FIELDS` (default 100) top-level keys.
- A string value in `data` exceeds `DROOLS_VALIDATION_DATA_MAX_STRING_LENGTH` (default 10000) chars.
- A numeric value in `data` has |value| > `DROOLS_VALIDATION_DATA_MAX_NUMBER_VALUE` (default 1B).
- Sent `ruleId` (camelCase) instead of `rule_id` (snake_case) in JSON. Field names are strict.

**Fix**: Read the `details` field. It contains the specific validation message. See [09-environment-variables-reference.md](09-environment-variables-reference.md) Validation section to adjust limits if defaults are too tight.

---

## UNAUTHORIZED (401)

**When**: A request to `/admin/*` was made and the `X-Admin-API-Key` header is missing or wrong, *and* `ADMIN_API_KEY` env var is set.

> If `ADMIN_API_KEY` is empty/unset, admin endpoints are open and this error never fires. A warning is logged at startup. See [15-admin-authentication.md](15-admin-authentication.md).

**Response**:
```json
{
  "code": "UNAUTHORIZED",
  "message": "Missing or invalid admin API key",
  "timestamp": "..."
}
```

**Code path**: [`AdminAuthFilter.java:74-92`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java#L74-L92). Filter intercepts requests where `uri.startsWith("/admin/")` and compares the `X-Admin-API-Key` header to the configured value.

**Fix**:
- Include the header on every admin request:
  ```bash
  curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/health
  ```
- If you don't know the configured key, ask whoever deployed the service. Never log the key.
- For local dev: leave `ADMIN_API_KEY` unset to disable auth (do NOT do this in production).

---

## NOT_FOUND (404)

**When**: A request hits a path that no Spring controller handles.

**Response**:
```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "The requested resource was not found",
    "timestamp": "..."
  }
}
```

**Code path**: [`GlobalExceptionHandler.java:131-141`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L131-L141). Spring Boot 3.x throws `NoResourceFoundException` for unhandled paths; we map it to a clean error envelope.

**Common causes**:
- Typo in the URL (e.g., `/exec-rule` instead of `/execute-rule`).
- Calling a deprecated/removed endpoint.
- Calling Actuator endpoints (e.g., `/actuator/health`) on the **main port** (8080) instead of the **management port** (8081).

**Fix**: See [10-api-reference.md](10-api-reference.md) for the endpoint inventory.

---

## TIMEOUT_ERROR (408)

**When**: A rule execution or other timed operation exceeded its configured timeout.

**Response**:
```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "TIMEOUT_ERROR",
    "message": "Operation 'rule-execution' timed out after 30 seconds",
    "timestamp": "..."
  }
}
```

**Code path**: [`GlobalExceptionHandler.java:62-75`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L62-L75). Triggered by [`TimeoutException`](../src/main/java/com/company/drools/api/exception/TimeoutException.java) which carries the operation name and timeout value.

In rule execution, the timeout is enforced via `CompletableFuture.get(timeoutSeconds, SECONDS)` in [`RuleExecutor`](../src/main/java/com/company/drools/core/engine/RuleExecutor.java). On timeout, `future.cancel(true)` is called to interrupt the rule-firing thread. The `maxRuleFirings = 10000` cap also prevents infinite-loop rules from running forever even within the timeout window.

**Common causes**:
- Rule has expensive operations (large list iteration, heavy math).
- Rule is in an infinite loop (firing rule modifies its own match condition without `no-loop true`).
- Storage backend is slow to respond (S3 cold path).

**Fix**:
1. Identify the slow rule from logs.
2. Add `no-loop true` if the rule modifies its conditions.
3. Profile the rule logic; simplify or split.
4. If S3 is slow, see `SERVICE_UNAVAILABLE` below — circuit breaker may be opening.
5. If 30s isn't enough for a legitimately heavy rule, increase the timeout:
   ```bash
   RULE_EXECUTION_TIMEOUT_SECONDS=60   # or DROOLS_RULE_EXECUTION_TIMEOUT=60
   ```

---

## REQUEST_TOO_LARGE (413)

**When**: Request body exceeds `DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES` (default 1 MiB) OR Spring Boot's multipart caps.

Two code paths emit this:
1. **`RequestSizeValidationFilter`** ([line 82-90](../src/main/java/com/company/drools/api/filter/RequestSizeValidationFilter.java#L82-L90)) — checks Content-Length and wraps the input stream with a `SizeLimitedInputStream` for chunked transfers.
2. **Spring Boot multipart** (`MaxUploadSizeExceededException`) — handled at [`GlobalExceptionHandler.java:109-124`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L109-L124).

**Response**:
```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "REQUEST_TOO_LARGE",
    "message": "Request size exceeds maximum allowed limit",
    "details": "Please reduce the size of your request payload",
    "timestamp": "..."
  }
}
```

**Fix**:
- Reduce the payload (the rule probably doesn't need huge data).
- If you genuinely need bigger payloads, increase the cap:
  ```bash
  DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES=2097152   # 2 MiB
  MAX_HTTP_REQUEST_SIZE=20MB                          # raise Tomcat backstop too
  ```
- Note: the validation filter cap (1 MiB) is **lower** than the Tomcat backstop (10 MB). Both are enforced; the lower one wins.

---

## RATE_LIMIT_EXCEEDED (429)

**When**: A client identified by the multi-tier ID exceeds the configured per-minute or per-hour rate limit on `/execute-rule` (or other non-admin endpoints).

> Admin endpoints (`/admin/*`) are **exempt** from rate limiting.

**Response**:
```json
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit exceeded for client. Please try again later.",
  "timestamp": "..."
}
```

**Response headers** (also present on successful requests):
- `X-RateLimit-Limit: 1000`
- `X-RateLimit-Remaining: 0`
- `X-RateLimit-Reset: <epoch_seconds>`

**Code path**: [`RateLimitingFilter.java:115-126`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java#L115-L126). Filter writes the response directly without going through `GlobalExceptionHandler`.

**Fix**:
- Slow down. Read `X-RateLimit-Reset` and back off until then.
- If your client is being identified incorrectly (e.g., everyone shares the same IP behind a NAT), set an `X-API-Key` or `X-Client-Id` header — this gives each client an independent bucket. See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md).
- Raise the limit if your traffic profile justifies it:
  ```bash
  DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=5000
  DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR=100000
  ```

---

## SERVICE_UNAVAILABLE (503)

**When**: The Resilience4j circuit breaker for S3 or Redis is OPEN — too many recent failures to that backend.

**Response**:
```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "External service 's3' is temporarily unavailable (open). Please try again later.",
    "timestamp": "..."
  }
}
```

The breaker name (`s3` or `redis`) is in the message. The state is in lowercase (`open`, `half-open`).

**Code path**: [`GlobalExceptionHandler.java:77-96`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L77-L96). Triggered by [`CircuitBreakerException`](../src/main/java/com/company/drools/api/exception/CircuitBreakerException.java) when `S3RuleStorage` is rejected by the S3 breaker. Note: `RedisCachedRuleStorage` Redis-op failures do NOT surface to clients — the decorator falls through to base storage silently when the Redis breaker is open.

**Common causes**:
- S3 rate-limited or throttling the client.
- Network partition between the service and S3 / Redis.
- Misconfigured AWS credentials (every call fails → breaker opens).
- Redis container down and `REDIS_ENABLED=true` with traffic actually using it.

**Fix**:
1. Check the breaker state directly:
   ```bash
   curl http://localhost:8080/admin/health | jq '.components."circuit-breakers"'
   ```
2. Look for the underlying error in app logs:
   ```bash
   docker compose logs app | grep -E 'S3|Redis|CircuitBreaker'
   ```
3. If S3 credentials are wrong, fix them and restart — the breaker will close on its own once HALF_OPEN test calls succeed.
4. The breaker auto-recovers after `wait-duration-in-open-state` (default 60s for S3, 30s for Redis). Just wait, retry, and the breaker transitions to HALF_OPEN.

See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) for full behavior + state machine.

---

## INTERNAL_ERROR (500)

**When**: An exception was thrown that no specific handler caught.

**Response**:
```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "An unexpected error occurred",
    "details": "Please contact support if this persists",
    "timestamp": "..."
  }
}
```

**Code path**: [`GlobalExceptionHandler.java:143-155`](../src/main/java/com/company/drools/api/exception/GlobalExceptionHandler.java#L143-L155). The catch-all.

> The full stack trace is logged server-side (level `ERROR`). The client gets only a generic message. This is intentional — avoids leaking internal class names, line numbers, and stack traces to potentially-untrusted callers.

**Fix**:
1. Pull the server log:
   ```bash
   docker compose logs app | grep -A 20 'Unexpected error occurred'
   ```
2. Use the correlation ID (in the `X-Correlation-ID` response header or in MDC log fields) to find the matching log entry.
3. If the same error reproduces, file a bug. The handler is a safety net — anything reaching it indicates a coding gap (an exception type that should have its own handler, or a code path that shouldn't throw at all).

---

## DRL sandbox rejection (during rule loading, not request handling)

When `POST /admin/refresh-rules` is called and a rule violates [`DrlSanitizer`](../src/main/java/com/company/drools/core/engine/DrlSanitizer.java) rules, the rule is rejected and the refresh response includes the violation.

This is **not** an HTTP error code — it's a per-rule entry in the refresh response:

```json
{
  "status": "completed_with_errors",
  "rules_loaded": 9,
  "rules_failed": 1,
  "errors": [
    {
      "rule_id": "dangerous.rule",
      "error": "DRL content contains blocked import: java.io.File"
    }
  ]
}
```

Common DrlSanitizer rejections:
- `DRL content contains blocked import: <import>` — see [16-drl-sandboxing.md](16-drl-sandboxing.md) allowlist
- `DRL content contains blocked class reference: <class>` — Runtime, ProcessBuilder, etc.
- `DRL content contains blocked method call: <method>` — System.exit, Class.forName, etc.
- `eval() is not allowed in DRL rules` — use pattern matching instead

See [16-drl-sandboxing.md](16-drl-sandboxing.md) for the full list and how to write rules that pass.

---

## What this catalog does NOT cover

- **Spring Boot Actuator errors** (`/actuator/*`, port 8081) — those use Spring's default error format, not our envelope.
- **Tomcat-level errors** (e.g., HTTP 414 URI Too Long, HTTP 415 Unsupported Media Type) — emitted before Spring sees the request.
- **TLS / network errors** at the load balancer — those originate before our service.
- **Health-check failures** — surfaced as `503` from the load balancer; not an error envelope from our service.
