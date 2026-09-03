import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    // F-18/F-19: Maven Central Portal publishing + API docs (javadoc.jar source).
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "io.github.prathamesh2640.sync.workmanager"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        // WorkManager's test helpers spin up on the JVM under Robolectric.
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // api(): WorkManagerSyncScheduler implements sync-core's SyncScheduler and its
    // engineProvider returns a sync-core SyncEngine, so those types are in the
    // public API and must resolve transitively for consumers.
    api(project(":sync-core"))

    // WorkManager runtime. implementation(): no WorkManager type appears in this
    // module's public API (the scheduler hides it behind SyncScheduler).
    implementation(libs.androidx.work.runtime.ktx)

    // JVM tests: drive SyncWorker via WorkManagerTestInitHelper under Robolectric,
    // no device needed.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

// F-19: Maven Central Portal coordinates + POM.
mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )

    // Explicit host: this plugin version (0.30.0) still defaults publishToMavenCentral()
    // to the legacy OSSRH host, but this account only has a Central Portal token/namespace
    // (no legacy staging profile) — the mismatch surfaces as a 402 "Cannot get
    // stagingProfiles" from createStagingRepository.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    // JitPack (README's alternative install path) runs `publishToMavenLocal`
    // with no signing key configured — only sign when one is present so that
    // build isn't forced to fail alongside the real, key-bearing Central publish.
    if (project.hasProperty("signingInMemoryKey")) signAllPublications()

    // Version comes from the root build.gradle.kts `allprojects { version }` — the
    // single place to bump for a release (RELEASE_CHECKLIST.md step 3).
    coordinates("io.github.prathamesh2640", "sync-workmanager", version.toString())

    pom {
        name.set("SyncEngine WorkManager Scheduler")
        description.set(
            "WorkManager-backed background sync scheduling for SyncEngine. " +
                "WorkManagerSyncScheduler schedules a periodic SyncWorker with a network " +
                "constraint and exponential backoff; no WorkManager type appears in the public API."
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
