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
    // Binary Compatibility Validator is applied to the root project (it configures
    // subprojects itself), so it is applied here, not `apply false`.
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // Not published, so their public API is not tracked:
    // the sample app and the debug-only Compose dashboard.
    ignoredProjects.addAll(listOf("sample-app", "sync-ui-dashboard"))
}
