# Can Vespa be embedded in-process (SQLite-style)? — a study

The question this answers: the per-query decomposition showed a ~3–4 ms fixed
pipeline (HTTP + JVM container dispatch + RPC to the search core) around every
query, versus ~18 µs for an in-process SQLite lookup. **Can that pipeline be
removed by embedding Vespa in the application — even with a hand-made
connection?**

## TL;DR

- **True in-process embedding: no.** Vespa is a multi-process distributed
  system by design; there is no library build of the search core and no
  supported single-process mode. A "minimal" single node runs ~15 cooperating
  processes (measured on this repo's benchmark container: 7 JVMs +
  `vespa-proton`, `vespa-distributor`, `vespa-slobrok`, config server,
  sentinel, `logd`).
- **Skipping the HTTP/container layer: yes, two supported paths.** Document
  operations (get/put/remove/visit) can go over Vespa's internal **message
  bus** from an external JVM — measured here at **2.36 ms/get vs 4.71 ms/get
  for HTTP** in the same process at the same moment (2× faster). And query
  logic can move **into** Vespa's JVM container as a custom component, where
  `execution.search()` reaches the C++ core over the internal protobuf/RPC
  dispatch with no HTTP or JSON at all.
- **The floor stays milliseconds, not microseconds.** Every path keeps a
  process boundary + RPC + per-hit summary work (~1–2 ms). SQLite's 18 µs is
  an address-space property, not a protocol property: no IPC of any kind gets
  a separate C++ daemon to function-call latency.

## Why proton cannot be embedded

`proton` (the C++ search core) is a daemon, not a library:

- It is **configured by subscription**: it boots by pulling its config from a
  config server (which itself embeds ZooKeeper) via the config proxy, and
  registers in `slobrok` (Vespa's service-location broker) so other services
  can find it. None of that machinery is optional or linkable.
- The write path requires the **distributor** (content-layer routing,
  redundancy, bucket management) even with one node and redundancy 1.
- The Vespa FAQ and operations docs describe no embedded/in-process/library
  mode; the minimum documented footprint is the multi-process node above.
  The only in-process piece Vespa ships is the **JDisc container for unit
  tests** (`JDisc.fromServicesXml(xml, Networking.disabled)`) — middleware
  only, no proton, no real index.

## Where the milliseconds actually go (measured, this repo's benchmark box)

| step | cost |
|---|---:|
| HTTP + container dispatch + client parse | ~1.5–2.7 ms |
| container→proton RPC + query setup (matching nothing) | ~1 ms |
| matching (e.g. kind-scan) | ~3–5 ms |
| summary fill, 200 hits | ~5 ms |

The fixed part is what embedding would attack; the matching/summary part
travels with the query regardless.

## The supported no-HTTP paths

### 1. Document API over message bus — from OUR OWN JVM (measured)

`DocumentAccess.createForNonContainer()` (artifact
`com.yahoo.vespa:documentapi`) connects a standalone JVM directly to the
cluster: it reads `VESPA_CONFIG_SOURCES` (config server RPC, port 19070),
finds services via slobrok (19099), and speaks the binary document protocol
over message bus straight to the distributor/proton — **no container, no
HTTP, no JSON**. This is the documented "hand-made connection".

Measured against the loaded 30k-event corpus, same process, back to back:

| path | get (1 doc) | put (sync, acked) |
|---|---:|---:|
| HTTP `/document/v1`, persistent connection | 4.71 ms | — |
| Message bus, `SyncSession` | **2.36 ms** | 6.5 ms |

Sync puts pay one round trip each; an `AsyncSession` pipelines like the HTTP
feed client, so bulk feeding is a throughput wash — the win is per-operation
latency. Requirements: network reach to ports 19070/19099 and the dynamic
19100+ range (same host or an open cluster network), and a client jar roughly
version-matched to the server.

What it maps to in this repo: `EventIndex` is already an interface. A
`DocumentApiEventIndex` could implement `get`/`put`/`putAll`/`remove`/`visit`
over message bus and cut the per-event `insert()` guard-read latency and
id-lookup REQs roughly in half — **`search()` cannot move**, the query
protocol is not exposed to external clients (below).

### 2. Move the logic INTO Vespa's container (custom components)

The JVM container that serves `/search/` also hosts user code: custom
`Searcher`/`RequestHandler` components deployed in the application package.
Inside a component, `execution.search(query)` goes down the internal
dispatch — protobuf over Vespa's own RPC — to proton, and `DocumentAccess` is
**injectable** for writes. That deletes the HTTP+JSON edge entirely (~1.5–2.7
ms of the floor) while staying fully supported and upgrade-safe.

Limits: the container has no WebSocket support, so a Nostr relay itself can't
live there — the shape would be relay ⇄ (compact API) ⇄ custom handler ⇄
proton, i.e. you relocate the tax to a cheaper protocol rather than remove
it. Best reserved for hot query shapes if a same-box relay needs the last
factor of 2–3.

### 3. Hand-rolling the query protocol — possible in principle, a trap

The container↔proton search path is protobuf messages over `fnet` (Vespa's
custom RPC framing), plus a separate summary-fetch protocol, plus
config-subscribed cluster state to know where to send anything. It is
internal, undocumented, and version-skewed by design (the container and
proton upgrade in lockstep). Reimplementing it in Kotlin would work until the
next server upgrade and is the one option this study recommends against
outright.

## What none of this changes

An in-process SQLite lookup is a function call into pages already mapped into
your address space. Every Vespa path above still crosses a process boundary,
serializes an operation, and schedules threads in two processes — that is the
~1–2 ms floor, and it is the price of the things the embedded library cannot
do: an index that lives on other hardware, scales horizontally, and ranks.
The practical stance for this store stays what the benchmark README says:
SQLite below a few hundred-k events on one box; this store when scale,
list-shaped recall, or ranked search is the workload — and if per-op latency
on a same-box deployment ever matters, path 1 is the measured, supported
lever.
