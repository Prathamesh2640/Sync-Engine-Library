import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    // F-18/F-19: Maven Central Portal publishing + API docs (javadoc.jar source).
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "io.github.prathamesh2640.sync.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // Consumer ProGuard rules are bundled into the AAR so host apps
        // automatically get the correct keep rules for the public API.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Coroutines: api() because Flow appears in the public API surface.
    // Host apps get this transitively — they do NOT need to declare it separately.
    api(libs.kotlinx.coroutines.core)

    // Unit tests — run on JVM, no emulator needed for pure Kotlin logic
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Turbine — asserts on StateFlow emissions (engine state transitions).
    testImplementation(libs.turbine)
}

// F-19: Maven Central Portal coordinates + POM. Shared boilerplate (license,
// developer, scm) is intentionally duplicated across the 5 publishable modules
// rather than factored into a convention plugin — see memory.md Pending Work
// for that as a future, optional simplification.
mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )

    // Manual review on the Central Portal UI before the first release goes
    // live — see PUBLISHING.md. Switch to publishAndReleaseToMavenCentral()
    // once the release process is trusted.
    // Explicit host: this plugin version (0.30.0) still defaults publishToMavenCentral()
    // to the legacy OSSRH host, but this account only has a Central Portal token/namespace
    // (no legacy staging profile) — the mismatch surfaces as a 402 "Cannot get
    // stagingProfiles" from createStagingRepository.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    // Version comes from the root build.gradle.kts `allprojects { version }` — the
    // single place to bump for a release (RELEASE_CHECKLIST.md step 3).
    coordinates("io.github.prathamesh2640", "sync-core", version.toString())

    pom {
        name.set("SyncEngine Core")
        description.set(
            "Core contracts and engine for SyncEngine, an offline-first sync library for " +
                "Android: SyncableEntity, sealed SyncResult/SyncError, ConflictResolver, " +
                "SyncNetworkAdapter, LocalSyncStore, and the SyncEngine implementation itself. " +
                "Depends only on the Kotlin stdlib and Coroutines — no Android framework classes."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/Prathamesh2640/Sync-Engine-Library")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("prathamesh2640")
                name.set("Prathamesh Sharma")
                email.set("prathameshsharma1694@gmail.com")
                url.set("https://github.com/Prathamesh2640")
            }
        }

        scm {
            url.set("https://github.com/Prathamesh2640/Sync-Engine-Library")
            connection.set("scm:git:git://github.com/Prathamesh2640/Sync-Engine-Library.git")
            developerConnection.set("scm:git:ssh://git@github.com/Prathamesh2640/Sync-Engine-Library.git")
        }
    }
}
