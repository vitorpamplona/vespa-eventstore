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

import com.vitorpamplona.quartz.eventstore.vespa.client.VespaEventIndex
import com.vitorpamplona.quartz.eventstore.vespa.doc.EventDoc
import com.yahoo.component.annotation.Inject
import com.yahoo.documentapi.DocumentAccess
import com.yahoo.search.Query
import com.yahoo.search.Result
import com.yahoo.search.Searcher
import com.yahoo.search.result.Hit
import com.yahoo.search.searchchain.Execution
import kotlinx.coroutines.runBlocking

/**
 * Insert A/B harness for the in-container write path. On `?insertspike=1&n=N&rounds=R`
 * it writes fresh, unique events two ways — through [VespaLocalWriteIndex]
 * (in-process messagebus [DocumentAccess]) and through the store's real
 * [VespaEventIndex] feed client over loopback HTTP — and reports throughput for
 * both a BATCH put (all N in flight, awaited as a group) and SINGLE puts (N
 * sequential, each awaited).
 *
 * Every round uses a distinct id range per side, so both sides perform genuine
 * new inserts (no dedup / no-op writes) and neither warms the other's docs. The
 * per-round order is alternated to counterbalance drift. The removable cost this
 * isolates is exactly the JSON-over-loopback-HTTP hop; messagebus + indexing +
 * content-node durability are paid identically by both paths.
 *
 * [DocumentAccess] is injected by the container (requires `<document-api/>`).
 */
class InsertSpikeSearcher
    @Inject
    constructor(
        private val access: DocumentAccess,
    ) : Searcher() {
        private val http by lazy { VespaEventIndex(baseUrl = "http://localhost:8080") }

        override fun search(
            query: Query,
            execution: Execution,
        ): Result {
            if (query.properties().getString("insertspike") == null) return execution.search(query)

            val n = query.properties().getString("n")?.toIntOrNull() ?: 500
            val rounds = query.properties().getString("rounds")?.toIntOrNull() ?: 5
            val local = VespaLocalWriteIndex(access)

            // Warm both paths (JIT, connections, client window) on throwaway id ranges.
            local.putAll(docs("warmL", 0, n))
            runBlocking { http.putAll(docs("warmH", 0, n)) }

            val localBatch = ArrayList<Double>()
            val httpBatch = ArrayList<Double>()
            val localSingle = ArrayList<Double>()
            val httpSingle = ArrayList<Double>()

            for (r in 1..rounds) {
                // BATCH: all N in flight, awaited as a group.
                if (r % 2 == 0) {
                    localBatch += ms { local.putAll(docs("bL", r, n)) }
                    httpBatch += ms { runBlocking { http.putAll(docs("bH", r, n)) } }
                } else {
                    httpBatch += ms { runBlocking { http.putAll(docs("bH", r, n)) } }
                    localBatch += ms { local.putAll(docs("bL", r, n)) }
                }
                // SINGLE: N sequential puts, each awaited (per-op latency).
                val sn = (n / 5).coerceAtLeast(20)
                if (r % 2 == 0) {
                    localSingle += ms { docs("sL", r, sn).forEach { local.put(it) } } / sn
                    httpSingle += ms { runBlocking { docs("sH", r, sn).forEach { http.put(it) } } } / sn
                } else {
                    httpSingle += ms { runBlocking { docs("sH", r, sn).forEach { http.put(it) } } } / sn
                    localSingle += ms { docs("sL", r, sn).forEach { local.put(it) } } / sn
                }
            }
            local.close()

            val lb = median(localBatch)
            val hb = median(httpBatch)
            val ls = median(localSingle)
            val hs = median(httpSingle)
            val batchLine =
                "batch(%d): local=%.1fms (%.0f/s) http=%.1fms (%.0f/s) speedup=%.2fx"
                    .format(n, lb, n / lb * 1000, hb, n / hb * 1000, hb / lb)
            val singleLine =
                "single: local=%.3fms/op http=%.3fms/op speedup=%.2fx".format(ls, hs, hs / ls)
            query.trace("INSERTSPIKE $batchLine :: $singleLine", 1)

            val result = Result(query)
            val hit = Hit("insertspike:result")
            hit.setField("n", n)
            hit.setField("rounds", rounds)
            hit.setField("local_batch_ms", lb)
            hit.setField("http_batch_ms", hb)
            hit.setField("batch_speedup", hb / lb)
            hit.setField("local_single_ms", ls)
            hit.setField("http_single_ms", hs)
            hit.setField("single_speedup", hs / ls)
            result.hits().add(hit)
            return result
        }

        /** N unique synthetic kind-1 events. `tag` + `round` keep every id range disjoint. */
        private fun docs(
            tag: String,
            round: Int,
            n: Int,
        ): List<EventDoc> {
            val seed = "$tag-$round".hashCode().toLong() and 0xffffffffL
            return (0 until n).map { i ->
                val h = "%016x".format(seed) + "%048x".format(i.toLong())
                val pk = "a" + "%063x".format((i % 256).toLong())
                EventDoc(
                    id = h.take(64),
                    pubkey = pk.take(64),
                    createdAt = 1_700_000_000L + round * 1000L + i,
                    kind = 1,
                    tags = emptyList(),
                    content = "insert-spike $tag $round $i",
                    sig = "0".repeat(128),
                    owner = pk.take(64),
                )
            }
        }

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
