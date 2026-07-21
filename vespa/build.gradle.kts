plugins {
    alias(libs.plugins.kotlin.jvm)
    // Publishes the test doubles (MockVespaEngine, InMemoryProfileIndex) in
    // src/testFixtures to downstream module tests.
    `java-test-fixtures`
    alias(libs.plugins.vanniktech.mavenPublish)
}

dependencies {
    // Quartz (the Nostr library) is available here — reuse its primitives
    // (e.g. Hex) instead of re-implementing them.
    implementation(libs.quartz)
    // Only the JsonElement tree API is used, so no serialization plugin needed.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    // Writes go through Vespa's official feed client (async, HTTP/2 multiplexed,
    // retries + throttling built in). Its types stay out of our public API.
    implementation(libs.vespa.feed.client)
    // MockVespaEngine serves HTTP/1.1 + clear-text HTTP/2 (h2c) on one Jetty
    // port: the feed client refuses HTTP/1.1. Pinned to the feed client's Jetty line.
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines)
    testFixturesImplementation(libs.jetty.server)
    testFixturesImplementation(libs.jetty.http2.server)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Bundle the Vespa application package (schemas + rank profiles) into the jar as
// `vespa-app.zip`, so the library can deploy the schema to a running Vespa. It is
// zipped at build time from app/ — the very directory the CLI's docker flow
// deploys — so there is a single source of truth for the schema.
val vespaAppZip by tasks.registering(Zip::class) {
    archiveFileName.set("vespa-app.zip")
    destinationDirectory.set(layout.buildDirectory.dir("vespa-app"))
    from(layout.projectDirectory.dir("app"))
}

tasks.named<Copy>("processResources") {
    from(vespaAppZip)
}

mavenPublishing {
    coordinates(
        groupId = "com.vitorpamplona.quartz.eventstore",
        artifactId = "vespa",
        version = libs.versions.app.get(),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name = "Vespa Event Store: engine"
        description = "Vespa document shapes, EventQuery -> YQL, the Vespa clients, and the bundled Vespa application package."
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
