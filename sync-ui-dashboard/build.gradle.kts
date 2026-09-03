import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    // Compose compiler plugin (Kotlin 2.x). Not the standalone Kotlin Android
    // plugin — AGP 9 supplies Kotlin itself (ADL-005).
    alias(libs.plugins.compose.compiler)
    // F-18/F-19: Maven Central Portal publishing + API docs (javadoc.jar source).
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "io.github.prathamesh2640.sync.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // api(): SyncDashboardState is public and its `syncState` property is typed
    // io.github.prathamesh2640.sync.core.model.SyncState, so a sync-core type *is*
    // in this module's public API. With implementation() sync-core lands only in
    // the published runtime variant, and a consumer that takes this module without
    // depending on sync-core itself cannot compile against SyncDashboardState.
    // (This also carries kotlinx-coroutines through for the public StateFlow
    // parameter, which sync-core already exposes via api().)
    api(project(":sync-core"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    // api(): androidx.compose.ui.Modifier appears in SyncDashboardRoute's public
    // signature, so it belongs on consumers' compile classpath, not just runtime.
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
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
    coordinates("io.github.prathamesh2640", "sync-ui-dashboard", version.toString())

    pom {
        name.set("SyncEngine Debug Dashboard")
        description.set(
            "Jetpack Compose debug dashboard for SyncEngine: SyncDashboardActivity and " +
                "SyncDashboardRoute observe sync state via SyncEngine's StateFlow, with a " +
                "\"Trigger Sync Now\" action. Intended for debugImplementation-only use."
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
