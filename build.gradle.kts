import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "2.4.20"
    `java-library`
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// Coordinates, version and POM metadata live in gradle.properties (GROUP, POM_ARTIFACT_ID,
// VERSION_NAME, POM_*); the maven-publish plugin reads them from there.
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

// Honk.VERSION (and the User-Agent) comes from VERSION_NAME, so the published version and the
// version the client reports can never drift apart.
val generateSdkVersion = tasks.register("generateSdkVersion") {
    val sdkVersion = version.toString()
    val outputDir = layout.buildDirectory.dir("generated/sources/sdkVersion/kotlin")
    inputs.property("version", sdkVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("app/honkme/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Generated from VERSION_NAME in gradle.properties. Do not edit.
            |package app.honkme
            |
            |internal const val SDK_VERSION: String = "$sdkVersion"
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateSdkVersion) }

dependencies {
    // Coroutines for the suspend API (cancellation, withTimeout) and CompletableFuture interop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        if (System.getenv("HONK_URL").isNullOrEmpty() || System.getenv("HONK_KEY").isNullOrEmpty()) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs the integration tests against HONK_URL / HONK_KEY."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    testLogging { events("passed", "failed", "skipped") }
}

dokka {
    moduleName.set("Honk")
}

mavenPublishing {
    // -sources.jar from src/main, -javadoc.jar with the Dokka HTML reference.
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = SourcesJar.Sources()))
    // Sonatype Central Portal. The release workflow runs publishAndReleaseToMavenCentral with
    // ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password (a portal user token).
    publishToMavenCentral()
    // Central requires signed artifacts. The release workflow passes the key in memory
    // (ORG_GRADLE_PROJECT_signingInMemoryKey / ...KeyPassword); without one (local builds,
    // publishToMavenLocal, CI) nothing is signed.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}
