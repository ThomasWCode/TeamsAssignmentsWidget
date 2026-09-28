// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no kotlin-android plugin here.
// The compose and serialization compiler plugins still come from the Kotlin Gradle plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
