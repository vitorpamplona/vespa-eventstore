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
package com.vitorpamplona.quartz.eventstore.store

import com.vitorpamplona.quartz.eventstore.vespa.doc.EventDoc
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip09Deletions.DeletionEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip23LongContent.LongTextNoteEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent

/**
 * Rebuild a stored [EventDoc] into its Quartz [Event] — the query result path.
 *
 * The obvious `Event.fromJson(toEventJson())` reconstructs by SERIALIZING the doc
 * to a JSON string and PARSING it back, once per returned event. On a hot query
 * path that string + parse is the single biggest source of garbage (tens of KB
 * per event). But it is the only way Quartz hands back the correct SUBCLASS
 * (kind 1 -> TextNoteEvent, …), which a consumer may rely on — so it cannot just
 * be dropped for a bare [Event].
 *
 * This maps the HIGH-VOLUME kinds (a relay is overwhelmingly notes, reactions,
 * profiles, deletions, long-form) straight to their subclass constructor — one
 * allocation, no JSON at all. Those subclass constructors only STORE the seven
 * NIP-01 fields; every derived view (indexableContent, eventHints, contactMetaData)
 * is computed lazily on access, so a directly-built instance is indistinguishable
 * from a `fromJson` one. Any OTHER kind falls back to `fromJson`, so correctness
 * never depends on this table being exhaustive — only the mapped kinds skip the
 * round trip, and `EventReconstructionTest` pins each of them to `fromJson`'s
 * exact type and serialization.
 */
internal fun EventDoc.toEvent(): Event? {
    // Only allocate the String[][] when a mapped kind will actually use it.
    fun tagArray() = Array(tags.size) { tags[it].toTypedArray() }
    return when (kind) {
        0 -> MetadataEvent(id, pubkey, createdAt, tagArray(), content, sig)
        1 -> TextNoteEvent(id, pubkey, createdAt, tagArray(), content, sig)
        5 -> DeletionEvent(id, pubkey, createdAt, tagArray(), content, sig)
        7 -> ReactionEvent(id, pubkey, createdAt, tagArray(), content, sig)
        30023 -> LongTextNoteEvent(id, pubkey, createdAt, tagArray(), content, sig)
        // Every other kind keeps the canonical typed path (gift wraps, relay
        // lists, follow lists, and the long tail) — correctness over speed.
        else -> Event.fromJsonOrNull(toEventJson())
    }
}
