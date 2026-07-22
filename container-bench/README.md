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

## Measured (Phase A) — in-container vs external HTTP, recall shapes

Vespa 8.727, single node, 8k-doc corpus (page-cached), 2 runs (reps 120/150),
count-parity OK on every shape (the searcher executes the same query, same match set):

| shape | external (client ms) | in-container (ms) | speedup |
|---|--:|--:|--:|
| id-lookup       | 4.9 / 3.5  | 1.49 / 1.03 | **3.3× / 3.4×** |
| author-timeline | 4.9 / 3.6  | 1.58 / 1.34 | **3.1× / 2.7×** |
| follow-feed(300)| 16.8 / 17.8| 5.99 / 5.82 | **2.8× / 3.1×** |
| kind-scan(200)  | 7.4 / 6.7  | 3.09 / 2.97 | **2.4× / 2.3×** |
| tag-list(p100)  | 16.2 / 23.1| 3.85 / 3.73 | **4.2× / 6.2×** (noisy external) |

**Reads are ~2.3–3.4× faster in-container** (tag-list more, noisier). In-container
even beats server `searchtime` — it drops the container's request-handling +
response-prep too, not just network + JSON encode/parse + client parse.

Caveats: 8k corpus is page-cached, so this is the *fixed-overhead-dominated*
regime where the win is largest; at a much larger corpus the engine work (match +
summary-fill) grows while the removed overhead is constant, so the ratio should
compress — measure at scale before generalizing. Count-parity, not full id-order
parity. Reads only; the searcher does field-access reconstruction, not full Quartz
`EventFactory` (a real store adds ~equal reconstruction cost to both paths).

## Findings so far
- The searcher deploys, loads, and executes in-container (Vespa 8.727, Java 17).
- Gotchas baked into the build: `--release 17`, `DynamicImport-Package: *`, its own
  `bench` chain (a failed searcher fails the whole component graph), and
  `@After(TRANSFORMED_QUERY) @Before(BACKEND)` so the cloned sub-query is parsed.
- Split baseline (500-hit feed): external ~16.7ms client / ~8.4ms server →
  in-container ≈ server time → ~2× on feed latency, ~half the fixed floor.

## Quartz-in-OSGi spike result (the Phase B gate)

Built a bundle embedding Quartz's full 55-jar runtime closure (Kotlin, kotlinx
coroutines/serialization, jackson, BouncyCastle, secp256k1) and deployed a
searcher that calls `Event.fromJson` inside Felix. Findings:

- **The classloader risk is CLEARED.** The entire closure loaded with ZERO
  class-load errors — including jackson 2.22.1 and BouncyCastle 1.84, which
  Vespa's platform *also* exports. No split-package / version conflicts. The
  whole non-Quartz closure is Java-8 bytecode (major 52) and resolves cleanly.
- **The ONE blocker is a Java target mismatch.** Quartz's own classes are
  compiled to **Java 21** (class-file major 65); Vespa 8.727's container runs
  **Java 17** (max 61). `Event.fromJson` fails with `UnsupportedClassVersionError`.

So Phase B is gated not on "does the Kotlin/OSGi mess work" (it does) but on a
cheap, known fix — one of:
  1. **Build Quartz with `jvmTarget = 17`** (the rest of its closure is already
     Java-8/17-safe, so a 17-targeted Quartz artifact should just load), or
  2. run a **Vespa release whose container is on Java 21** (verify one exists), or
  3. skip Quartz in-container and keep the Phase-A lightweight reconstruction.

### RESOLVED — jvmTarget=17 fix verified

Amethyst merged `jvmTarget = 17` (commit **52ce590505**). Re-ran the exact spike
with that Quartz build:

- The new Quartz artifact is **Java-17 bytecode (class-file major 61)**, down from
  65. The repo still builds + tests clean against it (HTTP path unaffected).
- Rebuilt the 55-jar bundle with the new closure, redeployed, and `Event.fromJson`
  now returns **`ok=true` — "Event loaded; parsed OK; kind=1"** inside Felix.

**Quartz-in-OSGi is CONFIRMED WORKING.** The embedded ("both options") path is fully
unblocked: the shared core, Quartz included, loads and runs in-container. Phase B is
now gated only on the code work (VespaLocalEventIndex + DocumentProcessor + handler),
not on any feasibility unknown.

## Not done (Phase B, the "full lib")
- Write path as a `DocumentProcessor` (dedup/supersession/NIP-09/62 guards internal).
- Nostr REQ/EVENT `RequestHandler` + a Nostr-wire `Renderer`.
- Real Quartz `EventFactory` reconstruction in-container (needs Quartz-in-OSGi spike).
