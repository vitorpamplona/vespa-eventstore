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
import com.vitorpamplona.quartz.eventstore.store.TrustProjection
import com.vitorpamplona.quartz.eventstore.vespa.client.VespaEventIndex
import com.vitorpamplona.quartz.eventstore.vespa.client.VespaReputationIndex
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.yahoo.documentapi.DocumentAccess
import com.yahoo.search.searchchain.Execution
import com.yahoo.search.searchchain.ExecutionFactory

/**
 * The embedded front door: builds a [NostrEventStore] that runs INSIDE Vespa's
 * jdisc container, the mirror of `VespaEventStore.open(url)` for the co-located
 * case. It is the same store, the same [TrustProjection], the same query builders
 * and extractors — only the [com.vitorpamplona.quartz.eventstore.vespa.client.EventIndex]
 * transport changes:
 *
 *  - the hot read/write paths run in-process ([VespaLocalEventIndex]: reads via a
 *    fresh [ExecutionFactory] execution, writes via [DocumentAccess] messagebus);
 *  - the cold grouping/visit paths delegate to a loopback [VespaEventIndex]
 *    (reusing the tested HTTP implementation — no re-port);
 *  - trust ranking needs no code here at all: it runs in Vespa's rank profile
 *    (the schema imports the `author_ref` reputation parent server-side). The
 *    [VespaReputationIndex] wired into [TrustProjection] only MAINTAINS the
 *    reputation documents (a cold path), so it too rides the loopback client.
 *
 * The host must be a [com.yahoo.container.jdisc.RequestHandler] (or docproc) — NOT
 * a [com.yahoo.search.Searcher]. [ExecutionFactory] depends on the registry of all
 * searchers, so a searcher that injects it forms a component-graph CYCLE and the
 * container refuses to configure. A handler is outside that registry, so it can
 * inject [ExecutionFactory] and [DocumentAccess] (both long-lived components) and
 * hold the returned store for the container's lifetime. The store is a Quartz
 * `IEventStore`; [NostrEventStore.close] it on component deconstruct.
 *
 * This is the library seam. It deliberately does NOT open a socket or speak the
 * Nostr wire protocol; terminating client connections (WebSocket REQ/EVENT/CLOSE)
 * is the consumer's front door to build on top.
 *
 * CONNECTS EAGERLY: [open] builds a loopback client that handshakes the local
 * container on construction. The container isn't serving during its own startup,
 * so a host must NOT call [open] from its constructor — open it lazily, on the
 * first request (see [EmbeddedStoreHandler]). Otherwise the boot-time connection
 * is refused and the component graph fails.
 */
object EmbeddedEventStore {
    /**
     * @param executionSource yields a fresh backend [Execution] per read — the
     *   decoupling seam. A long-lived host passes `{ executionFactory.newExecution("vespa") }`
     *   ([open] overload); a per-request caller can pass `{ requestExecution }`. The
     *   chain MUST NOT contain the store's own components, or reads would recurse.
     * @param documentAccess injected; the messagebus write path.
     * @param loopbackUrl this container's own query/document endpoint, for the cold
     *   delegate and reputation upkeep. Defaults to the conventional local port.
     * @param relay the store's own relay url (NIP-62 vanish scope / NIP-42), or null.
     */
    fun open(
        executionSource: () -> Execution,
        documentAccess: DocumentAccess,
        loopbackUrl: String = "http://localhost:8080",
        relay: NormalizedRelayUrl? = null,
    ): NostrEventStore {
        val loopback = VespaEventIndex(baseUrl = loopbackUrl)
        val local =
            VespaLocalEventIndex(
                executionSource = executionSource,
                access = documentAccess,
                cold = loopback,
            )
        val reputations = VespaReputationIndex(baseUrl = loopbackUrl)
        return NostrEventStore(TrustProjection(local, reputations), relay = relay)
    }

    /**
     * Long-lived convenience: reads mint a fresh [Execution] from [executionFactory]
     * on the built-in `vespa` backend chain (dispatch → proton). Only callable from
     * a non-searcher host (see the class note on the injection cycle).
     */
    fun open(
        executionFactory: ExecutionFactory,
        documentAccess: DocumentAccess,
        loopbackUrl: String = "http://localhost:8080",
        backendChain: String = "vespa",
        relay: NormalizedRelayUrl? = null,
    ): NostrEventStore = open({ executionFactory.newExecution(backendChain) }, documentAccess, loopbackUrl, relay)
}
