plugins {
    // Kotlin compilation itself is built into AGP 9+ — no org.jetbrains.kotlin.android plugin needed.
    // See https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
