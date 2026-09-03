// Top-level build file — configuration applied to all subprojects/modules.
// Plugins declared here with `apply false` make the version resolvable in
// submodules via the version catalog without applying to the root project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // KSP resolvable in submodules (applied only in :sync-storage-room). Kept
    // separate from AGP's built-in Kotlin — the standalone Kotlin Android plugin
    // must never be re-added (ADL-005).
    alias(libs.plugins.ksp) apply false
    // Compose compiler, resolvable in the Compose modules (:sync-ui-dashboard,
    // :sample-app). Applies only the Compose compiler plugin — not the standalone
    // Kotlin Android plugin (ADL-005).
    alias(libs.plugins.compose.compiler) apply false
    // Maven Central Portal publishing (F-18) — resolvable in the 5 publishable
    // modules; never applied at root or in :sample-app.
    alias(libs.plugins.maven.publish) apply false
    // Dokka — applied here (not apply-false) so the root project can run
    // `dokkaHtmlMultiModule`, aggregating every publishable module's API docs
    // (F-23 -> GitHub Pages). Each publishable module also applies it directly.
    alias(libs.plugins.dokka)
}

// F-18: shared Maven coordinates group/version for every module. This is the
// SINGLE place the release version is declared — the 5 publishable modules pass
// `version.toString()` to their own `coordinates(...)` call (F-19), so bumping
// here bumps everything (RELEASE_CHECKLIST.md step 3).
allprojects {
    group = "io.github.prathamesh2640"
    version = "0.2.0"
}

// Library hygiene, centralized: every declaration in a publishable module must
// state its visibility explicitly, so a type is never public by accident
// (compile error, not a warning). Scoped to the 5 publishable modules via the
// maven-publish plugin they already all apply — the same signal that already
// distinguishes them from :sample-app elsewhere in this file — instead of a
// new marker, and instead of repeating this block in each module.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension> {
            explicitApi()
        }
    }
}
