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

    // Integration test: stand up a real Vespa, deploy THIS bundle, assert the
    // in-container store answers correctly. Reuses :vespa's bundled app package
    // (vespa-app.zip) off the classpath.
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(libs.testcontainers)
}

kotlin {
    jvmToolchain(21)
    // Java-17 bytecode so these classes load in Vespa's Java-17 jdisc container.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The deployable OSGi bundle: this module's classes plus its full runtime closure
// (store, vespa, Quartz, kotlin/coroutines/serialization, okhttp, ...) embedded
// under dependencies/ and wired with a Bundle-ClassPath. The container APIs are
// compileOnly, so runtimeClasspath already EXCLUDES them (they come from the
// platform); DynamicImport-Package: * resolves them at load. This replaces the
// hand-rolled Python/shell assembly — the closure is now whatever Gradle resolves,
// so it can't drift, and it builds in CI.
val containerBundle by tasks.registering(Jar::class) {
    description = "Assembles the containerstore OSGi bundle (classes + embedded runtime closure)."
    group = "build"
    archiveBaseName.set("containerstore-bundle")
    destinationDirectory.set(layout.buildDirectory.dir("bundle"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val jarTask = tasks.named("jar", Jar::class)
    val ownJar = jarTask.flatMap { it.archiveFile }
    dependsOn(jarTask)

    // Deferred closures so the jars are copied as FILES (into dependencies/) at
    // execution time — never expanded — and resolution happens after the jar exists.
    from({ ownJar.get().asFile }) { into("dependencies") }
    from({ configurations.getByName("runtimeClasspath").files }) { into("dependencies") }

    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Bundle-ManifestVersion" to "2",
            "Bundle-Name" to "containerstore",
            "Bundle-SymbolicName" to "containerstore",
            "Bundle-Version" to libs.versions.app.get(),
            "DynamicImport-Package" to "*",
        )
    }
    // Bundle-ClassPath must list every embedded jar; compute once inputs resolve.
    doFirst {
        val jars = listOf(ownJar.get().asFile) + configurations.getByName("runtimeClasspath").files
        manifest.attributes["Bundle-ClassPath"] = (listOf(".") + jars.map { "dependencies/${it.name}" }).joinToString(",")
    }
}

// The IT deploys the freshly-built bundle, so it depends on it and gets its path.
tasks.test {
    useJUnitPlatform()
    dependsOn(containerBundle)
    val bundleJar = containerBundle.flatMap { it.archiveFile }
    inputs.file(bundleJar)
    doFirst { systemProperty("containerstore.bundle.jar", bundleJar.get().asFile.absolutePath) }
}
