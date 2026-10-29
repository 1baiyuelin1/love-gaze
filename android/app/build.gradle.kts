import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Deployment values stay in the ignored android/local.properties file.
val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val syncOrigin = localConfig.getProperty("sync.origin")?.trim()
    ?: error("Set sync.origin in android/local.properties; see README.md.")
val origin = URI(syncOrigin)
require(origin.scheme == "https" && !origin.host.isNullOrBlank() &&
    origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null &&
    origin.rawPath.isNullOrEmpty()) {
    "sync.origin must be an HTTPS origin without a path or trailing slash."
}
val legacyEnvironment = localConfig.getProperty("sync.legacyCloudbaseEnvironment", "").trim()
require(legacyEnvironment.isEmpty() || legacyEnvironment.matches(Regex("[a-z0-9-]+"))) {
    "sync.legacyCloudbaseEnvironment must contain only lowercase letters, digits and hyphens."
}

android {
    namespace = "app.champion"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.champion"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.6.0"
        buildConfigField("String", "SYNC_ORIGIN", "\"$syncOrigin\"")
        buildConfigField("String", "LEGACY_CLOUDBASE_ENV", "\"$legacyEnvironment\"")
    }
    flavorDimensions += "identity"
    productFlavors {
        create("white") {
            dimension = "identity"
            applicationIdSuffix = ".white"
            buildConfigField("String", "DEVICE_ROLE", "\"white\"")
        }
        create("champion") {
            dimension = "identity"
            applicationIdSuffix = ".champion"
            buildConfigField("String", "DEVICE_ROLE", "\"champion\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
