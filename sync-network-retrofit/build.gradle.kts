import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    // F-18/F-19: Maven Central Portal publishing + API docs (javadoc.jar source).
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "io.github.prathamesh2640.sync.retrofit"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    coordinates("io.github.prathamesh2640", "sync-network-retrofit", version.toString())

    pom {
        name.set("SyncEngine Retrofit Adapter")
        description.set(
            "Retrofit-backed SyncNetworkAdapter implementation for SyncEngine. Wraps host-" +
                "supplied suspend call references returning retrofit2.Response, mapping every " +
                "outcome to the sealed NetworkResult type — never throws across the boundary."
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
