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
Add `BENCH_THROUGHPUT=1` (with `BENCH_CONCURRENCY=1,8,32`) for the concurrent
read table (§3b), `BENCH_CONCURRENT_INGEST=1` (with `BENCH_CI_SIZE` /
`BENCH_CI_CONC`) for the concurrent-writer A/B (§2b), and `BENCH_SCALE_CURVE=1`
(with `BENCH_CURVE_SIZES=…`) for the [scale study](#scale-study-25k--400k).

## Results

Measured on a 4-core / 16 GB Linux box, JDK 21, corpus = 30,000 events
(seed 42), single-node Vespa 8.x in Docker. Relative numbers matter more than
absolutes; re-run on your own hardware. Kind mix per run is printed at the top
(~53% notes, ~26% reactions, plus reposts, metadata/contacts/relay-lists that
supersede, deletions, and addressable long-form).

### 1. Round-trip amplification — the headline finding

| path | reads/event | round-trips/event |
|---|---|---|
| `insert()` | 1.30 | **2.34** |
| `batchInsert()` | 0.05 | **0.05** |

**The bulk path issues ≈47× fewer engine round-trips per event.** The per-event
path pays, per event, up to four reads before the write — a dup probe (`get`), a
NIP-09 deletion probe (`search`), a NIP-62 vanish probe (`search`), and a
replaceable/addressable supersession probe (`search`) — averaging ~1.3 reads
across this corpus (most kind-1 notes skip the supersession/guard probes).
`batchInsert()` reads the whole working set in a handful of chunked queries and
pipelines the writes, amortizing all of it to ~0.05 round-trips/event.

This is engine-independent, and it dominates real-Vespa ingest (below).

### 2. Insert throughput (events/sec)

| backend | `insert()` | `batchInsert(500)` |
|---|---:|---:|
| SQLite (memory) | 14,844 | 18,388 |
| SQLite (disk) | 9,597 | **36,116** |
| Real Vespa (1 node) | **109** | **765** |
| Vespa/InMemory ref\* | 11,213 | 16,534 |

- **On-disk SQLite batching is ~3.8×** (9.6k → 36k): the transaction amortizes
  `fsync`. In-memory SQLite gains less (~1.2×; no `fsync` to amortize).
- **Real Vespa: `insert()` = 109/s vs `batchInsert()` = 765/s — a ~7× wall-clock
  gap on one node**, exactly the round-trip fan-out showing up as latency (each
  round-trip is a ~4–9 ms HTTP hop; the per-event path's ~2.3 of them ≈ 9 ms/event).
  On a real multi-node cluster the feed client pipelines much further, widening
  the gap. **Rule: never ingest into Vespa via per-event `insert()`; always
  `batchInsert`.**

### 2b. Concurrent-writer ingest (`BENCH_CONCURRENT_INGEST=1`)

The write-side mirror of §3b, and geode's `publishThroughputConcurrent`: N
publishers each `batchInsert` a disjoint corpus slice at once (60k events/level,
id-disjoint per level), aggregate events/sec as N rises. The hypothesis was that
SQLite's **single writer** would bottleneck and this store's lock-free feed
client would scale past it. **It doesn't — on one box SQLite wins outright:**

| writers | SQLite ev/s | Vespa ev/s | SQLite p50 | Vespa p50 |
|---:|---:|---:|---:|---:|
| 1 | 10,881 | 670 | 42 ms | 544 ms |
| 4 | **13,362** | 1,047 | 151 ms | 1,774 ms |
| 8 | 12,471 | 978 | 310 ms | 3,932 ms |
| 16 | 12,351 | 1,066 | 614 ms | 6,927 ms |
| 32 | 11,432 | 1,109 | 1,337 ms | 13,875 ms |

Both results run against the hypothesis, and both are worth knowing:

- **SQLite's single writer is not the bottleneck it looks like.** Aggregate EPS
  holds ~11–13k across every writer count (peaks at 4), **0 rejects** — concurrent
  batches are *coalesced by group commit* behind the lock, not blocked. More
  writers only raise per-batch latency linearly (42 → 1,337 ms) while total
  throughput stays flat. In-memory SQLite ingest is just fast.
- **The Vespa store barely scales with publishers (1.66× from 1→32) and is ~10×
  slower in absolute terms.** Two reasons: (1) the feed client *already*
  pipelines a single stream — hundreds of writes in flight over HTTP/2 — so one
  `batchInsert` already saturates what this container's proton can absorb
  (~700–1,100 EPS), leaving little for external concurrency to add; (2) one
  shared-core container is **proton-bound, not lock-bound**, so latency balloons
  (p50 13.9 s at 32 writers) as every batch queues against that fixed ceiling.

So on a single box SQLite out-ingests single-node Vespa ~10× whether writers are
serial or concurrent — consistent with §2 and the ingest curve, and with geode's
own choice of SQLite + group commit for a single-box relay. Vespa's write scaling
is a **multi-node** property (proton sharded across content nodes); it does not
show on one container, and the feed client already extracts the available
single-node concurrency from one stream. **Net: concurrent publishers are not a
reason to pick single-node Vespa; corpus size, read concurrency at scale, and
search relevance are.**

### 3. Query throughput (queries/sec; higher is better)

| query | SQLite (mem) | Real Vespa (1 node) |
|---|---:|---:|
| profile (kind 0) | **48,759** | 391 |
| tag-mentions (`#p`) | **12,297** | 331 |
| id-lookup (16 ids) | **6,686** | 98 |
| count(reactions) | 4,924 | 343 |
| kind-scan (notes, limit 200) | 1,393 | 83 |
| author-timeline (limit 50) | 581 | 220 |
| NIP-50 search | **132** | 68 |

**At 30k events on a single node, SQLite wins every single-query shape.** That is
expected and important: SQLite is an in-process embedded DB with no network hop,
so a point read (`profile`) is ~49k/s. Single-node Vespa pays a 4–9 ms HTTP
round-trip per query, and this box runs Vespa as one container sharing cores with
the client, so its absolute numbers are conservative. **Vespa's value is not
single-node small-corpus latency** — it is concurrency (next section),
horizontal scale, corpus sizes past SQLite's ceiling (the [scale
study](#scale-study-25k--400k) — author timelines cross ~70k, search ~280k), and
search **relevance quality** (trust-ranked BM25, trigram typo-recall). At this
scale the new SQLite FTS5 is even ~2× faster on NIP-50 search — the workload the
Vespa store exists to do *better* by ranking, once trust data and scale enter.

Practical read: **don't reach for Vespa until you outgrow SQLite.** Below a
hundred-k events on one box, Quartz's SQLite store is faster and simpler; the
Vespa store earns its keep under **concurrent load** (next) and **at scale**.

### 3b. List-shaped REQs and concurrency — where Vespa's parallelism shows

Every query above uses a single-value filter, but real relay traffic is
dominated by **list-shaped** REQs: a follow-feed carries the observer's whole
contact list as `authors`, a thread fetch carries dozens of `ids`, a
notification subscription carries a wide `#p` value list. The suites (latency,
real-Vespa, concurrent, parity) all run four such shapes, drawn from a shared
`BenchWorkload` whose value windows rotate per rep so no single hot list is
re-served from cache:

| query (per rep) | SQLite (mem) q/s | Real Vespa (1 node) q/s |
|---|---:|---:|
| follow-feed — 300 authors, kinds 1/6/7, limit 500 | **30.7** | 15.2 |
| contact-sync — 100 authors, kinds 0/3/10002, limit 300 | **171** | 97 |
| tag-list — `#p` ×100, kinds 1/7, limit 300 | **127** | 62 |
| ids-set — 100 ids | **1,330** | 115 |

(client and Vespa sharing cores, 30k corpus, seed 42 — same-run pairs, so compare
across the row, not against the earlier tables. Vespa is a single shared-core
container here, so single-query latency is its worst case.)

**Single-query at 30k, SQLite wins the list shapes too** — no network hop beats
one HTTP round-trip, even on a 300-author `IN`. But a relay never serves one
subscription at a time: **every connected client holds a follow-feed open**, and
that is where Vespa's HTTP/2 parallelism earns its place. Driving the real Vespa
store from rising client counts (events/sec, Vespa-only, same box):

| query (events/query) | conc 1 | conc 8 | conc 32 | bytes/event |
|---|---:|---:|---:|---:|
| follow-feed(a300) (500) | 7,506 | 72,971 | **78,265** | ~5,900 |
| kind-scan (200) | 16,872 | 60,901 | 62,596 | ~4,600 |
| ids-set(100) (100) | 13,475 | 47,212 | 47,931 | ~6,900 |

The follow-feed goes from 15 q/s single-threaded to **78k events/sec at
concurrency 32** — clearing the 50k events/sec relay target by >1.5× — because
each of the 32 in-flight REQs overlaps the others' round-trips over one
multiplexed HTTP/2 connection. That parallelism, not single-query latency, is
the read-side case for the Vespa store; SQLite's in-process reads don't overlap
the same way.

## Scale study: 25k → 400k

The full curves — five metrics × five checkpoints, both engines — are in
[`docs/scale-curve.html`](../docs/scale-curve.html) (interactive, log–log). One
prefix-stable seed-42 corpus was delta-ingested into both engines and measured
at each checkpoint (`BENCH_SCALE_CURVE`, this box; Vespa a single shared-core
container, so its absolute numbers are conservative — the *shape* is the point).
Parity holds **127/127** on both engines at every checkpoint.

The pattern: **SQLite's multi-row read shapes erode with the table while Vespa
stays flat**, so the two engines cross as the corpus grows.

| metric (q/s unless noted) | 25k | 50k | 100k | 200k | 400k | crossover |
|---|---:|---:|---:|---:|---:|---|
| author-timeline — SQLite | 599 | 303 | 155 | 51.5 | 25.5 | **≈ 70k** |
| author-timeline — Vespa | 176 | 280 | 290 | 255 | 242 | |
| NIP-50 search — SQLite | 162 | 65.6 | 40.4 | 16.1 | 9.6 | **≈ 140k** |
| NIP-50 search — Vespa | 42.2 | 48.8 | 32.2 | 19.2 | 12.5 | |
| follow-feed(a300) — SQLite | 49.8 | 40.9 | 36.9 | 23.3 | 23.6 | none ≤400k |
| follow-feed(a300) — Vespa | 14.3 | 14.9 | 15.0 | 14.8 | 15.3 | (gap narrows) |
| id-lookup(16) — SQLite | 5,318 | 5,117 | 5,721 | 3,854 | 5,146 | none (~50×) |
| id-lookup(16) — Vespa | 81 | 101 | 100 | 107 | 106 | |
| batch ingest ev/s — SQLite | 15,950 | 16,456 | 16,743 | 15,157 | 11,947 | none (8–24×) |
| batch ingest ev/s — Vespa | 661 | 1,490 | 1,635 | 1,715 | 1,424 | |

Read:

- **Author timelines cross ≈70k.** SQLite falls 23× (599 → 26 q/s) as the
  single-author index walk lengthens; Vespa's fast-search attribute holds ~260
  q/s flat. Past ~70k, Vespa is the faster engine for this shape.
- **NIP-50 search crosses ≈140k.** SQLite's FTS5 decays 162 → 9.6 q/s over the
  growing match set; Vespa flattens and overtakes. (Both fall in absolute terms;
  Vespa just falls less — trust-ranked relevance is the reason to run it here,
  not raw speed.)
- **follow-feed, id-lookup(16), and ingest never cross ≤400k on this box.**
  SQLite's in-process reads pay no HTTP hop, and this single-node container's
  follow-feed sits at ~15 q/s single-threaded. The follow-feed gap narrows
  (SQLite 50 → 24, Vespa flat ~15) but does not close — its real Vespa win is
  **concurrency**, not single-query throughput (§3b: 78k events/sec at conc 32).

Extrapolating the eroding SQLite curves, more shapes flip past ~1M. Practical
read: **a search/timeline-serving relay starts to benefit from this store in the
low hundreds-of-thousands of events; a point-lookup or small-corpus cache stays
better on SQLite.** The [store-migration A/B](#new-sqlite-store-quartz-72b8df7f6d-same-box-ab)
below isolates what amethyst PR #3663 changed in SQLite itself (old pin
`0d534a3149` → `72b8df7f6d`), run twice on this box with Vespa as the control.

Runs written with `BENCH_JSON=` are machine-readable and diff with
`compare_results.py` as a relative reference.

## New SQLite store (Quartz `72b8df7f6d`): same-box A/B

The bump from `0d534a3149` → `72b8df7f6d` pulls in amethyst PR #3663
("SQLite query scaling: FTS contentless, tag-merge, pooled statements"). To
attribute its effect **without** cross-box noise, the scale curve
(`BENCH_SCALE_CURVE`) was run **twice on this same box, back to back** — once
per Quartz pin, identical prefix-stable corpus, seed 42. **Vespa's store code
is unchanged between the two runs, so its curve is the box control**: Vespa
ingest matched to within 8% across every checkpoint (e.g. 400k: 1,177 → 1,174
ev/s), confirming the box did not drift — so every SQLite delta below is the
Quartz change, not the hardware.

**SQLite, old → new Quartz, same box (events/sec ingest; q/s queries):**

| shape (SQLite mem) | 25k | 50k | 100k | 200k | 400k | net |
|---|---:|---:|---:|---:|---:|---|
| **batch ingest** | 6,380→**11,165** | 3,775→**12,590** | 1,684→**12,181** | 769→**10,475** | 306→**8,912** | **+75% → +2,815% (29× at 400k)** |
| **NIP-50 search** | 47.8→**114.1** | 27.2→**55.5** | 13.1→**29.5** | 6.5→**15.5** | 3.4→**7.0** | **~2.2× at every size** |
| author-timeline | 347→450 | 263→275 | 97→81 | 42→44 | 24→22 | flat (±noise) |
| follow-feed(a300) | 33→36 | 34→32 | 25→25 | 19→18 | 16→16 | flat |
| id-lookup | 22.7k→25.2k | 30.3k→35.4k | 32.2k→24.8k | 28.8k→32.3k | 25.5k→29.6k | flat (noisy) |

**Two curves moved, three did not:**

- **Batch ingest no longer collapses.** The old store fell **21×** across this
  range (6,380 → 306 ev/s) as the table grew; the new store *rises then holds*
  at **~9–12k ev/s** (a shallow −20% 50k→400k). This is the FTS-contentless
  win: every `batchInsert` that supersedes a replaceable event or applies a
  NIP-09 deletion issued FTS5 deletes that were **O(n) column scans** on the
  old contentless-less table; the new schema seeks by rowid (**O(log n)**), so
  the per-batch delete cost stops scaling with the corpus. It is the single
  biggest change the bump makes.
- **NIP-50 (FTS5) search is ~2.2× faster at every scale** (400k: 3.4 → 7.0
  q/s). The contentless index is smaller and hotter in page cache; BM25 scoring
  still grows with the match set, so the *slope* is unchanged — the whole curve
  just lifts ~2.2×.
- **author-timeline, follow-feed, id-lookup: unchanged** (every point within
  the ±8–15% run noise this box shows). The tag-merge heap executor and pooled
  statements did not move the single-author, author-list, or point-id paths at
  these scales.

The **per-event `insert()`** path (not on the curve, but in the 30k and 300k
full suites) tells the same ingest story from the admission side: it degrades
**−3.8× memory / −1.7× disk** across 30k → 300k on this box (11,555 → 3,029 and
7,838 → 4,577 ev/s), versus the old store's documented **−12× / −9×**. In
absolute terms at 300k the new per-event insert is **3,029 vs the old
reference's 507 ev/s (≈6×)** — so the README's old headline "SQLite per-event
insert collapses 9–12× by 300k" no longer holds; the same O(log n) FTS deletes
that fix batch ingest also stop the per-event admission SELECTs from collapsing.

The **current SQLite-vs-Vespa crossovers** are the fresh
[scale study](#scale-study-25k--400k) above — author timelines ≈70k, NIP-50
search ≈140k, and feeds / point-reads / ingest never crossing ≤400k on this box.
Those supersede an older `BENCH_SIZE=300000` head-to-head that used to sit here:
this box runs its single-node Vespa container slower than the earlier reference
box, so the absolute Vespa figures shifted and are no longer directly
comparable. The box-**independent** result is the SQLite-side change measured
above (Vespa held as the control): sustained ingest stops collapsing, and FTS5
search lifts ~2.2× at every size.

So the new store **buys SQLite headroom on exactly the two axes it used to lose
first at scale — sustained ingest and full-text search.** A relay that ingests
heavily or serves search stays on SQLite meaningfully longer than the old
"~150k ingest / ~40k search" crossovers implied; the feed and timeline
crossovers are set by the fresh curve above.

> **tag-list caveat.** The `tag-list(p100)` shape (100 `#p` values, the query
> the tag-merge executor targets) is **not** on the scale curve, so it has no
> clean same-box A/B here. In the 30k suite the new store measured **90 q/s**
> vs the old README's 331 (a *cross-box* figure). The same-box control for the
> closest list shape — `follow-feed(a300)` — is unchanged, so part of that drop
> is box difference; but tag-merge does rewrite the tag path specifically, so a
> real small-corpus cost (it opens one cursor per value and heap-merges, paying
> per-stream overhead that only amortizes on large tag sets / large corpora) is
> plausible and **unconfirmed same-box**. Flagged honestly rather than claimed.

Regenerated graph: `docs/scale-curve.html` (both engines, this box, new
Quartz). Machine-readable curves: `results-curve-reference.csv` is now the
**new-Quartz** baseline; the old-Quartz curve is preserved in git history
(commit that added it) for anyone reproducing the A/B.

## Query-path optimizations (applied)

Profiling the live Vespa (via `presentation.timing` and payload sizing) showed
per-query latency is dominated by **server-side matching plus fixed overhead**
(2–7 ms) and, for large result sets, **payload transfer + client-side parse**;
network RTT and summary-fetch are small. Single-node throughput is also very
noisy (±15–50 % run to run — even a query whose code never changed swings that
much), so only structural changes are reliably attributable. Two landed:

1. **Trim the returned summary to the reconstruction fields** (`EventYql`): a
   served event needs only `id/pubkey/created_at/kind/tags/content/sig/owner`, so
   the query selects those instead of `*`. That drops the BM25 index fields —
   `search_text` (a full copy of note content), `name`, `about`, the `_gram`
   views — from every hit: **~35 % fewer bytes** on a 200-hit note scan, more on
   long-form content.
2. **Pure-id lookups use the document API's direct key get** (`VespaEventIndex`)
   instead of a `/search/` over the id attribute: **≈35–55 % faster per id**
   (raw `get` 3.8 ms vs search-by-id 5.9 ms). Capped at one concurrent get wave
   so the bulk-insert dedup preload (500-id chunks) stays on the single-search
   path and **ingest is unaffected**.

3. **Multi-value tag lists compile to the `in` operator** (`EventYql`): a NIP-01
   tag filter with N values used to build an OR-chain of `tag_index contains`;
   it now builds `tag_index in ("p:a", "p:b", …)`. `tag_index` is a fast-search
   attribute, so `in` resolves the whole list through one dictionary-backed
   iterator where the OR tree paid a per-term iterator plus the merge. A/B on
   the live corpus (same values, both forms, identical hit counts — semantics
   are equal; `MockYql` parses the new grammar so the wire tests still pin it):

   | `#p` values | or-chain ms | `in` ms | speedup |
   |---:|---:|---:|---:|
   | 10 | 6.6 | 5.6 | 1.17× |
   | 50 | 12.6 | 9.8 | 1.29× |
   | 100 | 13.2 | 10.7 | 1.23× |
   | 200 | 24.7 | 10.6 | **2.32×** |

   The `in` form is essentially flat in list width; the OR-chain grows
   linearly. Single values and `tagsAll` (AND semantics — no `in` form) keep
   `contains`.

Everything is gated by the parity harness (now **127/127**, including the
list-shaped specs) and the vespa test suite. Measured id-lookup gain from (2):
**+34–65 %** across runs. Server-side matching and the NIP-50 ranking cost are
inherent to the engine and were left alone (search relevance is a feature, not
overhead to cut).

The in-memory reference engine also compiles a query's list constraints to hash
sets once per scan (was per-doc linear membership — a 300-author filter over a
30k corpus was ~9M string compares per query). Same semantics; it just stops
punishing exactly the list shapes under study, in the benchmark and in every
store test that runs on the reference.

## Latency tails (p50/p95/p99 — now on every row)

Every suite records per-operation latency and prints the tail next to the
throughput number. Two immediate findings on the 30k corpus:

- **Single-stream tails are tight**: p99 runs 1.4–1.6× p50 across the query
  shapes (follow-feed p50 14.4ms → p99 22.3ms) — no pathological outliers on
  a quiet store.
- **Concurrency buys throughput with queueing, and the tail pays**: at
  concurrency 128 author-timeline reaches 1,892 q/s but p99 hits 117ms
  (vs 41.5ms at concurrency 32 for only ~26% less throughput); kind-scan's
  p99 reaches 448ms at 128. **For latency-sensitive serving the sweet spot on
  this box is concurrency ~32–64** — beyond it, added clients mostly deepen
  the queue. This is exactly the trade `numthreadspersearch=1` makes
  (throughput over per-query latency), now visible per shape.

## Bulk-ingest sweep (`BENCH_INGEST_SWEEP=1`) — chunk × streams

Single-node ingest is throttle-floor-bound, so which knob raises it —
bigger `batchInsert` chunks or more parallel streams? The grid (fresh
id-band events per cell, real store stack, per-chunk commit tails):

| chunk | 1 stream | 2 streams | 4 streams |
|---:|---:|---:|---:|
| 250 | 688 | 1,169 | 1,241 |
| 500 | 1,294 | 1,704 | 1,573 |
| 1000 | 1,719 | **1,868** | **1,947** |
| 2000 | 1,642 | 1,665 | 1,865 |

(events/sec; single 4-core node, shared with the client.)

**Both knobs matter, and they compound: chunk 1000 × 2 streams ≈ 1,850–1,950
events/sec — ~45% over the 500×1 default and 2.8× over small 250-event
chunks.** The curve plateaus at ~1,900–2,000 (the engine/throttle ceiling on
this box); 2,000-event chunks add nothing and double the per-chunk commit
latency (p50 ~1.2s). Practical rule for syncers: **feed `batchInsert` in
~1,000-event chunks from ~2 parallel streams**; going wider or larger only
deepens latency. (Parallel streams are safe: the store's guard/plan reads
overlap outside the writer lock, commits serialize behind it — and if the
streams are split by owner, this is exactly the multi-lane ingest shape from
docs/multi-node-consistency.md.)

## Ingest stage profile (`BENCH_INGEST_PROFILE=1`) — where the ceiling actually is

The profile mode books each `batchInsert`'s wall time into the store's own
stage counters and prints the split plus the feed-client gauge. On the live
400k corpus (batch 1000): **the write stage — the pipelined `putAll` awaiting
engine acks — is 69% of wall time** (10.2s of ~15s per 20k events); the read
stages (dedup, guards, versions) barely register. Average per-put latency
inside a burst is ~56ms versus ~6ms for a lone put: the puts queue.

The obvious suspect — the feed client's throttle window — was then swept and
**acquitted**: connections 32→64, streams 128→256, and every combination
land within noise (1,306–1,322 ev/s, write stage unchanged), and more
connections only raise per-put latency (57→92ms) while throughput holds.
That is the signature of a server-side ceiling, and `docker stats` confirms
it: during ingest the engine runs ~1.5 cores while the client JVM
(serialization + guard reads) takes much of the rest — the shared 4-core box
is the bottleneck, not the client pipeline.

Practical conclusions:

- **Client-side ingest tuning is exhausted.** Chunk ~1,000 × 2 streams (the
  sweep's sweet spot) is the ceiling this box allows; no feed-window knob
  moves it. The knobs are now overridable anyway
  (`VESPA_FEED_CONNECTIONS` / `VESPA_FEED_STREAMS` /
  `VESPA_FEED_INFLIGHT_FACTOR`) for hosts with more cores, where the
  defaults' small-core compromise no longer applies.
- **The remaining ingest levers are topology**, exactly as docs/scaling.md
  lays out: separate client hardware (stop sharing cores with proton), more
  engine cores, owner-lane parallel writers, multi-node. The fbench study
  reached the same conclusion for reads.

## Mixed read/write load (`BENCH_MIXED=1`)

A relay never serves REQs from a quiet store — queries arrive while syncs
feed it. `MixedLoadBench` measures the interference directly: a read-only
baseline (16 readers looping follow-feed + author-timeline), a write-only
baseline (2 writers streaming `batchInsert(500)` of fresh id-band-disjoint
events), then both at once, then reads again immediately after. Measured on
the 4-core box (client and Vespa sharing cores), ~35k-doc corpus:

| mode | ingest ev/s | query q/s | query ev/s |
|---|---:|---:|---:|
| read-only | — | 449 | 64,443 |
| write-only | 802 | — | — |
| **mixed** | **556 (−31%)** | **191 (−57%)** | **30,704 (−52%)** |
| post-ingest reads | — | 458 | 65,997 |

Both sides pay for concurrency: ingest keeps ~70% of its throughput, queries
keep ~half. Recovery is immediate — the post-ingest read pass is back at the
baseline, so the "flush shadow" seen after a full 30k ingest does not appear
at this write volume. On this box the interference is largely CPU contention
(three processes on four cores); separate client hardware would soften it,
which is exactly what this bench exists to verify per deployment.

## Schema & index study (vs SQLite's indexes) — A/B'd on live Vespa

A review of the `event.sd` schema against Quartz's SQLite indexing, with each
candidate change A/B-tested on a live single-node Vespa: fresh container per
variant, the identical 30,793-doc corpus fed raw both times, the same
rotating-window query shapes timed, and a **repeated baseline (v0 run twice)**
to calibrate run noise (±8–9% on this class of box — anything inside that band
is not a result).

### Index coverage: SQLite ↔ this schema

| SQLite index (`EventIndexesModule`) | Vespa equivalent |
|---|---|
| `UNIQUE (id)` | `id` fast-search attribute |
| `(created_at DESC, id ASC)` | `created_at` fast-search attribute (+ `order by`) |
| `(pubkey, created_at DESC)` | `pubkey` fast-search attribute |
| `(kind, created_at DESC)` | `kind` fast-search attribute |
| `(kind, pubkey, created_at DESC)` | posting-list intersection of the two |
| `event_tags (tag_hash[, kind, pubkey_hash], created_at DESC)` | `tag_index` fast-search array |
| `pubkey_owner_hash` | `owner` fast-search attribute |

Coverage is complete. The structural difference: SQLite's composite indexes
end in `created_at DESC` so newest-first order falls out of the index walk;
Vespa intersects independent per-field posting lists, then sorts on the
`created_at` attribute. Every filter the store issues hits a fast-search
attribute — there are no unindexed scans.

### CORRECTNESS: attribute matching was case-insensitive (now `match: cased`)

Probing the live engine showed Vespa string attributes match **uncased by
default**: a stored `t:MixedCase` matched `#t:["mixedcase"]` — and even a
query for `T:MIXEDCASE`. NIP-01 tag values compare by exact bytes and SQLite's
`tag_hash` is case-sensitive, so this was a real-Vespa-only spec divergence
(the same class as the empty-`sig` bug: the in-memory reference and the mock
compare strings exactly, and the old all-lowercase corpus could never catch
it). Fixed with `match: cased` on `id`/`pubkey`/`owner`/`tag_index`; the
corpus now gives ~20% of notes a Capitalized hashtag and the parity battery
asserts the exact value matches while the lowercased form matches **nothing**,
on both stores — so the gate holds on every future run.

### `dictionary: hash` (shipped) and `rank: filter` (rejected)

These four fields are only ever equality/`in`-matched — never range- or
prefix-scanned — so the default B-tree term dictionary buys nothing; `hash`
makes each term lookup O(1) in the number of unique terms (which grows with
every event: each id is unique). Cased match is a prerequisite, so the
correctness fix and this optimization ship together. Measured **at 30k docs:
noise-level** (B-tree depth is tiny; follow-feed −3%, ids-set −6%, both inside
the band) with **no cost** — feed rate unchanged (721 vs 710–727 docs/s). The
win this buys is scale headroom, priced at zero.

`rank: filter` on the filter attributes (compact rank-free posting lists) was
the other candidate: **no read-side win beyond noise, and the two variants
carrying it produced the two slowest feeds** (648 and 521 docs/s vs 710–727)
— a write-side cost with no benefit at any tested shape. Not shipped.

| variant | follow-feed | ids-set | tag-list | contact-sync | feed docs/s |
|---|---:|---:|---:|---:|---:|
| v0 baseline | 25.3 ms | 11.7 ms | 19.4 ms | 9.1 ms | 727 |
| v0 repeat (noise) | 25.2 ms | 11.2 ms | 22.6 ms | 10.8 ms | 710 |
| v1 hash-dict + cased | 24.5 ms | 11.0 ms | 19.6 ms | 9.0 ms | 721 |
| v2 rank:filter | 30.6 ms | 11.8 ms | 17.6 ms | 11.0 ms | 648 |
| v3 both | 26.0 ms | 11.6 ms | 28.3 ms | 9.0 ms | 521 |

### Is the store reading more from disk than the filters need?

The question was whether the many non-filter columns (search fields, grams)
slow queries down by inflating what is read per hit. How Vespa actually lays
this out:

- **Matching and sorting never touch the disk blob.** Every field the filters
  use is a fast-search attribute — in-memory posting lists and value arrays.
  The extra columns are irrelevant to match cost.
- **Summary fill reads the whole document blob per hit** from the document
  store (disk, page-cached), no matter how few fields the query selects: a
  document's fields are stored together in one compressed chunk. The
  `SUMMARY_FIELDS` select-list trim cuts network transfer and client parse
  (~35% fewer bytes measured), not the disk read itself.
- The blob does carry dead weight on the read path: `search_text` duplicates
  each note's `content`, and the profile/tier fields double a kind-0. Roughly
  2× blob size for notes. Shrinking it would mean deriving the search fields
  inside the schema (synthetic fields) — impossible here because extraction is
  per-kind Kotlin logic — or splitting search fields into a separate document
  type (a real option at scale, at the cost of dual writes). At benchmark
  scale the blobs sit in page cache and summary fill is CPU-bound decompress +
  copy, so this is documented as the known trade, not changed.

## Concurrent throughput and GC (`BENCH_THROUGHPUT=1`)

The latency suite issues one query at a time, so its throughput is capped by a
single round trip (~250 q/s). A relay's load is *concurrent*, so `ThroughputBench`
drives the store from N coroutines for a fixed duration and reports events/sec,
queries/sec, GC time, and **bytes allocated per event** (from the JVM beans) at
rising concurrency — the real metric, and the GC lens.

The current headline concurrency numbers are in [§3b](#3b-list-shaped-reqs-and-concurrency--where-vespas-parallelism-shows)
(fresh 30k run, conc 1/8/32 — follow-feed clears the 50k target at conc 32).
The figures below are a deeper GC-focused run at higher concurrency (@128) on a
~15k-doc corpus, `numthreadspersearch=1`, kept for the allocation findings:

| query (events/query) | events/sec @128 | bytes/event |
|---|---:|---:|
| kind-scan (200) | **~66,000–76,000** | ~9,200 |
| author-timeline (50) | ~27,000 | ~12,800 |
| id-lookup (16 ids) | ~6,000 q/s | ~49,000 |

**Bulk reads clear the 50k events/sec target**, and because the client and Vespa
are fighting over the same 4 cores here, separate hardware would go higher.
Throughput measured immediately after a heavy ingest drops (~48k) while proton
flushes in the background — the steady-state number is the representative one.

**How much higher on separate hardware?** `vespa-fbench` run *inside* the
container (no JVM client parsing on the same cores) isolates Vespa's own ceiling:

| query | Vespa Q/s | events/sec | JVM-client events/sec |
|---|---:|---:|---:|
| kind-scan (200) | 527 | **~105,000** | ~78,000 |
| author-timeline (50) | 1,578 | **~79,000** | ~24,000 |

QPS plateaus (16→64 clients) while p99 climbs 50→185 ms — Vespa is CPU-bound, not
thread-starved. The **author-timeline 24k → 79k gap proves the client-side
response parse/reconstruction was the shared-host limiter**, which is exactly what
the GC work below attacks.

### GC findings

Two optimizations roughly **halved per-event allocation on bulk reads**
(kind-scan 9,230 → 4,843 bytes/event, author-timeline 12,800 → 8,479):

- **Trimmed summary fields (above) directly cut allocation** — fewer response
  bytes to parse per hit.
- **`getByIds` short-circuits the single-id REQ** past the `mapBounded` fan-out
  (coroutineScope + Semaphore + async all allocate per call).
- **Direct reconstruction via Quartz's `EventFactory.create`** (applied): the
  store used to rebuild every result with `Event.fromJson(toEventJson())` — a
  serialize+parse round trip whose only purpose is to recover the typed subclass.
  `toEvent()` now calls Quartz's own by-kind factory straight from the stored
  fields — no JSON — which covers every kind and returns a base `Event` for
  unknown ones. Reconstruction alone, microbenchmarked over 40k events:
  **313 ns / 120 bytes per event, vs 5,317 ns / 3,892 bytes for the round trip
  (~17× faster, ~32× less garbage)**. `EventReconstructionTest` pins the factory
  to `fromJson`'s exact subclass and serialization across kinds. This cut
  kind-scan end-to-end from ~9,200 toward ~6,000 bytes/event.
- **Streaming response decode** (applied): the recall path parsed each response
  into a full immutable `JsonElement` tree, then walked it. It now decodes hits
  straight into flat `@Serializable` DTOs (allocating the target objects, not a
  wrapper per field); the count/grouping paths still build the tree. `get()`
  stays lossless (the DTO carries the search columns too). Measured on top of the
  above: **kind-scan 6,181 → 4,843 (−22%)**, author-timeline 9,887 → 8,479 (−14%).
- Remaining floor for tiny results (id-lookup ~45 KB/event): with the tree gone,
  what's left is the JDK HttpClient's per-request allocation plus the query
  objects — inherent to a 1-doc round trip, not JSON.

### Insert-side latency

The per-event `insert()` path ran its three independent admission reads — dedup,
NIP-09 tombstone, NIP-62 vanish — strictly in series. They now fire concurrently
and are checked in the original precedence, so a per-event insert pays one round
trip's latency for the guards instead of three (the bulk `batchInsert` path,
which already batches these, is untouched). All 135 store+vespa tests, including
the full NIP-09/40/62 semantics suite, still pass.

### Vespa config studied

`numthreadspersearch=1` (one matching thread per query): for concurrent serving
this keeps cores saturated with *independent* queries instead of splitting one
query across cores. Measured: bulk throughput ~66k → ~76k events/sec and it kept
scaling to concurrency 128 instead of plateauing. Config-only, so results/parity
are unchanged. A latency-critical (few big queries) deployment
would raise it instead.

### NIP-50 two-phase ranking: MEASURED at 489k — real but modest; not the default

The corpus-size precondition from the study below was finally met (489k docs;
"nostr" matches 100k), so the two-phase profile was built and gated properly:
`text2` in the schema (phase 1 = `relevance()` minus its two expensive
within-band tie-breakers — band-exact by construction; phase 2 = the full
tuned `relevance()` over the top 1,000 per node, `ranking.rerankCount`
overridable per query), judged by the new `BENCH_RANK_QUALITY` harness
(overlap@10/50 + Kendall-τ vs the single-phase ground truth, per term class,
with latency tails).

**Quality: PASSED.** Every single-word class — common, rare, short — is
IDENTICAL (overlap 1.00, τ 1.00). Multi-word terms show exactly the predicted
proximity effect at the window edge: 2 of 4 terms at overlap@10 0.80 with
τ ≥ 0.95 and membership ≥ 0.86@50. (The typo class matched nothing on kind-1
content — its gram net covers profile/title fields, so that class needs
kind-0 queries to bite.)

**Performance: MISSED the ≥2× bar.** Consistent wins — 14 of 16 terms faster,
median ~10–25%, best −29% ("bitcoin" 135.6 → 95.7ms p50), multi-word tails
tightened most (p99 320 → 270ms) — but nowhere near 2×. The instructive
finding is WHY: search at this scale is **match-bound, not rank-bound**. The
first phase still pays full matching (weakAnd + gram OR-trees over 100k
postings), and that, not the ranking math, is the floor. The scale curve's
search degradation is therefore mostly *match-set growth*, and the next real
lever would be match-phase approximation — a recall trade, a different and
heavier decision than reranking.

Verdict per the pre-declared criteria: **`text` stays the default**
(single-phase, exact tuned order). `text2` remains in the schema — inert
unless a query names it — so any operator can re-run
`BENCH_RANK_QUALITY=1` on their corpus and flip if a 10–25% median win with
the measured multi-word top-10 drift is the right trade for them. The
harness is permanent: no future ranking change ships without it.

### The original study (30k, 2026-07): two-phase studied, not shipped

Search is the slowest query (ranking-bound: `relevance()` runs over every match —
~2,700 hits for a common term). A two-phase profile (cheap bm25-sum first-phase,
then `relevance()` reranking the top 200) was measured against the current
single-phase `text` profile on the corpus. It **reordered results** — the top-50
overlapped 49–50/50 but the top-10 changed for 4 of 5 terms — while the speedup
was **marginal and noisy at this scale** (6→4 ms one term, 5→7 ms another). So it
trades the tuned relevance for a speedup that only materializes on far larger
match sets. **Not shipped**: it needs a corpus large enough for the win to appear
*and* a relevance-quality regression harness (representative signed data) to
prove the reranking is acceptable — search relevance is a headline feature, not
overhead to cut blind.

## CI correctness gate

`VespaParityIT` (`:benchmark`) stands up a real Vespa via testcontainers, ingests
the corpus, and asserts this store matches Quartz's SQLite store on the whole
NIP-01 battery — the gate the in-memory reference can't provide (only a real
Vespa exercises YQL, the streaming decode, the get fast path, and reconstruction
end to end; the empty-`sig` bug was real-Vespa-only). It is tagged `integration`,
excluded from the fast default build, run by a dedicated CI job with
`-Pintegration`, and self-skips where no Docker daemon is present.

## Correctness parity (SQLite ↔ this store)

The benchmark is also a **correctness gate**: `ParityCheck` loads the identical
corpus into Quartz's SQLite store and this store, then asserts they return the
**same event ids, in the same newest-first order, with the same counts** across a
battery of NIP-01 filters (kind scans, author timelines, id sets, `#p`/`#e` tags,
`since`/`until` windows, replaceable/deletion effects, and the list-shaped
follow-feed / id-set / wide-tag filters). The corpus advances
`created_at` by a positive step per event, so timestamps are distinct and the
newest-first order is a total order — the comparison is exact, not best-effort.
It runs SQLite ↔ in-memory reference always, and SQLite ↔ **real Vespa** when
`BENCH_VESPA_URL` is set. NIP-50 `search` is excluded (relevance ordering is
engine-defined; FTS5 and BM25 legitimately differ).

Current status: **127/127 checks agree** across SQLite, the in-memory reference,
and real Vespa.

### A NIP-09 divergence the parity gate surfaced

Building this harness turned up a spec-compliance difference. NIP-09 says:

> "Publishing a deletion request event against a deletion request has no effect."

Given N (note), D1 (kind 5 deleting N), D2 (kind 5 deleting D1):

- **This store keeps `[D1, D2]`** — D2 targeting the deletion D1 is a no-op, per
  spec. (`NostrEventStore.applyDeletion` skips kind-5/kind-62 targets.)
- **Quartz's SQLite store keeps `[D2]`** — it has no such guard and erases D1,
  **violating NIP-09**. (Confirmed in `sqlite/DeletionRequestModule.kt`: the
  delete-by-id path has no kind-5/62 exclusion.)

So on this edge case **this store is correct and SQLite is not**. The corpus
therefore has its deletions target real *non-deletion* events (what clients
actually delete), keeping the parity gate a clean pass; the divergence is a
SQLite bug to report upstream, not something to "fix" by breaking this store.

## Where to hammer performance (framework optimization targets)

1. **Push ingest onto `batchInsert`.** The single biggest win, already available:
   ~47× fewer round-trips (§1). Any syncer/relay path still calling `insert()` in a
   loop against Vespa is leaving ~7× wall-clock on the table. Consider making the
   per-event `insert()` documentation warn against bulk use, or coalescing bursts.
2. **Trim the per-event read fan-out — DONE (`GuardOwners`).** The store now
   tracks the owners with any stored tombstone/vanish (two grouping queries at
   load, maintained on every guard written, self-disabling at the engine's
   group cap) and skips both admission probes for everyone else; the bulk path
   queries guards only for flagged owners. Measured: **reads/event 3.26 →
   1.73** on the benchmark corpus (whose 4%-deletion mix flags many of its
   400 authors — realistic deleter densities sit near 1.1), round-trips/event
   4.26 → 2.80, parity 127/127. Single-stream insert latency is UNCHANGED
   (counterbalanced A/B, all runs within 2%) — the probes ran concurrently
   with the dup get, so this win is engine READ CAPACITY, not per-op latency:
   it pays under concurrent load, exactly where the mixed bench showed reads
   and writes fighting.
3. **Real-Vespa single-node ingest is COMPUTE-bound, not throttle-bound**
   (see the ingest stage profile above): the feed-window knobs are exhausted;
   chunk ~1000 × 2 streams is this box's ceiling and the remaining levers are
   topology (docs/scaling.md).

## BUG found while benchmarking — now fixed (correctness, real Vespa)

`EventDoc.fromSummary` read `fields.getValue("sig")`, which **throws** when `sig`
is absent. Real Vespa **omits empty-string fields from query summaries**, so any
stored event with `sig == ""` — i.e. **every unsigned rumor**, which this store is
explicitly designed to hold (NIP-59 inner events, drafts) — came back from
`search()` as a dropped hit, and the query returned *nothing*. `count()`
(grouping) is unaffected, which masked it. `MockVespaEngine` never caught this
because it echoes empty fields back verbatim; only a real Vespa omits them.

**Fixed** in this branch — `sig` is now tolerant like `content`/`owner` already
were (`fields["sig"]?.jsonPrimitive?.content ?: ""`), with a regression test in
`EventDocTest` that reconstructs a rumor whose empty `sig` the summary dropped.

The corpus here signs its events (128-hex fake signature) so the benchmark
measures the common signed path; the fix is what makes *rumor* queries work
against real Vespa.
