# 30 · Runbooks and Monitoring

| | |
|---|---|
| **Audience** | Operators, on-call engineers, SREs |
| **Purpose** | Operational procedures (start/stop/refresh/scale/incident response) plus monitoring setup (metrics, dashboards, alerts) |
| **Last verified against** | Running stack on 2026-05-10 |
| **Related docs** | [25-memory-monitoring-guide.md](25-memory-monitoring-guide.md), [26-performance-tuning-runbook.md](26-performance-tuning-runbook.md), [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md), [31-troubleshooting.md](31-troubleshooting.md) |

---

## Part 1: Runbooks

### Start the service

#### Local (Docker compose)

```bash
docker compose up -d
docker compose ps   # all 3 services should be (healthy) within ~60s
curl -fsS http://localhost:8080/admin/health | jq '.status'   # → "UP"
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
# Quick — just status
curl -fsS http://localhost:8080/admin/health | jq '.status'   # → "UP" / "DOWN"

# Full — component breakdown
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" | jq

# What components are checked: drools, storage, cache, redis (if enabled), circuit-breakers
# Overall status is "UP" iff drools and storage are UP. Cache/redis/breaker DOWN does not flip overall status.
```

### Refresh rules

#### All rules

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules | jq

# Expected:
# {
#   "status": "completed",
#   "rules_loaded": 17,
#   "rules_failed": 0,
#   "duration_ms": 1234,
#   "errors": []
# }
```

The compile happens **outside** the write lock; the lock is held only briefly for `KieContainer.updateToVersion(ReleaseId)`. In-flight `/execute-rule` requests are not interrupted. New requests use the new rule set immediately after the version swap. See [04-architecture.md](04-architecture.md) and [39-load-test-findings.md](39-load-test-findings.md) for measured behavior at 1000 rules.

#### Single rule

```bash
curl -X POST -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  http://localhost:8080/admin/refresh-rules/pricing.discount.simple | jq
```

Faster than full refresh when only one rule changed.

#### After uploading a new rule to S3

The service does not auto-detect S3 changes (unless `AUTO_REFRESH_ENABLED=true`, which polls every `AUTO_REFRESH_INTERVAL_MINUTES`). Call refresh after any S3 change.

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

- Each replica has its own compiled `kieContainer` (the execution hot path) and per-instance rate-limit buckets. Horizontal scaling **shares the Redis cache** (when `REDIS_ENABLED=true`) via `RedisCachedRuleStorage`, but rate limits multiply across replicas.
- Cross-instance refresh coherence is provided by `RuleRefreshPublisher`/`Subscriber` on channel `drools:rule:events` (requires `REDIS_PUBSUB_ENABLED=true`). When pub/sub is disabled, replicas must each be refreshed individually or via TTL expiry.
- If you need cluster-wide rate limiting, you'd need to back the limiter with Redis — currently not implemented (see [13-rate-limiting-and-throttling.md](13-rate-limiting-and-throttling.md)).
- Cold-start time is ~30-45s per replica. Plan capacity accordingly.

### Diagnose 503 errors

```bash
# Step 1: Check breaker state
curl -fsS http://localhost:8080/admin/health -H "X-Admin-API-Key: $ADMIN_API_KEY" \
  | jq '.components."circuit-breakers".details | {s3_state, redis_state}'
```

If `s3_state: "OPEN"` → S3 dependency is failing. See [29-circuit-breakers-and-resilience.md](29-circuit-breakers-and-resilience.md).

If both states are `CLOSED` and you still see 503s, look elsewhere:
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
| `drools.rule.execution.time` | Timer | `rule_id` (or `unknown` for misses) | Per-rule latency. **Cardinality**: rules not loaded are tagged `unknown` to prevent metric explosion. |
| `drools.rule.execution.error` | Counter | `rule_id`, `error_type` | Per-rule error rate |
| `drools.cache.hit` | Counter | — | `RedisCachedRuleStorage` cache hit (Redis GET / MGET returned the rule) |
| `drools.cache.miss` | Counter | — | Decorator fell through to base storage (S3/file/memory) for this rule |
| `drools.refresh.published` | Counter | `type=single\|bulk\|delete` | `RuleRefreshPublisher` sent an event to `drools:rule:events` |
| `drools.refresh.received` | Counter | `type=single\|bulk\|delete` | `RuleRefreshSubscriber` received an event |
| `drools.refresh.skipped_self` | Counter | — | Subscriber skipped a self-emitted event (loop-back prevention) |
| `drools.refresh.failed` | Counter | `layer=publisher\|subscriber\|storage\|engine` | Refresh failure by stage |

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
| Cache hit rate low | LRU hit rate < 70% for 30 min | P3 | Tune cache size |
| Rule execution errors | Per-rule error rate > 5% for 10 min | P2 | Validate rule logic |
| Rate limit "client map at capacity" | Log line `Rate limiter client map at capacity` | P2 | Possible spoofing |
| Admin auth disabled in production | Log line `Admin API key is not configured` AND env != local | P1 | Set `ADMIN_API_KEY` |
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
3. **Check breakers**: `/admin/health` → circuit breaker components. Is S3 OPEN?
4. **Check resources**: memory > 90%? thread pool saturated? GC pauses?
5. **Check logs**: `tail` recent ERROR lines. What's the most common stack trace?
6. **Mitigate**:
   - If breaker open: confirm dependency outage; nothing to do but wait for recovery.
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
   - Many `KieContainer` instances → disposal regression (Drools 10 `KieRepository.removeKieModule` missing after `updateToVersion`)
   - Many `ProjectClassLoader` instances → old `KieModule` not removed from `KieRepository`
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
| Health endpoint exposed | ✅ `/admin/health`, `/actuator/health` |
| Metrics emitted to standard backend | ⚠️ CloudWatch in `prod`, no Prometheus pipeline currently |
| Structured logging with correlation IDs | ✅ `logstash-logback-encoder` + MDC |
| Graceful shutdown | ✅ `server.shutdown: graceful` |
| Heap dump on OOM | ✅ `-XX:+HeapDumpOnOutOfMemoryError` in JAVA_OPTS |
| Circuit breakers on external deps | ✅ S3 + Redis |
| Stateless service (horizontal scale ready) | ✅ |
| Documented incident playbooks | ✅ this doc |
| Synthetic monitoring | ⚠️ recommended, not built-in |
| Cluster-wide rate limiting | ❌ per-instance only |
| Admin API audit log | ⚠️ logs exist, but not a dedicated audit channel |
| Backup & restore for rule storage | ⚠️ relies on S3 versioning; explicit backup procedure not documented in repo |
| Disaster recovery plan | ⚠️ not in repo |
| Performance baseline tests | ❌ JMeter deferred |

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
