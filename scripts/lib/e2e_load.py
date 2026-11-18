#!/usr/bin/env python3
"""Keep-alive load generator for /execute-rule that validates every response's VALUES.

Used by scripts/e2e-load-test.sh. Python 3 stdlib only.

Why this exists (2026-11-17): the previous bash/curl load step opened a new TCP connection per
request, exhausted Linux's ephemeral ports (~28k, 60 s TIME_WAIT) in ~30 s, and its workers then
died silently under `set -e` -- reporting "0 errors / PASS" for a test that had stopped. This
harness reuses connections, counts every exception, checks result values (with all 17 sample
rules loaded, so rule stacking applies), detects stalls, and exits non-zero on any failed gate.

Usage:
  e2e_load.py --workers 20 --duration 300 --reload-at 120 --min-rps 200 --label e2e
  e2e_load.py --workers 100 --duration 60 --reload-every 15 --label stress-100
Exit code: 0 = all gates passed, 1 = a gate failed, 2 = usage / setup error.
"""
import argparse
import collections
import http.client
import json
import sys
import threading
import time


def near(x, y):
    return isinstance(x, (int, float)) and abs(x - y) < 0.005


# (payload, predicate on result) -- expected values assume ALL 17 sample rules are loaded, so
# every rule whose LHS matches the input fires (e.g. pricing.discount.simple also applies 10%).
CASES = [
    ({"rule_id": "pricing.discount.simple", "data": {"amount": 150}},
     lambda r: near(r["amount"], 135.0) and near(r["discount"], 15.0)),
    ({"rule_id": "pricing.discount.vip", "data": {"customerType": "VIP", "amount": 100}},
     lambda r: near(r["amount"], 72.0)),
    ({"rule_id": "pricing.discount.bulk", "data": {"quantity": 15, "amount": 200}},
     lambda r: near(r["amount"], 153.0) and r.get("qualifyingQuantity") == 15),
    ({"rule_id": "pricing.discount.first-time", "data": {"isFirstTimeCustomer": True, "amount": 100}},
     lambda r: near(r["amount"], 85.5)),
    ({"rule_id": "pricing.loyalty.salience", "data": {"loyaltyMember": True, "amount": 100}},
     lambda r: near(r["amount"], 76.5) and r.get("loyaltyApplied") is True),
    ({"rule_id": "seasonal.holiday.blackfriday", "data": {"promotionCode": "BLACK2024", "amount": 200}},
     lambda r: near(r["amount"], 135.0) and r.get("promotionValid") is True),
    ({"rule_id": "seasonal.holiday.discount", "data": {"isHolidaySeason": True, "amount": 100}},
     lambda r: near(r["amount"], 79.2)),
    ({"rule_id": "pricing.shipping.standard", "data": {"shippingType": "standard", "weight": 0.5}},
     lambda r: near(r["shippingCost"], 5.99) and r.get("estimatedDays") == 5),
    ({"rule_id": "pricing.shipping.express", "data": {"shippingType": "express", "weight": 3, "amount": 150}},
     lambda r: r.get("freeShipping") is True and near(r["shippingCost"], 0.0)),
    ({"rule_id": "validation.customer.age", "data": {"customerAge": 15}},
     lambda r: r.get("validationResult") == "REJECTED" and r.get("eligible") is False),
    ({"rule_id": "validation.customer.credit", "data": {"creditScore": 720, "requestedAmount": 10000}},
     lambda r: r.get("creditTier") == "Good" and near(r["approvedAmount"], 5000.0)),
    ({"rule_id": "pricing.bundle.accumulate",
      "data": {"items": [{"price": 50, "stockLevel": 9}, {"price": 60, "stockLevel": 9}]}},
     lambda r: near(r["bundleTotal"], 110.0) and near(r["bundleFinalAmount"], 99.0)),
    ({"rule_id": "validation.email.compound",
      "data": {"email": "alice@company.com", "customerType": "internal"}},
     lambda r: r.get("emailValidationResult") == "VALID"),
    ({"rule_id": "seasonal.expiry.temporal",
      "data": {"currentDate": "2026-05-10", "expiryDate": "2026-12-31"}},
     lambda r: r.get("promoStatus") == "ACTIVE" and r.get("daysRemaining") == 235),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--workers", type=int, default=20)
    ap.add_argument("--duration", type=int, default=300, help="seconds")
    ap.add_argument("--reload-at", type=int, default=0, help="one full refresh at T seconds (0 = off)")
    ap.add_argument("--reload-every", type=int, default=0, help="full refresh every N seconds (0 = off)")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--admin-key", default="admin-secret")
    ap.add_argument("--min-rps", type=float, default=0.0, help="fail if average RPS is below this")
    ap.add_argument("--expect-rules", type=int, default=17, help="rules loaded after the run (0 = skip)")
    ap.add_argument("--allow-503", action="store_true", help="tolerate 503 load-shedding responses")
    ap.add_argument("--label", default="load")
    a = ap.parse_args()
    if a.workers < 1 or a.duration < 1:
        print("workers and duration must be >= 1", file=sys.stderr)
        return 2

    bodies = [json.dumps(c[0]).encode() for c in CASES]
    lock = threading.Lock()
    status = collections.Counter()
    wrong = collections.Counter()
    exc = collections.Counter()
    windows = collections.Counter()
    lat = []
    reloads = []
    start = time.time()
    stop_at = start + a.duration

    def worker(i):
        idx = i % len(CASES)
        conn = None
        local = []
        while time.time() < stop_at:
            try:
                if conn is None:
                    conn = http.client.HTTPConnection(a.host, a.port, timeout=30)
                t0 = time.perf_counter()
                conn.request("POST", "/execute-rule", bodies[idx], {"Content-Type": "application/json"})
                resp = conn.getresponse()
                raw = resp.read()
                local.append((time.perf_counter() - t0) * 1000.0)
                ok_value = True
                if resp.status == 200:
                    try:
                        j = json.loads(raw)
                        ok_value = j.get("error") is None and bool(CASES[idx][1](j["result"]))
                    except Exception:
                        ok_value = False
                with lock:
                    status[resp.status] += 1
                    windows[int((time.time() - start) // 10)] += 1
                    if resp.status == 200 and not ok_value:
                        wrong[CASES[idx][0]["rule_id"]] += 1
                if (resp.getheader("Connection") or "").lower() == "close":
                    conn.close()
                    conn = None
            except Exception as e:  # count, reconnect, never die silently
                with lock:
                    exc[type(e).__name__] += 1
                try:
                    if conn:
                        conn.close()
                except Exception:
                    pass
                conn = None
                time.sleep(0.05)
            idx = (idx + 1) % len(CASES)
        with lock:
            lat.extend(local)

    def admin(method, path):
        c = http.client.HTTPConnection(a.host, a.port, timeout=180)
        c.request(method, path, headers={"X-Admin-API-Key": a.admin_key})
        r = c.getresponse()
        body = r.read()
        c.close()
        return r.status, (json.loads(body) if body else {})

    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(a.workers)]
    for t in threads:
        t.start()
    next_reload = a.reload_every or a.reload_at or None
    while time.time() < stop_at:
        time.sleep(0.5)
        el = time.time() - start
        if next_reload and el >= next_reload:
            try:
                s, j = admin("POST", "/admin/refresh-rules")
                reloads.append({"t": round(el), "http": s, "loaded": j.get("rules_loaded"),
                                "failed": j.get("rules_failed"), "ms": j.get("duration_ms")})
            except Exception as e:
                reloads.append({"t": round(el), "error": type(e).__name__})
            next_reload = (next_reload + a.reload_every) if a.reload_every else None
    for t in threads:
        t.join(timeout=60)
    elapsed = time.time() - start

    rules_after = heap_after = None
    try:
        _, rules = admin("GET", "/admin/rules")
        rules_after = rules.get("total_rules")
        _, mem = admin("GET", "/admin/memory/info")
        heap_after = mem.get("heap", {}).get("usedMB")
    except Exception as e:
        exc["admin_" + type(e).__name__] += 1

    total = sum(status.values())
    lat.sort()

    def pct(p):
        return round(lat[min(len(lat) - 1, int(len(lat) * p))], 1) if lat else None

    full_windows = [windows.get(w, 0) for w in range(int(elapsed // 10))]
    non200 = {k: v for k, v in status.items() if k != 200}
    tolerated = {503} if a.allow_503 else set()
    failures = []
    if total == 0:
        failures.append("no requests completed")
    if any(k not in tolerated for k in non200):
        failures.append(f"non-200 responses: {non200}")
    if wrong:
        failures.append(f"wrong result values: {dict(wrong)}")
    if exc:
        failures.append(f"client exceptions: {dict(exc)}")
    if a.min_rps and total / elapsed < a.min_rps:
        failures.append(f"avg rps {total / elapsed:.0f} < min {a.min_rps:.0f}")
    if full_windows and min(full_windows) == 0:
        failures.append("stall: a 10 s window completed 0 requests")
    if a.expect_rules and rules_after != a.expect_rules:
        failures.append(f"rules loaded after = {rules_after}, expected {a.expect_rules}")
    for r in reloads:
        if r.get("http") != 200 or r.get("failed") not in (0, None) or "error" in r:
            failures.append(f"reload failed: {r}")

    summary = {
        "label": a.label, "workers": a.workers, "duration_s": round(elapsed), "requests": total,
        "rps": round(total / elapsed) if elapsed else 0, "http_status": dict(status),
        "wrong_results": dict(wrong), "client_exceptions": dict(exc),
        "latency_ms": {"p50": pct(.50), "p95": pct(.95), "p99": pct(.99), "p999": pct(.999),
                       "max": round(lat[-1], 1) if lat else None},
        "min_10s_window": min(full_windows) if full_windows else None,
        "reloads": reloads, "rules_loaded_after": rules_after, "heap_after_mb": heap_after,
        "result": "PASS" if not failures else "FAIL", "failures": failures,
    }
    print(json.dumps(summary))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
