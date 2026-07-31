import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

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

// Library hygiene: every declaration must state its visibility explicitly, so a
// type is never public by accident. Compile error, not a warning.
kotlin {
    explicitApi()
}

dependencies {
    // implementation(): the dashboard observes sync state through sync-core
    // interfaces but exposes no sync-core type in its own public API.
    implementation(project(":sync-core"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
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

    publishToMavenCentral()
    signAllPublications()

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
