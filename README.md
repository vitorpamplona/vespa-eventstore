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
import com.vitorpamplona.quartz.eventstore.store.VespaEventStore

// Connects, and on a fresh Vespa deploys the bundled schema (autoDeploy, default on).
VespaEventStore.open("http://localhost:8080").use { store ->

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
  `VespaEventStore.open` front door.

## Developer Setup

Make sure to have the following pre-requisites installed:
1. Java 21+ (JDK 21)
2. IntelliJ IDEA CE or Android Studio

Kotlin 2.4 / JDK 21. Quartz comes from JitPack, pinned by commit in
`gradle/libs.versions.toml`.

On first build the project installs the repo's git hooks (`.git-hooks` →
`.git/hooks`) so [Spotless](https://github.com/diffplug/spotless) runs on commit
and the tests run on push, mirroring Amethyst.

## Building

Build the library (compile + tests + `spotlessCheck`):
```bash
./gradlew build
```

Fix formatting the linter flags:
```bash
./gradlew spotlessApply
```

## Testing

Run the full test suite:
```bash
./gradlew test
```

The `:vespa` testFixtures (`InMemoryEventIndex`, `MockVespaEngine`) let the tests
run with no Vespa instance up, so `./gradlew test` needs nothing external.

## Publishing

Publishing uses the [vanniktech Maven Publish](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin (the same one Quartz ships to Central with).

Install GnuPG and generate a key:

```bash
gpg --gen-key
```

Run `gpg --list-keys` to show your GPG keys.

Distribute the public key:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <pubkey>
```

Export your private key to a file:

```bash
gpg --export-secret-keys > ~/.gnupg/secring.gpg
```

Generate a User Token on Maven Central.

To publish from local, add the following fields to your `~/.gradle/gradle.properties` file:

```properties
mavenCentralUsername=<maven user>
mavenCentralPassword=<maven password>
signing.keyId=<gpg key id>
signing.password=<gpg key passphrase>
signing.secretKeyRingFile=<yourhome>/.gnupg/secring.gpg
```

Then run:

```bash
./gradlew publishAllPublicationsToMavenCentral --no-configuration-cache
```

To publish from GitHub Actions, export your private key as a base64 string:

```bash
gpg --export-secret-keys --armor <key-id> ~/.gnupg/secring.gpg | grep -v '\-\-' | grep -v '^=.' | tr -d '\n'
```

and add the following secrets to your GitHub secrets:

```properties
SONATYPE_USERNAME=<maven user>
SONATYPE_PASSWORD=<maven password>
SIGNING_PRIVATE_KEY=<base64versionOfTheFile>
SIGNING_PASSWORD=<gpg key passphrase>
```

- **CI** (`.github/workflows/build.yml`) runs `./gradlew build` on every push and PR to `main`.
- **Release** (`.github/workflows/create-release.yml`) publishes to Maven Central on a
  `v*` tag via `./gradlew publishAllPublicationsToMavenCentral`.

Bump the version in `gradle/libs.versions.toml` (`app`), then just tag the release
version starting with `v` (`vX.Y.Z`) and push the tag.

## Contributing

Issues can be logged on [GitHub issues](https://github.com/vitorpamplona/vespa-eventstore/issues). [Pull requests](https://github.com/vitorpamplona/vespa-eventstore/pulls) are very welcome.

By contributing to this repository, you agree to license your work under the MIT license. Any work contributed where you are not the original author must contain its license header with the original author(s) and source.

# Contributors

<a align="center" href="https://github.com/vitorpamplona/vespa-eventstore/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=vitorpamplona/vespa-eventstore" />
</a>

# MIT License

<pre>
Copyright (c) 2026 Vitor Pamplona

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
</pre>
