#!/usr/bin/env bash
# Preflight checks for the load-test orchestrator.
# Sourced by run-load-test.sh; not meant to be run standalone.

preflight::check() {
  local errors=0

  echo "==> Pre-flight checks"

  # Docker daemon
  if ! docker info > /dev/null 2>&1; then
    echo "  [FAIL] Docker is not running. Start Docker Desktop and retry."
    errors=$((errors + 1))
  else
    echo "  [ok]   Docker daemon is responsive"
  fi

  # docker compose subcommand
  if ! docker compose version > /dev/null 2>&1; then
    echo "  [FAIL] 'docker compose' subcommand is missing. Update Docker Desktop."
    errors=$((errors + 1))
  else
    echo "  [ok]   docker compose subcommand available"
  fi

  # JMeter
  if ! command -v jmeter > /dev/null 2>&1; then
    echo "  [FAIL] jmeter not on PATH. Install with: brew install jmeter"
    errors=$((errors + 1))
  else
    local jmv
    jmv=$(jmeter --version 2>&1 | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)
    echo "  [ok]   jmeter ${jmv}"
  fi

  # jq (used everywhere for parsing /admin/* JSON)
  if ! command -v jq > /dev/null 2>&1; then
    echo "  [FAIL] jq not on PATH. Install with: brew install jq"
    errors=$((errors + 1))
  else
    echo "  [ok]   jq present"
  fi

  # Required ports — warn (not fail) if something else is bound; the stack-up step will fail
  # cleanly if there's a real conflict.
  local port
  for port in 8080 8081 4566 6379; do
    if lsof -i ":${port}" -sTCP:LISTEN > /dev/null 2>&1; then
      local owner
      owner=$(lsof -i ":${port}" -sTCP:LISTEN -F c 2>/dev/null | awk '/^c/' | head -1 | cut -c2-)
      echo "  [warn] port ${port} is already bound by '${owner}' — may conflict with the stack"
    fi
  done

  # Local Redis can fight the docker-compose Redis on 6379
  if pgrep -f redis-server > /dev/null 2>&1; then
    echo "  [warn] redis-server is running locally; the stack will conflict on 6379."
    echo "         Stop with: brew services stop redis"
  fi

  if [[ ${errors} -gt 0 ]]; then
    echo "==> ${errors} pre-flight check(s) failed"
    return 1
  fi
  echo "==> Pre-flight OK"
  return 0
}
