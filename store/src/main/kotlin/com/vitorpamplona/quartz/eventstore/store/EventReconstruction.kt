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
import com.vitorpamplona.quartz.utils.EventFactory

/**
 * Rebuild a stored [EventDoc] into its typed Quartz [Event] — the query result path.
 *
 * The obvious `Event.fromJson(toEventJson())` reconstructs by SERIALIZING the doc
 * to a JSON string and PARSING it back, once per returned event — and on a hot
 * query path that string + parse is the single biggest source of garbage
 * (measured ~8x slower and ~5x more allocation per event than building it
 * directly).
 *
 * [EventFactory.create] is Quartz's OWN by-kind dispatch — the same registry
 * `fromJson` uses to pick the subclass (kind 1 -> TextNoteEvent, 0 ->
 * MetadataEvent, …) — but invoked straight from the stored fields, with no JSON
 * in the middle. It covers every known kind and returns a base [Event] for the
 * rest, so this is both faster AND complete: no hand-maintained kind table, and
 * the result is identical to what `fromJson` would have produced (pinned by
 * `EventReconstructionTest`). Those subclass constructors only store the seven
 * NIP-01 fields; every derived view is computed lazily, so a directly-built
 * instance is indistinguishable from a parsed one.
 */
internal fun EventDoc.toEvent(): Event = EventFactory.create(id, pubkey, createdAt, kind, tagsAsArray(), content, sig)

private fun EventDoc.tagsAsArray(): Array<Array<String>> = Array(tags.size) { tags[it].toTypedArray() }
