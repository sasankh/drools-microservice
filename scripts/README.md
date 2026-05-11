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

`--quick` overrides: `RULE_COUNT=100`, `BASELINE_MIN=1`, `HOT_REFRESH_MIN=2`, `SOAK_MIN=5`, `RAMP_STEP_MIN=1`.

## Library helpers

The orchestrator sources these from `scripts/lib/`:

- **preflight.sh** — `preflight::check` validates Docker / JMeter / jq / port availability
- **stack.sh** — `stack::up`, `stack::down`, `stack::wait_for_health`, `stack::heap_used_mb`, `stack::trigger_gc`, `stack::app_pid`
- **corpus.sh** — `corpus::generate <count> <out_dir>`, `corpus::upload <out_dir>`, `corpus::clear_s3`. Currently ships 5 synthetic templates that cover the existing 10 cookbook patterns; Phase 1 of the plan extends this to 17 templates.
- **memory-poll.sh** — `memory_poll::start <csv> <pid_file> [interval]`, `memory_poll::stop <pid_file>`. Background CSV poller hitting `/admin/memory/info`.
- **refresh-loop.sh** — `refresh_loop::start <csv> <pid_file> [interval]`, `refresh_loop::stop <pid_file>`. Background `POST /admin/refresh-rules` loop with per-call latency capture.
- **single-refresh-loop.sh** — same shape as refresh-loop but picks a random rule from `rule-ids.csv` each iteration and calls `POST /admin/refresh-rules/{id}`.
- **heap-dump.sh** — `heap_dump::capture <out_path>`. `docker exec jmap -dump:live` + `docker cp` to host. Live dump triggers a full GC first so the hprof reflects retained set only.

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
