// Top-level build file — configuration applied to all subprojects/modules.
// Plugins declared here with `apply false` make the version resolvable in
// submodules via the version catalog without applying to the root project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
