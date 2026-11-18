# 11 · Integration Guide

| | |
|---|---|
| **Audience** | External integrators, partner application developers |
| **Purpose** | How to call this service from a client application — language-agnostic patterns plus copy-paste examples in curl, Python, Java, and Node.js |
| **Last verified against** | Live stack on 2026-08-20 |
| **Related docs** | [10-api-reference.md](10-api-reference.md), [12-error-code-catalog.md](12-error-code-catalog.md), [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md), [15-admin-authentication.md](15-admin-authentication.md) |

---

## TL;DR

To execute a rule:
- `POST /execute-rule` with body `{"rule_id": "...", "data": {...}}`
- Rate-limit buckets are keyed on your **source IP only** — no header changes your bucket (see below)
- Read `X-RateLimit-Remaining` / `X-RateLimit-Reset` and back off when needed
- Retry only on **429** (rate limit) and **503** (circuit breaker open **or** transient thread-pool saturation), never on **400** (validation) or **404** (no such rule)
- Treat the response's `error.code` field as the source of truth (not the HTTP status)

---

## Five-minute curl tutorial

The simplest integration possible: bash + curl.

```bash
# Replace with your service URL
DROOLS_URL="http://localhost:8080"

# Execute a rule
curl -sX POST "$DROOLS_URL/execute-rule" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule_id": "pricing.discount.simple",
    "data": { "amount": 100 }
  }' | jq

# Expected output:
# {
#   "rule_id": "pricing.discount.simple",
#   "result": {
#     "amount": 90.0,
#     "discount": 10.0,
#     "discountPercent": 10,
#     "discountReason": "Order over $50 discount"
#   },
#   "error": null,
#   "execution_time_ms": 5
# }
```

Done. The full surface is in [10-api-reference.md](10-api-reference.md); the rest of this doc is on integration *patterns*.

---

## Authentication setup

For most integrators, **no authentication is needed on `/execute-rule`** — the service trusts the upstream gateway / API management layer for identity.

If you call admin endpoints (`/admin/*`) and the deployment has `ADMIN_API_KEY` set, send:
```
X-Admin-API-Key: <your-key>
```

If the deployment exposes the service publicly without a gateway, the rate limiter alone protects against abuse — but you should put a real auth gateway in front of it before exposing externally.

See [15-admin-authentication.md](15-admin-authentication.md).

---

## Client identity is your source IP

The service rate-limits per client, and **"client" always means your source IP** — `request.getRemoteAddr()`, the TCP peer the service sees. There is **no header you can send to change your bucket**: `X-API-Key`, `Authorization`, and `X-Client-Id` are deliberately **not** read by the limiter (keying on unauthenticated headers would let any caller defeat it — finding P2).

| What you send | Bucket key in the limiter |
|---|---|
| `X-API-Key`, `Authorization`, `X-Client-Id`, anything | ignored for bucketing |
| (bucket key is always) | `ip:<source-address>` |

Implications:
- **Behind a load balancer that terminates the connection, everyone shares one bucket** (the LB's IP). The operator fixes this by deploying behind a trusted proxy that overwrites `X-Forwarded-For` and setting `DROOLS_RATE_LIMITING_TRUST_PROXY=true` — then each real client IP gets its own bucket. This is an operator setting; there is nothing a client sends to opt in.
- If you call from behind NAT / a shared egress, you share a bucket with everyone on that egress IP. Plan your request budget accordingly.

---

## Code examples

All four examples below execute the same simple-discount rule and produce the same output. They are tested against the live stack at the time of writing.

### 1. curl

```bash
#!/usr/bin/env bash
set -euo pipefail

DROOLS_URL="${DROOLS_URL:-http://localhost:8080}"

response=$(curl -sX POST "$DROOLS_URL/execute-rule" \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}')

# Parse and use jq for branching
if echo "$response" | jq -e '.error' >/dev/null; then
  code=$(echo "$response" | jq -r '.error.code')
  echo "ERROR: $code" >&2
  exit 1
fi

echo "$response" | jq -r '.result | "discount: \(.discount), final: \(.amount)"'
```

### 2. Python (using `requests`)

```python
"""
Drools rule execution client.

pip install requests tenacity
"""
import os
from typing import Any

import requests
from tenacity import (
    retry,
    retry_if_result,
    stop_after_attempt,
    wait_exponential,
)

DROOLS_URL = os.environ.get("DROOLS_URL", "http://localhost:8080")
TIMEOUT = 30  # match the service's RULE_EXECUTION_TIMEOUT_SECONDS

session = requests.Session()
session.headers.update({
    "Content-Type": "application/json",
})


def _should_retry(response: requests.Response) -> bool:
    """Retry only on transient failures."""
    return response.status_code in (429, 503)


@retry(
    retry=retry_if_result(_should_retry),
    wait=wait_exponential(multiplier=1, min=1, max=30),
    stop=stop_after_attempt(5),
)
def _post(payload: dict) -> requests.Response:
    return session.post(
        f"{DROOLS_URL}/execute-rule",
        json=payload,
        timeout=TIMEOUT,
    )


class DroolsError(Exception):
    def __init__(self, code: str, message: str, status_code: int):
        self.code = code
        self.message = message
        self.status_code = status_code
        super().__init__(f"[{code}] {message}")


def execute_rule(rule_id: str, data: dict[str, Any]) -> dict[str, Any]:
    """Execute a rule. Returns the result map. Raises DroolsError on failure."""
    response = _post({"rule_id": rule_id, "data": data})
    body = response.json()

    if body.get("error"):
        raise DroolsError(
            code=body["error"]["code"],
            message=body["error"]["message"],
            status_code=response.status_code,
        )

    return body["result"]


if __name__ == "__main__":
    result = execute_rule("pricing.discount.simple", {"amount": 100})
    print(f"discount: {result['discount']}, final: {result['amount']}")
```

### 3. Java (Spring `WebClient`)

```java
package com.example.drools.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class DroolsClient {

  private final WebClient webClient;

  public DroolsClient(String baseUrl) {
    this.webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Content-Type", "application/json")
        .build();
  }

  /** Execute a rule. Returns the result Map. Throws DroolsException on failure. */
  public Map<String, Object> executeRule(String ruleId, Map<String, Object> data) {
    Request body = new Request(ruleId, data);

    return webClient.post()
        .uri("/execute-rule")
        .bodyValue(body)
        .retrieve()
        .onStatus(this::isClientError, response ->
            response.bodyToMono(JsonNode.class)
                .flatMap(node -> Mono.error(toException(node, response.statusCode()))))
        .bodyToMono(Response.class)
        .timeout(Duration.ofSeconds(30))
        .retryWhen(
            Retry.backoff(5, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(30))
                .filter(this::isRetryable))
        .map(Response::result)
        .block();
  }

  private boolean isClientError(HttpStatusCode status) {
    return status.is4xxClientError() || status.is5xxServerError();
  }

  private boolean isRetryable(Throwable t) {
    if (t instanceof WebClientResponseException wcre) {
      int s = wcre.getStatusCode().value();
      return s == 429 || s == 503;
    }
    return false;
  }

  private DroolsException toException(JsonNode body, HttpStatusCode status) {
    String code = body.path("error").path("code").asText("UNKNOWN");
    String msg = body.path("error").path("message").asText("");
    return new DroolsException(code, msg, status.value());
  }

  // DTOs
  record Request(@JsonProperty("rule_id") String ruleId, Map<String, Object> data) {}
  record Response(
      @JsonProperty("rule_id") String ruleId,
      Map<String, Object> result,
      JsonNode error,
      @JsonProperty("execution_time_ms") long executionTimeMs) {}

  public static class DroolsException extends RuntimeException {
    public final String code;
    public final int statusCode;
    public DroolsException(String code, String message, int statusCode) {
      super("[" + code + "] " + message);
      this.code = code;
      this.statusCode = statusCode;
    }
  }
}

// Usage:
//   DroolsClient client = new DroolsClient("http://localhost:8080");
//   Map<String, Object> result = client.executeRule(
//       "pricing.discount.simple", Map.of("amount", 100));
//   // result.get("discount") == 10.0
```

### 4. Node.js (`fetch`, ESM)

```javascript
// drools-client.mjs
//
// Tested with Node.js 18+ (built-in fetch).
// No dependencies required.

const DROOLS_URL = process.env.DROOLS_URL ?? "http://localhost:8080";
const TIMEOUT_MS = 30_000;

class DroolsError extends Error {
  constructor(code, message, statusCode) {
    super(`[${code}] ${message}`);
    this.code = code;
    this.statusCode = statusCode;
  }
}

async function _post(body) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    return await fetch(`${DROOLS_URL}/execute-rule`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

async function _retry(fn, { tries = 5, baseMs = 1000, maxMs = 30_000 } = {}) {
  let attempt = 0;
  for (;;) {
    const response = await fn();
    if (![429, 503].includes(response.status) || ++attempt >= tries) {
      return response;
    }

    // Honor X-RateLimit-Reset-After if present
    const wait = parseInt(response.headers.get("X-RateLimit-Reset-After"), 10);
    const backoff = Number.isFinite(wait)
      ? Math.min(wait * 1000, maxMs)
      : Math.min(baseMs * 2 ** (attempt - 1), maxMs);

    await new Promise((r) => setTimeout(r, backoff));
  }
}

export async function executeRule(ruleId, data) {
  const response = await _retry(() => _post({ rule_id: ruleId, data }));
  const body = await response.json();

  if (body.error) {
    throw new DroolsError(
      body.error.code,
      body.error.message,
      response.status,
    );
  }

  return body.result;
}

// Usage:
//   import { executeRule } from "./drools-client.mjs";
//   const result = await executeRule("pricing.discount.simple", { amount: 100 });
//   console.log(`discount: ${result.discount}, final: ${result.amount}`);
```

---

## Error handling pattern

The service emits **HTTP status + JSON envelope** on every error path. Client logic should be:

1. Always parse the response body (success and error). Don't trust HTTP status alone — `error.code` is the canonical signal.
2. Switch on `error.code` for specific handling:

```python
if body.get("error"):
    code = body["error"]["code"]
    if code == "RATE_LIMIT_EXCEEDED":
        # back off, retry
    elif code == "SERVICE_UNAVAILABLE":
        # circuit breaker open OR rule-execution pool saturated; back off, retry
    elif code == "RULE_NOT_FOUND":
        # don't retry; this rule isn't deployed
    elif code == "INVALID_INPUT":
        # don't retry; fix your request
    elif code == "TIMEOUT_ERROR":
        # don't retry blindly — the rule may be hung
    else:
        # log + alert
```

See [12-error-code-catalog.md](12-error-code-catalog.md) for every code.

---

## Retry strategy

| HTTP | Code | Retry? | Backoff |
|---|---|---|---|
| 200 | (success) | n/a | n/a |
| 400 | `INVALID_INPUT` | **No** | Fix the request and try again |
| 400 | `RULE_EXECUTION_ERROR` | **No** | The rule failed; same input will fail again |
| 401 | `UNAUTHORIZED` | **No** | Get the right key |
| 404 | `RULE_NOT_FOUND` | **No** | Rule isn't deployed; talk to the operator |
| 408 | `TIMEOUT_ERROR` | Maybe | Only if you suspect transient slowness; not a normal retry case |
| 413 | `REQUEST_TOO_LARGE` | **No** | Reduce payload |
| 429 | `RATE_LIMIT_EXCEEDED` | **Yes** | Honor `X-RateLimit-Reset-After`. Don't retry faster than that. |
| 500 | `INTERNAL_ERROR` | Maybe | Once or twice with exponential backoff. If it keeps happening, the service has a bug. |
| 503 | `SERVICE_UNAVAILABLE` | **Yes** | Circuit breaker open (wait `wait-duration-in-open-state` — 60s S3, 30s Redis defaults) **or** the rule-execution thread pool is momentarily saturated (retry after a short backoff). |

**Recommended client retry policy**: exponential backoff with jitter, max 5 attempts, max 30s total wait. Honor `X-RateLimit-Reset-After` when present.

---

## Idempotency

| Endpoint | Idempotent? | Notes |
|---|---|---|
| `POST /execute-rule` | **Yes** | The rule reads `data`, modifies a copy, returns. No state change. Calling 5× returns the same result. |
| `POST /admin/refresh-rules` | **No** | Each call may return different rule sets if S3 changed in between. Don't blindly retry. |
| `POST /admin/refresh-rules/{id}` | **No** | Same. |
| `POST /admin/memory/gc` | **No** (but harmless) | Just hints at the JVM. |

You can safely retry `/execute-rule` after a 429 / 503 / 500 without a deduplication key. The service has no notion of request IDs, so it cannot deduplicate even if you wanted it to — but rule execution is referentially transparent so retry is safe.

---

## Batching

The service has no batch endpoint. To execute many rules, make many `POST /execute-rule` calls. Recommended client patterns:

### Sequential — simplest, fits within rate limits

```python
results = []
for input in inputs:
    results.append(execute_rule("pricing.discount.simple", input))
```

### Concurrent — bounded by your client's connection pool, AND service's rate limit

```python
import asyncio, aiohttp

async def execute_one(session, rule_id, data):
    async with session.post(URL, json={"rule_id": rule_id, "data": data}) as r:
        return await r.json()

async def execute_batch(rule_id, inputs, concurrency=10):
    sem = asyncio.Semaphore(concurrency)
    async with aiohttp.ClientSession(headers={"Content-Type": "application/json"}) as session:
        async def bounded(input):
            async with sem:
                return await execute_one(session, rule_id, input)
        return await asyncio.gather(*[bounded(i) for i in inputs])
```

> Don't go above ~10–20 concurrent unless you've raised both:
> - Your client's connection pool (`aiohttp.TCPConnector(limit=...)`, `requests.Session` adapters, etc.)
> - The service's rate limit (`DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE`)

### When to ask for a batch endpoint

If you have batches of ≥1000 rules per call regularly, the per-request overhead becomes the bottleneck. File a feature request — a `/execute-rules-batch` endpoint would amortize the HTTP overhead but is currently not implemented.

---

## Connection pooling

For high-volume integrators, configure a persistent connection pool to avoid TCP/TLS handshake overhead per request.

| Client | How |
|---|---|
| Python `requests` | `requests.Session()` with `HTTPAdapter(pool_connections=10, pool_maxsize=20)` |
| Java `WebClient` | Reactor-Netty defaults are usually fine; tune `ConnectionProvider.builder("name").maxConnections(100)` |
| Node.js | `http.Agent({ keepAlive: true, maxSockets: 50 })` for the global agent |
| Go | Default `http.Transport` is fine; tune `MaxIdleConnsPerHost` |

> The **service-side** S3 connection pool is unrelated — that's how the service talks to S3. The **client-side** pool is how your app talks to the service.

---

## Rate limit headers — pseudocode

```
on response:
    limit = response.headers["X-RateLimit-Limit"]            # e.g., 1000
    remaining = response.headers["X-RateLimit-Remaining"]    # e.g., 999
    reset_epoch = response.headers["X-RateLimit-Reset"]      # epoch seconds
    reset_after = response.headers["X-RateLimit-Reset-After"]# seconds from now

    if remaining == 0 or response.status == 429:
        sleep(reset_after + 1)   # wait until window resets
        retry()
```

---

## Observability — request correlation

Send a correlation ID header so logs on the service side include your client's request ID:

```
X-Correlation-ID: <your-trace-id-or-uuid>
X-Request-ID: <per-request-uuid>
```

The service's `LoggingConfig` validates these against `^[a-zA-Z0-9\-]{1,128}$` and propagates them through MDC into structured logs. If you send malformed IDs, they're dropped silently — the service generates its own.

---

## Testing your integration

### Local LocalStack stack (recommended for development)

```bash
git clone <repo>
cd drools-microservice
docker compose up -d --build
# Wait ~2 minutes for first build
curl http://localhost:8080/admin/health   # → {"status":"UP", ...}
```

Now your integration can hit `http://localhost:8080/execute-rule`. The 17 sample rules in `sample-rules/` are pre-loaded.

### Stub responses for unit tests

If you don't want to bring up Docker for unit tests, mock the HTTP layer:

```python
# Python with responses library
import responses

@responses.activate
def test_my_integration():
    responses.add(
        responses.POST,
        "http://drools/execute-rule",
        json={
            "rule_id": "pricing.discount.simple",
            "result": {"amount": 90.0, "discount": 10.0, "discountPercent": 10},
            "error": None,
            "execution_time_ms": 5,
        },
        status=200,
    )
    # ... your code that calls the rule client
```

Same pattern works in Java (`MockWebServer`), Node.js (`nock`).

---

## Anti-patterns to avoid

| Don't | Do |
|---|---|
| Send `ruleId` (camelCase) | Send `rule_id` (snake_case) |
| Trust the HTTP status alone | Parse `body.error.code` for branching |
| Retry 4xx errors | Only retry 429 and 503 (and maybe 500 once or twice) |
| Open a new TCP connection per request | Use a session / connection pool |
| Expect `X-API-Key` / `X-Client-Id` to give you a private rate-limit bucket | Remember buckets are keyed on source IP only; size your request budget for the IP you call from |
| Set timeout < `RULE_EXECUTION_TIMEOUT_SECONDS` (30s) | Match or exceed the service's timeout — otherwise you give up before the service does |
| Pollute logs with raw response bodies | Log `error.code` and correlation IDs only |
| Build a "smart" client that auto-retries everything | Use a deterministic retry policy; let real errors surface |

---

## What's NOT supported

- **No streaming**. Each request is one-and-done.
- **No long-polling / SSE / WebSockets**.
- **No async result fetch** (`POST /jobs` then `GET /jobs/{id}`). Rule execution is synchronous.
- **No webhook callbacks**.
- **No SDK**. Currently no first-party SDK in any language. The 4 examples above are the closest thing — copy and adapt.
- **No rule discovery API**. The `GET /admin/rules` endpoint exists but is admin-only. If you don't know the rule ID, ask the operator.

---

## Verification

Run all four code examples against the live stack as a sanity check before deploying your integration:

```bash
# curl
curl -sX POST localhost:8080/execute-rule -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq '.result.discount'
# → 10

# Python (after `pip install requests tenacity`)
DROOLS_URL=http://localhost:8080 python drools_client.py
# → discount: 10.0, final: 90.0

# Node.js (Node 18+)
node drools-client.mjs
# → discount: 10, final: 90

# Java — see DroolsClient class above; integrate into your Spring Boot project
```

If any of them produce different output, see [31-troubleshooting.md](31-troubleshooting.md).
