plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yourlibrary.sync.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // testInstrumentationRunner added in Commit 10 when UI tests exist
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
    // Jetpack Compose + appcompat + material added in Commit 10
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
