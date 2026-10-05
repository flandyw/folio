import java.util.Properties
import java.util.Base64
import java.io.Serializable
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
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

// Connection settings are independent of APK versions. AGP's BuildConfig includes
// VERSION_CODE/NAME, making every experimental reservation a Kotlin source change.
@CacheableTask
abstract class GenerateFocalConfigTask : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val publishableKey: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = outputDirectory.file("com/folio/notes/FocalBuildConfig.java").get().asFile
        source.parentFile.mkdirs()
        source.writeText("""
            package com.folio.notes;
            public final class FocalBuildConfig {
                private FocalBuildConfig() {}
                public static final String FOCAL_SUPABASE_URL = ${url.get()};
                public static final String FOCAL_SUPABASE_PUBLISHABLE_KEY = ${publishableKey.get()};
            }
        """.trimIndent() + "\n")
    }
}

// Keep version providers lazy: a new reservation should update APK metadata,
// without invalidating the configuration cache or generated connection settings.
data class FolioVersion(val code: Int, val name: String) : Serializable
val commitCount = providers.exec {
    commandLine("git", "-C", rootProject.projectDir.absolutePath, "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toInt() }
val checkedCommitCount = commitCount.zip(
    providers.gradleProperty("folioExperimentalCommitCount").orElse("")
) { count, expected ->
    require(expected.isEmpty() || expected.toIntOrNull() == count) {
        "HEAD changed after reserving the experimental build; rerun build.sh"
    }
    count
}
val experimentalBuild = providers.gradleProperty("folioExperimentalBuild").map {
    it.toIntOrNull() ?: error("folioExperimentalBuild must be an integer")
}.orElse(0)
val automaticVersion = checkedCommitCount.zip(experimentalBuild) { count, experimental ->
    require(count in 1..210_000 && experimental in 0..9999) {
        "Folio commit count or experimental build number is out of range"
    }
    val code = count.toLong() * 10_000 + experimental
    require(code in 1..2_100_000_000L) { "Generated Android versionCode is out of range" }
    val stableName = "${count / 100}.${(count / 10) % 10}.${count % 10}"
    FolioVersion(code.toInt(), if (experimental == 0) stableName else "$stableName-exp.$experimental")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun supabaseConfig(name: String, fallback: String): String {
    // Treat blank env/property values (e.g. unset GitHub secrets on forks) as missing
    // so local CI builds fall back to safe placeholders. Real values come from
    // environment, -P gradle properties, or untracked local.properties, never source.
    val value = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: localConfig.getProperty(name)?.takeIf { it.isNotBlank() } ?: fallback
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
// injected via FOCAL_SUPABASE_URL / FOCAL_SUPABASE_PUBLISHABLE_KEY secrets
// (see .github/workflows/*.yml and docs/focal.md). Mistake review and study sessions
// share this one project since ExamTrack was merged into Focal. Never commit real keys here.
tasks.register<GenerateFocalConfigTask>("generateFocalConfig") {
    url.set(supabaseConfig("FOCAL_SUPABASE_URL", "https://example.supabase.co"))
    publishableKey.set(supabaseConfig("FOCAL_SUPABASE_PUBLISHABLE_KEY", "sb_publishable_example_placeholder_for_local_ci_builds_only"))
    outputDirectory.set(layout.buildDirectory.dir("generated/source/focalConfig"))
}

android {
    namespace = "com.folio.notes"
    // compileSdk stays at 36; targetSdk stays 35 so no new runtime behavior is opted into.
    compileSdk = 36
    defaultConfig {
        applicationId = "com.folio.notes"
        minSdk = 26
        targetSdk = 35
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
    buildFeatures { compose = true; buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Release lint-vital re-analyses the whole app on every build; CI runs :app:lintDebug instead.
        checkReleaseBuilds = false
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

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            tasks.named<GenerateFocalConfigTask>("generateFocalConfig"),
            GenerateFocalConfigTask::outputDirectory
        )
        variant.outputs.forEach { output ->
            output.versionCode.set(automaticVersion.map { it.code })
            output.versionName.set(automaticVersion.map { it.name })
        }
    }
}

tasks.register<PrintReleaseVersionTask>("printReleaseVersion") {
    versionCode.set(automaticVersion.map { it.code })
    versionName.set(automaticVersion.map { it.name })
    releaseTag.set(automaticVersion.map { "v${it.name}" })
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
    // Bundled Latin OCR: available offline on first use, including scanned exam papers.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// JVM smoke checks use real org.json rather than Android's throwing SDK stubs. This standalone
// configuration is only resolved for verification and never packaged into the APK.
val backupSmokeRuntime = configurations.create("backupSmokeRuntime")

dependencies {
    add(backupSmokeRuntime.name, "org.json:json:20240303")
}

tasks.register("prepareBackupSmoke") {
    dependsOn("bundleDebugClassesToCompileJar")
    inputs.files(backupSmokeRuntime)
    doLast { /* Resolving the declared inputs populates the smoke runner's JVM dependency cache. */ }
}
