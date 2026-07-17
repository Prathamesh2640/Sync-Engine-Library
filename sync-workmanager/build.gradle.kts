plugins {
    alias(libs.plugins.android.library)
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
