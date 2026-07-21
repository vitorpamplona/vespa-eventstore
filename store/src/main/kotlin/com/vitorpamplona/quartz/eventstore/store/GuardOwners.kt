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

import com.vitorpamplona.quartz.eventstore.vespa.client.EventIndex
import com.vitorpamplona.quartz.eventstore.vespa.query.EventQuery
import com.vitorpamplona.quartz.eventstore.vespa.query.EventYql
import com.vitorpamplona.quartz.nip09Deletions.DeletionEvent
import com.vitorpamplona.quartz.nip62RequestToVanish.RequestToVanishEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The set of owners with ANY stored tombstone or vanish — the key that lets the
 * admission path SKIP its NIP-09/NIP-62 guard probes for everyone else, which
 * is nearly everyone (content authors seldom publish deletions). Both guard
 * probes query kind-5/62 docs whose AUTHOR is the inserted event's owner, so
 * "no stored guard doc by this author" proves both probes would come back
 * empty.
 *
 * Why this is safe:
 *  - OVER-flagging (an owner in the set with no live guard) only costs a probe.
 *  - UNDER-flagging cannot happen within the store's write model: the set is
 *    preloaded from the engine (two grouping queries over kind 5 and kind 62)
 *    and every guard this store subsequently stores is added via
 *    [noteGuardStored]. A single writer — or one owner-sharded lane, since
 *    lanes never insert another lane's owners (docs/multi-node-consistency.md)
 *    — therefore always sees its owners' guards. A FOREIGN feeder writing
 *    kind 5/62 directly to the engine would bypass this (and every other
 *    query-then-write guarantee); that deployment shape needs the probes, so
 *    it should disable the cache.
 *  - The engine's grouping is capped at [EventYql.MAX_AUTHOR_GROUPS]; a corpus
 *    with that many distinct deleters can't be represented faithfully, so the
 *    cache DISABLES itself and every insert probes, exactly as before.
 */
internal class GuardOwners(
    private val index: EventIndex,
) {
    @Volatile
    private var flagged: MutableSet<String>? = null

    @Volatile
    private var disabled = false

    private val loadLock = Mutex()

    /** False only when this owner provably has no stored tombstone/vanish — the probes can be skipped. */
    suspend fun mightHaveGuards(owner: String): Boolean {
        if (disabled) return true
        val set = loaded() ?: return true
        return owner in set
    }

    /** The subset of [owners] that can have guard docs at all (bulk path: query only these). */
    suspend fun filterFlagged(owners: Collection<String>): Collection<String> {
        if (disabled) return owners
        val set = loaded() ?: return owners
        return owners.filter { it in set }
    }

    /** A kind 5/62 by [author] was just stored — their events must probe from now on. */
    fun noteGuardStored(author: String) {
        flagged?.add(author)
    }

    private suspend fun loaded(): Set<String>? {
        flagged?.let { return it }
        if (disabled) return null
        loadLock.withLock {
            flagged?.let { return it }
            val deleters = index.distinctAuthors(EventQuery(kinds = listOf(DeletionEvent.KIND)))
            val vanishers = index.distinctAuthors(EventQuery(kinds = listOf(RequestToVanishEvent.KIND)))
            if (deleters.size >= EventYql.MAX_AUTHOR_GROUPS || vanishers.size >= EventYql.MAX_AUTHOR_GROUPS) {
                disabled = true
                return null
            }
            val set = ConcurrentHashMap.newKeySet<String>()
            set.addAll(deleters)
            set.addAll(vanishers)
            flagged = set
            return set
        }
    }
}
