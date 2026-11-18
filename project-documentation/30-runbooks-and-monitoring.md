# 30 · Runbooks and Monitoring

| | |
|---|---|
| **Audience** | Operators, on-call engineers, SREs |
| **Purpose** | Operational procedures (start/stop/refresh/scale/incident response) plus monitoring setup (metrics, dashboards, alerts) |
| **Last verified against** | Running stack on 2026-08-20; refresh, readiness, limits and audit sections checked against the code and the docker test plan (Steps 12.5b–13) on 2026-11-18 |
| **Related docs** | [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md), [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md), [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md), [31-troubleshooting.md](31-troubleshooting.md) |

---

## Part 1: Runbooks

### Start the service

#### Local (Docker compose)

```bash
docker compose up -d
docker compose ps   # all 3 services should be (healthy) within ~60s
# docker-compose sets ADMIN_API_KEY=admin-secret, so /admin/* needs the header
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: admin-secret" | jq '.status'   # → "UP"
```

#### Local (Maven, dev profile)

```bash
docker compose up -d localstack redis
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

#### Production (assumed: ECS Fargate or similar)

The container is started by the orchestrator. It runs `java $JAVA_OPTS -jar app.jar` (entrypoint). Spring profile and env vars come from the task definition / Helm values / etc.

Verify externally:
```bash
curl -fsS https://$DROOLS_HOST/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" | jq '.status'
# → "UP"
```

### Stop the service

#### Local

```bash
docker compose down       # stops, keeps volumes (sample-rule data persists)
docker compose down -v    # stops AND wipes volumes (LocalStack data lost)
```

#### Production

```bash
# graceful via orchestrator
aws ecs update-service --cluster prod --service drools --desired-count 0
# kubernetes:
kubectl scale deployment drools --replicas=0
```

The service supports graceful shutdown (`server.shutdown: graceful`). On SIGTERM:
- New requests are refused (return 503)
- In-flight requests finish (up to grace period)
- Spring context shuts down cleanly

### Check health

```bash
# Quick — just status (all /admin/* requires the admin key when ADMIN_API_KEY is set)
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" | jq '.status'   # → "UP" / "DOWN"

# Full — component breakdown
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" | jq

# What components are checked: drools, storage, cache, redis (if enabled), circuit-breakers
# Overall status: UP; DEGRADED (rules serve, storage unreachable; HTTP 200);
# DOWN (can't serve rules: startup load not succeeded or 0 rules; HTTP 503).
# Cache/redis/breaker DOWN does not change the overall status.

# Orchestrator probes (actuator port 8081, no admin key)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/actuator/health/readiness   # 200 ready / 503 not
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/actuator/health/liveness    # 200 while the JVM is alive
```

Use **readiness** for load-balancer / Kubernetes readiness, and **liveness** for restarts. Readiness includes the `rules` indicator, so an instance with no rules is taken out of rotation. Liveness ignores rule state, so such an instance is not restarted in a loop.

### Refresh rules

#### All rules

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules | jq

# Expected:
# {
#   "status": "completed",
#   "rules_loaded": 17,       # serving from this snapshot (recompiled + unchanged)
#   "rules_failed": 0,
#   "rules_unchanged": 17,    # identical DRL, not recompiled
#   "rules_removed": 0,       # gone from storage, removed
#   "duration_ms": 41,
#   "errors": []
# }
# 500 "failed"         → storage unreadable; nothing changed
# 409 "rejected_empty" → storage returned 0 rules; nothing changed
```

The read is all-or-nothing: a failed or incomplete storage read changes nothing. Each rule ID is compiled into its own KieBase and its handle is swapped atomically ([ADR-025](36-architecture-decision-records.md#adr-025-per-rule-id-compiled-kiebase-isolation-2026-11-18)); execution takes no lock. In-flight `/execute-rule` requests finish on the version they took. New requests for a rule ID use its new version as soon as that ID is swapped. A rule ID that fails to compile keeps its previous version. See [04-architecture.md](04-architecture.md).

#### Single rule

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules/pricing.discount.simple | jq
```

Faster than full refresh when only one rule changed: only that rule ID is recompiled; every other rule ID is untouched.

#### After uploading a new rule to S3

Call refresh after any S3 change; it invalidates the Redis cache, re-reads S3 and publishes to sibling instances. With `AUTO_REFRESH_ENABLED=true` (default on `prod`) the scheduled reconcile also picks the change up within `AUTO_REFRESH_INTERVAL_MINUTES` (default 5), reading S3 directly.

### Scale horizontally

The service is **stateless** — every replica behind a load balancer works identically.

#### ECS

```bash
aws ecs update-service --cluster prod --service drools --desired-count 5
```

#### Kubernetes

```bash
kubectl scale deployment drools --replicas=5
```

#### Considerations

- Each replica has its own compiled per-rule-ID KieBases (the execution hot path) and per-instance rate-limit buckets. Horizontal scaling **shares the Redis cache** (when `REDIS_ENABLED=true`) via `RedisCachedRuleStorage`, but rate limits multiply across replicas.
- Cross-instance refresh coherence is provided by `RuleRefreshPublisher`/`Subscriber` on channel `drools:rule:events` (requires `REDIS_PUBSUB_ENABLED=true`). When pub/sub is disabled, replicas must each be refreshed individually, or rely on the scheduled reconcile (`AUTO_REFRESH_ENABLED=true`). Reconcile also repairs events a replica missed while pub/sub was on.
- If you need cluster-wide rate limiting, you'd need to back the limiter with Redis — currently not implemented (see [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md)).
- Cold-start time is ~30-45s per replica. Plan capacity accordingly.

### Diagnose 503 errors

```bash
# Step 1: Check breaker state
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  | jq '.components."circuit-breakers".details | {s3_state, redis_state}'
```

If `s3_state: "OPEN"` → S3 dependency is failing. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

An open S3 breaker does **not** cause 503s on `/execute-rule`: requests run on the compiled rules, and only refreshes fail (500).

If `/admin/health` itself returns 503, its `status` is `DOWN`: the instance has no rules to serve. See [Instance not ready](#instance-not-ready).

If both states are `CLOSED` and you still see 503s, look elsewhere:
- **Rule-execution pool saturation** — the rule-execution thread pool uses an `AbortPolicy`, so once the pool and its queue are full the executor rejects new work and the request is mapped to `ServiceUnavailableException` → HTTP 503 (a distinct 503 source from the circuit breakers and from graceful-shutdown refusal). Check `/admin/thread-pools` for active/queue/rejected counts and raise `DROOLS_THREAD_POOL_MAX_SIZE` (and/or the queue capacity) or reduce inbound load.
- Memory exhaustion (check `/admin/memory/info`)
- Thread pool saturated (check `/admin/thread-pools`)
- Specific endpoint failing (check Spring Actuator metrics)

### Diagnose 429 errors

```bash
# Look for the rate-limit warnings
docker compose logs app | grep 'Rate limit exceeded' | tail -20
```

Each warning includes the client identifier. If they're all `ip:1.2.3.4` (same IP), it's one client hammering. If they're `ip:*` (varied) or `client-id:*`, it's distributed.

See [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md) for tuning.

### Instance not ready

Symptom: `/actuator/health/readiness` returns 503 and `/admin/health` returns 503 with `status: DOWN`.

```bash
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  | jq '.components.drools.details | {phase, loaded_rules, reason, last_refresh_result, last_error}'
```

- `phase: NOT_LOADED` → the startup load is still running. Wait (a cold load of many rules takes a while).
- `phase: FAILED` → the startup load couldn't read storage (bucket, credentials, network, `RULE_SOURCE`, or an empty bucket). The instance stays up but not ready. Fix storage, then `POST /admin/refresh-rules` (or wait for the reconcile, if `AUTO_REFRESH_ENABLED=true`). Set `DROOLS_STARTUP_FAIL_ON_LOAD_ERROR=true` if you'd rather the process exit.
- `phase: LOADED` but `loaded_rules: 0` → every rule failed to compile, or storage really is empty. Check `error_rule_ids` and the refresh response `errors`.
- One bad rule file does **not** make an instance DOWN. Its ID is listed in `error_rule_ids`, and every other rule keeps serving.

### Refresh returned 409 `rejected_empty`

Storage returned a complete but **empty** set while rules are loaded. Nothing changed; every rule keeps serving, and no event was published.

1. Check the source: `RULE_SOURCE`, `RULE_BUCKET_NAME` / `LOCAL_RULES_DIRECTORY`, and the bucket contents (`aws s3 ls s3://$RULE_BUCKET_NAME --recursive | grep -c .drl`).
2. If the bucket really should be empty, set `DROOLS_REFRESH_ALLOW_EMPTY=true`, restart, and refresh again.

### Refresh returned 500 `failed`

Storage couldn't be read completely (an S3 listing or object error, an open S3 breaker, a missing rules directory). Nothing changed. The reason is in `errors[0].error` and in the ERROR log line `admin refresh: Storage read failed; nothing changed: …`. Fix the dependency and refresh again.

### Clients get 422 `RULE_LIMIT_EXCEEDED`

One request made the rules fire more than `DROOLS_MAX_RULE_FIRINGS` (10,000 default) times. This is almost always a rule loop: a consequence that calls `update` / `modify` on the fact it matched, without `no-loop`.

```bash
docker compose logs app | grep 'exceeded the firing limit' | tail -5   # names the rule_id
```

Fix the DRL (add `no-loop true` or tighten the condition), upload it, and refresh that rule ID. Raise the cap only if the rule really needs more firings. Count: `drools.api.errors{endpoint=/execute-rule,error_type=limit_exceeded}`.

### `drools.rule.execution.stuck` > 0

A rule timed out (408) and its worker thread ignored both `halt()` and the interrupt: usually a consequence that never yields (`while (true) {}`). The thread stays busy until the process restarts, so each one permanently shrinks the rule-execution pool.

1. Find the rule: `docker compose logs app | grep 'worker did not stop'` → `Rule <rule_id> execution timed out after <n>s; worker did not stop (<k> stuck)`.
2. Fix or remove that DRL, and refresh it so new requests stop hitting it.
3. Restart the instance (rolling) to get the threads back. If a worker ends on its own, the log says `Worker for timed-out rule <rule_id> has finally finished` and the gauge drops.

### Instances disagree / a pub/sub event was missed

Pub/sub is fire-and-forget. An instance that was disconnected, restarting, or had a full listener queue can miss an event.

- With `AUTO_REFRESH_ENABLED=true` each instance reconciles every interval: it reads storage directly (bypassing Redis) and applies only the differences. A repaired drift logs WARN `Reconcile found drift: <n> rule(s) updated, <m> removed (a refresh event was probably missed)` and increments `drools.reconcile.changed`.
- A non-zero `drools.reconcile.changed` means events are being missed. Check Redis connectivity and `drools.refresh.failed{layer=listener,reason=queue_full}`.
- To converge now, call `POST /admin/refresh-rules` on any instance. It always publishes a bulk event, and siblings skip unchanged rules by digest.
- Compare instances with the `AUDIT` lines (digests per rule ID) or `GET /admin/rules`.

### Audit log (who changed which rules)

Every rule-changing action writes one line on the `AUDIT` logger, at INFO in every profile (including `prod`, where root is WARN). It never contains DRL text, request payloads or keys.

```bash
docker compose logs app | grep 'AUDIT action=' | tail -20
```

```
AUDIT action=refresh_all source=admin actor=172.18.0.1 result=ok loaded=1 unchanged=16 failed=0 removed=0 loaded_ids=[pricing.discount.simple] failed_ids=[] removed_ids=[]
AUDIT action=refresh_rule source=admin actor=172.18.0.1 rule_id=pricing.discount.vip result=installed old_digest=3f2a9c0d1e4b new_digest=7c81d2e0aa19
AUDIT action=refresh_rule source=admin actor=172.18.0.1 rule_id=broken.rule result=failed old_digest=null new_digest=null error=compile_failed
AUDIT action=remove_rule source=pubsub actor=- rule_id=pricing.discount.vip result=removed old_digest=7c81d2e0aa19
AUDIT action=refresh_all source=admin actor=172.18.0.1 result=storage_failed error="Storage read failed; nothing changed: ..."
```

- `source`: `startup`, `admin`, `pubsub` or `reconcile` (`action=reconcile`; quiet when nothing changed).
- `actor`: the admin caller's remote address (the shared admin key carries no user identity); `-` otherwise.
- Digests are the first 12 hex chars of the DRL's SHA-256. ID lists are capped at 50 (`…+N`).
- Single-refresh failures record `error=compile_failed` or `error=storage_read_failed`, never the compiler message (it can quote DRL). The message is in the normal application log.

### Memory diagnostics

```bash
# Snapshot
curl -fsS http://localhost:8080/admin/memory/snapshot -H "X-Admin-API-Key: $ADMIN_API_KEY" | jq

# Full info with warnings
curl -fsS http://localhost:8080/admin/memory/info -H "X-Admin-API-Key: $ADMIN_API_KEY" | jq

# Force GC (diagnostic only — don't run in production under load)
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" http://localhost:8080/admin/memory/gc | jq
```

See [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md) for full diagnostic flow.

### Roll out a config change

Configuration is via env vars. To change one:

1. Update the orchestrator's task definition / deployment manifest with the new env var value.
2. Trigger a rolling deploy (orchestrator-specific).
3. New instances start with new config; old drain.

You **cannot** change env vars on a running container — Spring reads them at startup only. Always restart.

### Roll back a bad rule

If you uploaded a `.drl` that's failing rule execution:

```bash
# Find the offending rule from logs
docker compose logs app | grep 'Rule execution failed' | tail -10

# Option A: revert the file in S3 (S3 versioning)
aws s3api list-object-versions --bucket prod-drools-rules --prefix pricing/discount/vip.drl
aws s3api copy-object --copy-source 'prod-drools-rules/pricing/discount/vip.drl?versionId=<previous-version-id>' \
                      --bucket prod-drools-rules --key pricing/discount/vip.drl

# Option B: re-upload the previous content
aws s3 cp ./backup/vip.drl s3://prod-drools-rules/pricing/discount/vip.drl

# Then refresh
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://prod/admin/refresh-rules/pricing.discount.vip
```

Use S3 versioning + lifecycle policies in production for a real rollback story.

### Emergency: drop traffic via LB; restart in clean state

```bash
# Drain via LB (orchestrator-specific)
aws elbv2 deregister-targets --target-group-arn arn:... --targets Id=i-...

# Wait for in-flight to complete (default 60s)
sleep 90

# Restart instance
aws ecs stop-task --cluster prod --task <task-id>
# Orchestrator auto-replaces with fresh instance

# Verify health on the new instance before re-registering
```

---

## Part 2: Monitoring

### Metrics architecture

Spring Boot Actuator → Micrometer → backend (CloudWatch / Prometheus / etc.)

```
Application
   │
   ▼
Micrometer registry (in JVM)
   │
   ├──→ CloudWatch (when CLOUDWATCH_METRICS_ENABLED=true; default true in prod profile)
   ├──→ /actuator/prometheus (only if micrometer-registry-prometheus on classpath; currently NOT)
   └──→ /actuator/metrics/{name} (Spring Actuator JSON endpoint, port 8081)
```

### Key metrics

#### Drools-specific

| Metric | Type | Tags | What it means |
|---|---|---|---|
| `drools.api.requests` | Counter | `endpoint=/execute-rule` (etc.) | Per-endpoint request count |
| `drools.api.errors` | Counter | `endpoint`, `error_type` | Per-endpoint error count by type |
| `drools.api.response.time` | Timer | `endpoint`, `status` | Per-endpoint latency distribution |
| `drools.rule.execution.time` | Timer | `rule_id` (or `unknown` for misses), `status` | Per-rule latency. **Cardinality**: rules not loaded are tagged `unknown` to prevent metric explosion. |
| `drools.rule.execution.error` | Counter | `rule_id`, `error=rule_not_found\|rule_not_active\|execution_failed` | Per-rule error rate |
| `drools.rule.execution.stuck` | Gauge | — | **New 2026-11-18.** Workers still running after their request timed out (a rule body that never yields). Should be 0; see the runbook above |
| `drools.rules.loaded` | Gauge | `application`, `environment` | Rule IDs compiled and serving. Real since 2026-11-18 (it was always 0 before) |
| `drools.rule.compile.time` | Timer | `result=success\|error` | **New.** Per-rule-ID compile time |
| `drools.rule.compile.error` | Counter | `rule_id` | **New.** Compile failures per rule ID (the previous version, if any, keeps serving) |
| `drools.refresh.runs` | Counter | `source=startup\|admin\|pubsub\|reconcile`, `result` | **New.** Every refresh. Bulk `result`: `ok`, `partial`, `storage_failed`, `rejected_empty`; single: `installed`, `unchanged`, `failed`, `removed`, `not_found`, `storage_failed` |
| `drools.reconcile.changed` | Counter | — | **New.** Rule IDs a scheduled reconcile updated or removed, i.e. drift that pub/sub didn't deliver |
| `drools.cache.hit` | Counter | `layer=redis` | `RedisCachedRuleStorage` cache hit (Redis GET / MGET returned the rule) |
| `drools.cache.miss` | Counter | `layer=redis` | Decorator fell through to base storage (S3/file/memory) for this rule |
| `drools.refresh.published` | Counter | `event=RULE_REFRESHED\|RULE_REFRESHED_BULK\|RULE_DELETED` | `RuleRefreshPublisher` sent an event to `drools:rule:events` |
| `drools.refresh.received` | Counter | `event=…` (same values) | `RuleRefreshSubscriber` received an event from another instance |
| `drools.refresh.skipped_self` | Counter | — | Subscriber skipped a self-emitted event (loop-back prevention) |
| `drools.refresh.failed` | Counter | `layer=publisher\|subscriber\|listener`, `reason` | Refresh failure by stage. `publisher`: `circuit_breaker_open`, `exception`. `subscriber`: `deserialize`, `process_error` (the refresh failed), `compile` (per failed rule ID in a bulk event). `listener`: `queue_full` (event dropped; reconcile repairs it) |

`drools.api.errors{endpoint=/execute-rule}` uses `error_type` values including `timeout` (408), `unavailable` (503) and, since 2026-11-18, `limit_exceeded` (422).

#### JVM and system

| Metric | What |
|---|---|
| `jvm.memory.used` | Heap + non-heap usage |
| `jvm.memory.max` | Heap + non-heap max |
| `jvm.gc.pause` | GC pause distribution |
| `jvm.gc.live.data.size` | Old-gen live data after GC |
| `jvm.threads.live` | Total threads |
| `jvm.threads.daemon` | Daemon threads |
| `system.cpu.usage` | Whole-process CPU (0.0-1.0) |
| `process.cpu.usage` | JVM CPU |
| `system.load.average.1m` | Load average |

#### Resilience4j

| Metric | What |
|---|---|
| `resilience4j.circuitbreaker.state` | Per-breaker state gauge (`s3` / `redis`) |
| `resilience4j.circuitbreaker.calls` | Per-breaker, per-outcome counter (`successful` / `failed` / `not_permitted` / `ignored`) |
| `resilience4j.circuitbreaker.failure.rate` | Current rolling failure rate |
| `resilience4j.circuitbreaker.slow.call.rate` | Current rolling slow-call rate |
| `resilience4j.circuitbreaker.buffered.calls` | Calls in the sliding window |

#### HTTP

| Metric | What |
|---|---|
| `http.server.requests` | Per-URI, per-status latency. Standard Spring Actuator. |

### Querying metrics

#### Via Spring Actuator (port 8081)

```bash
# List available metrics
curl -fsS http://localhost:8081/actuator/metrics | jq '.names'

# A specific metric
curl -fsS http://localhost:8081/actuator/metrics/jvm.memory.used | jq

# With tag filter
curl -fsS 'http://localhost:8081/actuator/metrics/http.server.requests?tag=uri:/execute-rule&tag=status:200' | jq
```

#### Via CloudWatch (production)

In the `prod` profile, metrics are exported to CloudWatch in namespace `DroolsEngine`. Query:

```bash
aws cloudwatch get-metric-statistics \
  --namespace DroolsEngine \
  --metric-name drools.rule.execution.time \
  --start-time 2026-05-10T10:00:00Z --end-time 2026-05-10T11:00:00Z \
  --period 60 --statistics Average,Maximum
```

### Logs

#### Local

```bash
docker compose logs -f app
docker compose logs --tail 100 app | grep ERROR
```

#### Production (CloudWatch Logs)

The `prod` profile uses structured JSON logging via `logstash-logback-encoder`. Each log line is a JSON object with `timestamp`, `level`, `message`, `correlationId`, `requestId`, `requestUri`, `requestMethod`, etc.

Query CloudWatch Logs Insights:
```
fields @timestamp, level, message, correlationId, ruleId
| filter level = "ERROR"
| sort @timestamp desc
| limit 100
```

Or by correlation ID (when chasing a specific request):
```
fields @timestamp, message
| filter correlationId = "abc-123-def-456"
| sort @timestamp asc
```

### Recommended dashboards

#### Dashboard 1: Service health overview

- **Status**: `up{job="drools"}` (or platform equivalent) — single-stat green/red
- **Latency P50/P95/P99**: `http.server.requests` percentile distribution
- **Throughput**: `rate(http.server.requests[1m])`
- **Error rate**: `rate(http.server.requests{status=~"5.."}[1m]) / rate(http.server.requests[1m])`

#### Dashboard 2: Rule execution

- Top 10 slowest rules (by P95 of `drools.rule.execution.time`)
- Error rate per rule (`drools.rule.execution.error` / `drools.api.requests`)
- Cache hit rate over time (`drools.cache.hit / (drools.cache.hit + drools.cache.miss)`)
- Refresh fan-out: `drools.refresh.published` vs `drools.refresh.received` (per-instance ratio should approximate cluster size − 1 after dedup)

#### Dashboard 3: External dependencies

- Circuit breaker state (per-breaker timeline showing CLOSED/HALF_OPEN/OPEN)
- S3 call latency
- Redis call latency
- Failure rates

#### Dashboard 4: Resources

- Heap usage % (with thresholds at 70/80/90%)
- GC pause time (P99)
- Old-gen size after GC (trend — flat = healthy, rising = leak)
- Thread pool active / queued counts
- HTTP connection pool usage

### Recommended alerts

| Alert | Condition | Severity | Action |
|---|---|---:|---|
| Service down | `up == 0` for 2 min | P1 | Page |
| High error rate | 5xx > 1% for 5 min | P1 | Page |
| Latency degraded | P99 latency > 500ms for 10 min | P2 | Investigate |
| Memory critical | Heap usage > 90% for 2 min | P1 | Page; possible OOM imminent |
| Memory warning | Heap usage > 80% for 10 min | P2 | Investigate |
| GC pause anomaly | P99 GC pause > 500ms | P2 | Tune |
| Circuit breaker open | Any breaker state == OPEN > 30s | P1 (S3) / P3 (Redis) | Investigate dependency |
| Cache hit rate low | `drools.cache.hit{layer=redis}` hit rate < 70% for 30 min (when `REDIS_ENABLED=true`) | P3 | Check if refresh storms are evicting Redis keys; tune `REDIS_DRL_RULES_TTL_MINUTES` |
| Rule execution errors | Per-rule error rate > 5% for 10 min | P2 | Validate rule logic |
| Instance not ready | `/actuator/health/readiness` 503 for > 5 min | P1 if all instances, else P2 | [Instance not ready](#instance-not-ready) |
| Stuck workers | `drools.rule.execution.stuck` > 0 | P2 | [Stuck workers runbook](#droolsruleexecutionstuck--0); rolling restart |
| Rule loop | `drools.api.errors{error_type=limit_exceeded}` > 0 | P3 | Fix the looping rule |
| Refresh failing | `drools.refresh.runs{result=storage_failed}` or `{result=rejected_empty}` increasing | P2 | Check storage; nothing changed until it succeeds |
| Compile errors | `drools.rule.compile.error` increasing | P3 | A bad DRL upload; the previous version keeps serving |
| Missed pub/sub | `drools.reconcile.changed` > 0, or `drools.refresh.failed{layer=listener}` > 0 | P3 | Check Redis connectivity; reconcile already repaired it |
| Rate limit "client map at capacity" | Log line `Rate limiter client map at capacity` | P2 | Possible spoofing |
| Startup fails: admin key blank on prod/docker | App exits at startup — `prod`/`docker` profiles hard-fail when `ADMIN_API_KEY` is blank (fail-closed); a running-but-unauthenticated production state is no longer possible. Only `local`/`dev` start open, logging a WARN | P1 | Set `ADMIN_API_KEY` before deploy |
| Unexpected exception | Log entry `Unexpected error occurred` ERROR | P3 | Check stack trace |

### Correlation IDs

Every request gets:
- `X-Correlation-ID` (sticky across hops; client can supply, otherwise auto-generated)
- `X-Request-ID` (per-request, always auto-generated)

Both are in MDC (`%X{correlationId}` and `%X{requestId}` in logback patterns) so they appear in every log line for that request.

When a user reports an issue, ask for the `X-Correlation-ID` from their response. You can then trace the entire request lifecycle in logs.

### Synthetic monitoring

Run a periodic external check from outside the service (e.g., from another AWS region or a third-party monitor like Pingdom):

```bash
# Every 5 minutes
curl -fsS https://drools.example.com/admin/health \
  -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  -m 10 | jq -e '.status == "UP"'

# Functional check: actually execute a known rule
curl -fsS https://drools.example.com/execute-rule \
  -H 'Content-Type: application/json' \
  -d '{"rule_id":"pricing.discount.simple","data":{"amount":100}}' \
  -m 10 | jq -e '.result.discount == 10'
```

If either fails, page on-call.

---

## Part 3: Incident response

### Severity levels

| P1 | Service is fully unavailable or returning errors > 50% |
| P2 | Service is degraded (high latency, partial errors) |
| P3 | A non-critical feature is broken (memory diagnostics, etc.) |
| P4 | Cosmetic or future risk |

### P1 runbook: "Service is returning 5xx for everyone"

1. **Confirm**: hit `/admin/health` from outside. If 503 / no response, real outage.
2. **Look at recent changes**: deploy in last hour? rule refresh? config change?
3. **Check readiness and breakers**: `/admin/health` → `status` `DOWN` means no rules to serve (see [Instance not ready](#instance-not-ready)). Is the S3 breaker OPEN? (That blocks refreshes, not execution.)
4. **Check resources**: memory > 90%? thread pool saturated? GC pauses?
5. **Check logs**: `tail` recent ERROR lines. What's the most common stack trace?
6. **Mitigate**:
   - If breaker open: confirm dependency outage; compiled rules keep serving, so wait for recovery and refresh afterwards.
   - If memory full: restart the service.
   - If a bad rule: revert via S3 + refresh.
   - If a bad deploy: roll back.
7. **Post-mortem**: capture metrics + logs + correlation IDs from a sample of failures. Add a post-mortem in your incident system.

### P1 runbook: "Memory leak"

1. **Confirm**: `/admin/memory/snapshot` returns growing heap over 10+ minutes.
2. **Force GC**: `/admin/memory/gc` and re-check. If memory drops back, it was just delayed reclamation, not a leak.
3. **If still high**: take a heap dump.
   - Inside container: `jcmd 1 GC.heap_dump /tmp/heap.hprof` (or use the OOM auto-dump if OOM is imminent — `-XX:+HeapDumpOnOutOfMemoryError` is set).
4. **Restart**: buys time. Schedule deeper investigation.
5. **Investigate**: open the heap dump in MAT or VisualVM. Look for:
   - Many more `CompiledRuleset` / `KieBase` instances than loaded rule IDs → retired handles still referenced somewhere
   - Per-request objects retained → request-scoped state leak

### P2 runbook: "Latency is degraded"

1. **Quantify**: how much? P50/P95/P99 each?
2. **Check breaker states** — slow dependency could be tripping slow-call detection.
3. **Check thread pool** — saturated?
4. **Check cache hit rate** — drop?
5. **Check GC** — long pauses?
6. Apply the [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md) decision tree.

### P2 runbook: "A specific rule is slow / failing"

1. Find the rule from logs (correlation ID + ruleId).
2. Inspect `/admin/rules` to see its current state.
3. Pull the `.drl` from S3.
4. Review the rule logic — does it have a slow loop? Missing `no-loop`?
5. Replace with a tested version; refresh.

---

## Part 4: Operational maturity checklist

| Item | Status |
|---|---|
| Health endpoint exposed | ✅ `/admin/health` (503 when DOWN), `/actuator/health`, readiness/liveness probes |
| Metrics emitted to standard backend | ⚠️ CloudWatch in `prod`, no Prometheus pipeline currently |
| Structured logging with correlation IDs | ✅ `logstash-logback-encoder` + MDC |
| Graceful shutdown | ✅ `server.shutdown: graceful` |
| Heap dump on OOM | ✅ `-XX:+HeapDumpOnOutOfMemoryError` in JAVA_OPTS |
| Circuit breakers on external deps | ✅ S3 + Redis |
| Stateless service (horizontal scale ready) | ✅ |
| Documented incident playbooks | ✅ this doc |
| Synthetic monitoring | ⚠️ recommended, not built-in |
| Cluster-wide rate limiting | ❌ per-instance only |
| Admin API audit log | ✅ `AUDIT` logger: one line per refresh / reconcile / remove (2026-11-18). Actor is a remote address only; no per-user identity |
| Backup & restore for rule storage | ⚠️ relies on S3 versioning; explicit backup procedure not documented in repo |
| Disaster recovery plan | ⚠️ not in repo |
| Performance baseline tests | ✅ [`scripts/run-load-test.sh`](../scripts/run-load-test.sh) — Phases 0–8 (1000-rule baseline, JMeter mixed-workload, refresh storm) + Phase 9 (3-replica multi-instance convergence). See [39-load-test-findings.md](39-load-test-findings.md). |

### Recommendations for production deployment

Beyond the in-repo features:
- Add **Prometheus** registry to `pom.xml` and scrape `/actuator/prometheus`.
- Set up **Grafana** dashboards using the metrics catalog above.
- Configure **CloudWatch Logs Insights** queries for routine triage.
- Implement **Synthetics** (CloudWatch / Datadog / etc.) for outside-in monitoring.
- Document a **runbook for AWS S3 outage** specific to your region.
- Configure **PagerDuty / OpsGenie** integration for the alerts above.
- Set up **chaos testing** in a staging environment (kill LocalStack mid-test, inject latency).

These are out of scope for this repo but should be standard for any production deployment.

---

## Verification

```bash
# Operational endpoints all reachable
for ep in /admin/health /admin/info /admin/rules /admin/thread-pools /admin/memory/info /admin/memory/snapshot; do
  echo -n "$ep: "
  curl -fsS -H "X-Admin-API-Key: ${ADMIN_API_KEY:-}" "http://localhost:8080$ep" > /dev/null && echo OK || echo FAIL
done

# Spring Actuator endpoints
for ep in /actuator/health /actuator/info /actuator/metrics; do
  echo -n "$ep: "
  curl -fsS "http://localhost:8081$ep" > /dev/null && echo OK || echo FAIL
done
```

If any FAIL, see [31-troubleshooting.md](31-troubleshooting.md).
