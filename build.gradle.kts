plugins {
    // AGP 9's built-in Kotlin support means the separate kotlin-android
    // plugin is no longer needed (or allowed); only the Compose compiler
    // plugin is still required.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
