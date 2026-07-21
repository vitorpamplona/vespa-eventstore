plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Internal tooling: a head-to-head benchmark of this framework's IEventStore
// against Quartz's SQLite one. NOT published (no vanniktech plugin) — it depends
// on the SQLite driver and a fixed corpus generator that have no place in the
// released artifacts.
dependencies {
    // :store brings the Vespa-backed IEventStore and, transitively (api), Quartz —
    // whose SQLite `EventStore` is the comparison target.
    implementation(project(":store"))
    // The bundled SQLite driver Quartz's EventStore opens (BundledSQLiteDriver).
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines)
    // The corpus generator builds canonical event JSON with the JsonElement tree API.
    implementation(libs.kotlinx.serialization.json)

    // VespaParityIT stands up a real Vespa in a container and runs the parity
    // battery against it — the CI correctness gate. Skips when Docker is absent.
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers)
}

tasks.test {
    useJUnitPlatform {
        // The Vespa integration test (VespaParityIT) stands up a container and is
        // slow, so the default build stays unit-only. Run it with -Pintegration
        // (the CI 'integration' job does); it self-skips without a Docker daemon.
        if (!project.hasProperty("integration")) excludeTags("integration")
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.vitorpamplona.quartz.eventstore.benchmark.EventStoreBenchmark")
}

// Large corpora need heap: a BENCH_SIZE=1M run holds the generated Event list
// (~2 GB) plus the SQLite stores' native memory. BENCH_HEAP sets -Xmx for the
// benchmark JVM (default 2g keeps the everyday 30k run lean).
tasks.named<JavaExec>("run") {
    maxHeapSize = System.getenv("BENCH_HEAP") ?: "2g"
}
