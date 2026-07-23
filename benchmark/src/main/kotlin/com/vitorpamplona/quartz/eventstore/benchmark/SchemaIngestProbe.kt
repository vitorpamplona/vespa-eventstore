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

import com.vitorpamplona.quartz.eventstore.vespa.client.VespaEventIndex
import com.vitorpamplona.quartz.eventstore.vespa.doc.EventDoc
import kotlinx.coroutines.runBlocking
import kotlin.system.measureNanoTime

/**
 * Pure index-write probe (`BENCH_SCHEMA_INGEST=1`): feed N documents straight
 * through [VespaEventIndex] (`putAll`), bypassing all store logic (no dedup /
 * supersession / deletion probes), against whatever schema is currently deployed
 * at `VESPA_URL`. Reports feed events/sec.
 *
 * Run it once per deployed schema variant (full vs summary-only) to isolate how
 * much of Vespa's ingest cost is index/attribute build vs. the base document
 * write + transaction-log durability. The store logic and the query path are
 * deliberately out of the loop — this measures the engine's write path only.
 *
 * Env: VESPA_URL, BENCH_SI_SIZE (docs, default 60000), BENCH_SI_BATCH (500).
 */
object SchemaIngestProbe {
    fun main(args: Array<String>) {
        val url = System.getenv("VESPA_URL") ?: "http://localhost:8080"
        val size = System.getenv("BENCH_SI_SIZE")?.toIntOrNull() ?: 60_000
        val batch = System.getenv("BENCH_SI_BATCH")?.toIntOrNull() ?: 500

        // Build the docs once (id-disjoint band so reruns against a live engine stay fresh writes).
        val corpus = NostrCorpus.generate(NostrCorpus.Config(size = size, seed = 42, idBand = 0x7L))
        val docs = corpus.map { EventDoc.fromEventJson(it.toJson()) }

        VespaEventIndex(url).use { idx ->
            val nanos = measureNanoTime { runBlocking { docs.chunked(batch).forEach { idx.putAll(it) } } }
            val secs = nanos / 1e9
            println(String.format("schema-ingest: fed %d docs (batch %d) in %.1fs = %.0f events/sec", size, batch, secs, size / secs))
            println(idx.feedGauge())
        }
    }
}
