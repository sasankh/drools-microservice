# 13 · Rate Limiting and Throttling

| | |
|---|---|
| **Audience** | Partners, developers, operators |
| **Purpose** | Complete behavior of the per-client rate limiter — client identity is the source IP only, which is the most misunderstood part of the design |
| **Last verified against** | [`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java), [`RateLimitingConfig.java`](../src/main/java/com/company/drools/config/RateLimitingConfig.java), [`RateLimitingFilterTest.java`](../src/test/java/com/company/drools/api/filter/RateLimitingFilterTest.java) on 2026-08-20 |
| **Related docs** | [09-environment-variables-reference.md](09-environment-variables-reference.md), [12-error-code-catalog.md](12-error-code-catalog.md), [14-security-architecture.md](14-security-architecture.md) |

---

## TL;DR

- **Default limits**: 1000 requests/minute, 10,000/hour, burst 100. Per client.
- **Client is identified by source IP only** — `request.getRemoteAddr()` → `ip:{addr}`. Application-level headers (`X-API-Key`, `Authorization`, `X-Client-Id`) are **not** read for bucketing; keying on unauthenticated headers would let any caller pick a fresh bucket per request (unlimited throughput) or rotate headers to churn the client map (finding P2).
- **`X-Forwarded-For` is ignored by default.** Only when `drools.rate-limiting.trust-proxy=true` (env `DROOLS_RATE_LIMITING_TRUST_PROXY`, default `false`) is the left-most `X-Forwarded-For` entry used as the client IP — enable this **only** behind a trusted proxy/LB that overwrites inbound `X-Forwarded-For`, otherwise it is spoofable.
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
   getClientIdentifier(request)
       │  (source IP only: getRemoteAddr(), or left-most X-Forwarded-For when trust-proxy=true)
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

## Client identification — source IP only

This is the most often-misunderstood part of the design. **The rate limiter keys buckets on the source IP address only.** Application-level headers are never read for identity.

[`RateLimitingFilter.java`](../src/main/java/com/company/drools/api/filter/RateLimitingFilter.java) `getClientIdentifier`:

```java
private String getClientIdentifier(HttpServletRequest request) {
  // Identify the client by network address only. Application-level headers (X-API-Key,
  // Authorization, X-Client-Id) are unauthenticated on the public /execute-rule API — keying on
  // them let any caller pick a fresh bucket per request (unlimited throughput) or rotate headers
  // to fill the client map and lock out real users. See finding P2.
  if (trustProxy) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      // Left-most entry is the originating client. Only trustworthy when a trusted proxy
      // overwrites inbound X-Forwarded-For (operator's responsibility via trust-proxy=true).
      int comma = forwardedFor.indexOf(',');
      String first = (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
      if (!first.isEmpty()) {
        return "ip:" + first;
      }
    }
  }
  return "ip:" + request.getRemoteAddr();
}
```

### What identity gets assigned to a client?

| Deployment | Bucket key |
|---|---|
| Default (`trust-proxy=false`) | `ip:` + `request.getRemoteAddr()` — the actual TCP peer |
| `trust-proxy=true`, request has `X-Forwarded-For` | `ip:` + left-most `X-Forwarded-For` entry |
| `trust-proxy=true`, no `X-Forwarded-For` | `ip:` + `request.getRemoteAddr()` (falls back) |

> `X-API-Key`, `Authorization`, and `X-Client-Id` have **no effect** on the rate-limit bucket. Sending them changes nothing about which bucket a request lands in.

### Why headers are not used for bucketing

On the public `/execute-rule` API these headers are unauthenticated. If the limiter keyed on them:
- Any caller could send a **fresh random `X-API-Key` per request** and get a brand-new 1000/min budget every time — unlimited throughput, rate limiting defeated.
- Any caller could **rotate `X-Client-Id` values** to churn distinct entries through the client map and evict legitimate users' buckets.

Keying purely on the network address the JVM actually sees (`getRemoteAddr()`) removes both attacks. See finding P2.

### Why `X-Forwarded-For` is off by default

`X-Forwarded-For` is set by upstream proxies, and anyone can prepend a fake value client-side:
```
X-Forwarded-For: spoofed-victim-ip, real-client-ip, real-proxy-ip
```
A limiter that trusts the left-most value on untrusted input gets spoofed. By default the service uses only `request.getRemoteAddr()`, the actual TCP peer — spoofable only with L3-level network access.

**When you are behind a trusted proxy/LB** (ALB, Cloudflare, nginx) that **overwrites** inbound `X-Forwarded-For` with the real client IP, set `DROOLS_RATE_LIMITING_TRUST_PROXY=true`. The limiter then reads the left-most `X-Forwarded-For` entry so per-client buckets survive the proxy. This is safe **only** because a trusted proxy has stripped any client-supplied XFF. Do **not** enable it if the proxy appends to (rather than overwrites) inbound XFF.

> **Implication if you leave `trust-proxy=false` behind a load balancer**: **the load balancer's IP** becomes `getRemoteAddr()` for every request, so all clients share one bucket. Set `trust-proxy=true` (with a trusted proxy that overwrites XFF) to differentiate clients by their real IP.

---

## Limits and how they apply

| Limit | Default | Env var | Type |
|---|---:|---|---|
| Requests per minute | 1000 | `DROOLS_RATE_LIMITING_REQUESTS_PER_MINUTE` | Per-client |
| Requests per hour | 10,000 | `DROOLS_RATE_LIMITING_REQUESTS_PER_HOUR` | Per-client |
| Burst | 100 | `DROOLS_RATE_LIMITING_BURST_SIZE` | Per-client |
| Max clients tracked | 10,000 | `DROOLS_RATE_LIMITING_MAX_CLIENTS` | Service-wide |
| Cleanup interval | 5 min | `DROOLS_RATE_LIMITING_CLEANUP_INTERVAL` | Service-wide |
| Trust proxy (`X-Forwarded-For`) | false | `DROOLS_RATE_LIMITING_TRUST_PROXY` | Service-wide |
| Master switch | true | `DROOLS_RATE_LIMITING_ENABLED` | Service-wide |

### Both limits apply

A client can hit *either* limit. The first one tripped causes the 429.

Example: a client sends 1000 requests in the first 6 seconds of the minute. They hit the per-minute limit. Even though they could in principle do 10000/hr (avg ~167/min), they're throttled.

### `max-clients` and what happens at capacity

The rate limiter tracks per-client state in a `ConcurrentHashMap`. To bound memory (many distinct source IPs over time), there's a hard cap.

[`RateLimitingConfig.java`](../src/main/java/com/company/drools/config/RateLimitingConfig.java) `isAllowed` / `evictLeastRecentlyUsed`:
```java
if (!clientData.containsKey(clientId) && clientData.size() >= config.getMaxClients()) {
  evictLeastRecentlyUsed();
}
ClientRateData data = clientData.computeIfAbsent(clientId, k -> new ClientRateData());
return data.isAllowed(now, config);
```

**At capacity (10,000 distinct clients tracked)**:
- **Existing clients** continue to be served normally (their state is already in the map).
- A **new client is admitted** — the limiter **evicts the least-recently-used bucket** to make room, then creates the new client's bucket. New clients are **not** rejected with a 429.

This is a deliberate change from the earlier reject-new-client behavior, which turned the memory cap into a denial-of-service against genuine new users (finding P2). LRU eviction bounds memory without penalizing real traffic. Idle buckets are also removed by the periodic cleanup below.

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

### Your bucket is your source IP

Rate-limit buckets are keyed on the source IP only — sending `X-API-Key` / `X-Client-Id` / `Authorization` does **not** give you a separate bucket. If you call from behind NAT or a shared egress, you share a bucket with everyone on that IP. If you call through a proxy the service trusts (`trust-proxy=true`), your bucket follows the left-most `X-Forwarded-For` value the proxy sets. There is nothing a client can send to change its bucket.

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
WARN c.c.d.api.filter.RateLimitingFilter - Rate limit exceeded for client: ip:203.0.113.42
```

Log line when a new client is admitted by evicting the LRU bucket at capacity (DEBUG level):
```
DEBUG c.c.d.config.RateLimitingConfig - Rate limiter at capacity (10000), evicted least-recently-used client bucket
```

The eviction line is high-signal — it indicates the tracked-client population is churning through `max-clients`, which may mean a real spike in distinct source IPs. Enable DEBUG on `com.company.drools.config.RateLimitingConfig` if you want to alert on it.

### Disabling temporarily

For debugging or load testing where rate limiting interferes:
```bash
DROOLS_RATE_LIMITING_ENABLED=false
```

**Don't ship this to production.** Without rate limiting, a buggy client can saturate thread pools and starve legitimate traffic.

---

## Things that are *not* what they seem

### "Per-client" doesn't mean "per-user"

The rate limiter has no concept of users. It tracks whatever string `getClientIdentifier()` returns, which is always `ip:<address>`. Every user sharing a source IP (corporate NAT, a shared egress gateway, a load balancer without `trust-proxy`) shares one bucket. There is no header a user can send to get their own bucket.

### All traffic from one source IP shares a bucket

Every request from the same TCP peer shares one `ip:...` bucket. Behind a load balancer that terminates the connection, that peer is the LB — so all clients collapse into one bucket unless you set `trust-proxy=true` and the LB overwrites `X-Forwarded-For` with the real client IP. See the load-balancer note above.

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

Confirm IP-based identification with the running stack. Note that adding `X-API-Key` / `X-Client-Id` does **not** create a separate bucket — the counter for the same source IP keeps decrementing regardless of headers:

```bash
# Bucket keyed on source IP
curl -i -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | grep -i 'X-RateLimit'

# Same source IP + an API key header — SAME bucket, counter keeps dropping
curl -i -X POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: client-foo' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  | grep -i 'X-RateLimit'
```

The `X-RateLimit-Remaining` counter decrements across **both** calls together, proving the header is ignored for bucketing.

To force a 429 (test the rejection path):

```bash
# Loop fast; eventually hit the burst+per-minute cap for this source IP
for i in $(seq 1 1100); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/execute-rule \
    -H 'Content-Type: application/json' \
    -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}'
done | sort | uniq -c
```

You should see a mix of `200` and `429` codes (~1000 succeed, ~100 fail in the first minute, then more fail until the next minute).

After 60 seconds, the bucket refills.
