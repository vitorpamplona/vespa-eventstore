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

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

/**
 * The CI gate for the in-container path: stand up a REAL Vespa, deploy the freshly
 * built `containerstore` bundle (with the `local` search chain + the embedded
 * `handler`), and assert the store running INSIDE the container answers correctly.
 * This is what keeps the embedded path from rotting — the same recipe `deploy.sh`
 * runs by hand, as an assertion.
 *
 * Two checks, both hitting code that runs entirely in-container:
 *  - the embedded handler round-trips (insert → read-back → cold count agree), which
 *    exercises the composite index, the DocumentAccess write, and trust projection;
 *  - the store-insert A/B reports `parity=OK` — the in-container store accepts and
 *    rejects a realistic corpus identically to the HTTP store.
 */
class EmbeddedStoreIT {
    @Test
    fun `embedded store runs correctly in-container`() {
        val bundle = File(System.getProperty("containerstore.bundle.jar") ?: error("bundle path not set by Gradle"))
        assertTrue(bundle.exists(), "bundle jar missing: $bundle")

        // Prefer an already-running Vespa if the env names one (lets the IT run where
        // testcontainers can't reach the Docker daemon — e.g. some DinD sandboxes);
        // otherwise stand one up with testcontainers. Same assertions either way.
        val envQuery = System.getenv("VESPA_IT_QUERY_URL")
        val envConfig = System.getenv("VESPA_IT_CONFIG_URL")
        if (envQuery != null && envConfig != null) {
            runAssertions(envQuery, envConfig, bundle)
            return
        }

        assumeTrue(dockerAvailable(), "no VESPA_IT_QUERY_URL and Docker not reachable by testcontainers — skipping")
        GenericContainer("vespaengine/vespa:latest")
            .withExposedPorts(QUERY_PORT, CONFIG_PORT)
            .waitingFor(Wait.forHttp("/state/v1/health").forPort(CONFIG_PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5))
            .use { vespa ->
                vespa.start()
                runAssertions(
                    "http://${vespa.host}:${vespa.getMappedPort(QUERY_PORT)}",
                    "http://${vespa.host}:${vespa.getMappedPort(CONFIG_PORT)}",
                    bundle,
                )
            }
    }

    private fun runAssertions(
        query: String,
        config: String,
        bundle: File,
    ) {
        deploy(config, buildAppZip(bundle))
        awaitContainer(query)

        // 1) Embedded handler: long-lived store, in-container read/write + cold count.
        val embedded = get("$query/embedded-store?n=500&run=1")
        assertTrue(embedded.contains("\"ok\":true"), "embedded handler not ok: $embedded")

        // 2) Store-insert A/B: in-container store accepts/rejects like the HTTP store.
        val ab = get("$query/search/?searchChain=local&storeinsert=1&n=500&rounds=2&run=1")
        assertTrue(ab.contains("\"parity\":\"OK"), "in-container insert parity failed: $ab")
    }

    /** Rebuild vespa-app.zip with the `local` chain + embedded handler + the bundle. */
    private fun buildAppZip(bundle: File): ByteArray {
        val src = javaClass.getResourceAsStream("/vespa-app.zip") ?: error("vespa-app.zip not on classpath (needs :vespa)")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            ZipInputStream(src).use { zin ->
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    val entry = e
                    if (!entry.isDirectory) {
                        val bytes = zin.readBytes()
                        zout.putNextEntry(ZipEntry(entry.name))
                        if (entry.name.endsWith("services.xml")) {
                            zout.write(injectChain(bytes.toString(Charsets.UTF_8)).toByteArray())
                        } else {
                            zout.write(bytes)
                        }
                        zout.closeEntry()
                    }
                    e = zin.nextEntry
                }
            }
            zout.putNextEntry(ZipEntry("components/containerstore.jar"))
            bundle.inputStream().use { it.copyTo(zout) }
            zout.closeEntry()
        }
        return out.toByteArray()
    }

    private fun injectChain(services: String): String {
        val pkg = "com.vitorpamplona.quartz.eventstore.container"
        val block =
            "<search>\n" +
                "      <chain id=\"local\" inherits=\"vespa\">\n" +
                "        <searcher id=\"$pkg.LocalStoreSearcher\" bundle=\"containerstore\" />\n" +
                "        <searcher id=\"$pkg.InsertSpikeSearcher\" bundle=\"containerstore\" />\n" +
                "        <searcher id=\"$pkg.StoreInsertSpikeSearcher\" bundle=\"containerstore\" />\n" +
                "      </chain>\n    </search>\n" +
                "    <handler id=\"$pkg.EmbeddedStoreHandler\" bundle=\"containerstore\">\n" +
                "      <binding>http://*/embedded-store*</binding>\n" +
                "    </handler>"
        require(services.contains("<search />")) { "services.xml has no <search /> to replace" }
        return services.replace("<search />", block)
    }

    private fun deploy(
        configUrl: String,
        zip: ByteArray,
    ) {
        val resp =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("$configUrl/application/v2/tenant/default/prepareandactivate"))
                    .header("Content-Type", "application/zip")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertTrue(resp.statusCode() < 300 && resp.body().contains("activated"), "deploy failed: ${resp.statusCode()} ${resp.body().take(300)}")
    }

    /** Poll the query container until it serves (bundle loaded + reconfig applied). */
    private fun awaitContainer(queryUrl: String) {
        val deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos()
        var last = ""
        while (System.nanoTime() < deadline) {
            val ok =
                runCatching {
                    http
                        .send(
                            HttpRequest.newBuilder(URI.create("$queryUrl/state/v1/health")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(),
                        ).also { last = "${it.statusCode()}" }
                        .statusCode() == 200
                }.getOrDefault(false)
            if (ok) return
            Thread.sleep(2_000)
        }
        error("container did not come up (last=$last)")
    }

    private fun get(url: String): String =
        http
            .send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(1))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    private fun dockerAvailable(): Boolean = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

    private companion object {
        const val QUERY_PORT = 8080
        const val CONFIG_PORT = 19071

        // Vespa is local (testcontainers) — never route through the egress proxy.
        val http: HttpClient =
            HttpClient
                .newBuilder()
                .proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(10))
                .build()
    }
}
