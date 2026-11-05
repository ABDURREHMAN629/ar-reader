// Top-level build file where you can add configuration options common to all sub-projects/modules.
// NOTE: AGP 9.0+ has built-in Kotlin support, so the org.jetbrains.kotlin.android plugin
// must NOT be applied. See https://developer.android.com/build/releases/agp-9-0-0-release-notes
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
