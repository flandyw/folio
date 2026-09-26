import java.util.Properties
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class PrintReleaseVersionTask : DefaultTask() {
    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val releaseTag: Property<String>

    @TaskAction
    fun printReleaseVersion() {
        println("VERSION_CODE=${versionCode.get()}")
        println("VERSION_NAME=${versionName.get()}")
        println("RELEASE_TAG=${releaseTag.get()}")
    }
}

// Derive every local and release build from the same full Git commit count.
val automaticVersionCode = providers.exec {
    commandLine("git", "-C", rootProject.projectDir.absolutePath, "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.get()
require(automaticVersionCode in 1..2_100_000_000) { "Generated Android versionCode is out of range" }
val automaticVersionName = "${automaticVersionCode / 100}.${(automaticVersionCode / 10) % 10}.${automaticVersionCode % 10}"
// Keep release tags aligned with the version shown in the app and release assets.
val automaticReleaseTag = "v$automaticVersionName"

android {
    namespace = "com.folio.notes"
    // compileSdk stays at 36; targetSdk stays 35 so no new runtime behavior is opted into.
    compileSdk = 36
    defaultConfig {
        applicationId = "com.folio.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = automaticVersionCode
        versionName = automaticVersionName
        fun supabaseConfig(name: String, fallback: String): String {
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
                require(value.startsWith("sb_publishable_") || anonJwt) { "$name requires a publishable or anon client key" }
            } else require(value.startsWith("https://")) { "$name requires HTTPS" }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        // Safe placeholders for local/CI builds without credentials. Production values are
        // injected via EXAMTRACK_SUPABASE_URL / EXAMTRACK_SUPABASE_PUBLISHABLE_KEY secrets
        // (see .github/workflows/*.yml and docs/examtrack.md). Never commit real keys here.
        buildConfigField("String", "EXAMTRACK_SUPABASE_URL", supabaseConfig("EXAMTRACK_SUPABASE_URL", "https://example.supabase.co"))
        buildConfigField("String", "EXAMTRACK_SUPABASE_PUBLISHABLE_KEY", supabaseConfig("EXAMTRACK_SUPABASE_PUBLISHABLE_KEY", "sb_publishable_example_placeholder_for_local_ci_builds_only"))
        buildConfigField("String", "FOCAL_SUPABASE_URL", supabaseConfig("FOCAL_SUPABASE_URL", "https://example.supabase.co"))
        buildConfigField("String", "FOCAL_SUPABASE_PUBLISHABLE_KEY", supabaseConfig("FOCAL_SUPABASE_PUBLISHABLE_KEY", "sb_publishable_example_placeholder_for_local_ci_builds_only"))
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
    // jvmTarget is not set explicitly: built-in Kotlin defaults it to
    // compileOptions.targetCompatibility (Java 17, see above).
}

tasks.register<PrintReleaseVersionTask>("printReleaseVersion") {
    versionCode.set(automaticVersionCode)
    versionName.set(automaticVersionName)
    releaseTag.set(automaticReleaseTag)
}

// AGP 9 puts android.jar first on the unit-test *compile* classpath, while the
// runtime classpath still puts dependency jars first. The platform org.json in
// android.jar lacks JSONObject.similar(), so unit tests using it stopped compiling.
// Reorder: dependency jars first, android.jar last — matching the runtime order.
// Deferred to afterEvaluate: AGP wires these classpaths after the plugins block.
afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        if (name.contains("UnitTest")) {
            val snapshot = classpath.toList()
            setClasspath(files(snapshot.filter { it.name != "android.jar" } + snapshot.filter { it.name == "android.jar" }))
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name.contains("UnitTest")) {
            val snapshot = libraries.toList()
            (libraries as org.gradle.api.file.ConfigurableFileCollection).setFrom(
                snapshot.filter { it.name != "android.jar" } + snapshot.filter { it.name == "android.jar" }
            )
        }
    }
}

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
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
