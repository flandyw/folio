plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.folio.notes"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.folio.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("VERSION_NAME").orElse("0.1.1").get()
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
        getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { compose = true }
    lint {
        // These dependency detectors crash with IncompatibleClassChangeError against
        // AGP 8.7's Kotlin analysis API. Re-enable when upgrading the lint toolchain.
        disable += setOf("NullSafeMutableLiveData", "FrequentlyChangingValue")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
