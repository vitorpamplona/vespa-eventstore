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
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.vitorpamplona.quartz.eventstore.benchmark.EventStoreBenchmark")
}
