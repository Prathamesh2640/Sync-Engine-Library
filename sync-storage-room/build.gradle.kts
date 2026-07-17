plugins {
    alias(libs.plugins.android.library)
    // KSP for Room codegen (replaces annotationProcessor — ADL-003 closed).
    // No standalone Kotlin plugin: AGP 9 supplies Kotlin (ADL-005).
    alias(libs.plugins.ksp)
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
