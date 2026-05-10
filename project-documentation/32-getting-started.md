# 32 · Getting Started

| | |
|---|---|
| **Audience** | New developers and evaluators (see something work in under 30 minutes) |
| **Purpose** | Fastest path from `git clone` to "I just executed a rule and got a result" |
| **Last verified against** | Running stack on 2026-05-10 |
| **Related docs** | [27-development-setup.md](27-development-setup.md) (full dev setup), [33-simple-start.md](33-simple-start.md) (rule author quickstart), [10-api-reference.md](10-api-reference.md) |

---

## What you'll have in 30 minutes

- The full stack running locally in Docker
- 17 sample business rules loaded
- Three successful API calls under your belt
- A pointer to where to go next

---

## Prerequisites (5 min)

You need exactly these:

- **Docker** (with Docker Compose v2). Verify: `docker info` and `docker compose version`.
- **Ports free**: 8080, 8081, 4566, 6379. Quick check: `lsof -nP -iTCP:8080 -iTCP:8081 -iTCP:4566 -iTCP:6379 -sTCP:LISTEN`. Empty output = all clear.
- **`curl`** and **`jq`** for testing (`brew install jq` on Mac, `apt install jq` on Linux).
- **~4 GB RAM free** for the containers.
- **~10 GB disk** (Maven dependencies will download into the container).

You do **NOT** need: Java, Maven, or AWS CLI. The Docker stack handles all of that.

---

## Step 1: Clone (1 min)

```bash
git clone <your-repo-url>
cd drools-microservice
```

---

## Step 2: Start everything (5-10 min, mostly waiting)

```bash
docker compose up -d --build
```

What happens:
1. **Builds the app image** (Maven inside the container, ~3-5 min on first run, downloads dependencies). Future builds are seconds.
2. **Starts LocalStack** (S3 emulator). Auto-runs `init-localstack.sh` which uploads the 17 sample rules.
3. **Starts Redis** (cache).
4. **Starts the app**. Boots in ~30-45s.

Watch progress:
```bash
docker compose ps
```

Wait for all 3 services to show `(healthy)`. Should be ~2-5 minutes after the build completes.

If anything stalls:
```bash
docker compose logs --tail 50 app
docker compose logs --tail 50 localstack
```

---

## Step 3: Verify the service is up (30 sec)

```bash
curl -fsS http://localhost:8080/admin/health | jq '.status'
# → "UP"
```

If it says `"UP"`, you're ready.

If you get `Connection refused`, the app hasn't finished booting. Wait 30s and retry. If it persists, see [31-troubleshooting.md](31-troubleshooting.md).

---

## Step 4: Make your first three calls (5 min)

### Call 1: List loaded rules

```bash
curl -fsS http://localhost:8080/admin/rules | jq '.total_rules, .rules[].rule_id'
```

You should see `10` followed by these rule IDs:
```
"validation.customer.age"
"pricing.shipping.standard"
"pricing.shipping.express"
"pricing.discount.first-time"
"pricing.discount.bulk"
"pricing.discount.vip"
"validation.customer.credit"
"seasonal.holiday.blackfriday"
"seasonal.holiday.discount"
"pricing.discount.simple"
```

These are the [sample-rules](../sample-rules/) loaded from LocalStack S3.

### Call 2: Execute a discount rule

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq
```

Expected:
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

The rule fired: 10% off because the order is ≥ $50.

### Call 3: Execute the VIP discount (and discover stacking)

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.vip","data":{"customerType":"VIP","amount":100}}' | jq '.result'
```

Expected: `amount: 72.0` (NOT 80.0).

Why? Both `pricing.discount.vip` AND `pricing.discount.simple` matched and fired. The discounts compounded multiplicatively: `100 × 0.80 × 0.90 = 72`. The sample rules **stack** because none use `salience` to control firing order.

This is documented thoroughly in [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md). It's the most surprising thing about the sample rule set.

---

## Step 5: What to read next (audience-dependent)

You've now seen the service work. Where to go next depends on your role:

### "I'm a developer who'll work on the codebase"

→ [27-development-setup.md](27-development-setup.md) — local Java setup, IDE, conventions, build/test workflow.
→ [04-architecture.md](04-architecture.md) — full architecture: filter chain, threading, Drools 10 `updateToVersion` rule-loading pattern, security layers.
→ [28-testing-guide.md](28-testing-guide.md) — test suite map (45 files, 598 tests), how to add tests.

### "I'm an architect / I want to understand the design"

→ [04-architecture.md](04-architecture.md) — system design.
→ [14-security-architecture.md](14-security-architecture.md) — 8-layer security model.
→ [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md) — failure handling.
→ [36-architecture-decision-records.md](36-architecture-decision-records.md) — the load-bearing decisions and why.

### "I'm an operator / I'll run this in production"

→ [06-deployment.md](06-deployment.md) — Docker, AWS ECS reference architecture.
→ [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) — operational procedures + monitoring.
→ [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) — memory diagnostics.
→ [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) — tune for your workload.

### "I'm an integrator / I'll call this from my application"

→ [10-api-reference.md](10-api-reference.md) — every endpoint, every request/response shape.
→ [11-integration-guide.md](11-integration-guide.md) — code examples in curl, Python, Java, Node.js.
→ [12-error-code-catalog.md](12-error-code-catalog.md) — every error code with HTTP status.
→ [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) — multi-tier client identification.

### "I'm a rule author / I want to write business rules"

→ [33-simple-start.md](33-simple-start.md) — write and deploy your first custom rule.
→ [16-drl-sandboxing.md](16-drl-sandboxing.md) — what the sandbox blocks (read this first).
→ [17-rule-development.md](17-rule-development.md) — patterns for production rules.
→ [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) — 17 working examples.
→ [20-rule-generation-prompt.md](20-rule-generation-prompt.md) — AI-assisted rule authoring.

### "I'm an AI agent or I want a complete map"

→ [00-system-overview.md](00-system-overview.md) — high-level entry point.
→ [02-project-structure.md](02-project-structure.md) — clickable file index.
→ [09-environment-variables-reference.md](09-environment-variables-reference.md) — all env vars.
→ [37-glossary.md](37-glossary.md) — definitions of all jargon.

---

## When to reach for these other docs

- **Stuck**: [31-troubleshooting.md](31-troubleshooting.md), then [35-faq.md](35-faq.md).
- **Performance issue**: [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md).
- **Memory issue**: [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md).
- **External integration**: [11-integration-guide.md](11-integration-guide.md).
- **Production deploy**: [06-deployment.md](06-deployment.md), [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md).

---

## Stop the stack when done

```bash
docker compose down       # stops, keeps S3+Redis data
# or
docker compose down -v    # stops AND wipes data (clean slate)
```

---

## Common first-run issues

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop not running | Start Docker Desktop |
| `pull access denied` | Docker Hub rate limit (anonymous user) | `docker login` or wait |
| `port is already allocated` | Something using 8080/8081/4566/6379 | `lsof -nP -iTCP:8080`; kill or change port mapping |
| `docker-compose` not found | You have v1; project uses v2 | Use `docker compose` (with space, not hyphen) |
| Build hangs at "Downloading from central" | Slow Maven mirror | Wait — first build downloads ~250 MB |
| Services start but `/admin/health` returns 404 | App still booting | Wait 30s and retry |
| `RULE_NOT_FOUND` for sample rules | LocalStack init didn't complete | `docker compose restart localstack`; wait 10s; `docker compose restart app` |
| Healthcheck never goes green | Drools compilation failed | `docker compose logs app | grep ERROR` |

---

## You're set up

Total time elapsed (assuming first build): ~10 minutes.

Time to first successful call: ~30 seconds after the stack is healthy.

If something didn't work as documented, that's a bug — please file an issue.
