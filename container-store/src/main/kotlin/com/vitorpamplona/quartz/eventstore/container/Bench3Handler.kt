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
package com.vitorpamplona.quartz.eventstore.container

import com.vitorpamplona.quartz.eventstore.store.NostrEventStore
import com.vitorpamplona.quartz.eventstore.vespa.client.VespaEventIndex
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.yahoo.component.annotation.Inject
import com.yahoo.container.jdisc.HttpRequest
import com.yahoo.container.jdisc.HttpResponse
import com.yahoo.container.jdisc.ThreadedHttpRequestHandler
import com.yahoo.documentapi.DocumentAccess
import com.yahoo.search.searchchain.ExecutionFactory
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore as SqliteEventStore

/**
 * The single-vantage, in-container 3-way benchmark — the honest way to see the
 * FULL performance picture. All three stores are instantiated and driven IN-PROCESS
 * from inside Vespa's container JVM, so the caller→store hop is zero for every
 * contestant and each uses only its own native transport:
 *
 *  - SQLite   — Quartz's in-memory embedded DB (no network at all).
 *  - embed    — the store over [VespaLocalEventIndex]: reads via Execution, writes
 *               via DocumentAccess, all in-process to the engine.
 *  - http     — the store over [VespaEventIndex]: reads/writes over loopback HTTP to
 *               THIS container's own :8080 (co-located HTTP — flatters HTTP by
 *               removing the network a real remote deployment pays).
 *
 * Fairness: embed and http share one Vespa backend, so each gets a disjoint id +
 * author band (SQLite is independent). Trust projection is deliberately OMITTED on
 * both Vespa stores so the number isolates DB/engine + store logic + engine-transport,
 * not read-time ranking. Bump `run` for fresh id bands across invocations.
 *
 * GET `/bench3?n=20000&reps=300&run=1`
 */
class Bench3Handler
    @Inject
    constructor(
        executor: java.util.concurrent.Executor,
        private val executionFactory: ExecutionFactory,
        private val documentAccess: DocumentAccess,
    ) : ThreadedHttpRequestHandler(executor) {
        override fun handle(request: HttpRequest): HttpResponse {
            val n = request.getProperty("n")?.toIntOrNull() ?: 20_000
            val reps = request.getProperty("reps")?.toIntOrNull() ?: 300
            val run = request.getProperty("run")?.toIntOrNull() ?: 1

            val out = StringBuilder()
            out.append("in-container 3-way — n=$n reps=$reps run=$run\n")
            out.append("(all driven in-process from the container JVM; higher = faster)\n\n")

            val results =
                try {
                    // Disjoint bands: SQLite independent; embed vs http must not collide on the shared backend.
                    val sqlite = bench("sqlite", SqliteEventStore(dbName = null), corpus(n, run, "5", "6"), reps)
                    val embed = bench("embed", NostrEventStore(local()), corpus(n, run, "b$run", "a"), reps)
                    val http = bench("http", NostrEventStore(VespaEventIndex(baseUrl = LOOPBACK)), corpus(n, run, "d$run", "c"), reps)
                    listOf(sqlite, embed, http)
                } catch (t: Throwable) {
                    out
                        .append("FAILED: ")
                        .append(t.javaClass.name)
                        .append(": ")
                        .append(t.message)
                        .append('\n')
                    var c = t.cause
                    while (c != null) {
                        out
                            .append("  <- ")
                            .append(c.javaClass.name)
                            .append(": ")
                            .append(c.message)
                            .append('\n')
                        c = c.cause
                    }
                    return text(out.toString())
                }

            // Insert throughput.
            out.append(String.format("%-18s %14s %14s %14s%n", "INSERT (ev/s)", "sqlite", "embed", "http"))
            out.append(String.format("%-18s %14.0f %14.0f %14.0f%n%n", "batchInsert", results[0].insertPerSec, results[1].insertPerSec, results[2].insertPerSec))

            // Query throughput per shape.
            out.append(String.format("%-18s %14s %14s %14s%n", "QUERY (q/s)", "sqlite", "embed", "http"))
            for (shape in SHAPES.map { it.first }) {
                out.append(
                    String.format(
                        "%-18s %14.0f %14.0f %14.0f%n",
                        shape,
                        results[0].qps[shape] ?: 0.0,
                        results[1].qps[shape] ?: 0.0,
                        results[2].qps[shape] ?: 0.0,
                    ),
                )
            }
            return text(out.toString())
        }

        private class Result(
            val insertPerSec: Double,
            val qps: Map<String, Double>,
        )

        /** Insert the corpus (timed), then run every query shape (timed) against [store]. */
        private fun bench(
            name: String,
            store: IEventStore,
            corpus: List<Event>,
            reps: Int,
        ): Result =
            runBlocking {
                val insNs =
                    nanos {
                        corpus.chunked(500).forEach { store.batchInsert(it) }
                    }
                val work = Work(corpus)
                val qps = LinkedHashMap<String, Double>()
                for ((shape, op) in SHAPES) {
                    // warm, then measure reps.
                    repeat((reps / 10).coerceIn(1, 50)) { op(store, work, it) }
                    val ns = nanos { repeat(reps) { op(store, work, it) } }
                    qps[shape] = reps / (ns / 1e9)
                }
                runCatching { store.close() }
                Result(insertPerSec = corpus.size / (insNs / 1e9), qps = qps)
            }

        private fun local(): VespaLocalEventIndex =
            VespaLocalEventIndex(
                executionSource = { executionFactory.newExecution("vespa") },
                access = documentAccess,
                cold = VespaEventIndex(baseUrl = LOOPBACK),
            )

        /** Structurally-identical corpus with per-store disjoint id/author bands. */
        private fun corpus(
            n: Int,
            run: Int,
            idPrefix: String,
            authorPrefix: String,
        ): List<Event> = SpikeCorpus.generate(SpikeCorpus.Config(size = n, idPrefix = idPrefix, authorPrefix = authorPrefix))

        private inline fun nanos(body: () -> Unit): Double {
            val t0 = System.nanoTime()
            body()
            return (System.nanoTime() - t0).toDouble()
        }

        /** Picks authors/ids out of a store's own corpus so every shape hits real data. */
        private class Work(
            corpus: List<Event>,
        ) {
            val authors = corpus.map { it.pubKey }.distinct()
            val ids = corpus.map { it.id }

            fun author(i: Int) = authors[i % authors.size]

            fun id(i: Int) = ids[i % ids.size]

            fun authorList(
                i: Int,
                k: Int,
            ) = (0 until k).map { authors[(i + it) % authors.size] }
        }

        private fun text(body: String): HttpResponse {
            val bytes = body.toByteArray(Charsets.UTF_8)
            return object : HttpResponse(200) {
                override fun render(o: OutputStream) = o.write(bytes)

                override fun getContentType() = "text/plain"
            }
        }

        private companion object {
            const val LOOPBACK = "http://localhost:8080"

            // The relay-shaped query mix, mirrored from the SQLite-vs-Vespa benchmark.
            val SHAPES: List<Pair<String, suspend (IEventStore, Work, Int) -> Unit>> =
                listOf(
                    "author-timeline" to { s, w, i ->
                        s.query<Event>(Filter(authors = listOf(w.author(i)), limit = 50))
                        Unit
                    },
                    "kind-scan(1)" to { s, _, _ ->
                        s.query<Event>(Filter(kinds = listOf(1), limit = 200))
                        Unit
                    },
                    "id-lookup" to { s, w, i ->
                        s.query<Event>(Filter(ids = listOf(w.id(i))))
                        Unit
                    },
                    "tag-mentions(p)" to { s, w, i ->
                        s.query<Event>(Filter(kinds = listOf(1), tags = mapOf("p" to listOf(w.author(i))), limit = 50))
                        Unit
                    },
                    "count(kind7)" to { s, _, _ ->
                        s.count(Filter(kinds = listOf(7)))
                        Unit
                    },
                    "follow-feed(300)" to { s, w, i ->
                        s.query<Event>(Filter(authors = w.authorList(i, 300), kinds = listOf(1, 6, 7), limit = 500))
                        Unit
                    },
                )
        }
    }
