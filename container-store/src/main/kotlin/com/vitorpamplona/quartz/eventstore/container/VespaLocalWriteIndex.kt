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

import com.vitorpamplona.quartz.eventstore.vespa.doc.EventDoc
import com.yahoo.document.ArrayDataType
import com.yahoo.document.DataType
import com.yahoo.document.Document
import com.yahoo.document.DocumentId
import com.yahoo.document.DocumentPut
import com.yahoo.document.DocumentType
import com.yahoo.document.ReferenceDataType
import com.yahoo.document.datatypes.FieldValue
import com.yahoo.document.datatypes.IntegerFieldValue
import com.yahoo.document.datatypes.LongFieldValue
import com.yahoo.document.datatypes.ReferenceFieldValue
import com.yahoo.document.datatypes.StringFieldValue
import com.yahoo.documentapi.AsyncParameters
import com.yahoo.documentapi.AsyncSession
import com.yahoo.documentapi.DocumentAccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.atomic.AtomicLong
import com.yahoo.document.datatypes.Array as DocArray

/**
 * The in-container WRITE counterpart to [VespaLocalEventIndex]: puts events onto
 * the content cluster through the container's own [DocumentAccess] (messagebus
 * [AsyncSession]) instead of over HTTP through the feed client.
 *
 * It reuses the store's exact field set — [EventDoc.indexFields], the identical
 * map the feed client serializes into the `/document/v1` body — but builds the
 * [com.yahoo.document.Document] directly (typed [FieldValue]s, mapped generically
 * from the document type) instead of going through JSON. So this path removes the
 * whole JSON-over-loopback-HTTP hop: encode, socket, the container's HTTP request
 * handling, AND the JSON re-parse the feed path pays on arrival. The messagebus
 * put, the indexing pipeline, and the content-node durability are identical on
 * both paths. That framing is the point of the insert A/B in [InsertSpikeSearcher].
 *
 * (Building the Document directly also sidesteps the container's `JsonReader`,
 * which is bound to the platform's jackson — feeding it the bundle's embedded
 * jackson trips an OSGi loader-constraint violation.)
 *
 * REQUEST-SCOPED like its read sibling: one [AsyncSession] per instance, drained
 * to completion in [putAll]. Responses are counted through a [AsyncParameters]
 * handler; backpressure (a full client window → `TRANSIENT_ERROR`) is retried.
 */
class VespaLocalWriteIndex(
    access: DocumentAccess,
) : AutoCloseable {
    private val eventType: DocumentType = access.documentTypeManager.getDocumentType(DOCTYPE)

    private val acked = AtomicLong(0)
    private val failed = AtomicLong(0)

    private val session: AsyncSession =
        access.createAsyncSession(
            AsyncParameters().setResponseHandler { resp ->
                if (resp.isSuccess) acked.incrementAndGet() else failed.incrementAndGet()
            },
        )

    /**
     * Build a [DocumentPut] from [EventDoc.indexFields] — the same field set the
     * feed client sends — mapping each entry to a typed [FieldValue] by the
     * document type. Unknown fields (none in practice) are skipped.
     */
    private fun toPut(doc: EventDoc): DocumentPut {
        val d = Document(eventType, DocumentId("id:$NAMESPACE:$DOCTYPE::${doc.id}"))
        for ((name, elem) in doc.indexFields()) {
            val field = eventType.getField(name) ?: continue
            toFieldValue(field.dataType, elem)?.let { d.setFieldValue(field, it) }
        }
        return DocumentPut(d)
    }

    private fun toFieldValue(
        dt: DataType,
        e: JsonElement,
    ): FieldValue? =
        when {
            dt === DataType.STRING -> {
                StringFieldValue(e.jsonPrimitive.content)
            }

            dt === DataType.INT -> {
                IntegerFieldValue(e.jsonPrimitive.int)
            }

            dt === DataType.LONG -> {
                LongFieldValue(e.jsonPrimitive.long)
            }

            dt is ArrayDataType -> {
                DocArray<FieldValue>(dt).apply {
                    e.jsonArray.forEach { el -> toFieldValue(dt.nestedType, el)?.let { add(it) } }
                }
            }

            dt is ReferenceDataType -> {
                ReferenceFieldValue(dt, DocumentId(e.jsonPrimitive.content))
            }

            else -> {
                null
            }
        }

    /**
     * Put every doc onto messagebus and block until each has been acked — the
     * in-container mirror of the feed client's `putAll` (all operations in flight
     * together, awaited as a group).
     */
    fun putAll(docs: List<EventDoc>) {
        acked.set(0)
        failed.set(0)
        val puts = docs.map(::toPut)
        for (put in puts) {
            var r = session.put(put)
            while (!r.isSuccess) {
                // Client window full (TRANSIENT_ERROR): let acks drain, then retry.
                parkTenthOfMs()
                r = session.put(put)
            }
        }
        val target = docs.size.toLong()
        while (acked.get() + failed.get() < target) parkTenthOfMs()
        val fails = failed.get()
        require(fails == 0L) { "in-container put: $fails/${docs.size} operations failed" }
    }

    fun put(doc: EventDoc) = putAll(listOf(doc))

    override fun close() = session.destroy()

    private fun parkTenthOfMs() =
        java.util.concurrent.locks.LockSupport
            .parkNanos(100_000)

    companion object {
        // Matches VespaEventIndex's DocumentId.of(NAMESPACE, DOCTYPE, id).
        private const val NAMESPACE = "event"
        private const val DOCTYPE = "event"
    }
}
