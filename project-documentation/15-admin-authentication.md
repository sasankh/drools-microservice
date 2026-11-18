# 15 · Admin Authentication

| | |
|---|---|
| **Audience** | Operators, developers |
| **Purpose** | The Admin API key flow end-to-end — when it activates, what happens when it doesn't, and how to operate it |
| **Last verified against** | [`AdminAuthFilter.java`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java), [`AdminAuthFilterTest.java`](../src/test/java/com/company/drools/api/filter/AdminAuthFilterTest.java) on 2026-08-20 |
| **Related docs** | [10-api-reference.md](10-api-reference.md), [12-error-code-catalog.md](12-error-code-catalog.md), [14-security-architecture.md](14-security-architecture.md) |

---

## TL;DR

- **One env var controls everything**: `ADMIN_API_KEY`.
- **If set**: every `/admin/*` request must include `X-Admin-API-Key: <value>`. Missing or wrong → HTTP 401. Comparison is **constant-time** (`MessageDigest.isEqual`).
- **If empty/unset**: behavior depends on the active profile:
  - **`prod` or `docker`**: **fail-closed** — the application refuses to start (`IllegalStateException`). Admin endpoints are never served unprotected in a deployable profile.
  - **`local` or `dev`**: admin endpoints are **open** (no auth) and a WARN is logged at startup.
- **Filter only protects `/admin/*`** (paths starting with `/admin/`). `/execute-rule` and `/actuator/*` are unaffected.
- **The filter is defense in depth.** Primary auth should be at your API gateway. This is a backstop.

---

## How the filter works

[`AdminAuthFilter`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java) is a `@Component` Spring filter at `@Order(0)`. It runs second in the chain (after `SecurityHeadersFilter` at `@Order(-1)`).

### Startup validation (fail-closed in deployable profiles)

The constructor decides at startup what a blank key means, based on the active profile. The set of "key-required" profiles is `{prod, docker}`:

```java
private static final Set<String> KEY_REQUIRED_PROFILES = Set.of("prod", "docker");

// In the constructor:
boolean blank = adminApiKey == null || adminApiKey.isBlank();
if (blank && isKeyRequiredProfileActive(environment)) {
  // Fail closed: refuse to start rather than serve /admin/* unprotected.
  throw new IllegalStateException(
      "ADMIN_API_KEY must be set when running with a deployable profile ...");
}
```

So a blank key **fails startup** under `prod`/`docker`, and only falls through to the open-with-WARN path under `local`/`dev`.

### Activation logic

```java
private boolean shouldAuthenticate(HttpServletRequest request) {
  // Only enforce when an API key is configured
  if (adminApiKey == null || adminApiKey.isBlank()) {
    return false;
  }
  String uri = request.getRequestURI();
  return uri.startsWith("/admin/");
}
```

Two conditions must both be true to enforce auth:
1. `ADMIN_API_KEY` env var is non-empty.
2. The request URI starts with `/admin/`.

If either is false, the filter passes through without checking anything. (Under `prod`/`docker`, condition 1 being false has already been ruled out — the app would not have started.)

### Startup log line

The filter's constructor logs based on whether the key is set:

| State | Log line | Level |
|---|---|---|
| Key set | `Admin endpoint authentication enabled` | INFO |
| Key empty/null, `local`/`dev` | `Admin API key is not configured — admin endpoints are unprotected. Set ADMIN_API_KEY environment variable for production.` | **WARN** |
| Key empty/null, `prod`/`docker` | `IllegalStateException: ADMIN_API_KEY must be set when running with a deployable profile [prod, docker] ...` | **startup fails** |

> Under `local`/`dev` the WARN log is your tripwire — but you should never see it in a deployable profile, because there a blank key stops the app from starting at all. Alert on both the WARN line and on startup failures.

### Header check

```java
if (shouldAuthenticate(request)) {
  String providedKey = request.getHeader(API_KEY_HEADER);  // "X-Admin-API-Key"

  if (providedKey == null || !constantTimeEquals(providedKey, adminApiKey)) {
    log.warn("Unauthorized admin access attempt from {}", request.getRemoteAddr());
    writeUnauthorizedResponse(response);
    return;
  }
}
filterChain.doFilter(request, response);
```

The comparison is **constant-time**, backed by `MessageDigest.isEqual` over the UTF-8 bytes of the two keys — not `String.equals`:

```java
private static boolean constantTimeEquals(String provided, String expected) {
  return MessageDigest.isEqual(
      provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
}
```

This avoids leaking the key one byte at a time through response-timing side channels, so the check does not depend on admin traffic being low-volume or low-stakes to be safe.

### Response on failure

HTTP **401 Unauthorized** with this body:

```json
{
  "rule_id": null,
  "result": null,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Admin API key required",
    "details": "Provide a valid API key via the X-Admin-API-Key header",
    "timestamp": "..."
  }
}
```

Implementation: [`AdminAuthFilter.writeUnauthorizedResponse`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java).

The same response shape is returned whether the header is missing or wrong — no information leak about which case it was. (A 401 with `WWW-Authenticate` header isn't used because we're not implementing HTTP Basic / Digest auth — this is a custom API key scheme.)

---

## Operational guidance

### Setting the key

Generate a long random key (32+ chars):

```bash
# 32 hex chars (128 bits)
ADMIN_API_KEY=$(openssl rand -hex 16)

# Or 32 base64url chars (192 bits)
ADMIN_API_KEY=$(openssl rand -base64 24 | tr '+/' '-_' | tr -d '=')
```

Pass it via the deployment mechanism:

| Deployment | How to set |
|---|---|
| Local dev | `.env` file: `ADMIN_API_KEY=...` (gitignored) |
| Docker run | `docker run -e ADMIN_API_KEY=...` |
| docker-compose | `environment:` section in `app` service |
| ECS Fargate | Task definition `secrets:` (from AWS Secrets Manager or SSM Parameter Store) |
| Kubernetes | `Secret` mounted as env var |
| systemd | `EnvironmentFile=` in unit file (with restricted perms) |

### Don't do this

| Don't | Why |
|---|---|
| Hardcode the key in `application.yml` | It would land in git |
| Log the key | Even at debug. Use `LogSanitizer` if you must reference it |
| Put it in URL query string | `?api_key=...` would leak via access logs and Referer headers |
| Reuse the key across environments | Compromise of dev key = compromise of prod key |
| Send it from JavaScript in browsers | Browser cannot keep a secret. Use a server-side proxy. |

### Key rotation procedure

The filter does not support multiple valid keys simultaneously. To rotate without downtime:

```
Time 0:  Production running with KEY_OLD
Time 1:  Deploy new instances with KEY_NEW behind LB; old still serving KEY_OLD
Time 2:  All clients begin using KEY_NEW; LB drains old instances
Time 3:  Old instances terminated
```

This requires **deploying** to rotate — not just changing config on the live process. Spring Boot doesn't pick up env var changes at runtime without a restart.

If you can't do blue/green deployment:
- Schedule a maintenance window
- Accept downtime equal to deploy + boot time (~30s for this service)

### Verifying the key is set

```bash
# The startup log will show one of:
docker compose logs app | grep -E 'Admin (endpoint authentication enabled|API key is not configured)'

# At runtime, hit /admin/health without the key:
curl -i http://localhost:8080/admin/health 2>&1 | head -1
# If unauth: HTTP/1.1 401
# If open:   HTTP/1.1 200
```

---

## Why this design (and what it's NOT)

### It's defense in depth

In production, this service runs behind:
- A load balancer (ALB / Cloudflare / etc.)
- Optionally an API gateway (Kong / AWS API Gateway / etc.)

The gateway should be the **primary** authenticator — bearer tokens, JWTs, OAuth, mTLS, IP allowlists. The Admin API key is a **second** check, behind the gateway. If the gateway is misconfigured or compromised, the API key still requires the attacker to also have the key.

That's why the implementation is intentionally simple: it's not trying to be a full identity layer.

### Why not Spring Security?

We use a 131-line filter instead of `spring-boot-starter-security`. The trade-off:

| Pro | Con |
|---|---|
| Tiny attack surface — 131 lines vs ~50,000 in spring-security | Doesn't get OAuth / JWT / RBAC for free |
| No dependency drift | Have to write our own auth if we ever need more |
| Easy to audit | Less ergonomic for complex auth flows |

The current scope is a single API key for admin endpoints. Spring Security would be over-engineering. Adding it later is straightforward (swap the filter; replace `@Component` with a `SecurityFilterChain` bean).

### What this filter is NOT

- **Not user authentication.** No "who is this caller?" identity. Just "do you have the right key?"
- **Not authorization.** No roles, no permissions. Either you have the key (full access to all admin endpoints) or you don't (none).
- **Not session-based.** Every request must include the header. There is no login/logout.
- **Not encrypted at rest.** The key in your `.env` file is plain text. Use a secret manager in production.

For richer auth, layer on:
- API gateway with OAuth → JWT
- mTLS for service-to-service
- IP allowlists for human operators
- Spring Security if you need fine-grained roles in-process

---

## Common errors and fixes

### "I'm getting 401 on /admin/health"

You set `ADMIN_API_KEY` and didn't include the header. Add it:
```bash
curl -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/health
```

### "The header doesn't seem to do anything"

Check that `ADMIN_API_KEY` is actually set in the running process:
```bash
# In a docker compose env:
docker compose exec app sh -c 'echo $ADMIN_API_KEY'

# Or check the startup log:
docker compose logs app | grep -i 'admin endpoint authentication'
```

If the log says "is not configured", the key isn't being passed through. Common causes:
- `.env` file not mounted into container
- Env var named slightly different (e.g., `DROOLS_ADMIN_API_KEY` doesn't work — must be `ADMIN_API_KEY`)
- Double-quoting in shell shipping a literal value with quotes

### "I see UNAUTHORIZED logs from a legit caller"

Look at the log message — `Unauthorized admin access attempt from <ip>`. The IP is the actual TCP peer. If it's an internal LB IP, your client probably isn't sending the header at all (LB stripped it?).

### "Production key got committed to git"

Rotate immediately. Steps:
1. Generate a new key.
2. Deploy with the new key (blue/green or maintenance window).
3. Once new is live, mark the old one revoked.
4. Audit recent admin endpoint access logs for misuse.
5. Force-push or BFG to scrub the leaked key from git history (note: this is risky and doesn't help if anyone else has cloned the repo — assume it leaked).

### "I want different keys for different operators"

Currently not supported — there's one key. If you need per-operator audit trails, layer in an API gateway that issues per-user tokens and forwards a stable `X-Client-Id` header. The gateway holds the operator → service-key mapping.

---

## Why `/admin/*` and not `/admin/**`?

Look closely at the check in [`AdminAuthFilter.shouldAuthenticate`](../src/main/java/com/company/drools/api/filter/AdminAuthFilter.java):
```java
return uri.startsWith("/admin/");
```

This is a **prefix** check, not a glob. It matches:
- `/admin/health` ✓
- `/admin/rules` ✓
- `/admin/refresh-rules/pricing.discount.simple` ✓
- `/admin/memory/info` ✓
- `/admin/memory/snapshot` ✓
- `/admin/memory/gc` ✓
- `/admin/thread-pools` ✓
- `/admin/info` ✓
- `/admin/anything-else` ✓ (would also be protected)

It does NOT match:
- `/admin` (no trailing slash) — note this in case Spring routes that to `/admin/`
- `/Admin/...` (case sensitive)
- `/api/admin/...`

If you add new admin endpoints, putting them anywhere under `/admin/...` keeps them protected automatically.

---

## Verification

### Live: with key set

```bash
# Set ADMIN_API_KEY=test-key in your env, restart
export ADMIN_API_KEY=test-key
docker compose up -d --force-recreate app

# Without header
curl -i http://localhost:8080/admin/health 2>&1 | head -1
# → HTTP/1.1 401

# With wrong key
curl -i -H "X-Admin-API-Key: wrong" http://localhost:8080/admin/health 2>&1 | head -1
# → HTTP/1.1 401

# With correct key
curl -i -H "X-Admin-API-Key: test-key" http://localhost:8080/admin/health 2>&1 | head -1
# → HTTP/1.1 200

# /execute-rule still works without the header (not an admin endpoint)
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  -i 2>&1 | head -1
# → HTTP/1.1 200
```

### Live: with key unset

Behavior depends on the active profile.

**`local`/`dev` (open with WARN):**
```bash
# No ADMIN_API_KEY set, dev profile
unset ADMIN_API_KEY
SPRING_PROFILES_ACTIVE=dev docker compose up -d --force-recreate app

# /admin/health is open
curl -i http://localhost:8080/admin/health 2>&1 | head -1
# → HTTP/1.1 200

# Startup log confirms:
docker compose logs app | grep 'Admin API key'
# → WARN  c.c.d.api.filter.AdminAuthFilter - Admin API key is not configured ...
```

**`prod`/`docker` (fail-closed — app does not start):**
```bash
# No ADMIN_API_KEY set, docker profile
unset ADMIN_API_KEY
SPRING_PROFILES_ACTIVE=docker docker compose up -d --force-recreate app

# The container fails to become healthy; startup log shows:
docker compose logs app | grep -i 'ADMIN_API_KEY must be set'
# → IllegalStateException: ADMIN_API_KEY must be set when running with a deployable profile [prod, docker] ...
```

---

## Test coverage

[`AdminAuthFilterTest.java`](../src/test/java/com/company/drools/api/filter/AdminAuthFilterTest.java) covers (13 tests, grouped in `@Nested` classes):
- **When API key is configured**: valid key proceeds; invalid key → 401; missing header → 401; non-admin and `/actuator/*` paths bypass auth; `/admin/refresh-rules` is protected; error-response JSON shape.
- **When API key is not configured (non-deployable profile)**: admin requests allowed with empty key and with null key.
- **Fail-closed in deployable profiles (P1)**: constructor throws when the key is blank under `prod`; throws when null under `docker`; does NOT throw under `local`; starts normally when a key is configured in a deployable profile.

Run with:
```bash
mvn test -Dtest=AdminAuthFilterTest
```
