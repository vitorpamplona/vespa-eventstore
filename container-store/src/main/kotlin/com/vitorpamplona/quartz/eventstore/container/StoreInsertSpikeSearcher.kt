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
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.yahoo.component.annotation.Inject
import com.yahoo.component.chain.dependencies.Before
import com.yahoo.documentapi.DocumentAccess
import com.yahoo.search.Query
import com.yahoo.search.Result
import com.yahoo.search.Searcher
import com.yahoo.search.result.Hit
import com.yahoo.search.searchchain.Execution
import com.yahoo.search.searchchain.PhaseNames
import kotlinx.coroutines.runBlocking

/**
 * The REAL end-to-end insert A/B: it runs the actual store ingest path —
 * [NostrEventStore.batchInsert], with all of its dedup, replaceable/addressable
 * supersession, NIP-09/62 deletion handling and NIP-50 search-field extraction —
 * with only the [com.vitorpamplona.quartz.eventstore.vespa.client.EventIndex]
 * backend swapped:
 *   - LOCAL: [VespaLocalEventIndex] (reads via [Execution], writes via
 *     [DocumentAccess] messagebus) — the store running INSIDE the container.
 *   - HTTP: [VespaEventIndex] (the feed client + `/search/` over loopback) — the
 *     store as it runs today, outside the container.
 *
 * Unlike [InsertSpikeSearcher] (which times a bare put), this measures what a
 * relay actually pays per event, so the store-layer CPU (extraction, dedup logic)
 * is present on BOTH sides and the speedup is the honest end-to-end number, not
 * just the transport delta.
 *
 * Both stores share one Vespa backend, so each side gets a disjoint id band AND a
 * disjoint author band ([SpikeCorpus.Config.idPrefix]/`authorPrefix`) — otherwise
 * one side's replaceable events would supersede the other's. Corpora are
 * structurally identical per round (same seed, realistic relay kind-mix), so the
 * accepted-count parity line is a correctness gate: the in-container store must
 * accept/reject exactly what the HTTP store does.
 *
 * `?storeinsert=1&n=N&rounds=R&run=K` — bump `run` to get fresh id/author bands
 * across invocations (avoids dedup against a prior run's writes).
 */
@Before(PhaseNames.TRANSFORMED_QUERY)
class StoreInsertSpikeSearcher
    @Inject
    constructor(
        private val access: DocumentAccess,
    ) : Searcher() {
        override fun search(
            query: Query,
            execution: Execution,
        ): Result {
            if (query.properties().getString("storeinsert") == null) return execution.search(query)

            val n = query.properties().getString("n")?.toIntOrNull() ?: 2000
            val rounds = (query.properties().getString("rounds")?.toIntOrNull() ?: 5).coerceIn(1, 12)
            val run = query.properties().getString("run")?.toIntOrNull() ?: 1

            val localStore = NostrEventStore(VespaLocalEventIndex({ execution }, access))
            val httpStore = NostrEventStore(VespaEventIndex(baseUrl = "http://localhost:8080"))

            runBlocking {
                // Warm both stores (JIT, connections, and prime each side's author
                // band so measured rounds all do steady-state replaceable churn).
                localStore.batchInsert(corpus(n, run, 0, local = true))
                httpStore.batchInsert(corpus(n, run, 0, local = false))

                val localMs = ArrayList<Double>()
                val httpMs = ArrayList<Double>()
                val localAcc = ArrayList<Int>()
                val httpAcc = ArrayList<Int>()

                for (r in 1..rounds) {
                    val lc = corpus(n, run, r, local = true)
                    val hc = corpus(n, run, r, local = false)
                    if (r % 2 == 0) {
                        localMs += ms { localAcc += accepted(localStore.batchInsert(lc)) }
                        httpMs += ms { httpAcc += accepted(httpStore.batchInsert(hc)) }
                    } else {
                        httpMs += ms { httpAcc += accepted(httpStore.batchInsert(hc)) }
                        localMs += ms { localAcc += accepted(localStore.batchInsert(lc)) }
                    }
                }

                val lms = median(localMs)
                val hms = median(httpMs)
                val parity =
                    if (localAcc == httpAcc) {
                        "OK (accepted=${localAcc.firstOrNull() ?: 0}/$n)"
                    } else {
                        "MISMATCH local=$localAcc http=$httpAcc"
                    }
                val line =
                    "STOREINSERT n=%d rounds=%d local=%.1fms (%.0f/s) http=%.1fms (%.0f/s) speedup=%.2fx parity=%s"
                        .format(n, rounds, lms, n / lms * 1000, hms, n / hms * 1000, hms / lms, parity)
                query.trace(line, 1)

                val result = Result(query)
                val hit = Hit("storeinsert:result")
                hit.setField("n", n)
                hit.setField("rounds", rounds)
                hit.setField("local_ms", lms)
                hit.setField("http_ms", hms)
                hit.setField("local_per_s", n / lms * 1000)
                hit.setField("http_per_s", n / hms * 1000)
                hit.setField("speedup", hms / lms)
                hit.setField("parity", parity)
                result.hits().add(hit)
                result
            }.let { return it }
        }

        /** A structurally-identical corpus per (run, round); disjoint id + author bands per side. */
        private fun corpus(
            n: Int,
            run: Int,
            round: Int,
            local: Boolean,
        ): List<Event> {
            val salt = run * 64 + round
            val cfg =
                SpikeCorpus.Config(
                    size = n,
                    idPrefix = (if (local) "b" else "d") + salt.toString(16),
                    authorPrefix = if (local) "a" else "c",
                )
            return SpikeCorpus.generate(cfg)
        }

        private fun accepted(outcomes: List<IEventStore.InsertOutcome>): Int = outcomes.count { it is IEventStore.InsertOutcome.Accepted }

        private inline fun ms(body: () -> Unit): Double {
            val t0 = System.nanoTime()
            body()
            return (System.nanoTime() - t0) / 1e6
        }

        private fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            return if (s.isEmpty()) 0.0 else s[s.size / 2]
        }
    }
