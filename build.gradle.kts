plugins {
    id("com.android.application") version "9.4.0" apply false
    // Kotlin is built into AGP 9 (runtime KGP 2.2.10); kotlin-android is no longer applied.
    // The Compose compiler plugin must match that KGP version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
