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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [toEvent] fast path must be INDISTINGUISHABLE from the canonical
 * `Event.fromJson(toEventJson())` it replaces on the query hot path: for every
 * mapped kind the directly-built event has the SAME runtime subclass AND
 * serializes to the SAME JSON, and every other kind falls back to `fromJson`.
 * If a Quartz upgrade ever remapped a kind, this test fails loudly rather than
 * letting the store hand back a mis-typed event.
 */
class EventReconstructionTest {
    private var seq = 0

    private fun id() = (++seq).toString(16).padStart(64, '0')

    private fun doc(
        kind: Int,
        tags: List<List<String>> = listOf(listOf("e", "a".repeat(64)), listOf("p", "b".repeat(64)), listOf("alt", "note")),
        content: String = "hello \"world\"\nwith\ttabs and emoji 🤙 and a \\backslash",
    ) = EventDoc(
        id = id(),
        pubkey = "c".repeat(64),
        createdAt = 1_700_000_000L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "d".repeat(128),
    )

    /** The kinds [toEvent] builds directly must match fromJson exactly. */
    private val mappedKinds = listOf(0, 1, 5, 7, 30023)

    @Test
    fun `mapped kinds match fromJson type and serialization exactly`() {
        for (kind in mappedKinds) {
            val d = doc(kind)
            val direct = d.toEvent() ?: error("toEvent returned null for kind $kind")
            val canonical = Event.fromJson(d.toEventJson())
            assertEquals(canonical::class, direct::class, "kind $kind: subclass diverged from fromJson")
            assertEquals(d.toEventJson(), direct.toJson(), "kind $kind: fast-path serialization diverged from the canonical form")
            assertEquals(canonical.toJson(), direct.toJson(), "kind $kind: fast-path JSON diverged from fromJson's")
        }
    }

    @Test
    fun `unmapped kinds fall back to the canonical typed path`() {
        // Follow list, repost, relay list, gift wrap, zap receipt, addressable app data.
        for (kind in listOf(3, 6, 10002, 1059, 9735, 30078)) {
            val d = doc(kind)
            val direct = d.toEvent() ?: error("toEvent returned null for kind $kind")
            val canonical = Event.fromJson(d.toEventJson())
            assertEquals(canonical::class, direct::class, "kind $kind: fallback type diverged")
            assertEquals(canonical.toJson(), direct.toJson(), "kind $kind: fallback JSON diverged")
        }
    }

    @Test
    fun `empty tags and empty content reconstruct correctly`() {
        for (kind in mappedKinds) {
            val d = doc(kind, tags = emptyList(), content = "")
            val direct = d.toEvent() ?: error("null for kind $kind")
            assertEquals(Event.fromJson(d.toEventJson()).toJson(), direct.toJson(), "kind $kind empty")
        }
    }
}
