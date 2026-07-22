#!/usr/bin/env python3
"""In-container store A/B + parity driver.

Fires the NIP-01 query shapes a relay actually serves through the deployed
`local` search chain. LocalStoreSearcher runs each shape TWICE in-container —
once via VespaLocalEventIndex (in-process Execution) and once via a loopback
HTTP VespaEventIndex — and reports both mean latencies plus whether the two id
lists agree. parity=OK on every shape is the in-container correctness gate.

Usage: driver.py [reps]   (needs the `local` chain deployed + a loaded corpus)
"""
import json, sys, urllib.parse, urllib.request

BASE = "http://localhost:8080/search/"
REPS = int(sys.argv[1]) if len(sys.argv) > 1 else 100


def sample(path, n):
    return [x for x in open(path).read().splitlines() if x][:n]


IDS = sample("/tmp/ids.txt", 50)
AUTHORS = sample("/tmp/authors.txt", 50)
PTAGS = sample("/tmp/ptags.txt", 50)

SHAPES = [
    ("id-lookup",       {"ids": IDS[0], "qlimit": "1"}),
    ("author-timeline", {"authors": AUTHORS[0], "qlimit": "100"}),
    ("follow-feed(20)", {"authors": ",".join(AUTHORS[:20]), "qlimit": "300"}),
    ("kind-scan",       {"kinds": "1", "qlimit": "200"}),
    ("tag-mentions",    {"ptags": PTAGS[0], "qlimit": "100"}),
    ("id-batch(20)",    {"ids": ",".join(IDS[:20]), "qlimit": "20"}),
]


def run(name, params):
    q = {"searchChain": "local", "localstore": "1", "reps": str(REPS),
         "trace.level": "1", **params}
    url = BASE + "?" + urllib.parse.urlencode(q)
    with urllib.request.urlopen(url, timeout=120) as r:
        d = json.load(r)
    for c in d.get("root", {}).get("children", []):
        f = c.get("fields", {})
        if "parity" in f:
            return f
    return {"parity": "NO-RESULT", "local_ms": 0, "http_ms": 0, "hits": 0,
            "raw": json.dumps(d)[:300]}


print(f"{'shape':<18}{'hits':>7}{'local ms':>11}{'http ms':>11}{'speedup':>9}  parity")
print("-" * 72)
all_ok = True
for name, params in SHAPES:
    f = run(name, params)
    lm, hm = f.get("local_ms", 0), f.get("http_ms", 0)
    sp = (hm / lm) if lm else 0
    ok = f.get("parity") == "OK"
    all_ok &= ok
    print(f"{name:<18}{f.get('hits',0):>7}{lm:>11.3f}{hm:>11.3f}{sp:>8.2f}x  {f.get('parity')}")
print("-" * 72)
print("PARITY:", "ALL OK" if all_ok else "MISMATCH — see above")
