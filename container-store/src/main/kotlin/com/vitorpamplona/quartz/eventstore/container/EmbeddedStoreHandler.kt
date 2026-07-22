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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.yahoo.component.annotation.Inject
import com.yahoo.container.jdisc.HttpRequest
import com.yahoo.container.jdisc.HttpResponse
import com.yahoo.container.jdisc.ThreadedHttpRequestHandler
import com.yahoo.documentapi.DocumentAccess
import com.yahoo.search.searchchain.ExecutionFactory
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.util.concurrent.Executor

/**
 * The real long-lived front-door shape for the embedded store — a jdisc
 * [ThreadedHttpRequestHandler] (NOT a searcher, which can't inject
 * [ExecutionFactory] without a component-graph cycle). It injects the two
 * long-lived container components — [ExecutionFactory] and [DocumentAccess] —
 * and holds ONE [EmbeddedEventStore] for the container's lifetime, exactly as a
 * production Nostr handler would.
 *
 * This exists to prove that seam end to end: `GET /embedded-store?n=N` inserts N
 * fresh events through the store (dedup + supersession + extraction + trust
 * projection over the in-container index), reads them back by author, and counts
 * them via the cold grouping delegate — so a deploy asserts the long-lived store,
 * `ExecutionFactory.newExecution`, the in-container read/write, and the loopback
 * cold path all work together. A production handler swaps this body for the Nostr
 * REQ/EVENT/CLOSE wire protocol.
 */
class EmbeddedStoreHandler
    @Inject
    constructor(
        executor: Executor,
        executionFactory: ExecutionFactory,
        documentAccess: DocumentAccess,
    ) : ThreadedHttpRequestHandler(executor) {
        private val store = EmbeddedEventStore.open(executionFactory, documentAccess)

        override fun handle(request: HttpRequest): HttpResponse {
            val n = request.getProperty("n")?.toIntOrNull() ?: 200
            val run = request.getProperty("run")?.toIntOrNull() ?: 1
            val events =
                SpikeCorpus.generate(
                    SpikeCorpus.Config(size = n, idPrefix = "e" + run.toString(16), authorPrefix = "f"),
                )
            val sampleAuthor = events.first().pubKey

            val body = StringBuilder("{")
            var status = 500
            try {
                runBlocking {
                    val accepted = store.batchInsert(events).size
                    val back = store.query<Event>(listOf(Filter(authors = listOf(sampleAuthor))))
                    val counted = store.count(listOf(Filter(authors = listOf(sampleAuthor))))
                    val ok = back.isNotEmpty() && counted == back.size
                    status = if (ok) 200 else 500
                    body
                        .append("\"ok\":")
                        .append(ok)
                        .append(",\"accepted\":")
                        .append(accepted)
                        .append(",\"author_timeline_hits\":")
                        .append(back.size)
                        .append(",\"count\":")
                        .append(counted)
                }
            } catch (t: Throwable) {
                body.append("\"ok\":false,\"error\":\"").append(chain(t)).append('"')
            }
            body.append('}')

            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            return object : HttpResponse(status) {
                override fun render(out: OutputStream) = out.write(bytes)

                override fun getContentType() = "application/json"
            }
        }

        private fun chain(t: Throwable): String {
            val sb = StringBuilder(t.javaClass.simpleName).append(": ").append(t.message)
            var c = t.cause
            while (c != null) {
                sb
                    .append(" <- ")
                    .append(c.javaClass.simpleName)
                    .append(": ")
                    .append(c.message)
                c = c.cause
            }
            return sb.toString().replace('"', '\'')
        }

        override fun destroy() {
            runCatching { store.close() }
            super.destroy()
        }
    }
