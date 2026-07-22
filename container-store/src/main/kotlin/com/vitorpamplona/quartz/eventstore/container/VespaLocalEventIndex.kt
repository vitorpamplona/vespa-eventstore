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

import com.vitorpamplona.quartz.eventstore.vespa.client.DocRef
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
 * A composite [EventIndex] for running the store INSIDE Vespa's container. The
 * HOT paths go in-process — reads through the jdisc search chain
 * ([Execution.search]) and writes onto the content cluster through the container's
 * own [DocumentAccess] (messagebus) — reusing the exact same [EventYql] builder
 * and [EventDoc.fromSummary]/[EventDoc.indexFields] as the HTTP
 * [com.vitorpamplona.quartz.eventstore.vespa.client.VespaEventIndex], so it
 * answers every NIP-01 filter identically and lands identical documents, with no
 * external hop and no JSON encode/parse. Those are the paths the in-container A/Bs
 * measured (reads ~2.3–3.4×, ingest ~1.8–2.1×).
 *
 * The COLD paths — grouping [count]/[countDistinctAuthors]/[countByKind] and the
 * full-corpus [visitIds] scan — are rare (status metrics, negentropy sync) and
 * their result-parsing is non-trivial, so instead of re-porting them this
 * delegates to an inner [cold] index (a loopback [VespaEventIndex]). Reusing the
 * tested HTTP implementation verbatim is worth one localhost round-trip on a call
 * that happens seldom. Without a [cold] delegate these fall back to the interface
 * defaults (capped-search) — fine for the parity battery, not for large corpora.
 *
 * LONG-LIVED: reads pull a fresh [Execution] from [executionSource] per call
 * (e.g. `executionFactory::newExecution` bound to the backend chain), so one
 * instance can back a long-lived embedded store rather than a single request.
 * Pass a [DocumentAccess] to enable writes; without one the write methods throw.
 */
class VespaLocalEventIndex(
    private val executionSource: () -> Execution,
    access: DocumentAccess? = null,
    private val cold: EventIndex? = null,
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
        val exec = executionSource()
        val result = exec.search(sub)
        exec.fill(result)
        return result.hits().asList().mapNotNull { toDoc(it) }
    }

    override suspend fun get(id: String): EventDoc? = search(EventQuery(ids = listOf(id), limit = 1)).firstOrNull()

    // ---- cold paths: grouping counts + the full-corpus visit ----
    // Delegated to the loopback [cold] index (the tested HTTP grouping/visit) when
    // present; otherwise the capped-search interface defaults. See the class kdoc.

    override suspend fun count(query: EventQuery): Int = cold?.count(query) ?: search(query.copy(limit = query.limit ?: maxHits)).size

    override suspend fun countDistinctAuthors(query: EventQuery): Int = cold?.countDistinctAuthors(query) ?: super.countDistinctAuthors(query)

    override suspend fun countByKind(query: EventQuery): Map<Int, Int> = cold?.countByKind(query) ?: super.countByKind(query)

    override suspend fun distinctAuthors(query: EventQuery): Set<String> = cold?.distinctAuthors(query) ?: super.distinctAuthors(query)

    override suspend fun visitIds(
        query: EventQuery,
        withDTag: Boolean,
        onPage: suspend (List<DocRef>) -> Boolean,
    ) {
        if (cold != null) cold.visitIds(query, withDTag, onPage) else super.visitIds(query, withDTag, onPage)
    }

    // ---- write path: in-process via DocumentAccess (messagebus) ----
    override suspend fun put(doc: EventDoc) = writer().put(doc)

    override suspend fun putAll(docs: List<EventDoc>) = writer().putAll(docs)

    override suspend fun remove(id: String) = writer().remove(id)

    override suspend fun removeAll(ids: List<String>) = writer().removeAll(ids)

    override fun close() {
        writer?.close()
        cold?.close()
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
