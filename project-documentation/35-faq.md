# 35 · FAQ

| | |
|---|---|
| **Audience** | Everyone — the doc you reach for when you have one specific question |
| **Purpose** | Quick answers to the questions most people actually ask, with links to full coverage |
| **Last verified** | 2026-05-10 against running stack |

---

## Categories

- [Quick start](#quick-start)
- [Authoring rules](#authoring-rules)
- [Calling the API](#calling-the-api)
- [Configuration](#configuration)
- [Operations](#operations)
- [Architecture & design](#architecture--design)
- [Errors](#errors)
- [Performance & scale](#performance--scale)
- [Security](#security)
- [Comparing & extending](#comparing--extending)

---

## Quick start

### How do I run this thing?

```bash
docker compose up -d --build
# wait ~2-5 minutes for first build
curl http://localhost:8080/admin/health | jq '.status'
# → "UP"
```

Full guide: [32-getting-started.md](32-getting-started.md).

### How do I execute a sample rule?

```bash
curl -sX POST http://localhost:8080/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' | jq
```

All 17 sample rules with examples: [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md).

### What's the JSON field name — `ruleId` or `rule_id`?

**`rule_id`** (snake_case) on the wire. The Java field is `ruleId` (camelCase) but mapped via `@JsonProperty("rule_id")`. See [ADR-008](36-architecture-decision-records.md#adr-008-snake_case-json-via-jsonproperty).

### Why is my VIP $100 order returning $72 instead of $80?

Both `pricing.discount.vip` (20% off) AND `pricing.discount.simple` (10% off) match. They stack multiplicatively: `100 × 0.80 × 0.90 = 72`. Sample rules don't use `salience` to control firing order — see [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) for the full explanation and three patterns to make rules non-stacking.

### Can I see all loaded rules?

```bash
curl -fsS http://localhost:8080/admin/rules | jq '.rules[].rule_id'
```

---

## Authoring rules

### How do I add a new rule?

1. Create a `.drl` file at the right path: rule ID `pricing.discount.gold` → `pricing/discount/gold.drl`. See [18-rule-id-and-storage-layout.md](18-rule-id-and-storage-layout.md).
2. Verify it passes the sandbox: must use only allowed imports, no `eval()`, no blocked classes/methods. See [16-drl-sandboxing.md](16-drl-sandboxing.md).
3. Upload to S3 (or place in your filesystem rules directory).
4. Refresh: `curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/refresh-rules`.

### Why is my rule being rejected?

Most likely the DrlSanitizer blocked it. Check the response from `/admin/refresh-rules` — failed rules appear in `errors[]`. Common rejections:
- `Blocked import: 'java.io.File'` — file system access denied.
- `Blocked class reference: 'Runtime'` — process-execution denied.
- `eval() is not allowed in DRL rules` — use pattern matching instead.

Full sandbox reference: [16-drl-sandboxing.md](16-drl-sandboxing.md).

### Can I use `eval()` in my DRL?

**No.** Banned by the sandbox. Use Drools pattern matching instead:

```drools
// REJECTED:
eval($data.get("amount") != null)

// USE THIS:
$data : Map(this["amount"] != null)
```

See [16-drl-sandboxing.md](16-drl-sandboxing.md) and [ADR-009](36-architecture-decision-records.md#adr-009-eval-banned-in-drl).

### What imports are allowed in DRL?

20 prefixes: `java.util.*`, `java.math.*`, `java.time.*`, `java.lang.{Math,String,Number,Integer,Long,Double,Float,Boolean,Byte,Short,Character,Comparable,Object,Enum}`, `java.text.{DecimalFormat,NumberFormat,SimpleDateFormat}`. Anything else is rejected (default-deny).

### Can I use rule units / OOPath / DataStream?

**No.** This project uses traditional DRL syntax only. See [ADR-001](36-architecture-decision-records.md#adr-001-traditional-drl-syntax-only-not-rule-units--oopath).

The upstream Drools 8 reference ([23-rule-language-reference.md](23-rule-language-reference.md)) documents these features for completeness; sections describing them are tagged `[Not used in this project]`.

### How do I make rules NOT stack?

Three options:

```drools
// Option A: salience + activation-group (only one in group fires)
rule "VIP discount"
  salience 100
  activation-group "discount"
when ...

// Option B: exclude from competing rule's pattern
rule "Simple discount (excluding VIP)"
when
  $data : Map(this["amount"] != null, this["customerType"] != "VIP")
  eval(((Number) $data.get("amount")).doubleValue() >= 50.0)

// Option C: flag-based gating
rule "VIP discount"
  salience 100
when ...
then
  // ... apply VIP discount
  $data.put("discountApplied", true);
end

rule "Simple discount"
  salience 50
when
  $data : Map(this["amount"] != null, this["discountApplied"] == null)
```

See [19-sample-rules-cookbook.md](19-sample-rules-cookbook.md) for full patterns.

### What's the maximum rule complexity?

- Each execution is capped at **10,000 rule firings** (`maxRuleFirings`). Defends against runaway loops.
- Each execution has a **30-second timeout** (`RULE_EXECUTION_TIMEOUT_SECONDS`).
- Beyond that — there's no enforced complexity limit. Test for performance.

### Can I have rules that depend on each other?

Yes — Drools' RETE network handles inter-rule firing automatically. Set `salience` to control order. Set `no-loop true` if a rule modifies its own match condition.

### How do I version my rules?

Currently: rely on S3 versioning. Each `.drl` upload to a versioned bucket creates a new version; you can roll back via `aws s3api copy-object --copy-source <prev-version>`.

Native rule-version support is **roadmap, not implemented**. The `version` field in `/admin/rules` always returns `"1.0"`.

---

## Calling the API

### How do I authenticate?

- **`/execute-rule`**: no auth on the service itself. Authentication is your gateway's responsibility. The service uses rate limiting per multi-tier client identity for resource protection.
- **`/admin/*`**: send `X-Admin-API-Key: <value>` header when `ADMIN_API_KEY` env var is set on the service. Otherwise admin endpoints are open (with a startup warning). See [15-admin-authentication.md](15-admin-authentication.md).

### How does rate limiting identify my client?

Multi-tier priority:
1. `X-API-Key` header → `api-key:{value}`
2. `Authorization: Bearer {token}` → `bearer:{hash}`
3. `X-Client-Id` header → `client-id:{value}`
4. `request.getRemoteAddr()` → `ip:{addr}` (fallback)

`X-Forwarded-For` is **explicitly ignored** (spoofable). See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md).

### My requests are rate-limited but my IP isn't busy. Why?

If you're behind a load balancer, **everyone behind the LB shares the IP-fallback bucket**. Inject `X-API-Key` or `X-Client-Id` at the LB to differentiate clients.

### How big can my request body be?

Default 1 MiB (`DROOLS_VALIDATION_REQUEST_MAX_SIZE_BYTES=1048576`). Tomcat's separate cap is 10 MB but the validation filter's lower limit wins. Increase via env var.

### Can I batch multiple rules in one request?

No — there's no batch endpoint. Make multiple `POST /execute-rule` calls. See [11-integration-guide.md](11-integration-guide.md) for batching patterns.

### How do I retry on errors?

Retry only `429` (rate limit) and `503` (circuit breaker). Don't retry `400` (validation), `404` (not found), `408` (timeout), or `413` (payload too large). For 429, honor `X-RateLimit-Reset-After`.

Full error code reference: [12-error-code-catalog.md](12-error-code-catalog.md).

### Are responses cached / idempotent?

`POST /execute-rule` is **idempotent** — same input always produces the same output (rules don't have side effects on the service). Safe to retry.

`POST /admin/refresh-rules` is **not** idempotent — each call may pick up new S3 contents.

### How do I provide a correlation ID?

```bash
curl -H 'X-Correlation-ID: my-trace-12345' ...
```

The service validates against `^[a-zA-Z0-9\-]{1,128}$`, propagates through MDC, and includes in all log lines for that request.

---

## Configuration

### What are the most important env vars?

| Var | What |
|---|---|
| `RULE_SOURCE` | Storage backend: `local` / `file` / `s3`. Default `local`. |
| `RULE_BUCKET_NAME` | S3 bucket (when `RULE_SOURCE=s3`) |
| `ADMIN_API_KEY` | Set this in production (see [15-admin-authentication.md](15-admin-authentication.md)) |
| `SPRING_PROFILES_ACTIVE` | Profile: `local` / `dev` / `prod` / `docker` |
| `DROOLS_CORS_ALLOWED_ORIGINS` | Production CORS origins (don't leave empty in prod) |

Full catalog: [09-environment-variables-reference.md](09-environment-variables-reference.md).

### Why is `RULE_SOURCE` defaulting to `local` instead of `s3`?

Yes, it's `local` by default ([application.yml:59](../src/main/resources/application.yml#L59)). For S3, set `RULE_SOURCE=s3`. The compose stack does this automatically.

### What's the difference between the dev, prod, and docker profiles?

- `local`: in-memory rules, no Redis, debug logging.
- `dev`: S3 via LocalStack, Redis, looser circuit breakers, smaller thread pools.
- `prod`: real AWS S3, Redis, **stricter** circuit breakers, larger thread pools, CloudWatch metrics, auto-refresh enabled, root logging at WARN.
- `docker`: same as dev but with hostnames pointing to compose services.

Full diff matrix: [05-environments-and-profiles.md](05-environments-and-profiles.md).

### How do I set CORS for production?

```bash
DROOLS_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
```

Wildcard `*` is OK in dev but fails the production deploy checklist.

### How do I rotate the admin API key?

Blue/green: deploy new instances with the new key, drain old. The service doesn't support multiple valid keys simultaneously. See [15-admin-authentication.md](15-admin-authentication.md).

---

## Operations

### How do I scale horizontally?

Each replica is independent. Add more replicas behind a load balancer.

```bash
# ECS
aws ecs update-service --cluster prod --service drools --desired-count 5

# k8s
kubectl scale deployment drools --replicas=5
```

Caveats: per-replica caches and rate-limit buckets. See [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md).

### How do I monitor production?

Metrics via Micrometer → CloudWatch (when `CLOUDWATCH_METRICS_ENABLED=true`). Logs via stdout (capture with your log aggregation). Use `/admin/health` for component-level status, `/actuator/health` for simple liveness.

Recommended dashboards + alerts: [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md#recommended-dashboards).

### What if Redis goes down?

**No user-visible impact.** The Redis circuit breaker opens, `RedisCachedRuleStorage` falls through to base storage (S3 / file / memory) on every call, and `RuleRefreshPublisher` no-ops. Cross-instance refresh fan-out also stops — each instance refreshes independently until Redis recovers. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

### What if S3 goes down?

503 `SERVICE_UNAVAILABLE` from new rule lookups after the S3 breaker trips. Already-loaded rules continue executing (compiled in `kieContainer`, no S3 touch per request). The breaker auto-recovers after `wait-duration` (60s default, 120s in prod) when S3 comes back.

### How often should I refresh rules in production?

`AUTO_REFRESH_ENABLED=true` is on by default in `prod` profile, with 5-minute interval. So 12 refreshes/hour. Adjust via `AUTO_REFRESH_INTERVAL_MINUTES`.

Manual refresh after a known rule change: `POST /admin/refresh-rules`.

### Can I refresh just one rule?

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules/pricing.discount.simple
```

Faster than full refresh.

### Where do heap dumps and GC logs go?

In docker-compose: bind-mounted to `./heap-dumps/` and `./gc-logs/` on the host. In production: mount to a container volume that survives crashes.

Configured by `JAVA_OPTS` in compose: `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap-dumps/heapdump.hprof`.

### How do I check memory usage?

```bash
curl http://localhost:8080/admin/memory/info -H "X-Admin-API-Key: $ADMIN_API_KEY" | jq '.heap'
```

See [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md).

---

## Architecture & design

### How does multi-instance refresh stay consistent?

`RuleRefreshPublisher` emits to `drools:rule:events` whenever `/admin/refresh-rules` succeeds; other instances' `RuleRefreshSubscriber` reload from storage and swap `kieContainer`. Self-dedup via `source_instance_id == this.instanceId` prevents loop-back. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20). Without `REDIS_PUBSUB_ENABLED=true`, each instance must be refreshed manually or wait for TTL expiry + next refresh.

### Why was the old `LocalLRUCache` / `RedisRuleCache` layer removed?

It was dead code — `RuleCache.get()` was never called from execution or refresh paths. Execution reads from `DroolsEngineService.kieContainer`; refresh fetches directly from storage. Replaced 2026-05-20 by `RedisCachedRuleStorage` (a real read-through decorator on `RuleStorage`) plus pub/sub. See [ADR-016](36-architecture-decision-records.md#adr-016-redis-decorator--pubsub-for-multi-instance-drl-cache-2026-05-20); [ADR-004](36-architecture-decision-records.md#adr-004-locallrucache-uses-write-lock-on-get) and [ADR-005](36-architecture-decision-records.md#adr-005-redis-bean-exists-but-is-dormant-by-default) are marked Superseded.

### Why no Spring Security?

93 lines of `AdminAuthFilter` is enough for one API key. Spring Security would be 50,000 lines for the same outcome. See [ADR-006](36-architecture-decision-records.md#adr-006-adminauthfilter-instead-of-spring-security).

### Why no Terraform?

Customer environments vary too much. The repo documents a reference architecture (ALB → ECS → S3 + ElastiCache) but ships no IaC. See [ADR-007](36-architecture-decision-records.md#adr-007-no-terraform-aws-deployment-documented-as-reference-only).

### Why are there two ports?

- **Port 8080**: main API (`/execute-rule`) AND custom admin endpoints (`/admin/*`).
- **Port 8081**: Spring Boot Actuator (`/actuator/*`) only.

The 8081 port is for management — typically firewalled to internal networks in production.

### What's the filter chain order?

1. `SecurityHeadersFilter` `@Order(-1)` — adds 7 response headers
2. `AdminAuthFilter` `@Order(0)` — admin API key check
3. `RateLimitingFilter` `@Order(1)` — rate limit check
4. `RequestSizeValidationFilter` (no @Order) — body size check, runs last

See [04-architecture.md](04-architecture.md).

### How does rule refresh work without blocking readers?

The full rule set is compiled into a new versioned `KieModule` **outside** the write lock. Then a brief write lock acquires, calls `KieContainer.updateToVersion(newReleaseId)` (Drools 10's in-place version swap), and releases. The old `KieModule` is then explicitly removed from the `KieRepository` (Drools 10 does **not** auto-clean — verified by load test 2026-05-10). Readers are never blocked during the (potentially slow) compile. See [ADR-003 2026-05-10 update](36-architecture-decision-records.md#adr-003-kiecontainer-atomic-swap-with-disposal), [04-architecture.md](04-architecture.md), and [39-load-test-findings.md](39-load-test-findings.md).

---

## Errors

### What does `RULE_NOT_FOUND` mean?

The rule ID doesn't exist in the loaded set. Possible causes:
- Rule isn't uploaded to S3 yet → upload it.
- Service hasn't refreshed since you uploaded → call `POST /admin/refresh-rules`.
- Rule ID typo (case-sensitive) → check `/admin/rules` for the exact name.
- Trailing whitespace in your `rule_id` (validator silently trims, storage lookup fails — see CODE_FINDINGS F-032).

### What does `INVALID_INPUT` mean?

Validation failed. The `details` field tells you exactly what. Common: rule_id format violation, data field count over 100, string value over 10K chars, number magnitude over 1B.

### What does `SERVICE_UNAVAILABLE` mean?

A circuit breaker is OPEN. Check `/admin/health` for `s3_state` / `redis_state`. Auto-recovers after wait duration. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

### What does `TIMEOUT_ERROR` mean?

Rule execution exceeded `RULE_EXECUTION_TIMEOUT_SECONDS` (30s default). Likely an infinite loop in the rule. Check `no-loop true`, simplify the rule, or extend the timeout.

### Why am I getting 401 on admin endpoints?

`ADMIN_API_KEY` is set on the service, but you're not sending the `X-Admin-API-Key` header. Add it. See [15-admin-authentication.md](15-admin-authentication.md).

Full error code catalog: [12-error-code-catalog.md](12-error-code-catalog.md).

---

## Performance & scale

### What's the expected RPS?

100-1000 RPS per replica with default config. Sample workload tests show 45+ RPS sustained, much higher for CPU-bound rules. Scale horizontally for more.

### What's the expected latency?

P99 < 100ms for cached rules. P99 < 500ms for cache miss (cold S3 read). Sample workload typically 1-40ms.

### How do I profile a slow rule?

1. Identify the rule from logs (look for `Operation 'rule-execution' timed out`).
2. Check `/admin/rules` for that rule's `avg_execution_time_ms`.
3. Inspect rule logic — heavy iteration? missing `no-loop`? expensive `then` block?
4. Use [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) Branch E for the diagnostic flow.

### How do I tune for higher throughput?

[26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) has a 10-branch decision tree. Common levers:
- Increase `DROOLS_THREAD_POOL_MAX_SIZE`
- Enable `REDIS_ENABLED=true` and bump `REDIS_DRL_RULES_TTL_MINUTES` so refresh/warm-start hits the shared cache instead of S3 (no effect on execution latency — that path doesn't touch Redis)
- Increase `AWS_S3_MAX_CONNECTIONS`
- Add more replicas (with `REDIS_PUBSUB_ENABLED=true` so refresh fan-out stays coherent)

### What's the memory footprint?

Default heap: 512m-2048m. Production sizing: depends on rule count and complexity. ~10-100 MB per `KieContainer`; sample workload uses ~250-500 MB total.

### What's the cache hit rate?

The legacy in-process LRU was removed on 2026-05-20 (ADR-016). What's left:

- **`/execute-rule` path**: reads compiled rules from `kieContainer` in-memory; never consults any cache. "Hit rate" doesn't apply.
- **Refresh / startup path** (when `REDIS_ENABLED=true`): goes through `RedisCachedRuleStorage`. Hit rate depends on whether sibling instances have populated Redis since the last refresh. Tracked via `drools.cache.hit{layer=redis}` / `drools.cache.miss{layer=redis}` Micrometer counters (visible at `/admin/health` → `components.cache.details.statistics` and `/actuator/metrics`).
- **Multi-instance deployment**: after one task refreshes and publishes on `drools:rule:events`, sibling tasks process the event and read from a now-warm Redis. Their bulk hit counters incremented during Phase 9.2 test runs.

Single-instance deployments with `REDIS_ENABLED=false` have no cache at all — base storage is consulted on every refresh. See [39-load-test-findings.md](39-load-test-findings.md) Phase 9 addendum for measured numbers.

---

## Security

### Is the service safe to expose externally?

**Not directly.** Always front it with:
- TLS termination at a load balancer
- Authentication at an API gateway (the service has no end-user auth)
- Rate limiting at the gateway level (in addition to the service's per-instance limiter)
- Restrict 8081 (Actuator) to internal-only

The service's defense-in-depth (DRL sandbox, rate limiter, security headers) is supplementary, not primary.

### Are rules sandboxed?

Yes. `DrlSanitizer` rejects DRL with dangerous imports/classes/methods/`eval()` BEFORE compilation. See [16-drl-sandboxing.md](16-drl-sandboxing.md).

### What sensitive data does the service log?

Nothing, intentionally. `LogSanitizer` masks credit cards, SSNs, tokens, API keys, and any field with a `password`/`secret`/`auth` key name (with word boundaries to avoid false positives). Recursive nested-map sanitization to depth 5.

### Is there any default authentication?

No. By default `ADMIN_API_KEY` is empty → admin endpoints are open with a startup WARN log. **Production must set the env var.** See [15-admin-authentication.md](15-admin-authentication.md).

### How do I report a security issue?

(Project-specific — adapt to your team's process.) Don't file a GitHub issue. Email security@your-org.example or use a private security disclosure channel.

---

## Comparing & extending

### How does this compare to other rule engines?

| Engine | Style | This project's choice |
|---|---|---|
| Drools (this) | Production-tested, RETE, JVM | ✅ |
| OpenL Tablets | Excel-based decision tables | Not chosen — different style |
| Easy Rules | Simple Java POJO rules | Too lightweight for our scale |
| RuleBook | Modern Java DSL | Less mature; smaller community |
| AWS Step Functions | Workflow, not rules | Different paradigm |

### Can I extend the storage layer?

Yes. Implement `RuleStorage`, register a bean, add a case in `StorageFactory`. See [ADR extension points](36-architecture-decision-records.md#extension-points).

### Can I add a custom HTTP filter?

Yes. `@Component` with `@Order` slot. Coordinate with existing filters (-1, 0, 1, none). See [ADR extension points](36-architecture-decision-records.md#extension-points).

### Can I swap the rate limiter?

Yes — implement a Redis-backed `InMemoryRateLimitingService` replacement. Reverses [ADR-010](36-architecture-decision-records.md#adr-010-rate-limiting-in-memory-not-redis-backed). See [ADR extension points](36-architecture-decision-records.md#extension-points).

### Can I add OAuth / JWT auth?

Yes — but it'd require swapping `AdminAuthFilter` for Spring Security or a custom JWT filter. The `AdminAuthFilter` is intentionally simple ([ADR-006](36-architecture-decision-records.md#adr-006-adminauthfilter-instead-of-spring-security)). Larger redesign needed for full OAuth.

---

## Things people ask but actually need to read a doc for

### "How does the whole thing work?"

→ [04-architecture.md](04-architecture.md) (1700 lines). No shortcut; read it.

### "How do I deploy to AWS?"

→ [06-deployment.md](06-deployment.md). Reference architecture, not turnkey IaC. Adapt to your environment.

### "What env vars are there?"

→ [09-environment-variables-reference.md](09-environment-variables-reference.md). All 66 catalogued by category.

### "How do I write a sandbox-passing rule?"

→ [16-drl-sandboxing.md](16-drl-sandboxing.md) (allowlist + blocklist + template).

### "What metrics should I monitor?"

→ [30-runbooks-and-monitoring.md](30-runbooks-and-monitoring.md) (recommended dashboards + alerts).

### "Why does X behave this way?"

→ [36-architecture-decision-records.md](36-architecture-decision-records.md). 12 ADRs for the load-bearing decisions.

---

## Got a question that's not here?

1. Search the corpus: every doc has a frontmatter table linking related docs.
2. Look at the test suite: tests are the most accurate behavior spec ([28-testing-guide.md](28-testing-guide.md)).
3. Read the source: [02-project-structure.md](02-project-structure.md) has clickable file index.
4. File an issue and ask for the FAQ to be expanded.
