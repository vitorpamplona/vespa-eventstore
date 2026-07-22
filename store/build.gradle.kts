plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.mavenPublish)
}

dependencies {
    // The store implements Quartz's IEventStore on top of :vespa's engine
    // port — both appear in its public API.
    api(libs.quartz)
    api(project(":vespa"))
    implementation(libs.kotlinx.coroutines)
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":vespa")))
    // Virtual-time test clock: measures read/write serialization deterministically
    // by injecting per-round-trip delays into the index (see BatchIngestConcurrencyTest).
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
    // Target Java 17 bytecode so the store's classes load in Vespa's Java-17 jdisc
    // container (the :container-store in-container path). Runtime deps already need 17.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    coordinates(
        groupId = "com.vitorpamplona.quartz.eventstore",
        artifactId = "store",
        version = libs.versions.app.get(),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name = "Vespa Event Store"
        description = "A Vespa-backed Quartz IEventStore with trust-ranked NIP-50 search: the open() front door, Nostr storage semantics, and the NIP-85 trust projection."
        inceptionYear = "2026"
        url = "https://github.com/vitorpamplona/vespa-eventstore/"
        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/vitorpamplona/vespa-eventstore/blob/main/LICENSE"
            }
        }
        developers {
            developer {
                id = "vitorpamplona"
                name = "Vitor Pamplona"
                url = "http://vitorpamplona.com"
                email = "vitor@vitorpamplona.com"
            }
        }
        scm {
            url = "https://github.com/vitorpamplona/vespa-eventstore/"
            connection = "https://github.com/vitorpamplona/vespa-eventstore/.git"
        }
    }
}
