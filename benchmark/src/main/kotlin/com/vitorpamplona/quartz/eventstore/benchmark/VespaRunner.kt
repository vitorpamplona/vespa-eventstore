/*
 * Copyright (c) 2026 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.eventstore.benchmark

import com.vitorpamplona.quartz.eventstore.store.VespaEventStore
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.measureNanoTime

/**
 * Real-Vespa runner. A live Vespa is a SHARED store (unlike the in-process SQLite
 * and in-memory backends, which are disposable per call), so this path never
 * re-inserts the same ids into it: it ingests the corpus once, queries what it
 * ingested, and measures per-event `insert()` on an id-disjoint second corpus
 * that lands on top of the loaded one — a per-event probe against a realistically
 * populated index, no clearing required.
 *
 * `open(url)` (autoDeploy on) deploys the bundled schema on first contact; this
 * then polls until the container actually serves queries before timing anything.
 */
object VespaRunner {
    fun run(
        url: String,
        corpus: List<Event>,
        batch: Int,
        queries: Int,
        seed: Long,
    ) = runBlocking {
        println("\n" + "=".repeat(72))
        println("REAL VESPA @ $url")
        println("=".repeat(72))

        // Fast-iteration flags. For a CLIENT-side change (EventYql, summary fields)
        // the stored data is unchanged, so BENCH_VESPA_SKIP_INGEST reuses the
        // already-loaded corpus. BENCH_VESPA_SKIP_PEREVENT leaves the store exactly
        // equal to the corpus (no id-band-2 extras), so parity stays valid across
        // repeated query-only runs. A SCHEMA change still needs a fresh ingest.
        val skipIngest = System.getenv("BENCH_VESPA_SKIP_INGEST") != null
        val skipPerEvent = System.getenv("BENCH_VESPA_SKIP_PEREVENT") != null

        val store: IEventStore = VespaEventStore.open(url)
        try {
            awaitServing(store)

            // --- bulk ingest (the path the framework is built around) ---
            if (!skipIngest) {
                println("ingesting ${corpus.size} events via batchInsert($batch) ...")
                val ingestNanos =
                    measureNanoTime {
                        runBlocking { corpus.chunked(batch).forEach { store.batchInsert(it) } }
                    }
                report("batchInsert($batch)", corpus.size, ingestNanos)
                // Let the write path settle so counts/queries see the full corpus.
                delay(1_000)
            } else {
                println("(skipping ingest — reusing the loaded corpus)")
            }
            println("store now reports count(all) ≈ ${runCatching { store.count(Filter()) }.getOrDefault(-1)}")

            // Correctness gate the user asked for: real Vespa must answer every
            // NIP-01 filter exactly as Quartz's SQLite store does. Load a SQLite
            // store with the SAME corpus in-process and diff every result set.
            println("\n-- parity: real Vespa vs SQLite (NIP-01 correctness) --")
            val sqlite = Backends.sqliteMemory()
            corpus.chunked(1_000).forEach { sqlite.batchInsert(it) }
            ParityCheck.report("Vespa", "SQLite", ParityCheck.run(corpus, "SQLite", sqlite, "Vespa", store))
            sqlite.close()

            // --- queries over the loaded corpus ---
            println("\n-- queries (BM25 search is real here, not a substring scan) --")
            val w = workloadFrom(corpus)
            Header.query()
            timeQuery("author-timeline", queries) { i -> store.query<Event>(Filter(authors = listOf(w.a(i)), limit = 50)) }
            timeQuery("kind-scan(notes)", queries) { store.query<Event>(Filter(kinds = listOf(1), limit = 200)) }
            timeQuery("tag-mentions(p)", queries) { i -> store.query<Event>(Filter(kinds = listOf(1), tags = mapOf("p" to listOf(w.a(i))), limit = 50)) }
            timeQuery("id-lookup", queries) { i -> store.query<Event>(Filter(ids = listOf(w.id(i)))) }
            timeQuery("profile(kind0)", queries) { i -> store.query<Event>(Filter(kinds = listOf(0), authors = listOf(w.a(i)), limit = 1)) }
            timeQuery("count(reactions)", queries) { store.count(Filter(kinds = listOf(7))) }
            timeQuery("nip50-search", queries) { i -> store.query<Event>(Filter(kinds = listOf(1), search = w.term(i), limit = 50)) }

            // Concurrent throughput — the metric a relay lives on (target 50k events/sec).
            if (System.getenv("BENCH_THROUGHPUT") != null) {
                val conc = (System.getenv("BENCH_CONCURRENCY") ?: "1,8,32,64,128").split(",").mapNotNull { it.trim().toIntOrNull() }
                val durMs = System.getenv("BENCH_DURATION_MS")?.toLongOrNull() ?: 4_000L
                ThroughputBench.run(store, corpus, conc, durMs)
            }

            // --- per-event insert() on an id-disjoint corpus (no dedup collisions) ---
            // Skipped in query-only iteration so the store stays == corpus for parity.
            if (!skipPerEvent) {
                val extra = NostrCorpus.generate(NostrCorpus.Config(size = (corpus.size / 10).coerceIn(1_000, 5_000), seed = seed + 1, idBand = 0x2L))
                println("\nper-event insert() of ${extra.size} fresh events onto the loaded index ...")
                val singleNanos =
                    measureNanoTime {
                        runBlocking { for (e in extra) runCatching { store.insert(e) } }
                    }
                report("insert()", extra.size, singleNanos)
            }
        } finally {
            store.close()
        }
    }

    /** Poll a trivial query until the freshly-deployed container answers (deploy activate precedes serving). */
    private suspend fun awaitServing(store: IEventStore) {
        repeat(60) {
            if (runCatching { store.count(Filter(kinds = listOf(0), limit = 1)) }.isSuccess) {
                println("vespa serving.")
                return
            }
            delay(2_000)
        }
        println("WARNING: vespa did not confirm serving in 120s; proceeding anyway.")
    }

    private class W(
        val authors: List<String>,
        val ids: List<String>,
        val terms: List<String>,
    ) {
        fun a(i: Int) = authors[i % authors.size]

        fun id(i: Int) = ids[i % ids.size]

        fun term(i: Int) = terms[i % terms.size]
    }

    private fun workloadFrom(corpus: List<Event>) =
        W(
            corpus.map { it.pubKey }.distinct().shuffled(kotlin.random.Random(1)).take(500).ifEmpty { listOf("a".repeat(64)) },
            corpus.map { it.id }.shuffled(kotlin.random.Random(2)).take(500).ifEmpty { listOf("0".repeat(64)) },
            "nostr bitcoin coffee freedom relay privacy vespa trust".split(" "),
        )

    private inline fun timeQuery(
        name: String,
        reps: Int,
        crossinline op: suspend (Int) -> Any?,
    ) = runBlocking {
        repeat((reps / 10).coerceIn(1, 50)) { runCatching { op(it) } }
        var checksum = 0L
        val nanos =
            measureNanoTime {
                runBlocking {
                    repeat(reps) { i ->
                        val r = runCatching { op(i) }.getOrNull()
                        if (r is Collection<*>) checksum += r.size
                    }
                }
            }
        val secs = nanos / 1e9
        println(String.format("%-20s %10d %14s %12.2f %10d", name, reps, num(reps / secs), nanos / 1000.0 / reps, checksum))
    }

    private fun report(
        op: String,
        events: Int,
        nanos: Long,
    ) {
        val secs = nanos / 1e9
        println(String.format("%-24s %10d events  %14s events/sec  %10.1f µs/event", op, events, num(events / secs), nanos / 1000.0 / events))
    }

    private object Header {
        fun query() = println(String.format("%-20s %10s %14s %12s %10s", "query", "reps", "queries/sec", "µs/query", "Σresults"))
    }

    private fun num(v: Double) = if (v >= 1000) String.format("%,d", v.toLong()) else String.format("%.1f", v)
}
