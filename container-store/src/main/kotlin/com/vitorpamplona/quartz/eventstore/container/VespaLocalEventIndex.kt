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

import com.vitorpamplona.quartz.eventstore.vespa.client.EventIndex
import com.vitorpamplona.quartz.eventstore.vespa.doc.EventDoc
import com.vitorpamplona.quartz.eventstore.vespa.query.EventQuery
import com.vitorpamplona.quartz.eventstore.vespa.query.EventYql
import com.yahoo.documentapi.DocumentAccess
import com.yahoo.search.Query
import com.yahoo.search.result.Hit
import com.yahoo.search.searchchain.Execution
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URLEncoder

/**
 * A full read+write [EventIndex] that runs entirely IN-PROCESS: reads go through
 * the jdisc search chain ([Execution.search]) and writes go onto the content
 * cluster through the container's own [DocumentAccess] (messagebus), instead of
 * over HTTP. It reuses the exact same [EventYql] query builder and
 * [EventDoc.fromSummary]/[EventDoc.indexFields] as the HTTP
 * [com.vitorpamplona.quartz.eventstore.vespa.client.VespaEventIndex], so it
 * answers every NIP-01 filter identically and lands identical documents — the
 * only difference is that there is no external hop, no JSON encode/parse.
 *
 * Dropped into [com.vitorpamplona.quartz.eventstore.store.NostrEventStore] in
 * place of the HTTP index, it makes the whole store (dedup, supersession, NIP-09,
 * search extraction) run against Vespa with no HTTP bridge — the basis of the
 * real end-to-end insert A/B in [StoreInsertSpikeSearcher].
 *
 * REQUEST-SCOPED: an [Execution] is bound to one in-flight request, so a fresh
 * instance is created per request by the hosting searcher/handler. Pass a
 * [DocumentAccess] to enable writes; without one this is a read-only index (the
 * write methods throw).
 */
class VespaLocalEventIndex(
    private val execution: Execution,
    access: DocumentAccess? = null,
    private val maxHits: Int = 10_000,
) : EventIndex {
    private val writer = access?.let { VespaLocalWriteIndex(it) }

    override suspend fun search(query: EventQuery): List<EventDoc> {
        val vq = EventYql.build(query) ?: return emptyList()
        val hits = query.limit ?: maxHits
        val req =
            StringBuilder("?yql=").append(enc(vq.yql)).append("&hits=").append(hits)
        if (vq.ranking.isNotEmpty()) req.append("&ranking=").append(enc(vq.ranking))
        vq.params.forEach { (k, v) ->
            req
                .append('&')
                .append(enc(k))
                .append('=')
                .append(enc(v))
        }

        val sub = Query(req.toString())
        val result = execution.search(sub)
        execution.fill(result)
        return result.hits().asList().mapNotNull { toDoc(it) }
    }

    override suspend fun get(id: String): EventDoc? = search(EventQuery(ids = listOf(id), limit = 1)).firstOrNull()

    /**
     * Exact recall count. The grouping `count()` path the HTTP store uses isn't
     * ported yet, so this counts the (capped) recall set — exact whenever the match
     * set fits under [maxHits], which covers the NIP-01 parity battery. TODO Phase
     * B/2: port [EventYql.buildCount] grouping for unbounded exact counts.
     */
    override suspend fun count(query: EventQuery): Int = search(query.copy(limit = query.limit ?: maxHits)).size

    // ---- write path: in-process via DocumentAccess (messagebus) ----
    override suspend fun put(doc: EventDoc) = writer().put(doc)

    override suspend fun putAll(docs: List<EventDoc>) = writer().putAll(docs)

    override suspend fun remove(id: String) = writer().remove(id)

    override suspend fun removeAll(ids: List<String>) = writer().removeAll(ids)

    override fun close() {
        writer?.close()
    }

    private fun writer(): VespaLocalWriteIndex = writer ?: throw UnsupportedOperationException("VespaLocalEventIndex is read-only: construct it with a DocumentAccess to enable writes")

    /** Build an [EventDoc] from a hit's summary fields — same fields the HTTP path decodes. */
    private fun toDoc(hit: Hit): EventDoc? {
        if (hit.getField("id") == null) return null
        val fields =
            buildJsonObject {
                for (name in SUMMARY_FIELDS) {
                    hit.getField(name)?.let { put(name, JsonPrimitive(it.toString())) }
                }
            }
        return EventDoc.fromSummary(fields)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        private val SUMMARY_FIELDS =
            listOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig", "owner")
    }
}
