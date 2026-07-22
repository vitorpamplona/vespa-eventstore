# container-store bundle — the in-container store, deployed (Phase B/1)

Packages the `:container-store` module as an OSGi bundle and runs it inside
Vespa's jdisc container, so the store's read path executes **in-process**
(`Execution.search()`) instead of over HTTP. This is the "embedded" option from
"keep both options open": the same `EventIndex` API, either pointed at a Vespa
URL (HTTP) or run inside the container (local).

Unlike the Phase-A `container-bench` spike (which measured a lightweight
field-access searcher), this bundle embeds the **real** closure — `store`,
`vespa`, Quartz `@ jvmTarget=17`, kotlin stdlib/coroutines/serialization,
okhttp — and drives the actual `VespaLocalEventIndex` + `EventYql` +
`EventDoc.fromSummary` code the HTTP store uses. Parity here means the embedded
path is answering NIP-01 filters with the same code, not a stand-in.

## What's in the bundle
- `LocalStoreSearcher` — entry point + A/B harness. On `?localstore=1` it runs
  the same `EventQuery` two ways (in-process `VespaLocalEventIndex` and loopback
  HTTP `VespaEventIndex`), compares the id lists, reports both mean latencies.
- `VespaLocalEventIndex` — `EventIndex` over `Execution`: builds `EventYql`,
  issues a fresh sub-query, `execution.search()` + `fill()`, reconstructs
  `EventDoc.fromSummary` from the hit fields. Read path only (writes stay on the
  feed client; Phase B/2).
- The full Java-17 runtime closure (57 embedded jars, ~44 MB), wired with an
  OSGi `Bundle-ClassPath` + `DynamicImport-Package: *` — the recipe the
  quartz-in-OSGi spike proved loads cleanly under Felix.

## Run (needs docker + a running Vespa, JDK 17+, a loaded corpus)
```bash
# from repo root, with a `vespa` container up and a corpus ingested:
container-store/bundle/deploy.sh      # build bundle + deploy the `local` chain
python3 container-store/bundle/driver.py 80
```
`deploy.sh` injects a `local` search chain into a TEMP copy of `vespa/app`, so
the tracked `services.xml` stays clean. `build-bundle.sh` re-jars the three
in-repo modules on every build so the bundle always ships current code.

## Measured — in-container NIP-01 parity, Vespa 8.727, 7.5k-doc corpus

`parity=OK` means `VespaLocalEventIndex` (in-process) and the HTTP store return
the **identical id list** for that filter. Stable across 3 consecutive runs on
every shape a relay serves:

| shape            | hits | parity | local vs http (representative) |
|------------------|-----:|:------:|:-------------------------------|
| id-lookup        |    1 |   OK   | ~1.1–1.5× (both tiny)          |
| author-timeline  |   16 |   OK   | ~2.5–4.9×                      |
| follow-feed(20)  |  300 |   OK   | ~2.5× (one 0.96× noise sample) |
| kind-scan        |  200 |   OK   | ~2.0–2.4×                      |
| tag-mentions     |   15 |   OK   | ~1.8–2.6×                      |
| id-batch(20)     |   20 |   OK   | ~8–9×                          |

**Correctness is the deliverable here, and it holds: the embedded store answers
every NIP-01 shape identically to the HTTP store.** The latency column is
directional only — unlike the clean external-client A/B in `container-bench`,
here *both* sides run inside the same busy container JVM and the HTTP baseline
gets fewer warmup reps, so absolute numbers are noisy. For a clean latency
figure the external-vs-in-container A/B in `container-bench` (reads ~2.3–3.4×,
scale-validated to 100k) is the reference; this harness exists to prove the
*real* code path is correct in-container, which it is.

## Gotchas baked into the recipe
- `jvmTarget = 17` on `:store`/`:vespa`/`:container-store` and a Java-17 Quartz
  (amethyst `52ce590505`) — Vespa 8.727's container runs Java 17; Java-21
  bytecode fails with `UnsupportedClassVersionError`.
- `DynamicImport-Package: *` + `Bundle-ClassPath` embedding — resolves the
  container APIs while keeping the whole store closure self-contained (no
  split-package clashes with the platform's jackson/BouncyCastle).
- `@Before(PhaseNames.TRANSFORMED_QUERY)` on `LocalStoreSearcher` — so the fresh
  YQL sub-queries the local index builds are parsed by the downstream chain
  (verified empirically: hits > 0, parity OK).
- Its own `local` chain (a failed searcher fails the whole component graph).

## Not done (Phase B/2)
- Write path as a `DocumentProcessor` (dedup/supersession/NIP-09/62 internal).
- Nostr REQ/EVENT `RequestHandler` + a Nostr-wire `Renderer`.
- `EventYql` grouping `count()` in-container (currently counts the capped recall
  set — exact for the parity battery, not yet for unbounded counts).
