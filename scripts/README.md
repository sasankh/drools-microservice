# scripts/ — Load Test Orchestrator + Helpers

Two scripts cover different use cases:

| Script | Purpose | Duration | When to use |
|---|---|---|---|
| [`e2e-load-test.sh`](#e2e-load-testsh) | Quick E2E health check + 5-min load test | ~10 min | After any code change, before merging |
| [`run-load-test.sh`](#run-load-testsh) | Full regression harness (JMeter, 1000 rules, soak) | ~5–6 hours | After Drools/Spring Boot bumps, production readiness gates |
| [`test-localstack.sh`](#helpers) | Validates LocalStack S3 bucket and uploaded rules | < 1 min | After `init-localstack.sh` to confirm S3 state |
| [`docker-build-test.sh`](#helpers) | Builds Docker image and validates health checks | ~5 min | After Dockerfile or dependency changes |

---

## e2e-load-test.sh

A one-command E2E health check that spins up the full Docker stack, runs a 5-minute load test with a mid-run hot reload, and tears everything down.

```bash
# Full run (tears down stack at end)
./scripts/e2e-load-test.sh

# Keep stack running after test
./scripts/e2e-load-test.sh --no-teardown

# Skip docker compose build if image is already current
./scripts/e2e-load-test.sh --skip-build
```

### What it checks

1. Docker stack starts healthy (LocalStack + Redis + App)
2. 17 sample rules upload to LocalStack S3
3. All 17 rules load into the engine with 0 failures
4. 4 smoke tests (different rule types)
5. Hot reload: 17 rules reload in < 2s, execution works immediately after
6. Memory stability: 5 rapid reloads, no monotonic heap growth; post-GC heap < 150 MB
7. 5-min load test: 20 workers across 6 rules, hot reload triggered at 2 min mid-run
8. Tear down

Exits non-zero if any check fails. Auto-disables rate limiting in `docker-compose.yml` for the duration of the test and restores it on exit (even on Ctrl-C).

**Baseline (2026-05-11):** 157,754 requests · 0 errors (0%) · ~518 RPS · heap 180–340 MB · hot reload 0 dropped requests

### Requirements

- Docker Desktop running
- `curl`, `python3`, `bc` (all standard on macOS)
- No JMeter needed

---

## Helpers

### test-localstack.sh

Validates that LocalStack S3 is correctly initialised — bucket exists, all 17 sample rules are present, and the app can reach them.

```bash
./scripts/test-localstack.sh
```

Run after `./init-localstack.sh` (or after `docker compose up -d`) to confirm S3 state before starting development.

### docker-build-test.sh

Builds the Docker image and runs a series of health and smoke checks — confirms image size, container startup, health endpoint, and basic rule execution.

```bash
./scripts/docker-build-test.sh
```

Run after Dockerfile changes, dependency updates, or a JVM/base-image bump to confirm the image still works end-to-end.

---

## run-load-test.sh

A re-runnable, end-to-end load + stress + soak harness for verifying the rule engine's production-readiness against the docker-compose stack (app + LocalStack S3 + Redis).

The orchestrator is the **regression contract**: a future engineer running `./scripts/run-load-test.sh` after a Drools/Spring Boot/cache-layer change should get a comparable PASS verdict, or fail loudly with specific deltas.

For the design, see [`.ai-workspace/project-plans/load-test-plan.md`](../.ai-workspace/project-plans/load-test-plan.md).

---

## Prerequisites

- Docker Desktop running
- Java 25 (only required if you want to build the app image; the orchestrator pulls it from `docker compose up --build`)
- `brew install jmeter jq`
- (Optional) `brew install gnuplot` for plotting `memory.csv` offline

The orchestrator's pre-flight checks fail loudly if any of these are missing.

## Quick start

```bash
# Full run (~5-6 hours; mostly unattended)
./scripts/run-load-test.sh

# Smoke test the harness itself (~10 min; 100 rules, 5-min soak)
./scripts/run-load-test.sh --quick

# Re-run only one phase (assumes earlier phases left the stack/corpus in place)
./scripts/run-load-test.sh --phase 3

# Resume from a specific phase
./scripts/run-load-test.sh --from 5

# Leave the stack up after exit (for manual poking)
./scripts/run-load-test.sh --no-cleanup

# Scale up
RULE_COUNT=5000 SOAK_MIN=240 ./scripts/run-load-test.sh

# Phase 9 — Redis cache + pub/sub validation (opt-in; ~90 min for all four sub-tests)
./scripts/run-load-test.sh --phase 9          # All 9.1-9.4 in sequence
./scripts/run-load-test.sh --phase 9.1        # Single-container REDIS=false baseline regression
./scripts/run-load-test.sh --phase 9.2        # 3-replica cache-only mode
./scripts/run-load-test.sh --phase 9.3        # 3-replica full mode + convergence headline
./scripts/run-load-test.sh --phase 9.4        # Redis kill/restart failure-mode
./scripts/run-load-test.sh --phase 9 --quick  # ~15 min smoke of all four
```

Each run writes to `scripts/load-test-results/<UTC-timestamp>/` (gitignored). Re-runs do not clobber prior data.

## Phases

| # | Name | What it does | Acceptance |
|---|---|---|---|
| 0 | Pre-flight | Docker, JMeter, ports, local Redis check | All checks pass |
| 1 | (Tier 1 sample-rules expansion) | Source-code change, not orchestrated. Phase 1 of the plan documents 7 new curated rules + cookbook entries + integration tests. | (Manual) |
| 2 | Corpus + boot | Generates `RULE_COUNT` synthetic DRL files, uploads to LocalStack S3, boots the stack, measures first-refresh compile time | All rules loaded |
| 3 | Baseline | Steady 50 RPS for 30 min, no refreshes | P99 < 200 ms, 0 errors |
| 4 | Concurrency ramp | 50 → 100 → 250 → 500 RPS, 5 min/step | Safe-RPS ≥ 250 |
| 5 | Hot full-refresh under load | Half safe-RPS execute traffic + full refresh every 10s for 30 min | 0 errors, P99 ≤ 2x baseline |
| 6 | Hot single-rule refresh under load | Half safe-RPS execute traffic + random single-rule refresh every 5s for 30 min | 0 errors, P95 stable |
| 7 | 1-hour soak | Mixed workload + heap dumps at start/end | Heap drift < 100 MB, 0 errors |
| 8 | Heap analysis + verdict | Auto-generates `summary.md` with PASS/FAIL per criterion | (Always runs) |
| 9.1 | Phase 9 baseline (opt-in) | Single-container with `REDIS_ENABLED=false`; 30-min steady load | 0 errors, P99 within ±10% of phase-3 baseline |
| 9.2 | Phase 9 cache-only (opt-in) | 3 replicas + nginx, `REDIS_PUBSUB_ENABLED=false`; 10-min load | Every replica touches Redis; no pub/sub events received; P99 ≤ 2× baseline |
| 9.3 | Phase 9 full mode (opt-in) | 3 replicas + nginx + pub/sub; 10 single + 5 bulk + 5 under-load convergence rounds | All rounds within `CONVERGENCE_DEADLINE_MS` (default 2000ms); `skipped_self` increments on publisher |
| 9.4 | Phase 9 failure mode (opt-in) | Full mode + `docker kill drools-redis` at T+120s; restart at T+240s; 10-min total load | CB opens within 30s on all 3 replicas; load error rate 0%; CB closes within 90s of restart; post-restart convergence round passes |

**Phase 9 is opt-in only**: it never runs unless you pass `--phase 9`, `--phase 9.x`, or `--from 9[.x]`. When a Phase 9 sub-test is selected, phases 0 (pre-flight) runs but 2–8 are skipped — Phase 9 manages its own stack lifecycle.

## Output layout

```
scripts/load-test-results/<UTC-timestamp>/
├── summary.md                          # final verdict — read this first
├── memory.csv                          # 5s memory poll across the entire run
├── rule-ids.csv                        # corpus rule_id → input_json mapping (JMeter input)
├── baseline_p99_ms.txt                 # used by later phases as a comparison floor
├── safe_rps.txt                        # used by Phases 5/6/7 to size background load
├── phase-2/
│   ├── synthetic/...                   # the generated DRL files
│   ├── rule-ids.csv                    # copy of the corpus index
│   ├── first-refresh.json              # raw response of the first /admin/refresh-rules
│   └── first-refresh-ms.txt            # compile time at this rule count
├── phase-3/
│   ├── jtl/baseline.jtl                # raw JMeter samples
│   ├── reports/baseline/index.html     # JMeter HTML report
│   ├── stats.csv                       # count,p50,p95,p99,err_pct
│   └── jmeter-baseline.{stdout,log}
├── phase-4/
│   ├── jtl/rps-{50,100,250,500}.jtl
│   ├── reports/rps-{50,100,250,500}/...
│   └── ramp-stats.csv                  # one row per RPS step
├── phase-5/                            # hot full-refresh
│   ├── jtl/exec-during-refresh.jtl
│   └── refresh-loop.csv                # per-refresh duration + outcome
├── phase-6/                            # hot single-rule refresh
│   ├── jtl/exec-during-single-refresh.jtl
│   └── single-refresh-loop.csv
└── phase-7/                            # soak
    ├── jtl/soak.jtl
    ├── refresh-loop.csv
    ├── single-refresh-loop.csv
    ├── heap-dump-start.hprof           # GC-cleaned start dump
    ├── heap-dump-end.hprof             # GC-cleaned end dump
    ├── heap-start-mb.txt
    ├── heap-end-mb.txt
    └── heap-delta-mb.txt
```

Phase 9 adds an alternate sub-tree (only the dirs you ran are present):

```
scripts/load-test-results/<UTC-timestamp>/
├── phase-9-summary.md                  # aggregate PASS/FAIL verdict — read this first
├── phase-corpus/synthetic/...          # generated only if not reusing phase-2 corpus
├── phase-9-1/                          # 9.1 baseline regression (single container)
│   ├── jtl/baseline-disabled.jtl
│   ├── reports/baseline-disabled/index.html
│   ├── stats.csv
│   ├── first-refresh.json
│   └── result.txt                      # PASS|FAIL + headline numbers
├── phase-9-2/                          # 9.2 cache-only mode (3 replicas + nginx)
│   ├── jtl/cache-only-load.jtl
│   ├── reports/cache-only-load/...
│   ├── stats.csv
│   ├── warm-refresh.json
│   ├── redis-metrics-post.csv          # per-replica bulk.hit/miss/hit/miss/refresh.received
│   ├── metric-snapshot-app-{1,2,3}-{pre,post}.json
│   └── result.txt
├── phase-9-3/                          # 9.3 full mode + convergence headline
│   ├── jtl/full-mode-load.jtl
│   ├── reports/full-mode-load/...
│   ├── stats.csv
│   ├── convergence-single.csv          # per-subscriber Δms for each single-rule round
│   ├── convergence-bulk.csv            # per-subscriber Δms for each bulk round
│   ├── convergence-under-load.csv      # per-subscriber Δms for rounds fired during load
│   ├── metric-snapshot-app-{1,2,3}-{pre,post}.json
│   └── result.txt
└── phase-9-4/                          # 9.4 failure mode (Redis kill)
    ├── jtl/failure-mode.jtl
    ├── reports/failure-mode/...
    ├── stats.csv
    ├── cb-state-timeline.csv           # epoch_s,replica,state for full kill+restart window
    ├── events.csv                      # condensed timeline: t_kill / t_restart / per-replica CB transitions / recovery Δms
    ├── convergence-recovery.csv        # post-restart convergence round (proves pub/sub re-subscribed)
    └── result.txt
```

## Env-var knobs

| Var | Default | Effect |
|---|---|---|
| `RULE_COUNT` | 1000 | Total rules generated for the corpus |
| `BASELINE_RPS` | 50 | Phase 3 RPS |
| `BASELINE_MIN` | 30 | Phase 3 duration in minutes |
| `RAMP_STEPS` | "50,100,250,500" | Phase 4 RPS steps (comma-separated) |
| `RAMP_STEP_MIN` | 5 | Phase 4 minutes per step |
| `HOT_REFRESH_MIN` | 30 | Phases 5 & 6 duration |
| `HOT_REFRESH_INTERVAL_S` | 10 | Phase 5 refresh cadence |
| `HOT_SINGLE_INTERVAL_S` | 5 | Phase 6 single-rule refresh cadence |
| `SOAK_MIN` | 60 | Phase 7 soak duration |
| `SOAK_FULL_REFRESH_S` | 30 | Phase 7 full-refresh cadence |
| `SOAK_SINGLE_REFRESH_S` | 10 | Phase 7 single-rule refresh cadence |
| `BASELINE_P99_MS_MAX` | 200 | Phase 3 acceptance |
| `SAFE_RPS_MIN` | 250 | Phase 4 acceptance |
| `HEAP_DRIFT_MB_MAX` | 100 | Phase 7 acceptance |
| `HOT_REFRESH_P99_MULTIPLIER_MAX` | 2 | Phase 5 acceptance |
| `PHASE9_CACHE_ONLY_MIN` | 10 | Phase 9.2 load duration (min) |
| `PHASE9_FULL_MODE_MIN` | 5 | Phase 9.3 background-load duration (min) |
| `PHASE9_FAILURE_MIN` | 10 | Phase 9.4 total JMeter window (min) |
| `PHASE9_SINGLE_ROUNDS` | 10 | Phase 9.3 single-rule convergence rounds |
| `PHASE9_BULK_ROUNDS` | 5 | Phase 9.3 bulk convergence rounds |
| `PHASE9_UNDER_LOAD_ROUNDS` | 5 | Phase 9.3 convergence rounds under load |
| `CONVERGENCE_DEADLINE_MS` | 2000 | Per-subscriber convergence-wait deadline |
| `REDIS_KILL_AT_S` | 120 | Phase 9.4 offset for `docker kill drools-redis` |
| `REDIS_DOWN_DURATION_S` | 120 | Phase 9.4 stay-down duration before `docker start` |
| `CB_OPEN_DEADLINE_S` | 30 | Phase 9.4 CB-must-open-by deadline |
| `CB_CLOSE_DEADLINE_S` | 90 | Phase 9.4 CB-must-close-by deadline (after restart) |

`--quick` overrides: `RULE_COUNT=100`, `BASELINE_MIN=1`, `HOT_REFRESH_MIN=2`, `SOAK_MIN=5`, `RAMP_STEP_MIN=1`, `PHASE9_CACHE_ONLY_MIN=2`, `PHASE9_FULL_MODE_MIN=1`, `PHASE9_FAILURE_MIN=4`, `REDIS_KILL_AT_S=60`, `REDIS_DOWN_DURATION_S=60`.

## Library helpers

The orchestrator sources these from `scripts/lib/`:

- **preflight.sh** — `preflight::check` validates Docker / JMeter / jq / port availability
- **stack.sh** — `stack::up`, `stack::down`, `stack::wait_for_health`, `stack::heap_used_mb`, `stack::trigger_gc`, `stack::app_pid`
- **corpus.sh** — `corpus::generate <count> <out_dir>`, `corpus::upload <out_dir>`, `corpus::clear_s3`. Currently ships 5 synthetic templates that cover the existing 10 cookbook patterns; Phase 1 of the plan extends this to 17 templates.
- **memory-poll.sh** — `memory_poll::start <csv> <pid_file> [interval]`, `memory_poll::stop <pid_file>`. Background CSV poller hitting `/admin/memory/info`.
- **refresh-loop.sh** — `refresh_loop::start <csv> <pid_file> [interval]`, `refresh_loop::stop <pid_file>`. Background `POST /admin/refresh-rules` loop with per-call latency capture.
- **single-refresh-loop.sh** — same shape as refresh-loop but picks a random rule from `rule-ids.csv` each iteration and calls `POST /admin/refresh-rules/{id}`.
- **heap-dump.sh** — `heap_dump::capture <out_path>`. `docker exec jmap -dump:live` + `docker cp` to host. Live dump triggers a full GC first so the hprof reflects retained set only.

Phase 9 adds three more (sourced only when a 9.x phase runs):

- **multi-stack.sh** — `multi_stack::up <cache-only|full>`, `multi_stack::down`, `multi_stack::wait_for_health <timeout>`, `multi_stack::wait_for_pubsub <timeout>`, `multi_stack::actuator_url <idx>`, `multi_stack::container_for_idx <idx>`, `multi_stack::list_app_containers`, `multi_stack::list_actuator_urls`, `multi_stack::container_for_actuator <url>`, `multi_stack::redis_container_name`, `multi_stack::kill_redis`, `multi_stack::start_redis`, `multi_stack::exec_admin_post <container> <path>`. Owns the 3-replica + nginx + named-Redis topology in [`scripts/docker-compose.loadtest-multi.yml`](docker-compose.loadtest-multi.yml).
- **actuator.sh** — `actuator::counter_at <base_url> <metric> [tag_pair]`, `actuator::wait_for_counter <base_url> <metric> <tag_pair> <target> <deadline_epoch_ms>`, `actuator::snapshot_phase9_metrics <base_url> <out_json>`. Pure-curl helpers against Spring's `/actuator/metrics/{name}?tag=key:val` API; counter values returned as bare scalars (Micrometer's `measurements[0].value`).
- **convergence.sh** — `convergence::write_csv_header <out>`, `convergence::measure_single <event_type> <pub_url> <sub_urls> <rule_id> <out_csv> [deadline_ms]`, `convergence::run_rounds <event_type> <rounds> <rule_ids_csv> <out_csv> [deadline_ms]`. Polls each subscriber's `drools.refresh.received{event=...}` at 50ms granularity; writes per-subscriber Δms rows.

Helpers can be sourced individually for ad-hoc experiments:

```bash
source scripts/lib/stack.sh
stack::up
stack::wait_for_health
echo "Heap: $(stack::heap_used_mb) MB"
```

## JMeter

`scripts/jmeter/execute-only.jmx` is a single parameterized plan reused across phases 3–7. Properties (passed via `-J` on the CLI by the orchestrator):

- `host` (default `localhost`) — target host
- `port` (default `8080`) — target port
- `rps_per_min` — target rate (the constant throughput timer is per-minute, all-active-threads-shared)
- `duration_s` — total run duration
- `threads` — concurrent JMeter threads
- `rule_ids_csv` — full path to the CSV produced by `corpus::generate`
- `jtl_path` — JTL output file

Each run also produces an HTML report under `reports/<tag>/`.

## Failure modes & recovery

| Symptom | Likely cause | Recovery |
|---|---|---|
| Phase 0 fails with "port 8080 bound" | Another stack still running | `docker compose down -v` and retry |
| Phase 0 fails with "redis-server running locally" | `brew services start redis` from earlier work | `brew services stop redis` |
| Phase 2 fails with "expected 1000 rules loaded, got X" | LocalStack init didn't pick up everything | Check `docker compose logs localstack` |
| jmeter exits non-zero with `Cannot find class...` | JMeter version mismatch with the .jmx | Re-install: `brew reinstall jmeter` |
| Heap dump fails with "could not find java pid" | App container started but JVM still warming | Wait or extend `stack::wait_for_health` timeout |
| Phase 4 stops at low RPS | Hardware-bound (load-runner CPU) — not a real cliff | Move load-runner to a separate machine |

Output is always preserved at `scripts/load-test-results/<timestamp>/` even on failure — the trap handler runs cleanup but doesn't delete results.

## Re-runnability contract

> Running `./scripts/run-load-test.sh` after a Drools or Spring Boot bump should produce a comparable PASS verdict — or fail loudly with specific numerical deltas pointing at the regression.

If you bump Drools, Spring Boot, the cache layer, or the rule shape: re-run this. If `summary.md` reports FAIL, drill into the phase output dir to see exactly which acceptance criterion was missed and by how much.
