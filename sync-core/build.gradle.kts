plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yourlibrary.sync.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // Consumer ProGuard rules are bundled into the AAR so host apps
        // automatically get the correct keep rules for the public API.
        consumerProguardFiles("consumer-rules.pro")
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
    // Coroutines: api() because Flow appears in the public API surface.
    // Host apps get this transitively — they do NOT need to declare it separately.
    api(libs.kotlinx.coroutines.core)

    // Unit tests — run on JVM, no emulator needed for pure Kotlin logic
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
