plugins {
    alias(libs.plugins.kotlin.jvm)
}

// EXPERIMENT (Phase B): the in-container store. VespaLocalEventIndex runs the
// store's NIP-01 queries through the jdisc search chain (execution.search) in
// process instead of over HTTP, behind the same EventIndex seam — so
// NostrEventStore serves reads with no external hop. Packaged as an OSGi bundle
// for Vespa's container (see container-bench build for the bundle recipe). NOT
// published to Maven Central.
dependencies {
    implementation(project(":store"))
    implementation(project(":vespa"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    // Vespa container component APIs — PROVIDED by the jdisc container at runtime.
    compileOnly(libs.vespa.container.dev)
}

kotlin {
    jvmToolchain(21)
    // Java-17 bytecode so these classes load in Vespa's Java-17 jdisc container.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
