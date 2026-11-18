# 13 · Rate Limiting and Throttling

| | |
|---|---|
| **Audience** | Partners, developers, operators |
| **Purpose** | Complete behavior of the per-client rate limiter — including the multi-tier client identification that's the most surprising part of the design |
| **Last verified against** | [`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java), [`RateLimitingConfig.java`](../src/main/java/com/company/drools/config/RateLimitingConfig.java), [`RateLimitingFilterTest.java`](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java) on 2026-05-24 |
| **Related docs** | [09-environment-variables-reference.md](09-environment-variables-reference.md), [12-error-code-catalog.md](12-error-code-catalog.md), [14-security-architecture.md](14-security-architecture.md) |

---

## TL;DR

- **Default limits**: 1000 requests/minute, 10,000/hour, burst 100. Per client.
- **Client is identified by header priority** (not just by IP):
  1. `X-API-Key` → `api-key:{value}`
  2. `Authorization: Bearer {token}` → `bearer:{hash}`
  3. `X-Client-Id` → `client-id:{value}`
  4. `request.getRemoteAddr()` → `ip:{addr}` (fallback only)
- **`X-Forwarded-For` is explicitly ignored** (spoofable; would let attackers consume someone else's quota).
- **Admin endpoints (`/admin/*`) are exempt** from rate limiting.
- **`/execute-rule` is what's rate-limited.** Other paths under `/api/` are also subject if they exist.
- On limit hit: HTTP **429 Too Many Requests**, JSON body with `RATE_LIMIT_EXCEEDED` error code.
- Response headers (success and 429) include `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `X-RateLimit-Reset-After`.

---

## Filter execution

The rate limiter is [`RateLimitingFilter`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) at `@Order(1)`. It runs **after** SecurityHeadersFilter (-1) and AdminAuthFilter (0), but **before** RequestSizeValidationFilter.

The decision flow per request:

```
incoming request
       │
       ▼
   shouldApplyRateLimit(request)?      ← line 61
   ┌── NO  → /admin/* paths skip limiter, proceed
   └── YES (paths under /execute-rule or /api/, NOT /admin/*)
       │
       ▼
   getClientIdentifier(request)        ← line 69
       │  (multi-tier: X-API-Key → Bearer → X-Client-Id → IP)
       ▼
   rateLimitingService.isAllowed(clientId)
       │
       ├── true  → addRateLimitHeaders → continue chain
       │
       └── false → write 429 response with RATE_LIMIT_EXCEEDED
```

Verified by [`RateLimitingFilterTest.java`](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java):
- `testFilter_AdminEndpoint_SkipsRateLimit()` (lines 125-132) proves admin endpoints are exempt
- `testFilter_PerClient_IndependentLimits()` (lines 101-121) proves clients have independent buckets

---

## Client identification — the multi-tier algorithm

This is the most often-misunderstood part of the design.

[`RateLimitingFilter.java:69-95`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java#L69-L95):

```java
private String getClientIdentifier(HttpServletRequest request) {
  String clientId;

  // 1. Check for API key header
  clientId = request.getHeader("X-API-Key");
  if (clientId != null && !clientId.isEmpty()) {
    return "api-key:" + clientId;
  }

  // 2. Check for Authorization header (Bearer)
  String authHeader = request.getHeader("Authorization");
  if (authHeader != null && authHeader.startsWith("Bearer ")) {
    return "bearer:" + Integer.toHexString(authHeader.hashCode());
  }

  // 3. Check for custom client ID header
  clientId = request.getHeader("X-Client-Id");
  if (clientId != null && !clientId.isEmpty()) {
    return "client-id:" + clientId;
  }

  // 4. Fall back to remote address (don't trust X-Forwarded-For — it's spoofable)
  return "ip:" + request.getRemoteAddr();
}
```

### What identity gets assigned to a client?

| Request sends | Identity | Bucket key |
|---|---|---|
| `X-API-Key: abc123` | api-key | `api-key:abc123` |
| `Authorization: Bearer eyJhbGc...` | bearer (hashed) | `bearer:9f4a2b71` (8 hex chars) |
| `X-Client-Id: partner-A` | client-id | `client-id:partner-A` |
| (none of the above) | IP | `ip:203.0.113.42` |

### What gets first match wins

If a client sends both `X-API-Key` and `Authorization: Bearer`, only the **first match** in the priority order is used. The bucket key would be `api-key:...`, not `bearer:...`.

### Why Bearer tokens are hashed

The full token would be a long string of secret material — logging or storing it as a bucket key is risky. We use `Integer.toHexString(authHeader.hashCode())` which gives us 8 hex chars derived from the full Authorization header.

> **This is hash-based collision risk acknowledged**: 32-bit hash space means two different tokens could (rarely) share a bucket. For rate-limit-only purposes this is acceptable — at worst, two attackers with hash-colliding tokens share a 1000/min budget, which still prevents abuse. **Do not** rely on this hash for security identity.

### Why `X-Forwarded-For` is ignored

`X-Forwarded-For` is set by upstream proxies. Anyone can prepend a fake value to it client-side:
```
X-Forwarded-For: spoofed-victim-ip, real-client-ip, real-proxy-ip
```
A naive rate limiter that uses the leftmost value gets spoofed. We use only `request.getRemoteAddr()`, which is the actual TCP peer the JVM sees — only spoofable if someone has L3-level network access.

> **Implication for deployment behind a load balancer**: if you put this service behind ALB / Cloudflare / similar, **the load balancer's IP** becomes `getRemoteAddr()` for every request. All clients then share one bucket. Production must inject `X-API-Key` or `X-Client-Id` at the LB / API gateway layer to differentiate clients. Otherwise, the IP-fallback is meaningless behind any proxy.

---

## Limits and how they apply

| Limit | Default | Env var | Type |
|---|---:|---|---|
| Requests per minute | 1000 | `DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE` | Per-client |
| Requests per hour | 10,000 | `DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR` | Per-client |
| Burst | 100 | `DROOLS_RATE_LIMITING_BURST_SIZE` | Per-client |
| Max clients tracked | 10,000 | `DROOLS_RATE_LIMITING_MAX_CLIENTS` | Service-wide |
| Cleanup interval | 5 min | `DROOLS_RATE_LIMITING_CLEANUP_INTERVAL` | Service-wide |
| Master switch | true | `DROOLS_RATE_LIMITING_ENABLED` | Service-wide |

### Both limits apply

A client can hit *either* limit. The first one tripped causes the 429.

Example: a client sends 1000 requests in the first 6 seconds of the minute. They hit the per-minute limit. Even though they could in principle do 10000/hr (avg ~167/min), they're throttled.

### `max-clients` and what happens at capacity

The rate limiter tracks per-client state in a `ConcurrentHashMap`. To prevent memory exhaustion (an attacker spoofing 10 million `X-Client-Id` values to fill the map), there's a hard cap.

[`RateLimitingConfig.java:85-91`](../src/main/java/com/company/drools/config/RateLimitingConfig.java#L85-L91):
```java
if (!clientData.containsKey(clientId)
    && clientData.size() >= config.getMaxClients()) {
  log.warn("Rate limiter client map at capacity, rejecting new client");
  return false;
}
```

**At capacity (10,000 distinct clients tracked)**:
- **Existing clients** continue to be served normally (their state is already in the map).
- **New clients** are **rejected outright** — first request returns 429.

This is intentional. A spoofing attack tries to inject many distinct client IDs to fill memory. Rejecting new clients at capacity caps the memory cost while keeping the legitimate-client population working. After the cleanup interval (5 min default), idle clients are evicted, freeing slots.

### Cleanup of stale buckets

Every 5 minutes (`DROOLS_RATE_LIMITING_CLEANUP_INTERVAL`), buckets idle for more than 1 hour are removed. This keeps memory bounded and lets new clients get slots once old ones go quiet. The cleanup is opportunistic (runs inline at request time, not via a scheduled thread), so it only fires when traffic is flowing.

---

## Response headers

On every rate-limited request (both 200 and 429), the filter sets:

| Header | What it means |
|---|---|
| `X-RateLimit-Limit` | The applicable limit (per-minute by default). |
| `X-RateLimit-Remaining` | How many requests remain in the current window. |
| `X-RateLimit-Reset` | Unix epoch (seconds) when the window resets. |
| `X-RateLimit-Reset-After` | Seconds from now until the window resets. Convenience for clients. |

```bash
curl -i -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}'
```

Response (truncated):
```
HTTP/1.1 200
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 999
X-RateLimit-Reset: 1715173025
X-RateLimit-Reset-After: 60
Content-Type: application/json
...
```

These headers are **also set on admin endpoints if rate-limiting is bypassed** — but the values reflect a free, unlimited bucket. Treat them as informational on admin paths.

---

## 429 response body

```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded",
    "details": "Too many requests. Please try again later.",
    "timestamp": "2026-05-10T08:57:10.123Z"
  }
}
```

See [12-error-code-catalog.md](12-error-code-catalog.md) for the canonical error reference.

---

## Client behavior recommendations

### Always read the headers

Don't blindly retry on 429. Read `X-RateLimit-Reset-After` and back off until then.

```python
# Python pseudocode
import time
import requests

def call_rule(rule_id, data, retries=3):
    for attempt in range(retries):
        r = requests.post(URL, json={"rule_id": rule_id, "data": data})
        if r.status_code == 429:
            wait = int(r.headers.get("X-RateLimit-Reset-After", 60))
            time.sleep(wait + 1)
            continue
        return r
    raise Exception("Rate limited after retries")
```

### Use a stable client identifier

If you're a service integrating against this API, **send `X-API-Key` or `X-Client-Id`** so your bucket is stable across redeploys, IP rotations, and proxies. Don't rely on IP-based identification.

### Stay under the 1-second-equivalent burst

The "burst" config (`burst-size: 100` default) lets you exceed the steady-state rate for short bursts. You can't sustain it. If you need 1000 calls/minute, pace them at ~17/sec, not 1000 in the first second.

---

## Operator behavior

### Tuning for your workload

Before raising limits, ask:
- "What's the legitimate steady-state rate per client?" → set `requests-per-minute`
- "What's the legitimate hourly volume?" → set `requests-per-hour`
- "How many distinct clients?" → set `max-clients` to ~2× expected (headroom for cleanup churn)

Common production overrides:
```bash
DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE=5000
DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR=200000
DROOLS_RATE_LIMITING_BURST_SIZE=500
DROOLS_RATE_LIMITING_MAX_CLIENTS=50000
```

### Monitoring

Log line on rate-limit rejection:
```
WARN c.c.d.api.filter.RateLimitingFilter - Rate limit exceeded for client: api-key:abc123
```

Log line at max-clients capacity:
```
WARN c.c.d.config.RateLimitingConfig - Rate limiter client map at capacity (10000), rejecting new client
```

The capacity warning is high-signal — it indicates either a real spike in distinct clients or a spoofing attack. Alert on this.

### Disabling temporarily

For debugging or load testing where rate limiting interferes:
```bash
DROOLS_RATE_LIMITING_ENABLED=false
```

**Don't ship this to production.** Without rate limiting, a buggy client can saturate thread pools and starve legitimate traffic.

---

## Things that are *not* what they seem

### "Per-client" doesn't mean "per-user"

The rate limiter has no concept of users. It tracks whatever string `getClientIdentifier()` returns. If your API gateway forwards a Bearer token *but doesn't decode it*, every user behind that gateway sharing the same... wait, actually each user has a unique Bearer token, so they each get a unique `bearer:hash` bucket. This works correctly **as long as the Authorization header is unique per user**.

If users somehow share the same Authorization value (a shared service token), they share a bucket.

### Anonymous traffic from one source IP shares a bucket

Without `X-API-Key` / Bearer / `X-Client-Id`, all anonymous requests from the same TCP peer share one `ip:...` bucket. In production this is rarely what you want — see the load-balancer note above. Inject a header at the gateway layer.

### Rate limiting is per-instance, not cluster-wide

The bucket state lives in a `ConcurrentHashMap` inside one JVM. If you run 4 service replicas behind a load balancer, a client's effective limit is **4×** the configured value (each replica grants the full quota independently).

To enforce cluster-wide limits, you'd need to back the rate limiter with Redis. Currently not implemented. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) and `CODE_FINDINGS.md` for the deferred infrastructure.

### Burst is independent of per-minute / per-hour

`burst-size: 100` allows up to 100 requests in a very short window (sub-second), regardless of the per-minute pace. If the per-minute limit is 1000, the actual short-term ceiling is `min(burst, remaining-in-minute)`. Burst was added because some legitimate clients send batches synchronously.

---

## What this filter does NOT do

- **No Retry-After header** — we use `X-RateLimit-Reset-After` instead. Some HTTP clients only auto-retry when they see `Retry-After`. (Possible improvement: add `Retry-After` for compatibility.)
- **No global / fleet-wide rate limiting** — each instance has its own buckets. Use a shared store (Redis, gateway-level rate limiting) for cluster-wide limits.
- **No per-rule rate limiting** — you can't say "rule X is limited to 10/sec but rule Y is 100/sec". Limits are flat across all `/execute-rule` calls.
- **No fairness across clients** — the limiter is bucket-per-client; one client hitting their limit doesn't help another client. But it also doesn't penalize them.
- **No tier-based limits** — every client gets the same limit. Premium-vs-free distinctions need to be made upstream (different API keys → different gateways → different downstream services with different config).

---

## Verification (live)

Confirm the multi-tier identification with the running stack:

```bash
# Without any header — ip-based bucket
curl -i -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | grep -i 'X-RateLimit'

# With API key — separate bucket
curl -i -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: client-foo' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | grep -i 'X-RateLimit'

# With Client-Id — yet another bucket
curl -i -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -H 'X-Client-Id: partner-bar' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | grep -i 'X-RateLimit'
```

Each of these will have its own `X-RateLimit-Remaining` counter, decrementing independently. To prove this empirically, hit each one a few times and observe the counters move separately.

To force a 429 (test the rejection path):

```bash
# Loop fast; eventually hit the burst+per-minute cap
for i in $(seq 1 1100); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/execute-rule \
    -H 'Content-Type: application/json' \
    -H 'X-Client-Id: throttle-test' \
    -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}'
done | sort | uniq -c
```

You should see a mix of `200` and `429` codes (~1000 succeed, ~100 fail in the first minute, then more fail until the next minute).

After 60 seconds, the bucket refills.
