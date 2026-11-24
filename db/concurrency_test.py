"""
CRAS concurrency test: proves (or disproves) that pessimistic locking stops overselling.

Scenario: a resource has STOCK units; N > STOCK requests of 1 unit each are allocated at the
SAME instant from N threads. Correct behaviour: exactly STOCK allocations succeed, the rest are
refused, and stock ends at exactly 0 (never negative, never double-spent).

Needs the backend running (default http://localhost:8080) and a manager or admin account.

  python concurrency_test.py --email manager@cras.local --password '<password>' --stock 20 --requests 60

Standard library only. Exit code 0 = PASS, 1 = FAIL.
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def call(base, method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--email", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--stock", type=int, default=20)
    ap.add_argument("--requests", type=int, default=60)
    a = ap.parse_args()

    code, body = call(a.base, "POST", "/api/auth/login", body={"email": a.email, "password": a.password})
    if code != 200:
        sys.exit(f"login failed: {code} {body}")
    token = body["token"]

    name = f"ConcTest-{int(time.time())}"
    code, res = call(a.base, "POST", "/api/resources", token,
                     {"name": name, "unit": "unit", "availableQuantity": a.stock})
    if code != 200:
        sys.exit(f"resource create failed: {code} {res}")
    rid = res["id"]
    print(f"created resource {name} (id {rid}) with stock {a.stock}")

    ids = []
    for _ in range(a.requests):
        code, r = call(a.base, "POST", "/api/requests", token,
                       {"resourceId": rid, "quantity": 1, "severity": 5,
                        "affectedPeople": 10, "scarcity": 5})
        if code != 200:
            sys.exit(f"request create failed: {code} {r}")
        ids.append(r["id"])
    print(f"created {len(ids)} pending requests of 1 unit each")

    barrier = threading.Barrier(len(ids))

    def fire(request_id):
        barrier.wait()  # release all threads at the same instant
        code_, _ = call(a.base, "POST", f"/api/manager/allocate/{request_id}", token)
        return code_

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=len(ids)) as pool:
        codes = list(pool.map(fire, ids))
    dt = time.time() - t0

    ok = sum(1 for c in codes if c == 200)
    refused = len(codes) - ok
    code, resources = call(a.base, "GET", "/api/resources", token)
    final = next(r["availableQuantity"] for r in resources if r["id"] == rid)

    print(f"\n{len(ids)} parallel allocations in {dt:.2f}s -> {ok} succeeded, {refused} refused")
    print(f"final stock = {final}")
    passed = (ok == min(a.stock, a.requests)) and final == max(0, a.stock - ok) and final >= 0
    print("RESULT:", "PASS (no overselling, stock never negative)" if passed else "FAIL")
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
