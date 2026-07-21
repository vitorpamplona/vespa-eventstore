# Server-side constraints in Vespa (vs SQLite triggers) — what the insert probes can and cannot shed

The per-event `insert()` path pays ~3.26 engine reads per event (dup probe,
NIP-09 tombstone probe, NIP-62 vanish probe, supersession probe — the first
three now run concurrently, so ~2 round trips of latency per insert). SQLite
pays none of that as *communication*: its UNIQUE constraints and triggers run
in-process, and Quartz's store enforces admission inside the same address
space. The question: does Vespa have constraint/trigger equivalents that move
these checks server-side?

Vespa has **no triggers and no cross-document constraints** — but it has three
mechanisms that between them cover most of the admission logic. Mapping:

| SQLite mechanism | Admission rule | Vespa equivalent | Status |
|---|---|---|---|
| `expires_at` sweep (client) | NIP-40 expiry | **GC selection** (`selection="event.expires_at > now()"`) | **SHIPPED** — validated live |
| `UNIQUE (id)` | duplicate insert | docid identity + **test-and-set** insert-if-absent | possible, 1 RTT, design below |
| `UNIQUE (kind, pubkey[, d_tag])` + replace | replaceable/addressable supersession | **address-keyed docid + test-and-set condition** | design below |
| trigger-like module (`DeletionRequestModule`) | NIP-09 tombstone block | **document processor** (custom Java in the container) | roadmap |
| trigger-like module (`RightToVanishModule`) | NIP-62 vanish block | **document processor** | roadmap |

## 1. SHIPPED: NIP-40 expiry as a garbage-collection selection

`services.xml` now declares:

```xml
<documents garbage-collection="true">
  <document type="event" mode="index" selection="event.expires_at &gt; now()" />
```

The engine itself periodically (default hourly) removes every event whose
`expires_at` has passed — a standing server-side constraint, no client
involvement. Validated on the live engine: with a 45s test interval, an
already-expired probe document was removed by Vespa within one cycle while a
never-expiring document (expires_at = Long.MAX_VALUE, always written) stayed.

The store's own guards remain, deliberately:
- the **read-time filter** (`expires_at > now`) stays because expiry must be
  exact-to-the-second and GC is periodic;
- the **insert-time block** on already-expired events stays because it costs
  no round trip — the expiration tag is on the event being inserted;
- the store's explicit sweep becomes a redundancy rather than the only
  mechanism — and the engine now also self-cleans events that expire while no
  store instance is running.

## 2. Test-and-set: single-document conditions (Vespa's CHECK constraint)

Every Vespa write (put/update/remove) accepts a `condition` — a selection
evaluated **server-side against the existing document with the same docid**,
atomically. Two admission rules fit inside it:

- **Insert-if-absent (the dup probe).** An update with `create=true` and
  condition `false` creates the document when absent and fails with
  `TestAndSetFailed` when present — one round trip that both writes and
  reports "duplicate", replacing the separate `get`.
- **Supersession, if replaceables were keyed by address.** Today every doc's
  id is the event id, so replacing kind 0/3/10002/3xxxx requires a search for
  the incumbent + a remove + a put. If replaceable/addressable events used
  `kind:pubkey[:dtag]` as their **docid**, supersession would become a single
  conditional put: `condition = "event.created_at < X or (event.created_at ==
  X and event.id > 'newid')"` — the NIP-01 newest-wins rule with the lexical
  tiebreak, enforced atomically by the engine, older-version inserts rejected
  for free. Cost: a docid-scheme split (regular events by id, replaceables by
  address), which touches dedup, deletion-by-id, and reconstruction — a real
  refactor, worth it only if replaceable churn ever dominates ingest (it is
  ~9% of the corpus mix today, and the batch path already amortizes it).

The limitation that shapes everything: **a condition can only see the one
document it addresses.** No condition can ask "does a kind-5 by this author
target this id" — that is a cross-document question.

## 3. Document processors: the actual trigger equivalent (roadmap)

Vespa's container runs user Java on the **write path**: a `DocumentProcessor`
chain sees every fed document before it reaches the content node, can look
other documents up (`DocumentAccess` is injectable; searches are available via
the same container's search chains), can mutate, and can **reject**. That is
where the NIP-09/NIP-62 admission belongs if it should leave the client:

- the client's `insert()` becomes one round trip (the put);
- the tombstone/vanish/dup lookups still happen, but as **container-local**
  hops (sub-ms, same box or same cluster network) instead of client round
  trips, and centrally — every feeder gets the semantics, not just this
  library.

Cost and why it is roadmap rather than shipped: the admission logic
(`NostrEventStore`'s guard reads and precedence rules) would need a port to a
container bundle (Java, deployed inside the app package), CI that stands up
the container with the bundle, and a versioning story between the bundle and
this library. The measured ceiling it attacks: per-event insert round trips
2 → 1 client-side (the bulk `batchInsert` path is already at 0.05
round-trips/event and would not benefit).

## What this does NOT change

The batch path already solved this problem for bulk ingest (chunked guard
reads amortized across 500 events). These mechanisms matter for the
*per-event* path — a relay accepting one EVENT at a time from a websocket —
where the guard-read round trip is the latency floor. Order of value:
GC selection (shipped, free) → test-and-set dup fold (small, contained) →
address-keyed replaceables (refactor, modest win) → docproc admission (big
lever, big project).
