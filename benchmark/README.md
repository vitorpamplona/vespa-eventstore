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
hundred-k events on one box, Quartz's SQLite store is faster and simpler —
**except for list-shaped filters (next section), where Vespa already wins.**

### 3b. List-shaped REQs — where Vespa beats SQLite head-on

Every query above uses a single-value filter, but real relay traffic is
dominated by **list-shaped** REQs: a follow-feed carries the observer's whole
contact list as `authors`, a thread fetch carries dozens of `ids`, a
notification subscription carries a wide `#p` value list. The suites (latency,
real-Vespa, concurrent, parity) all run four such shapes, drawn from a shared
`BenchWorkload` whose value windows rotate per rep so no single hot list is
re-served from cache:

| query (per rep) | SQLite (mem) q/s | Real Vespa (1 node) q/s |
|---|---:|---:|
| follow-feed — 300 authors, kinds 1/6/7, limit 500 | 27.9 | **66.8** |
| contact-sync — 100 authors, kinds 0/3/10002, limit 300 | **161** | 138 |
| tag-list — `#p` ×100, kinds 1/7, limit 300 | **331** | 101 |
| ids-set — 100 ids | **1,376** | 182 |

(4-core box, client and Vespa sharing cores, 30k corpus, seed 42 — same-run
pairs, so compare across the row, not against the earlier tables.)

**The follow-feed — THE relay workload, every connected client holds one open —
is the first shape where this store beats in-process SQLite outright (2.4×) at
30k events on a single node.** A 300-author `IN` plus a kind list plus
newest-first is exactly what an inverted index with fast-search attributes is
built for, while SQLite falls off its point-lookup cliff (52k q/s single-author
→ 28 q/s at 300 authors, a ~2,000× drop). The id-set and tag-list shapes stay
with SQLite at this corpus size (no network hop), same as the point lookups.

Under concurrency the follow-feed is the **fastest bulk shape in the whole
bench** — events/sec at rising client counts (same box, shared cores):

| query (events/query) | conc 1 | conc 32 | conc 128 | bytes/event |
|---|---:|---:|---:|---:|
| follow-feed(a300) (500) | 35,255 | **116,951** | 101,443 | ~5,900 |
| kind-scan (200) | 26,652 | 90,080 | 89,493 | ~4,700 |
| ids-set(100) (100) | 18,555 | 67,926 | 70,854 | ~7,900 |

Bulk list reads clear the 50k events/sec target by >2× at concurrency 32.

## Scale study: 30k → 300k (10×) — the crossover starts moving

One run at `BENCH_SIZE=300000` (same box, fresh engine, parity **127/127 on
both engines at 300k**), read against the 30k numbers. The pattern is stark:
**every SQLite advantage shrank, every Vespa advantage grew** — because
SQLite's costs scale with the table while Vespa's are dominated by
size-independent fixed costs at this range.

**SQLite degradation, 30k → 300k (its own numbers):**

| path | 30k | 300k | change |
|---|---:|---:|---|
| per-event `insert()` (at scale, memory) | 6,216 ev/s | 507 ev/s | **−12×** |
| per-event `insert()` (at scale, disk) | 5,041 ev/s | 571 ev/s | **−9×** |
| `batchInsert` (memory) | 6,912 ev/s | 1,238 ev/s | −5.6× |
| author-timeline | 522 q/s | 69.9 q/s | **−7.5×** |
| NIP-50 search (FTS5) | 51.5 q/s | 5.9 q/s | **−8.7×** |
| count(reactions) | 5,637 q/s | 648 q/s | −8.7× |
| id-lookup | 54,453 q/s | 35,888 q/s | −34% |
| kind-scan / follow-feed | 1,733 / 27.9 | 1,702 / 25.4 | ~flat |

(disk `batchInsert` stays ~36k ev/s — WAL amortization scales; the per-event
admission SELECTs against a growing B-tree are what collapse.)

**Vespa over the same growth: essentially flat.** Ingest 718 → **1,419 ev/s**
(chunk-1000; size-independent), per-event insert 117 → 112 ev/s, duplicates
311 → 372 ev/s, follow-feed 66.8 → 64.6 q/s, contact-sync 130 → 142,
concurrent follow-feed **126k events/sec at conc 32** (higher than at 30k).
The one real Vespa degradation is NIP-50 search, 81 → 20 q/s — ranking runs
over a 10× match set — which **re-opens the two-phase ranking study**: its
precondition ("a corpus large enough for the win to appear") is now met.

**Head-to-head at 300k (single node):**

| shape | SQLite (mem) | Real Vespa | winner |
|---|---:|---:|---|
| NIP-50 search | 5.9 | **20.4** | Vespa 3.5× |
| follow-feed(a300) | 25.4 | **64.6** | Vespa 2.5× |
| author-timeline | 69.9 | **152.9** | Vespa 2.2× |
| contact-sync(a100) | 135.7 | 141.9 | even |
| batch ingest (memory) | 1,238 | **1,419** | Vespa |
| count(reactions) | 648 | 134 | SQLite (shrinking) |
| tag-list(p100) | 286 | 87.5 | SQLite (shrinking) |
| id-lookup / profile / kind-scan / ids-set | 35.9k / 42.9k / 1.7k / 919 | 470 / 369 / 76 / 143 | SQLite |

Read: at 300k the Vespa store already wins **search, feeds, timelines, and
sustained ingest** — the workloads a relay lives on — while SQLite keeps
point lookups and flat scans (fixed-cost-bound on Vespa's side, still
index-walk-cheap on SQLite's). Extrapolating the curves, more shapes flip by
~1M. The practical crossover is therefore much earlier than "a few
hundred-k events": **by 300k, a feed/search-serving relay is already better
off on this store**; a pure key-value cache of events is not.

`results-300k-reference.json` in this module is the machine-readable run
(this box), usable with `compare_results.py` as a relative reference.

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

Steady-state, single 4-core node that the benchmark client *shares*, ~15k-doc
corpus, `numthreadspersearch=1`:

| query (events/query) | events/sec @128 | bytes/event |
|---|---:|---:|
| kind-scan (200) | **~66,000–76,000** | ~9,200 |
| author-timeline (50) | ~27,000 | ~12,800 |
| id-lookup (1) | ~6,000 q/s | ~49,000 |

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

### NIP-50 search: two-phase ranking studied, not shipped

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
