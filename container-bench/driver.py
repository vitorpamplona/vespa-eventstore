#!/usr/bin/env python3
"""In-container (searcher) vs external-HTTP A/B for the relay recall shapes.
Requires a Vespa on :8080 with the corpus loaded, the relaybench bundle in a
`bench` search chain, and the default chain intact. Run: python3 driver.py [reps]"""
import json, time, statistics, urllib.request, sys, re
URL = "http://localhost:8080/search/"
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
REPS = int(sys.argv[1]) if len(sys.argv) > 1 else 150
SEL = "id, pubkey, created_at, kind, tags, content, sig, owner"

def post(body):
    return json.load(op.open(urllib.request.Request(URL, json.dumps(body).encode(),
        {"Content-Type": "application/json"}), timeout=60))

def get(url):
    return json.load(op.open(urllib.request.Request(url), timeout=60))

def q(s): return s.replace("'", "")

# ---- sample the corpus for real filter values ----
s = post({"yql": f"select id,pubkey,tags,kind from event where true limit 800", "hits": 800})
hits = [h["fields"] for h in s["root"].get("children", [])]
authors = list(dict.fromkeys(h["pubkey"] for h in hits))
ids = [h["id"] for h in hits]
ptags = []
for h in hits:
    for row in json.loads(h.get("tags", "[]")):
        if len(row) >= 2 and row[0] == "p": ptags.append(row[1])
ptags = list(dict.fromkeys(ptags))
print(f"# sampled: {len(authors)} authors, {len(ids)} ids, {len(ptags)} p-tags")

def inlist(field, vals): return f"{field} in (" + ",".join(f'"{q(v)}"' for v in vals) + ")"

SHAPES = {
  "id-lookup":       f"select {SEL} from event where {inlist('id',[ids[0]])}",
  "author-timeline": f"select {SEL} from event where {inlist('pubkey',[authors[0]])} order by created_at desc limit 50",
  "follow-feed(300)":f"select {SEL} from event where {inlist('pubkey',authors[:300])} and kind in (1,6,7) order by created_at desc limit 500",
  "kind-scan(200)":  f"select {SEL} from event where kind in (1) order by created_at desc limit 200",
  "tag-list(p100)":  f"select {SEL} from event where {inlist('tag_index',['p:'+p for p in ptags[:100]])} and kind in (1,7) order by created_at desc limit 300",
}

def external(yql, hits_cap):
    body = {"yql": yql, "hits": hits_cap, "ranking": "unranked", "presentation.timing": True}
    for _ in range(8): post(body)
    W, S, cnt = [], [], 0
    for _ in range(REPS):
        t0 = time.perf_counter(); r = post(body); W.append((time.perf_counter()-t0)*1000)
        S.append((r.get("timing", {}).get("searchtime", 0) or 0)*1000)
        cnt = len(r["root"].get("children", []))
    return statistics.mean(W), statistics.mean(S), cnt

def incontainer(yql, hits_cap):
    import urllib.parse
    url = f"{URL}?searchChain=bench&relaybench=1&reps={REPS}&trace.level=1&hits={hits_cap}&ranking=unranked&timeout=60s&yql={urllib.parse.quote(yql)}"
    r = get(url)
    blob = json.dumps(r)
    m = re.search(r"RELAYBENCH mean_ms=([\d.]+) hits=(\d+)", blob)
    return (float(m.group(1)), int(m.group(2))) if m else (None, None)

print(f"\n# reps={REPS}  (in-container = searcher execution.search+fill+field-touch; external = client wall)\n")
print(f"{'shape':18} {'ext_wall':>9} {'ext_srv':>9} {'in_cont':>9} {'speedup':>8} {'parity(cnt)':>14}")
for name, yql in SHAPES.items():
    cap = 500 if "feed" in name else (300 if "tag" in name else (200 if "kind" in name else (50 if "timeline" in name else 1)))
    ew, es, ec = external(yql, cap)
    ic, ih = incontainer(yql, cap)
    if ic is None: print(f"{name:18}  in-container read FAILED (searcher not returning trace)"); continue
    spd = ew/ic if ic else 0
    par = "OK" if ih == ec else f"MISMATCH {ih}!={ec}"
    print(f"{name:18} {ew:8.2f}ms {es:8.2f}ms {ic:8.2f}ms {spd:7.2f}x  {par:>14}")
