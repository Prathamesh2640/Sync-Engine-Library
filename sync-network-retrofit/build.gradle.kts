plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.prathamesh2640.sync.retrofit"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    // api(): RetrofitSyncAdapter implements sync-core's SyncNetworkAdapter<T> and
    // returns NetworkResult, so those types are in this module's public API and
    // must resolve transitively for consumers (module-guide).
    api(project(":sync-core"))

    // api(): retrofit2.Response<…> appears in RetrofitSyncAdapter's public
    // constructor (the host passes suspend call refs returning Response), so
    // consumers need Retrofit on their compile classpath. They use Retrofit anyway.
    // OkHttp is pulled in transitively; no logging interceptor is added here
    // (module-guide: never log network traffic from the library).
    api(libs.retrofit)

    // JVM unit tests: exercise the adapter over real HTTP with MockWebServer.
    // Gson converter is a test-only fixture — the library itself is
    // serialization-agnostic (the host owns their Retrofit converter).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit.converter.gson)
}
