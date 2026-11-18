#!/usr/bin/env python3
"""Rule-engine benchmark client (engine-isolation plan, Phase 6; 2026-11-18).

Measures, against a running stack loaded with a synthetic corpus (scripts/lib/corpus.sh):
  1. paced    — fixed request rate (default 50 RPS, as in the 2026-05-10 baseline) for N seconds;
                latency percentiles, every response value-checked (its own fired_<key> marker).
  2. closed   — W keep-alive workers as fast as possible for N seconds (throughput + latency).
  3. refresh  — single-rule refresh under paced load: every R seconds one rule's DRL changes in
                S3 (via --mutate-cmd) and is refreshed; reports refresh time and the request
                latency during the run.
  4. heap     — heap after GC before/after K single-rule refreshes (leak check).

Stdlib only. Prints a JSON summary; exits 1 if any response was wrong or errored.
"""
import argparse
import csv
import http.client
import json
import random
import statistics
import subprocess
import sys
import threading
import time


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return round(s[min(len(s) - 1, int(len(s) * p))], 2)


class Client:
    def __init__(self, host, port, key):
        self.host, self.port, self.key = host, port, key
        self.conn = None

    def _c(self):
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=60)
        return self.conn

    def call(self, method, path, body=None, admin=False):
        headers = {"Content-Type": "application/json"}
        if admin:
            headers["X-Admin-API-Key"] = self.key
        data = json.dumps(body) if body is not None else None
        for attempt in (1, 2):
            try:
                c = self._c()
                c.request(method, path, body=data, headers=headers)
                r = c.getresponse()
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
            except (http.client.HTTPException, OSError):
                self.conn = None
                if attempt == 2:
                    raise
        return None, {}


def load_cases(path):
    cases = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            data = json.loads(row["input_json"])
            key = next(iter(data))
            cases.append((row["rule_id"], data, "fired_" + key))
    return cases


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.lat = []
        self.codes = {}
        self.wrong = 0
        self.errors = 0

    def add(self, ms, code, ok):
        with self.lock:
            self.lat.append(ms)
            self.codes[code] = self.codes.get(code, 0) + 1
            if code != 200:
                self.errors += 1
            elif not ok:
                self.wrong += 1

    def summary(self, seconds):
        return {
            "requests": len(self.lat),
            "rps": round(len(self.lat) / seconds, 1),
            "http": self.codes,
            "errors": self.errors,
            "wrong": self.wrong,
            "latency_ms": {
                "p50": pct(self.lat, 0.50),
                "p95": pct(self.lat, 0.95),
                "p99": pct(self.lat, 0.99),
                "p999": pct(self.lat, 0.999),
                "max": round(max(self.lat), 2) if self.lat else None,
            },
        }


def one(client, case, stats):
    rule_id, data, marker = case
    t0 = time.perf_counter()
    try:
        code, j = client.call("POST", "/execute-rule", {"rule_id": rule_id, "data": data})
    except Exception:
        stats.add((time.perf_counter() - t0) * 1000, "exception", False)
        return
    ms = (time.perf_counter() - t0) * 1000
    ok = code == 200 and (j.get("result") or {}).get(marker) is True
    stats.add(ms, code, ok)


def paced(args, cases, seconds, rps, stop_flag=None):
    """Open-loop: schedule rps requests/second across a small worker pool."""
    stats = Stats()
    interval = 1.0 / rps
    start = time.perf_counter()
    nxt = [start]
    lock = threading.Lock()

    def worker():
        client = Client(args.host, args.port, args.admin_key)
        while True:
            with lock:
                due = nxt[0]
                nxt[0] += interval
            if due - start >= seconds or (stop_flag and stop_flag.is_set()):
                return
            delay = due - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            one(client, random.choice(cases), stats)

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(max(4, rps // 10))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return stats.summary(time.perf_counter() - start)


def closed(args, cases, seconds, workers):
    stats = Stats()
    end = time.perf_counter() + seconds

    def worker():
        client = Client(args.host, args.port, args.admin_key)
        while time.perf_counter() < end:
            one(client, random.choice(cases), stats)

    t0 = time.perf_counter()
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return stats.summary(time.perf_counter() - t0)


def heap_after_gc(client):
    client.call("POST", "/admin/memory/gc", admin=True)
    time.sleep(3)
    _, j = client.call("GET", "/admin/memory/info", admin=True)
    return (j.get("heap") or {}).get("usedMB")


def mutate_and_refresh(args, client, rule_id, n):
    subprocess.run(args.mutate_cmd.format(rule_id=rule_id, n=n), shell=True, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    t0 = time.perf_counter()
    code, j = client.call("POST", "/admin/refresh-rules/" + rule_id, admin=True)
    wall = (time.perf_counter() - t0) * 1000
    return code, j.get("status"), wall


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="rule-ids.csv from corpus.sh")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--admin-key", default="admin-secret")
    ap.add_argument("--paced-seconds", type=int, default=120)
    ap.add_argument("--rps", type=int, default=50)
    ap.add_argument("--closed-seconds", type=int, default=60)
    ap.add_argument("--workers", type=int, default=20)
    ap.add_argument("--refresh-seconds", type=int, default=120)
    ap.add_argument("--refresh-every", type=float, default=5.0)
    ap.add_argument("--heap-refreshes", type=int, default=100)
    ap.add_argument("--mutate-cmd", required=True,
                    help="shell command that changes rule {rule_id} in storage (gets {n})")
    args = ap.parse_args()

    cases = load_cases(args.csv)
    admin = Client(args.host, args.port, args.admin_key)
    out = {"rules": len(cases)}

    out["paced"] = paced(args, cases, args.paced_seconds, args.rps)
    out["closed"] = closed(args, cases, args.closed_seconds, args.workers)

    # Single-rule refresh under paced load.
    stop = threading.Event()
    holder = {}
    t = threading.Thread(target=lambda: holder.update(
        r=paced(args, cases, args.refresh_seconds, args.rps, stop)), daemon=True)
    t.start()
    walls, statuses, n = [], {}, 0
    end = time.time() + args.refresh_seconds - args.refresh_every
    while time.time() < end:
        n += 1
        rule_id = random.choice(cases)[0]
        code, status, wall = mutate_and_refresh(args, admin, rule_id, n)
        walls.append(wall)
        statuses[f"{code}:{status}"] = statuses.get(f"{code}:{status}", 0) + 1
        time.sleep(args.refresh_every)
    t.join()
    out["refresh_under_load"] = {
        "refreshes": len(walls), "status": statuses,
        "refresh_ms": {"p50": pct(walls, 0.5), "max": round(max(walls), 1) if walls else None},
        "traffic": holder.get("r"),
    }

    # Heap stability over K single-rule refreshes (no traffic).
    h0 = heap_after_gc(admin)
    hw = []
    for i in range(args.heap_refreshes):
        rule_id = cases[i % len(cases)][0]
        _, _, wall = mutate_and_refresh(args, admin, rule_id, 10_000 + i)
        hw.append(wall)
    h1 = heap_after_gc(admin)
    out["heap"] = {"before_mb": h0, "after_mb": h1, "refreshes": args.heap_refreshes,
                   "refresh_ms_p50": pct(hw, 0.5),
                   "refresh_ms_mean": round(statistics.mean(hw), 1) if hw else None}

    bad = sum(s["errors"] + s["wrong"] for s in (out["paced"], out["closed"]))
    tr = out["refresh_under_load"]["traffic"] or {}
    bad += tr.get("errors", 0) + tr.get("wrong", 0)
    out["result"] = "PASS" if bad == 0 else "FAIL"
    print(json.dumps(out, indent=2))
    sys.exit(0 if bad == 0 else 1)


if __name__ == "__main__":
    main()
