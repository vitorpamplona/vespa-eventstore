# vespa-eventstore

A [Vespa](https://vespa.ai)-backed [Quartz](https://github.com/vitorpamplona/amethyst) `IEventStore` with **trust-ranked NIP-50 search**.

Drop it in wherever a Quartz app needs an event store that scales past SQLite and
comes with good full-text search out of the box — lexical by default, ranked by a
[NIP-85](https://github.com/nostr-protocol/nips/blob/master/85.md) web of trust when
you have the data for it.

This is the storage engine extracted from [SoT](https://github.com/vitorpamplona/sot)
(Search over Trust). SoT is the reference application — a relay + trust-sync service —
built on this library.

## What you get

- **A Vespa `IEventStore`.** One copy of the data: Vespa *is* the store, not a
  separate index to keep in sync. Full Nostr semantics — NIP-01 filters, NIP-09
  deletion + tombstones, NIP-40 expiry, NIP-62 vanish, replaceable/addressable
  supersession — plus a batched bulk-ingest fast path and negentropy snapshots.
- **NIP-50 search that's good with zero configuration.** A banded BM25 relevance
  (match-quality tiers, IDF weighting, trigram typo-recall) ranks results with no
  trust data at all.
- **Trust ranking when you want it.** Feed [NIP-85](https://github.com/nostr-protocol/nips/blob/master/85.md)
  kind-30382/10040 events like any other event and searches can rank by the
  searcher's web of trust — no extra API, the ranking projection updates itself on
  every insert.

## Quick start

Vespa is a prerequisite, like a database — stand one up, then point the store at it.

```kotlin
dependencies {
    implementation("com.vitorpamplona.quartz.eventstore:store:0.1.0")
    testImplementation(testFixtures("com.vitorpamplona.quartz.eventstore:vespa:0.1.0"))
}
```

Released to Maven Central. For a commit snapshot, JitPack works too:
`com.github.vitorpamplona.vespa-eventstore:store:<commit>`.

```kotlin
import com.vitorpamplona.quartz.eventstore.store.VespaEventStores

// Connects, and on a fresh Vespa deploys the bundled schema (autoDeploy, default on).
VespaEventStores.open("http://localhost:8080").use { store ->

    // Store anything — signed events OR unsigned rumors. The store never verifies
    // signatures (many Nostr events are rumors); verify at your ingress if you need to.
    store.insert(event)
    store.batchInsert(events)          // bulk fast path

    // Plain NIP-01 filter — newest first.
    store.query<Event>(Filter(kinds = listOf(1), authors = listOf(pk)))

    // NIP-50 search — pure-text relevance, no trust needed.
    store.query<Event>(Filter(kinds = listOf(0), search = "vitor"))

    // Trust-ranked — just name the observer lens in the search string.
    store.query<Event>(Filter(kinds = listOf(0), search = "vitor observer:<64-hex>"))
}
```

### The NIP-50 search grammar

Extensions travel inside the `search` string:

| Token | Effect |
|---|---|
| `observer:<64-hex>` | Rank through this pubkey's web of trust. Absent ⇒ pure-text relevance, and every trust token below quietly no-ops. |
| `sort:rank` / `sort:rank:asc` / `sort:followers` | Trust-order within match tiers. |
| `sort:text` | Force pure-text relevance. |
| `filter:rank:gte:N` / `filter:rank:gt:N` | Keep only authors the observer trusts ≥ N (0–100 scale). |
| `include:spam` | Turn off the default trust floor that a trust-ranked query applies. |

## Two things to know

- **The store never verifies signatures.** It stores whatever you hand it — signed
  events *and* unsigned rumors (NIP-59 inner events, drafts). Verifying signed
  network input is the caller's job, at ingress.
- **The schema ships with the code.** The Vespa application package is bundled into
  the `:vespa` jar and `open(autoDeploy = true)` deploys it to a fresh Vespa on first
  run — so the schema and the query builder can never drift. An operator who owns
  deployment out of band can pass `autoDeploy = false`.

## Modules

- **`:vespa`** — document shapes (`EventDoc`), `EventQuery` → YQL, the Vespa clients
  (`VespaEventIndex`), the bundled application package, and testFixtures
  (`InMemoryEventIndex`, `MockVespaEngine`) so you can unit-test with no Vespa running.
- **`:store`** — Quartz `IEventStore` semantics, the trust projection, and the
  `VespaEventStores.open` front door.

## Build

```bash
./gradlew build     # compile + tests + spotlessCheck
```

Kotlin 2.4 / JDK 21. Quartz comes from JitPack, pinned by commit in
`gradle/libs.versions.toml`.

## Releasing

Publishing uses the [vanniktech Maven Publish](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin (the same one Quartz ships to Central with).

- **CI** (`.github/workflows/build.yml`) runs `./gradlew build` on every push and PR to `main`.
- **Release** (`.github/workflows/create-release.yml`) publishes to Maven Central on a
  `v*` tag via `./gradlew publishAllPublicationsToMavenCentral`. It needs four repo secrets:
  `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `SIGNING_PRIVATE_KEY` (armored GPG key),
  `SIGNING_PASSWORD`.

Bump the version in `gradle/libs.versions.toml` (`app`), tag `vX.Y.Z`, and push the tag.

## License

MIT © Vitor Pamplona
