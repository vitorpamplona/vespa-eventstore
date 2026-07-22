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
import kotlinx.coroutines.runBlocking
import kotlin.system.measureNanoTime

/**
 * The raw-passthrough A/B against a REAL Vespa (already running at [VESPA_URL],
 * default localhost:8080; the schema is auto-deployed on open). For the
 * LIST-shaped REQs a relay actually serves, it times the WHOLE read path a relay
 * runs — recall THEN serialize every hit to the wire JSON the client receives —
 * two ways:
 *
 *  - OLD: `query<Event>()` -> `Event.toJson()` per hit. Every hit's tag string is
 *    parsed into per-tag objects, an EventDoc/Event is built, then re-serialized
 *    straight back to JSON.
 *  - NEW: `rawQuery()` -> `RawEvent.appendJsonObjectTo()` per hit. The stored tag
 *    string rides through verbatim; nothing is parsed or rebuilt.
 *
 * Both paths must emit the same events (asserted per shape by id order), so this
 * is an honest apples-to-apples of the serialization tax the raw path removes —
 * the tax that scales with result size and dominates the HTTP read.
 *
 * Env: VESPA_URL, RAWAB_SIZE (corpus, default 40000), RAWAB_REPS (default 120).
 */
object RawPassthroughAb {
    @JvmStatic
    fun main(args: Array<String>) {
        val url = System.getenv("VESPA_URL") ?: "http://localhost:8080"
        val size = System.getenv("RAWAB_SIZE")?.toInt() ?: 40_000
        val reps = System.getenv("RAWAB_REPS")?.toInt() ?: 120

        VespaEventStore.open(url = url, autoDeploy = true).use { store ->
            val corpus = NostrCorpus.generate(NostrCorpus.Config(size = size, seed = 42))
            println("ingesting ${corpus.size} events into $url ...")
            runBlocking { corpus.chunked(1_000).forEach { store.batchInsert(it) } }
            val w = BenchWorkload.from(corpus)

            println("\n== raw-passthrough A/B (corpus ${corpus.size}, reps $reps) ==")
            println(String.format("%-18s %10s %11s %11s %8s %9s", "shape", "Σresults", "old µs", "new µs", "faster", "KB wire"))

            // A point lookup (win should be negligible) plus the list REQs where
            // result size is large enough for the serialization tax to matter.
            ab(store, "author-timeline", reps, w) { i -> Filter(authors = listOf(w.author(i)), limit = 50) }
            ab(store, "follow-feed(a300)", reps, w) { i -> Filter(authors = w.authorList(i, 300), kinds = listOf(1, 6, 7), limit = 500) }
            ab(store, "tag-list(p100)", reps, w) { i -> Filter(kinds = listOf(1, 7), tags = mapOf("p" to w.pList(i, 100)), limit = 300) }
            ab(store, "contact-sync(a100)", reps, w) { i -> Filter(authors = w.authorList(i, 100), kinds = listOf(0, 3, 10002), limit = 300) }
        }
    }

    /** Time OLD vs NEW for one filter shape over [reps] reps, assert identical recall, print the row. */
    private fun ab(
        store: VespaEventStore,
        name: String,
        reps: Int,
        w: BenchWorkload,
        filter: (Int) -> Filter,
    ) = runBlocking {
        // Correctness: the two paths must return the SAME events in the SAME order,
        // or the timing compares different work.
        val f0 = filter(0)
        val oldIds = store.query<Event>(f0).map { it.id }
        val newIds = mutableListOf<String>()
        store.rawQuery(listOf(f0)) { newIds.add(it.id) }
        check(oldIds == newIds) { "$name: raw recall diverged from query()" }

        val warm = (reps / 10).coerceIn(1, 20)
        repeat(warm) { i ->
            serializeOld(store, filter(i))
            serializeNew(store, filter(i))
        }

        var bytes = 0L
        val oldNanos = measureNanoTime { repeat(reps) { i -> bytes = serializeOld(store, filter(i)).length.toLong() } }
        val newNanos = measureNanoTime { repeat(reps) { i -> serializeNew(store, filter(i)) } }
        val results = store.query<Event>(f0).size.toLong()

        val oldUs = oldNanos / 1000.0 / reps
        val newUs = newNanos / 1000.0 / reps
        println(String.format("%-18s %10d %11.1f %11.1f %7.2fx %9.1f", name, results, oldUs, newUs, oldUs / newUs, bytes / 1000.0))
    }

    /** The relay OLD read path: recall as parsed events, then serialize each to wire JSON. */
    private suspend fun serializeOld(
        store: VespaEventStore,
        filter: Filter,
    ): StringBuilder {
        val sb = StringBuilder(1 shl 16)
        store.query<Event>(filter).forEach { sb.append(it.toJson()) }
        return sb
    }

    /** The relay NEW read path: recall raw, splice each stored tag string straight to the wire. */
    private suspend fun serializeNew(
        store: VespaEventStore,
        filter: Filter,
    ): StringBuilder {
        val sb = StringBuilder(1 shl 16)
        store.rawQuery(listOf(filter)) { it.appendJsonObjectTo(sb) }
        return sb
    }
}
