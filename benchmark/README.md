# `:benchmark` — event-store performance harness

A head-to-head benchmark of this framework's Vespa-backed `IEventStore` against
**Quartz's own SQLite `EventStore`**, on one identical, reproducible corpus. It
measures the two things that matter for a relay — **insertion** and **query**
speed — and, crucially, the **store-layer I/O amplification** that is the real
lever for optimizing *this* framework.

Not published to Maven Central (it pulls in the SQLite driver and a fixed corpus
generator that have no place in the released artifacts).

## Did Quartz already have a benchmark to copy?

No. The closest thing in Amethyst's Quartz is
`nip01Core/store/sqlite/ParallelInsertTest.kt` — a **concurrency-correctness**
test (≈1,600 `TextNoteEvent`s across 8 coroutines, asserting "every insert is
visible" and no `SQLITE_MISUSE`), with **no timing and no throughput assertion**.
The `fs/` and `sqlite/` suites are parity/correctness, not performance. So there
was no events-per-second harness or standard event list to replicate — this
module builds one, modelled on a general relay's kind distribution, and runs it
against both stores through the shared `IEventStore` interface.

## What it measures

1. **Round-trip amplification** — how many engine calls (`get`/`search`/`count`
   reads, `put`/`remove` writes) `NostrEventStore` makes per event, for the
   per-event `insert()` path vs the bulk `batchInsert()` path. This count is
   **engine-independent** (a property of the store's own logic — each call is one
   Vespa network round-trip or one SQLite statement), so it is the cleanest lens
   for optimizing the framework. Measured with `CountingEventIndex`.
2. **Insert throughput** — `insert()` and `batchInsert()`, events/sec.
3. **Query throughput** — the shapes a relay actually serves: author timeline,
   kind scan, tag mentions, id lookup, profile lookup, count, NIP-50 search.

### Backends

| Backend | What it is | Meaningful for |
|---|---|---|
| **SQLite (memory / disk)** | Quartz's `EventStore(dbName=…)`, real embedded DB | the honest apples-to-apples comparison |
| **Real Vespa** (`BENCH_VESPA_URL`) | `VespaEventStore.open(url)` against a running cluster | real engine numbers (ingest + BM25 search) |
| **Vespa/InMemory ref** | this store over the O(n)-scan in-memory reference engine | round-trip counts only — **not** a throughput proxy |

> The in-memory reference engine answers every filter with a linear scan, so its
> *wall-clock* is not representative of Vespa. Read its **round-trip counts**, not
> its throughput. Real Vespa numbers require `BENCH_VESPA_URL`.

## Running it

```bash
# SQLite + in-memory reference only (no external services):
./gradlew :benchmark:run

# Add real Vespa (stand one up first — see below):
BENCH_VESPA_URL=http://localhost:8080 ./gradlew :benchmark:run
```

Stand up a local Vespa with Docker, deploy the bundled schema, wait for it to serve:

```bash
docker run --detach --name vespa --publish 8080:8080 --publish 19071:19071 vespaengine/vespa
# VespaEventStore.open(url) auto-deploys the bundled app package on first contact.
```

Tunables (env): `BENCH_SIZE` (corpus events, default 30000), `BENCH_BATCH`
(batchInsert chunk, 500), `BENCH_QUERIES` (reps per query shape, 2000),
`BENCH_REF_SIZE` (cap for the O(n²) in-memory reference, 8000), `BENCH_SEED`.

## Results

Measured on a 4-core / 16 GB Linux box, JDK 21, corpus = 30,000 events
(seed 42), single-node Vespa 8.x in Docker. Relative numbers matter more than
absolutes; re-run on your own hardware. Kind mix per run is printed at the top
(~53% notes, ~26% reactions, plus reposts, metadata/contacts/relay-lists that
supersede, deletions, and addressable long-form).

### 1. Round-trip amplification — the headline finding

| path | reads/event | round-trips/event |
|---|---|---|
| `insert()` | 3.26 | **4.29** |
| `batchInsert()` | 0.04 | **0.05** |

**The bulk path issues ≈89× fewer engine round-trips per event.** The per-event
path pays, for every event: a dup probe (`get`), a NIP-09 deletion probe
(`search`), a NIP-62 vanish probe (`search`), and a replaceable/addressable
supersession probe (`search`) — ~3.3 reads before the write. `batchInsert()`
reads the whole working set in a handful of chunked queries and pipelines the
writes, amortizing all of it.

This is engine-independent, and it dominates real-Vespa ingest (below).

### 2. Insert throughput (events/sec)

| backend | `insert()` | `batchInsert(500)` |
|---|---:|---:|
| SQLite (memory) | 5,654 | 6,338 |
| SQLite (disk) | 4,847 | **32,269** |
| Real Vespa (1 node) | **39.5** | **635** |
| Vespa/InMemory ref\* | 1,442 | 11,415 |

- **On-disk SQLite batching is ~10×** (4.8k → 32k): the transaction amortizes
  `fsync`. In-memory SQLite barely benefits (no `fsync` to amortize).
- **Real Vespa: `insert()` = 39.5/s vs `batchInsert()` = 635/s — a 16× wall-clock
  gap on one node**, exactly the 89× round-trip fan-out showing up as latency
  (each round-trip is a ~6 ms HTTP hop; 4.29 of them ≈ 25 ms/event). On a real
  multi-node cluster the feed client pipelines much further, widening the gap.
  **Rule: never ingest into Vespa via per-event `insert()`; always `batchInsert`.**

### 3. Query throughput (queries/sec; higher is better)

| query | SQLite (mem) | Real Vespa (1 node) |
|---|---:|---:|
| id-lookup | **50,145** | 198 |
| profile (kind 0) | **52,094** | 238 |
| tag-mentions (`#p`) | **13,568** | 183 |
| kind-scan (notes, limit 200) | 1,575 | 62 |
| author-timeline (limit 50) | 519 | 124 |
| count(reactions) | 4,944 | 199 |
| NIP-50 search | 46.9 | 46.7 |

**At 30k events on a single node, SQLite wins nearly everything.** That is
expected and important: SQLite is an in-process embedded DB with no network hop,
so point lookups are ~50k/s. Single-node Vespa pays a 4–8 ms HTTP round-trip per
query. **Vespa's value is not single-node small-corpus latency** — it is
horizontal scale, corpus sizes that exceed SQLite's practical ceiling, and search
**relevance quality** (trust-ranked BM25, trigram typo-recall), not raw search
speed. Note the two engines are already **even on NIP-50 search (~47/s)** at this
scale — and that is the workload the Vespa store exists to do *better*, by
ranking, once trust data and scale enter the picture.

Practical read: **don't reach for Vespa until you outgrow SQLite.** Below a few
hundred-k events on one box, Quartz's SQLite store is faster and simpler.

## Where to hammer performance (framework optimization targets)

1. **Push ingest onto `batchInsert`.** The single biggest win, already available:
   ~89× fewer round-trips. Any syncer/relay path still calling `insert()` in a
   loop against Vespa is leaving ~16× on the table. Consider making the per-event
   `insert()` documentation warn against bulk use, or coalescing bursts.
2. **Trim the per-event read fan-out (3.26 reads/event).** For *pure records*
   (the common case — no kind 5/62 in the batch), the deletion and vanish probes
   almost always come back empty. A per-owner bloom filter of "owners with any
   tombstone/vanish" would let the store skip both probes for the vast majority
   of events, cutting reads/event toward 1 (just the dup `get` + supersession).
3. **Real-Vespa single-node ingest (635/s) is throttle-floor-bound**, not
   engine-bound — the feed client's dynamic throttler idles at its `minInflight`
   floor under bursty batched writes (see the comment in `VespaEventIndex`).
   Larger batches, more connections, and multi-node raise it.

## BUG found while benchmarking (correctness, real Vespa)

`EventDoc.fromSummary` reads `fields.getValue("sig")`, which **throws** when
`sig` is absent. Real Vespa **omits empty-string fields from query summaries**, so
any stored event with `sig == ""` — i.e. **every unsigned rumor**, which this
store is explicitly designed to hold (NIP-59 inner events, drafts) — comes back
from `search()` as a dropped hit, and the query returns *nothing*. `count()`
(grouping) is unaffected, which masks it. `MockVespaEngine` never caught this
because it echoes empty fields back verbatim; only a real Vespa omits them.

One-line fix (make `sig` tolerant like `content`/`owner` already are):

```kotlin
// EventDoc.fromSummary
sig = fields["sig"]?.jsonPrimitive?.content ?: "",
```

The corpus here signs its events (128-hex fake signature) so the benchmark
measures the common signed path; the fix above is what makes *rumor* queries work
against real Vespa.
