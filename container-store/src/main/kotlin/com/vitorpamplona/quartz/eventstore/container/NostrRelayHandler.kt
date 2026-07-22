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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.OutputStream

/**
 * A PROTOTYPE Nostr relay front door running inside Vespa's container, on top of
 * the embedded store — the "last mile" over [EmbeddedEventStore]. It shows how a
 * real relay drives the in-container store: parse a Nostr client message, call
 * `store.insert` / `store.query`, and answer in the Nostr message shapes.
 *
 * TRANSPORT CAVEAT: real Nostr is a WebSocket protocol (a persistent socket with
 * live subscriptions). jdisc's native model is HTTP request/response, and hosting
 * a WebSocket from a bundle is a separate spike, so this prototype speaks the same
 * Nostr *messages* over HTTP POST — one client message in, the response frames out
 * as a JSON array. The relay LOGIC and the embedded-store wiring are exactly what
 * a WebSocket relay would use; only the socket is swapped. Wire it behind a real
 * WS transport and the body of [handle] is unchanged.
 *
 * POST `/nostr` a single Nostr client message:
 *   ["EVENT", {..event..}]          -> [["OK","<id>",true,""]]
 *   ["REQ","<sub>", {filter}, ...]  -> [["EVENT","<sub>",{..}], ..., ["EOSE","<sub>"]]
 *   ["CLOSE","<sub>"]               -> [["CLOSED","<sub>",""]]
 */
class NostrRelayHandler
    @Inject
    constructor(
        executor: java.util.concurrent.Executor,
        executionFactory: ExecutionFactory,
        documentAccess: DocumentAccess,
    ) : ThreadedHttpRequestHandler(executor) {
        // One long-lived store for the container's lifetime; opened lazily so the
        // loopback client connects only once :8080 is serving (see EmbeddedStoreHandler).
        private val storeLazy = lazy { EmbeddedEventStore.open(executionFactory, documentAccess) }
        private val store get() = storeLazy.value

        override fun handle(request: HttpRequest): HttpResponse {
            val body = request.data.readBytes().toString(Charsets.UTF_8)
            val frames =
                runCatching { dispatch(body) }
                    .getOrElse { listOf(noticeFrame("error: ${it.javaClass.simpleName}: ${it.message}")) }
            return json("[" + frames.joinToString(",") + "]")
        }

        /** One client message -> the relay frames it produces, each already-serialized JSON. */
        private fun dispatch(body: String): List<String> {
            val msg = Json.parseToJsonElement(body).jsonArray
            return when (val type = msg[0].jsonPrimitive.content) {
                "EVENT" -> listOf(handleEvent(msg[1].jsonObject))
                "REQ" -> handleReq(msg[1].jsonPrimitive.content, msg.drop(2).map { it.jsonObject })
                "CLOSE" -> listOf(frame("CLOSED", msg[1].jsonPrimitive.content, "\"\""))
                else -> listOf(noticeFrame("unsupported message type: $type"))
            }
        }

        /** NIP-01 EVENT: store the event, answer with an OK frame. */
        private fun handleEvent(eventObj: JsonObject): String {
            val event = Event.fromJson(eventObj.toString())
            // A production relay VERIFIES event.sig here (NIP-01) and rejects invalid
            // signatures before storing — omitted in this prototype so it's trivial to
            // drive; the store itself never verifies (that's the ingest path's job).
            return try {
                runBlocking { store.insert(event) }
                frame("OK", event.id, "true", "\"\"")
            } catch (t: Throwable) {
                frame("OK", event.id, "false", enc("error: ${t.message}"))
            }
        }

        /** NIP-01 REQ: run the filters through the store, stream EVENT frames + EOSE. */
        private fun handleReq(
            sub: String,
            filterObjs: List<JsonObject>,
        ): List<String> {
            val filters = filterObjs.map(::parseFilter)
            val events = runBlocking { store.query<Event>(filters) }
            val out = ArrayList<String>(events.size + 1)
            events.forEach { out += """["EVENT",${enc(sub)},${it.toJson()}]""" }
            out += frame("EOSE", sub)
            return out
        }

        /**
         * A Nostr filter object -> Quartz [Filter]. `#x` keys become single-letter tag
         * filters. CRITICAL: absent fields must be NULL, not empty — in Quartz an empty
         * list/map means "match nothing", while null means "no constraint" (see
         * FilterMapping.toEventQuery). An absent key here yields null.
         */
        private fun parseFilter(o: JsonObject): Filter {
            fun strs(k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content }
            val tags = HashMap<String, List<String>>()
            for ((k, v) in o) {
                if (k.length == 2 && k[0] == '#') tags[k.substring(1)] = v.jsonArray.map { it.jsonPrimitive.content }
            }
            return Filter(
                ids = strs("ids"),
                authors = strs("authors"),
                kinds = o["kinds"]?.jsonArray?.map { it.jsonPrimitive.int },
                tags = tags.ifEmpty { null },
                since = o["since"]?.jsonPrimitive?.longOrNull,
                until = o["until"]?.jsonPrimitive?.longOrNull,
                limit = o["limit"]?.jsonPrimitive?.intOrNull,
                search = o["search"]?.jsonPrimitive?.content,
            )
        }

        // ---- tiny framing helpers (each returns one serialized JSON array element) ----
        private fun frame(vararg parts: String) = "[" + parts.joinToString(",") { if (it.startsWith("\"") || it == "true" || it == "false") it else enc(it) } + "]"

        private fun noticeFrame(text: String) = """["NOTICE",${enc(text)}]"""

        private fun enc(s: String) = JsonPrimitive(s).toString()

        private fun json(text: String): HttpResponse {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return object : HttpResponse(200) {
                override fun render(out: OutputStream) = out.write(bytes)

                override fun getContentType() = "application/json"
            }
        }

        override fun destroy() {
            if (storeLazy.isInitialized()) runCatching { store.close() }
            super.destroy()
        }
    }
