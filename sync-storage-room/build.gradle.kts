import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    // KSP for Room codegen (replaces annotationProcessor — ADL-003 closed).
    // No standalone Kotlin plugin: AGP 9 supplies Kotlin (ADL-005).
    alias(libs.plugins.ksp)
    // F-18/F-19: Maven Central Portal publishing + API docs (javadoc.jar source).
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "io.github.prathamesh2640.sync.room"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        // Robolectric needs Android resources/assets on the unit-test classpath so
        // the in-memory Room database can spin up on the JVM.
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // api(): RoomSyncAdapter implements sync-core's LocalSyncStore<T> and is
    // generic over SyncableEntity, so those types appear in this module's public
    // API and must be visible to consumers transitively (module-guide: use api
    // only when sync-core types are already in the public API — which they are).
    api(project(":sync-core"))

    // Room — KSP replaces the old annotationProcessor path.
    // api() for room-runtime: RoomSyncAdapter's public `rawQuery` parameter type is
    // androidx.sqlite's SupportSQLiteQuery, so consumers must resolve it
    // transitively (they use Room directly anyway).
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // JVM unit tests (Robolectric): a real in-memory Room DB needs KSP to generate
    // the test DAO/database implementations, plus the AndroidX test runtime.
    // Running under Robolectric means these execute via testDebugUnitTest with no
    // connected device — CI- and headless-friendly (industry standard for a library).
    testImplementation(libs.junit)
    kspTest(libs.androidx.room.compiler)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
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
    coordinates("io.github.prathamesh2640", "sync-storage-room", version.toString())

    pom {
        name.set("SyncEngine Room Storage")
        description.set(
            "Room-backed LocalSyncStore implementation for SyncEngine. RoomSyncAdapter reads " +
                "and writes the host's own entity table (single source of truth, no separate " +
                "operation-queue table) through a plain host @Dao — no generic base DAO."
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
