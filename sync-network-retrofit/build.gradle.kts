plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yourlibrary.sync.retrofit"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // testInstrumentationRunner added in Commit 8 when instrumented tests exist
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
    implementation(project(":sync-core"))
    // Retrofit + OkHttp dependencies added in Commit 8
    testImplementation(libs.junit)
}
