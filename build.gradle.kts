import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "2.4.20"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// Placeholders, also used in the package name me.honk: see ../README.md "Publishing checklist".
group = "me.honk"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

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

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    // Maven Central requires signed artifacts; the release workflow provides the key
    // (ORG_GRADLE_PROJECT_signingInMemoryKey). Local builds and publishToMavenLocal skip signing.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "honk-me", version.toString())
    pom {
        name.set("honk-me")
        description.set("Official Kotlin/Java client for Honk: send events from apps, scripts and automations to your phone, with retries and idempotency built in.")
        inceptionYear.set("2026")
        url.set("https://github.com/honk-me/honk")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("honk")
                name.set("Honk contributors")
                url.set("https://github.com/honk-me")
            }
        }
        scm {
            url.set("https://github.com/honk-me/honk")
            connection.set("scm:git:https://github.com/honk-me/honk.git")
            developerConnection.set("scm:git:ssh://git@github.com/honk-me/honk.git")
        }
    }
}
