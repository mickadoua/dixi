import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Mistral key for debug builds only: environment first, then local.properties
// (ignored by git). Absent → the app uses the fake Voxtral services.
val mistralApiKey: String = providers.environmentVariable("MISTRAL_API_KEY").orNull
    ?: Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }.getProperty("MISTRAL_API_KEY")
    ?: ""

fun buildConfigString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "tech.dmconsulting.voxbulle"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tech.dmconsulting.voxbulle"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "MISTRAL_API_KEY", buildConfigString(""))
    }

    buildTypes {
        // Shrunk so the debug APK fits the 10 MB task attachment limit.
        debug {
            isMinifyEnabled = true
            buildConfigField("String", "MISTRAL_API_KEY", buildConfigString(mistralApiKey))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
