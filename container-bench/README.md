# container-bench — in-container read-path A/B (EXPERIMENT)

Measures how much the HTTP bridge costs by running the same recall queries two ways:
- **external**: over HTTP from an outside client (what `VespaEventStore` does today)
- **in-container**: a jdisc `BenchSearcher` re-runs the parsed query through the
  chain (dispatch → proton → summary-fill) in-process, no HTTP encode/parse.

This is Phase A: read path only (writes stay on the HTTP feed client). It does not
bundle Quartz — the searcher does reconstruction-equivalent field access, which is
the work that dominates read latency.

## Run (needs docker + a running Vespa, JDK 17+)
```bash
# 1. fresh Vespa + corpus (from repo root):
docker run -d --name vespa -p 8080:8080 -p 19071:19071 vespaengine/vespa
# wait for :19071 health, then:
BENCH_VESPA_URL=http://localhost:8080 BENCH_SIZE=8000 BENCH_VESPA_SKIP_PEREVENT=1 ./gradlew :benchmark:run
# 2. build + deploy the searcher bundle (data persists):
container-bench/deploy.sh
# 3. run the A/B:
python3 container-bench/driver.py 150
```

## Findings so far
- The searcher deploys, loads, and executes in-container (Vespa 8.727, Java 17).
- Gotchas baked into the build: `--release 17`, `DynamicImport-Package: *`, its own
  `bench` chain (a failed searcher fails the whole component graph), and
  `@After(TRANSFORMED_QUERY) @Before(BACKEND)` so the cloned sub-query is parsed.
- Split baseline (500-hit feed): external ~16.7ms client / ~8.4ms server →
  in-container ≈ server time → ~2× on feed latency, ~half the fixed floor.

## Not done (Phase B, the "full lib")
- Write path as a `DocumentProcessor` (dedup/supersession/NIP-09/62 guards internal).
- Nostr REQ/EVENT `RequestHandler` + a Nostr-wire `Renderer`.
- Real Quartz `EventFactory` reconstruction in-container (needs Quartz-in-OSGi spike).
