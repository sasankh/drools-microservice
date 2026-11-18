#!/usr/bin/env bash
# =============================================================================
# run-rule-benchmark.sh — 1,000-rule benchmark of the per-rule-ID engine (ADR-025)
#
# Generates a synthetic corpus (scripts/lib/corpus.sh), loads it into the docker-compose
# stack (rate limiting off via the loadtest overlay) and measures:
#   - cold load: time for the first full load of N rules (admin refresh after upload)
#   - time to ready after an app restart with N rules in S3 (startup load)
#   - unchanged refresh (digest skip: storage read, no recompiles)
#   - paced 50 RPS latency (same shape as the 2026-05-10 baseline), closed-loop throughput
#   - single-rule refresh under load (one rule changed in S3 + refreshed every 5 s)
#   - heap after GC before/after 100 single-rule refreshes
# Every response is value-checked (each synthetic rule sets its own fired_<key> marker).
#
# Usage:  ./scripts/run-rule-benchmark.sh [--rules 1000] [--skip-build] [--keep-stack]
# Needs:  docker (Engine + Compose v2), curl, jq, python3. ~15 minutes.
# Linux + Podman: DOCKER_HOST=unix:///var/run/docker.sock ./scripts/run-rule-benchmark.sh
# =============================================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES=1000; SKIP_BUILD=false; KEEP=false
while [ $# -gt 0 ]; do
  case "$1" in
    --rules) RULES="$2"; shift ;;
    --skip-build) SKIP_BUILD=true ;;
    --keep-stack) KEEP=true ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
cd "$ROOT_DIR"
TMP="$(mktemp -d)"
DC=(docker compose -f docker-compose.yml -f scripts/docker-compose.loadtest.yml)
A=http://localhost:8080; ACT=http://localhost:8081
KEY="${ADMIN_API_KEY:-admin-secret}"; K=(-H "X-Admin-API-Key: $KEY")
cleanup() { [ "$KEEP" = true ] || "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1; rm -rf "$TMP"; }
trap cleanup EXIT

ready_wait() { # seconds until readiness is 200 (max 300 s)
  local t0; t0=$(date +%s)
  for _ in $(seq 1 300); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$ACT/actuator/health/readiness")" = 200 ] && { echo $(( $(date +%s) - t0 )); return 0; }
    sleep 1
  done; echo -1; return 1
}

echo "==> Corpus: $RULES rules"
# shellcheck source=lib/corpus.sh
source scripts/lib/corpus.sh
corpus::generate "$RULES" "$TMP/corpus" >/dev/null

echo "==> Stack (rate limiting off)"
"${DC[@]}" down -v --remove-orphans >/dev/null 2>&1
if [ "$SKIP_BUILD" = true ]; then "${DC[@]}" up -d >/dev/null 2>&1; else "${DC[@]}" up -d --build >/dev/null 2>&1; fi
ready_wait >/dev/null || { echo "stack not ready"; exit 1; }
LS=$("${DC[@]}" ps -q localstack)

echo "==> Upload corpus to S3 (replacing the sample rules)"
docker exec "$LS" awslocal s3 rm s3://local-rules/ --recursive >/dev/null 2>&1
docker cp "$TMP/corpus/synthetic/." "$LS:/tmp/synthetic" >/dev/null
docker exec "$LS" awslocal s3 sync /tmp/synthetic/ s3://local-rules/ --no-progress >/dev/null 2>&1
n=$(docker exec "$LS" awslocal s3 ls s3://local-rules/ --recursive | grep -c '\.drl$'); echo "    $n objects"

echo "==> Cold load (first full load of $RULES rules, warm JVM)"
COLD=$(curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" | jq -c '{status,rules_loaded,rules_failed,rules_removed,duration_ms}'); echo "    $COLD"
UNCH=$(curl -s -X POST "$A/admin/refresh-rules" "${K[@]}" | jq -c '{status,rules_loaded,rules_unchanged,duration_ms}'); echo "    unchanged refresh: $UNCH"

echo "==> Restart app: time to ready with $RULES rules in S3"
"${DC[@]}" restart app >/dev/null 2>&1
RT=$(ready_wait); STARTUP=$("${DC[@]}" logs --no-log-prefix app 2>&1 | grep -o 'Startup load complete: [0-9]* rules loaded' | tail -1)
echo "    ready after ${RT}s ($STARTUP)"

echo "==> Benchmark (paced / closed / refresh under load / heap)"
MUT="docker exec $LS sh -c 'k=\$(echo {rule_id} | tr . /).drl; awslocal s3 cp s3://local-rules/\$k /tmp/m.drl >/dev/null && echo \"// rev {n}\" >> /tmp/m.drl && awslocal s3 cp /tmp/m.drl s3://local-rules/\$k >/dev/null'"
python3 scripts/lib/bench_rules.py --csv "$TMP/corpus/rule-ids.csv" --admin-key "$KEY" --mutate-cmd "$MUT" > "$TMP/bench.json"; rc=$?
HEAP=$(curl -s "$A/admin/memory/info" "${K[@]}" | jq -c '{heap_used_mb: .heap.usedMB, heap_max_mb: .heap.maxMB}')
jq --argjson cold "$COLD" --argjson unch "$UNCH" --arg ready "$RT" --argjson mem "$HEAP" \
  '. + {cold_load: $cold, unchanged_refresh: $unch, restart_to_ready_s: ($ready|tonumber), memory_now: $mem}' "$TMP/bench.json"
exit $rc
