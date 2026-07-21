#!/usr/bin/env python3
"""Diff two benchmark BENCH_JSON result files — the regression gate.

    python3 benchmark/compare_results.py baseline.json new.json [--fail-over PCT]

Rows are matched on (suite, name). For each shared metric the delta is
printed; deltas beyond the threshold (default 15%) are flagged, with the
direction that matters per metric: throughput (qps / events_per_sec) going
DOWN is a regression, latency (p50/p95/p99, ms_per_*) going UP is a
regression, and parity `mismatches` above zero always fails. With
--fail-over set, any flagged regression exits 1 (CI-gate mode).
"""
import argparse
import json
import sys

THROUGHPUT = ("qps", "events_per_sec", "ingest_events_per_sec", "query_qps", "query_events_per_sec")
LATENCY = ("p50_us", "p95_us", "p99_us", "rt_per_event", "reads_per_event", "bytes_per_event")


def load(path):
    with open(path) as f:
        data = json.load(f)
    rows = {}
    for r in data.get("results", []):
        key = (r.get("suite", ""), r.get("name", ""))
        rows[key] = {k: v for k, v in r.items() if isinstance(v, (int, float))}
    return data.get("meta", {}), rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline")
    ap.add_argument("new")
    ap.add_argument("--fail-over", type=float, default=None, metavar="PCT",
                    help="exit 1 if any regression exceeds PCT percent")
    args = ap.parse_args()
    threshold = args.fail_over if args.fail_over is not None else 15.0

    meta_a, a = load(args.baseline)
    meta_b, b = load(args.new)
    print(f"baseline: {args.baseline} (size={meta_a.get('size')})  new: {args.new} (size={meta_b.get('size')})")
    if meta_a.get("size") != meta_b.get("size"):
        print("WARNING: corpus sizes differ — deltas are not like-for-like\n")

    regressions = []
    for key in sorted(set(a) & set(b)):
        suite, name = key
        shared = sorted(set(a[key]) & set(b[key]))
        lines = []
        for m in shared:
            old, new = a[key][m], b[key][m]
            if m == "mismatches":
                if new > 0:
                    lines.append(f"    {m}: {old:g} -> {new:g}  ** PARITY FAILURE **")
                    regressions.append((suite, name, m, old, new))
                continue
            if old == 0:
                continue
            pct = (new - old) / old * 100
            worse = (m in THROUGHPUT and pct < -threshold) or (m in LATENCY and pct > threshold)
            better = (m in THROUGHPUT and pct > threshold) or (m in LATENCY and pct < -threshold)
            mark = "  ** REGRESSION **" if worse else ("  (improved)" if better else "")
            if worse or better:
                lines.append(f"    {m}: {old:,.1f} -> {new:,.1f}  {pct:+.1f}%{mark}")
            if worse:
                regressions.append((suite, name, m, old, new))
        if lines:
            print(f"[{suite}] {name}")
            print("\n".join(lines))

    only_a = set(a) - set(b)
    only_b = set(b) - set(a)
    if only_a:
        print(f"\nrows only in baseline: {len(only_a)}")
    if only_b:
        print(f"rows only in new run: {len(only_b)}")

    print(f"\n{len(regressions)} regression(s) beyond {threshold:.0f}%")
    if args.fail_over is not None and regressions:
        sys.exit(1)


if __name__ == "__main__":
    main()
