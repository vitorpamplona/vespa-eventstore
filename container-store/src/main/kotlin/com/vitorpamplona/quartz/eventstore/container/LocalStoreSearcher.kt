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
import com.vitorpamplona.quartz.eventstore.vespa.query.EventQuery
import com.yahoo.component.chain.dependencies.Before
import com.yahoo.search.Query
import com.yahoo.search.Result
import com.yahoo.search.Searcher
import com.yahoo.search.result.Hit
import com.yahoo.search.searchchain.Execution
import com.yahoo.search.searchchain.PhaseNames
import kotlinx.coroutines.runBlocking

/**
 * Entry point + A/B harness for the in-container store read path. Send the query
 * shape as params plus `&localstore=1&reps=N` and it runs the SAME [EventQuery]
 * two ways — through [VespaLocalEventIndex] (in-process, this request's
 * [Execution]) and through a loopback HTTP [VespaEventIndex] — reports both mean
 * latencies and whether the id lists agree (the in-container correctness gate).
 *
 * Placed before the query-transform phase so the fresh YQL sub-queries the local
 * index builds are parsed by the downstream chain.
 *
 * Params: ids, kinds, authors, ptags (comma lists), since, until, qlimit, reps.
 */
@Before(PhaseNames.TRANSFORMED_QUERY)
class LocalStoreSearcher : Searcher() {
    // One shared loopback HTTP index for the baseline side of the A/B.
    private val http by lazy { VespaEventIndex(baseUrl = "http://localhost:8080") }

    override fun search(
        query: Query,
        execution: Execution,
    ): Result {
        if (query.properties().getString("localstore") == null) return execution.search(query)

        val reps = query.properties().getString("reps")?.toIntOrNull() ?: 100
        val eq = buildQuery(query)
        val local = VespaLocalEventIndex({ execution })

        var localIds: List<String> = emptyList()
        var httpIds: List<String> = emptyList()

        repeat(10) { runBlocking { local.search(eq) } } // warm
        val localMs = timeMs(reps) { localIds = runBlocking { local.search(eq) }.map { it.id } }

        val httpReps = (reps / 4).coerceAtLeast(10)
        repeat(5) { runBlocking { http.search(eq) } }
        val httpMs = timeMs(httpReps) { httpIds = runBlocking { http.search(eq) }.map { it.id } }

        val parity = if (localIds == httpIds) "OK" else "MISMATCH local=${localIds.size} http=${httpIds.size}"
        query.trace(
            "LOCALSTORE local_ms=%.3f http_ms=%.3f hits=%d parity=%s".format(localMs, httpMs, localIds.size, parity),
            1,
        )

        val r = Result(query)
        val h = Hit("localstore:result")
        h.setField("local_ms", localMs)
        h.setField("http_ms", httpMs)
        h.setField("hits", localIds.size)
        h.setField("parity", parity)
        r.hits().add(h)
        return r
    }

    private inline fun timeMs(
        reps: Int,
        body: () -> Unit,
    ): Double {
        val t0 = System.nanoTime()
        repeat(reps) { body() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    private fun buildQuery(q: Query): EventQuery {
        fun csv(name: String) =
            q
                .properties()
                .getString(name)
                ?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList()

        fun long(name: String) = q.properties().getString(name)?.toLongOrNull()

        val tags = HashMap<String, List<String>>()
        csv("ptags").takeIf { it.isNotEmpty() }?.let { tags["p"] = it }

        return EventQuery(
            ids = csv("ids"),
            kinds = csv("kinds").mapNotNull { it.toIntOrNull() },
            authors = csv("authors"),
            tags = tags,
            since = long("since"),
            until = long("until"),
            limit = q.properties().getString("qlimit")?.toIntOrNull() ?: 500,
        )
    }
}
