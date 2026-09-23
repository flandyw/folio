import java.util.Properties
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.folio.notes"
    // compileSdk stays at 36; targetSdk stays 35 so no new runtime behavior is opted into.
    compileSdk = 36
    defaultConfig {
        applicationId = "com.folio.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("VERSION_NAME").orElse("0.2.0").get()
        fun examTrackConfig(name: String, fallback: String): String {
            val local = Properties().apply {
                rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            // Treat blank env/property values (e.g. unset GitHub secrets on forks) as missing
            // so local CI builds fall back to safe placeholders. Real values come from
            // environment, -P gradle properties, or untracked local.properties, never source.
            val value = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
                ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
                ?: local.getProperty(name)?.takeIf { it.isNotBlank() } ?: fallback
            if (name.endsWith("KEY")) {
                val anonJwt = runCatching {
                    val claims = String(Base64.getUrlDecoder().decode(value.split('.')[1]))
                    Regex("\"role\"\\s*:\\s*\"anon\"").containsMatchIn(claims)
                }.getOrDefault(false)
                require(value.startsWith("sb_publishable_") || anonJwt) { "ExamTrack requires a publishable or anon client key" }
            } else require(value.startsWith("https://")) { "ExamTrack requires HTTPS" }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        // Safe placeholders for local/CI builds without credentials. Production values are
        // injected via EXAMTRACK_SUPABASE_URL / EXAMTRACK_SUPABASE_PUBLISHABLE_KEY secrets
        // (see .github/workflows/*.yml and docs/examtrack.md). Never commit real keys here.
        buildConfigField("String", "EXAMTRACK_SUPABASE_URL", examTrackConfig("EXAMTRACK_SUPABASE_URL", "https://example.supabase.co"))
        buildConfigField("String", "EXAMTRACK_SUPABASE_PUBLISHABLE_KEY", examTrackConfig("EXAMTRACK_SUPABASE_PUBLISHABLE_KEY", "sb_publishable_example_placeholder_for_local_ci_builds_only"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            storeFile = file(providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                .orElse("missing-release-keystore.p12").get())
            storeType = "PKCS12"
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
            keyAlias = "folio"
            keyPassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            // Shrinking is off until a baseline profile + R8 keep-rules for pdfbox are validated;
            // enabling fullMode blindly strips reflectively loaded font tables. See README build notes.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // targetSdk 35 is intentional (see comment above): no new runtime behavior is opted into yet.
        // Dependencies are pinned for Compose 1.8 / SDK 35 compatibility; TrustAllX509TrustManager
        // fires on a third-party TLS jar in the Gradle cache, not Folio code.
        disable += setOf("OldTargetApi", "GradleDependency", "TrustAllX509TrustManager")
        // Keep informational AutoboxingStateCreation visible without failing builds.
        informational += setOf("AutoboxingStateCreation")
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    // Pin Expressive APIs to the release compatible with Compose 1.8 and SDK 35.
    implementation("androidx.compose.material3:material3:1.5.0-alpha01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // Delivers baseline profiles to devices so the library/editor path is AOT-compiled on install.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // PDF text extraction for in-app search (Apache 2.0). Rendering stays on the framework PdfRenderer.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("org.json:json:20240303")
}
